"""Test Character ID M2: manifest, validatori, CRUD store, job state machine,
sicurezza path/upload. store.py e validation.py sono solo-stdlib: import diretto.
api.py richiede fastapi: verificato via sorgente (rotte + guardie auth/ruoli).
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "gpu-manager"))

from character_id import (  # noqa: E402 (sys.path setup sopra)
    CHARACTER_JOB_STATUSES,
    CHARACTER_STATUSES,
    IDENTITY_MODES,
    LORA_FAMILIES,
    SCHEMA_VERSION,
)
from character_id.dataset import (  # noqa: E402 (sys.path setup sopra)
    build_caption,
    classify_asset,
    cosine,
    diversity_report,
    pick_references,
    pick_subject_refs,
    quality_score,
    stratified_split,
    write_dataset_jsonl,
)
from character_id.face_backend import available_backends, detect_faces  # noqa: E402
from character_id.imaging import (  # noqa: E402 (sys.path setup sopra)
    angle_bucket,
    dhash_hex,
    hamming_hex,
    mean_brightness,
    save_normalized,
    sha256_file,
    shot_scale,
)
from character_id.store import CharacterStore  # noqa: E402 (sys.path setup sopra)
from character_id.validation import (  # noqa: E402 (sys.path setup sopra)
    MAX_IMAGES,
    MIN_IMAGES,
    is_uuid,
    validate_default_mode,
    validate_lora_strength,
    validate_name,
    validate_upload_filename,
    validate_upload_size,
)


class TestValidation(unittest.TestCase):
    def test_name_ok(self):
        self.assertEqual(validate_name("  Sofia  "), "Sofia")

    def test_name_rejects(self):
        for bad in ("", "   ", "x" * 65, "a\x01b", None, 123):
            with self.assertRaises(ValueError, msg=repr(bad)):
                validate_name(bad)

    def test_modes(self):
        for mode in IDENTITY_MODES:
            self.assertEqual(validate_default_mode(mode), mode)
        with self.assertRaises(ValueError):
            validate_default_mode("hybrid")  # futuro: non ancora esistente

    def test_strength_range(self):
        self.assertAlmostEqual(validate_lora_strength("0.9"), 0.9)
        for bad in (-0.1, 2.5, "forte"):
            with self.assertRaises(ValueError):
                validate_lora_strength(bad)

    def test_upload_filename(self):
        self.assertEqual(validate_upload_filename("foto01.JPG"), ".jpg")
        for bad in ("../x.jpg", "a/b.png", "..\\x.png", ".jpg", "foto.bmp", "foto", "", None):
            with self.assertRaises(ValueError, msg=repr(bad)):
                validate_upload_filename(bad)

    def test_upload_size(self):
        self.assertEqual(validate_upload_size(1024), 1024)
        for bad in (0, -5, 16 * 1024 * 1024, True, "1024"):
            with self.assertRaises(ValueError, msg=repr(bad)):
                validate_upload_size(bad)

    def test_limits(self):
        self.assertEqual((MIN_IMAGES, MAX_IMAGES), (20, 80))

    def test_is_uuid(self):
        self.assertTrue(is_uuid("12345678-1234-1234-1234-1234567890ab"))
        for bad in ("", "../x", "Sofia", None, "XYZ"):
            self.assertFalse(is_uuid(bad), msg=repr(bad))


class TestStore(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = CharacterStore(Path(self.tmp.name) / "hcid")

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def test_create_manifest_shape(self):
        m = self.store.create_character("Sofia")
        self.assertEqual(m["schema_version"], SCHEMA_VERSION)
        self.assertTrue(is_uuid(m["id"]))
        self.assertEqual(m["name"], "Sofia")
        self.assertRegex(m["trigger_token"], r"^HCID_[0-9A-F]{6}$")
        self.assertEqual(m["status"], "draft")
        self.assertEqual(m["training_version"], 0)
        self.assertEqual(m["identity"]["default_mode"], "auto")
        self.assertIsNone(m["models"]["fl2va"])
        # Futuro hybrid: ref2va sempre null in V1.
        self.assertIsNone(m["models"]["ref2va"])
        self.assertIsNone(m["metrics"]["lora_identity_score"])
        # Manifest persistito su disco, permessi restrittivi.
        path = Path(self.tmp.name) / "hcid" / "characters" / m["id"] / "manifest.json"
        self.assertTrue(path.is_file())
        on_disk = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(on_disk["trigger_token"], m["trigger_token"])
        # Permessi restrittivi: verificabili solo su POSIX (Windows non ha i bit Unix).
        if os.name == "posix":
            mode = oct(os.stat(path).st_mode & 0o777)
            self.assertEqual(mode, "0o600")

    def test_same_name_allowed_uuid_distinguishes(self):
        a = self.store.create_character("Sofia")
        b = self.store.create_character("Sofia")
        self.assertNotEqual(a["id"], b["id"])
        self.assertNotEqual(a["slug"], b["slug"])
        self.assertNotEqual(a["trigger_token"], b["trigger_token"])

    def test_no_filesystem_name_leak(self):
        m = self.store.create_character("../../etc")
        self.assertNotIn("..", m["id"])
        self.assertTrue((Path(self.tmp.name) / "hcid" / "characters" / m["id"]).is_dir())

    def test_rename_patch_status(self):
        m = self.store.create_character("Sofia")
        renamed = self.store.rename_character(m["id"], "Marco")
        self.assertEqual(renamed["name"], "Marco")
        patched = self.store.patch_character(m["id"], {"default_mode": "reference"})
        self.assertEqual(patched["identity"]["default_mode"], "reference")
        with self.assertRaises(ValueError):
            self.store.patch_character(m["id"], {"trigger_token": "HCID_X"})
        with self.assertRaises(ValueError):
            self.store.patch_character(m["id"], {"default_mode": "hybrid"})
        status = self.store.set_status(m["id"], "ready_to_train")
        self.assertEqual(status["status"], "ready_to_train")
        with self.assertRaises(ValueError):
            self.store.set_status(m["id"], "pronto")
        self.assertIsNone(self.store.get_character("12345678-1234-1234-1234-1234567890ab"))
        self.assertIsNone(self.store.get_character("non-uuid"))

    def test_delete_removes_everything(self):
        m = self.store.create_character("Sofia")
        cid = m["id"]
        char_dir = Path(self.tmp.name) / "hcid" / "characters" / cid
        (char_dir / "originals" / "a.jpg").write_bytes(b"fake")
        self.assertTrue(self.store.delete_character(cid))
        self.assertFalse(char_dir.exists())
        self.assertIsNone(self.store.get_character(cid))
        self.assertFalse(self.store.delete_character(cid))

    def test_job_lifecycle_persists(self):
        m = self.store.create_character("Sofia")
        job = self.store.create_job(m["id"], "train")
        self.assertEqual(job["status"], "queued")
        self.assertIn(job["status"], CHARACTER_JOB_STATUSES)
        updated = self.store.update_job(job["id"], status="training", progress=0.5, pid=1234)
        self.assertEqual(updated["status"], "training")
        self.assertAlmostEqual(updated["progress"], 0.5)
        # Simulate reboot: nuova istanza sullo stesso root, stato intatto.
        store2 = CharacterStore(Path(self.tmp.name) / "hcid")
        try:
            again = store2.get_job(job["id"])
            self.assertEqual(again["status"], "training")
            active = store2.active_job(m["id"])
            self.assertEqual(active["id"], job["id"])
            done = store2.update_job(job["id"], status="failed", error="boom")
            self.assertEqual(done["error"], "boom")
            self.assertIsNone(store2.active_job(m["id"]))
            with self.assertRaises(ValueError):
                store2.update_job(job["id"], status="partito")
            with self.assertRaises(ValueError):
                store2.create_job(m["id"], "teletrasporto")
        finally:
            store2.close()

    def test_statuses_cover_spec(self):
        for want in ("draft", "analyzing", "ready_to_train", "training", "validating",
                     "ready", "failed"):
            self.assertIn(want, CHARACTER_STATUSES)
        for want in ("queued", "preparing", "caching", "smoke_test", "training",
                     "validating", "ready", "failed", "cancelled"):
            self.assertIn(want, CHARACTER_JOB_STATUSES)
        self.assertIn("fl2va", LORA_FAMILIES)
        self.assertIn("ref2va", LORA_FAMILIES)


class TestApiWiring(unittest.TestCase):
    """api.py richiede fastapi: verifica sul sorgente che rotte e guardie esistano."""

    @classmethod
    def setUpClass(cls):
        cls.src = (REPO / "gpu-manager" / "character_id" / "api.py").read_text(encoding="utf-8")

    def test_routes_present(self):
        for route in (
            '@app.get("/characters")',
            '@app.post("/characters"',
            '@app.get("/characters/{character_id}")',
            '@app.patch("/characters/{character_id}")',
            '@app.delete("/characters/{character_id}")',
            '"/characters/{character_id}/status"',
            '"/characters/{character_id}/images"',
            '"/characters/{character_id}/analyze"',
            '"/characters/{character_id}/train"',
            '"/characters/{character_id}/cancel"',
            '"/characters/{character_id}/retrain"',
            '"/characters/{character_id}/metrics"',
            '"/characters/{character_id}/previews"',
        ):
            self.assertIn(route, self.src, msg=route)

    def test_auth_and_roles(self):
        # Tutte le rotte richiedono la chiave...
        self.assertGreater(self.src.count("key_dep"), 10)
        # ...e ogni scrittura passa anche dal controllo-utente (localhost 403).
        self.assertGreaterEqual(self.src.count("user_dep(request)"), 7)

    def test_stubs_are_honest(self):
        self.assertIn("status_code=501", self.src)
        self.assertIn("not_implemented_yet", self.src)

    def test_m3_routes_present(self):
        for route in (
            '"/characters/{character_id}/images"',
            '"/characters/{character_id}/images/{image_id}"',
            '"/characters/{character_id}/analyze"',
        ):
            self.assertIn(route, self.src, msg=route)
        self.assertIn("UploadFile", self.src)
        self.assertIn("tools_python", self.src)
        # Regressione live: _run usato anche con kwargs (pid=..., detail=...).
        self.assertIn("async def _run(fn, *args, **kwargs):", self.src)


def make_photo(path: Path, size=(640, 480), color=(200, 120, 90), pattern=True, mirror=False) -> Path:
    from PIL import Image, ImageDraw

    image = Image.new("RGB", size, color)
    if pattern:
        draw = ImageDraw.Draw(image)
        # Motivo asimmetrico: dhash vede struttura, non solo tinta unita.
        bar = [size[0] * 2 // 3, 0, size[0], size[1]] if mirror else [0, 0, size[0] // 3, size[1]]
        draw.rectangle(bar, fill=(30, 30, 30))
        draw.ellipse([size[0] // 2, size[1] // 4, size[0] * 3 // 4, size[1] * 3 // 4], fill=(240, 240, 240))
    image.save(path, format="JPEG", quality=90)
    return path


class TestImaging(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_dhash_stable_and_similar(self):
        first = make_photo(self.root / "a.jpg")
        twin = make_photo(self.root / "b.jpg")
        other = make_photo(self.root / "c.jpg", mirror=True)
        self.assertEqual(dhash_hex(first), dhash_hex(first))
        self.assertEqual(len(dhash_hex(first)), 16)
        self.assertLessEqual(hamming_hex(dhash_hex(first), dhash_hex(twin)), 4)
        self.assertGreater(hamming_hex(dhash_hex(first), dhash_hex(other)), 4)

    def test_normalized_caps_size(self):
        big = make_photo(self.root / "big.jpg", size=(3000, 2000))
        out = self.root / "norm.jpg"
        width, height = save_normalized(big, out)
        self.assertLessEqual(max(width, height), 1536)
        self.assertTrue(out.is_file())

    def test_brightness_range(self):
        bright = make_photo(self.root / "bright.jpg", color=(250, 250, 250), pattern=False)
        dark = make_photo(self.root / "dark.jpg", color=(10, 10, 10), pattern=False)
        self.assertGreater(mean_brightness(bright), 0.8)
        self.assertLess(mean_brightness(dark), 0.2)

    def test_sha256(self):
        photo = make_photo(self.root / "s.jpg")
        self.assertEqual(len(sha256_file(photo)), 64)

    def test_buckets(self):
        self.assertEqual(shot_scale(0.20), "close-up")
        self.assertEqual(shot_scale(0.06), "medium shot")
        self.assertEqual(shot_scale(0.01), "full shot")
        self.assertEqual(angle_bucket(5.0), "front")
        self.assertEqual(angle_bucket(-30.0), "three_quarter_left")
        self.assertEqual(angle_bucket(60.0), "profile_right")
        self.assertEqual(angle_bucket(None), "unknown")

    def test_no_backend_no_crash(self):
        photo = make_photo(self.root / "n.jpg")
        self.assertEqual(available_backends(), [])
        self.assertEqual(detect_faces(photo), [])


class TestDataset(unittest.TestCase):
    def test_classify_rejects_objective_only(self):
        base = {"face_count": 1, "face_ratio": 0.05, "dominant_subject": True}
        self.assertEqual(classify_asset({**base}, False)[0], "accepted")
        self.assertEqual(classify_asset({**base, "corrupt": "x"}, False), ("rejected", "file corrotto"))
        self.assertEqual(classify_asset({**base, "duplicate_of": "y"}, False), ("rejected", "duplicato quasi identico"))
        self.assertEqual(classify_asset({**base, "face_count": 0}, False)[0], "rejected")
        self.assertEqual(classify_asset({**base, "face_count": 2, "dominant_subject": False}, False)[0], "rejected")
        self.assertEqual(classify_asset({**base, "face_ratio": 0.001}, False)[0], "rejected")
        self.assertEqual(classify_asset({**base, "identity_sim": 0.10}, True)[0], "rejected")
        # Borderline = warning, mai auto-reject.
        verdict, _ = classify_asset({**base, "identity_sim": 0.35}, True)
        self.assertEqual(verdict, "warning")

    def test_cosine_centroid(self):
        self.assertAlmostEqual(cosine([1.0, 0.0], [1.0, 0.0]), 1.0)
        self.assertAlmostEqual(cosine([1.0, 0.0], [0.0, 1.0]), 0.0)
        self.assertAlmostEqual(cosine([], []), 0.0)
        from character_id.dataset import centroid

        self.assertEqual(centroid([[1.0, 2.0], [3.0, 4.0]]), [2.0, 3.0])
        self.assertIsNone(centroid([]))

    def test_split_stratified(self):
        items = [{"id": f"front-{i}", "angle_bucket": "front"} for i in range(5)]
        items += [{"id": f"prof-{i}", "angle_bucket": "profile_left"} for i in range(5)]
        split = stratified_split(items)
        self.assertEqual(len(split), 10)
        front_val = [k for k, v in split.items() if k.startswith("front") and v == "validation"]
        prof_val = [k for k, v in split.items() if k.startswith("prof") and v == "validation"]
        self.assertTrue(front_val and prof_val)

    def test_caption_no_identity_traits(self):
        caption = build_caption("HCID_A7F29C", {"shot": "medium shot", "angle_bucket": "front", "brightness": 0.8})
        self.assertTrue(caption.startswith("HCID_A7F29C"))
        for banned in ("blue eyes", "brown hair", "nose", "jaw", "blonde", "brunette"):
            self.assertNotIn(banned, caption)

    def test_subject_refs_exclude_self_and_vary(self):
        items = [
            {"id": "t", "angle_bucket": "front", "face_quality": 0.9},
            {"id": "a", "angle_bucket": "profile_left", "face_quality": 0.5},
            {"id": "b", "angle_bucket": "front", "face_quality": 0.8},
            {"id": "c", "angle_bucket": "three_quarter_right", "face_quality": 0.7},
        ]
        refs = pick_subject_refs("t", items)
        self.assertNotIn("t", refs)
        self.assertGreaterEqual(len(refs), 2)
        self.assertEqual(refs[0], "c")  # angolo diverso, migliore qualita prima

    def test_references_and_diversity(self):
        picks = pick_references([{"id": f"x{i}", "angle_bucket": "front", "face_quality": 0.5} for i in range(3)])
        self.assertEqual(picks, {"front": ["x0"]})
        report = diversity_report([{"angle_bucket": "front"}] * 8)
        self.assertTrue(any("70" in w or "%" in w for w in report["warnings"]))

    def test_jsonl_validates_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "t.jpg"
            target.write_bytes(b"x")
            good = [{"target": str(target), "caption": "HCID_X photo", "references": []}]
            out = Path(tmp) / "d.jsonl"
            write_dataset_jsonl(out, good)
            self.assertTrue(out.is_file())
            bad = [{"target": str(Path(tmp) / "manca.jpg"), "caption": "x", "references": []}]
            with self.assertRaises(ValueError):
                write_dataset_jsonl(out, bad)

    def test_quality_score_bounds(self):
        score = quality_score({"face_ratio": 0.2, "blur_score": 500.0, "yaw": 5.0, "accepted": "accepted"})
        self.assertGreaterEqual(score, 0.0)
        self.assertLessEqual(score, 1.0)


class TestStoreAssets(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = CharacterStore(Path(self.tmp.name) / "hcid")

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def test_asset_crud_and_refs(self):
        m = self.store.create_character("Sofia")
        cid = m["id"]
        asset = self.store.insert_asset(cid, {"original_path": "/tmp/a.jpg", "sha256": "abc"})
        self.assertEqual(asset["accepted"], "pending")
        self.assertEqual(self.store.count_assets(cid), 1)
        listed = self.store.list_assets(cid)
        self.assertEqual(len(listed), 1)
        self.store.set_split_many({asset["id"]: "train"})
        self.assertEqual(self.store.get_asset(asset["id"])["dataset_split"], "train")
        self.store.set_references(cid, {"front": {"asset_id": asset["id"], "path": "/tmp/r.jpg"}})
        manifest = self.store.get_character(cid)
        self.assertIn(asset["id"], manifest["references"]["preferred"])
        self.assertEqual(manifest["references"]["front"], "/tmp/r.jpg")
        removed = self.store.delete_asset(cid, asset["id"])
        self.assertEqual(removed["id"], asset["id"])
        self.assertEqual(self.store.count_assets(cid), 0)
        self.assertIsNone(self.store.delete_asset(cid, asset["id"]))

    def test_dataset_stats_and_refresh(self):
        m = self.store.create_character("Sofia")
        self.store.record_dataset_stats(m["id"], {"a": "train", "b": "validation"}, {"buckets": {}})
        refreshed = self.store.refresh_manifest(m["id"])
        self.assertEqual(refreshed["id"], m["id"])
        self.assertIsNone(self.store.refresh_manifest("12345678-1234-1234-1234-1234567890ab"))


if __name__ == "__main__":
    unittest.main()
