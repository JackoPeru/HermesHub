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
    angle_bucket,
    build_caption,
    classify_asset,
    classify_pose,
    cosine,
    diversity_report,
    pick_references,
    pick_subject_refs,
    quality_score,
    stratified_split,
    write_dataset_jsonl,
)
from character_id.export_pkg import export_character, import_package  # noqa: E402
from character_id.face_backend import available_backends, detect_faces  # noqa: E402
from character_id.generate import (  # noqa: E402 (sys.path setup sopra)
    aspect_to_size,
    build_workflow,
    duration_to_length,
    inject_trigger,
    is_trusted_lora_path,
    parse_mentions,
    publish_extra_link,
    publish_lora_link,
    remove_character_links,
    resolve_request,
    stage_references,
    strip_mentions,
    validate_lora_file,
    validate_stack,
)
from character_id.recovery import scan_interruptions  # noqa: E402
from character_id.training import (  # noqa: E402 (sys.path setup sopra)
    CHECKPOINT_STEPS,
    EVAL_SUITE,
    ckpt_path,
    ckpt_steps_in,
    dataset_toml,
    pid_alive,
    train_cmd,
    training_active,
)
from character_id.imaging import (  # noqa: E402 (sys.path setup sopra)
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
    is_character_worker_cmdline,
    is_safe_preview_name,
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
        for bad in ("", "   ", "x" * 65, "a\x01b", None, 123,
                    "Sofia\u202e Evil", "a\u200bb"):
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
        self.assertEqual(validate_upload_filename("Screenshot 2026-10-08 120000.jpg"), ".jpg")
        self.assertEqual(validate_upload_filename("foto (1).png"), ".png")
        for bad in ("../x.jpg", "a/b.png", "..\\x.png", ".jpg", "foto.bmp", "foto", "", None,
                    "a:b.jpg", "x?.png", 'q"q.jpg'):
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

    def test_full_api_wired(self):
        for route in (
            '"/characters/{character_id}/train"',
            '"/characters/{character_id}/cancel"',
            '"/characters/{character_id}/retrain"',
            '"/characters/{character_id}/rollback"',
            '"/characters/{character_id}/generate"',
            '"/characters/{character_id}/metrics"',
            '"/characters/{character_id}/previews"',
            '"/characters/{character_id}/export"',
            '"/characters/import"',
        ):
            self.assertIn(route, self.src, msg=route)
        # M6+: niente piu stub 501, tutto implementato o con 409 onesto.
        self.assertNotIn("status_code=501", self.src)
        self.assertNotIn("not_implemented_yet", self.src)

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
        self.assertEqual(angle_bucket(-30.0), "left_three_quarter")
        self.assertEqual(angle_bucket(60.0), "profile_right")
        self.assertEqual(angle_bucket(None), "unknown")
        # full_body: volto piccolo + verticale (euristica, non detector).
        self.assertEqual(classify_pose(5.0, 0.02, 800, 1200), "full_body")
        self.assertEqual(classify_pose(5.0, 0.10, 800, 1200), "front")
        self.assertEqual(classify_pose(60.0, 0.02, 800, 1200), "profile_right")

    def test_no_backend_no_crash(self):
        photo = make_photo(self.root / "n.jpg")
        self.assertEqual(available_backends(), [])
        self.assertEqual(detect_faces(photo), [])


class TestDataset(unittest.TestCase):
    def test_classify_rejects_objective_only(self):
        base = {"face_count": 1, "face_ratio": 0.05, "dominant_subject": True}
        self.assertEqual(classify_asset({**base}, False)[0], "accepted")
        self.assertEqual(classify_asset({**base, "pre_reject": "risoluzione eccessiva"}, False),
                         ("rejected", "risoluzione eccessiva"))
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
        self.assertIn("<Subject 1>", caption)
        for banned in ("blue eyes", "brown hair", "nose", "jaw", "blonde", "brunette"):
            self.assertNotIn(banned, caption)

    def test_subject_refs_exclude_self_and_vary(self):
        items = [
            {"id": "t", "angle_bucket": "front", "face_quality": 0.9},
            {"id": "a", "angle_bucket": "profile_left", "face_quality": 0.5},
            {"id": "b", "angle_bucket": "front", "face_quality": 0.8},
            {"id": "c", "angle_bucket": "right_three_quarter", "face_quality": 0.7},
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
        # full_body mancante segnalato (non piu escluso).
        report2 = diversity_report([{"angle_bucket": "front"}])
        self.assertTrue(any("full_body" in w for w in report2["warnings"]))

    def test_jsonl_validates_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "t.jpg"
            target.write_bytes(b"x")
            ref = Path(tmp) / "r.jpg"
            ref.write_bytes(b"y")
            good = [{
                "image_path": str(target),
                "caption": "HCID_X photo of <Subject 1>, still photo",
                "references": [{"type": "image", "path": str(ref)}],
            }]
            out = Path(tmp) / "d.jsonl"
            write_dataset_jsonl(out, good)
            self.assertTrue(out.is_file())
            bad = [{
                "image_path": str(Path(tmp) / "manca.jpg"),
                "caption": "x",
                "references": [],
            }]
            with self.assertRaises(ValueError):
                write_dataset_jsonl(out, bad)
            bad_ref = [{
                "image_path": str(target),
                "caption": "x",
                "references": [{"type": "image", "path": str(Path(tmp) / "niente.jpg")}],
            }]
            with self.assertRaises(ValueError):
                write_dataset_jsonl(out, bad_ref)

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


def make_lora(path: Path, key: str = "lora_unet_blocks_0_attn_out_proj.lora_down.weight",
              meta: dict | None = None) -> Path:
    import json as _json

    header = _json.dumps({key: {"dtype": "F32", "shape": [2, 2]},
                          "__metadata__": meta or {}}).encode()
    with open(path, "wb") as handle:
        handle.write(len(header).to_bytes(8, "little"))
        handle.write(header)
        handle.write(b"\x00" * (2 * 1024 * 1024))
    return path


class TestGenerate(unittest.TestCase):
    def test_mentions(self):
        self.assertEqual(parse_mentions("@Sofia walking @Marco home"), ["Sofia", "Marco"])
        self.assertEqual(strip_mentions("@Sofia walking"), "walking")

    def test_trigger_injection(self):
        out = inject_trigger("@Sofia walking in Tokyo", "HCID_ABC123")
        self.assertTrue(out.startswith("HCID_ABC123 photo of <Subject 1>, "))
        self.assertNotIn("@Sofia", out)
        self.assertIn("Tokyo", out)

    def test_resolve_modes(self):
        manifest = {"identity": {"recommended_engine": "reference", "lora_strength": 0.9},
                    "trigger_token": "HCID_X",
                    "models": {"fl2va": None, "ref2va": None},
                    "references": {}}
        spec = resolve_request(manifest, {"prompt": "ciao", "identity_mode": "auto"})
        self.assertEqual(spec["engine"], "reference")
        with self.assertRaises(ValueError):
            resolve_request(manifest, {"prompt": "ciao", "identity_mode": "lora"})
        with self.assertRaises(ValueError):
            resolve_request(manifest, {"prompt": "ciao", "identity_mode": "hybrid"})
        manifest["models"]["fl2va"] = {"path": "/x.safetensors"}
        spec = resolve_request(manifest, {"prompt": "ciao", "identity_mode": "auto"})
        self.assertEqual(spec["engine"], "reference")  # recommended vince in auto
        self.assertEqual((spec["width"], spec["height"]), (1344, 768))
        self.assertEqual(duration_to_length(5), 124)
        self.assertEqual(duration_to_length(10), 243)
        self.assertEqual(duration_to_length(60), 360)
        self.assertEqual(aspect_to_size("1:1"), (1024, 1024))

    def test_stack_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            good = make_lora(Path(tmp) / "a.safetensors")
            other = make_lora(Path(tmp) / "b.safetensors", key="dense_layer.weight")
            ref_lora = make_lora(Path(tmp) / "c.safetensors",
                                 meta={"ss_minimax_h3_base_family": "ref2va"})
            cleaned = validate_stack([{"path": str(good), "strength": 0.9}], "fl2va")
            self.assertEqual(len(cleaned), 1)
            with self.assertRaises(ValueError):
                validate_stack([{"path": str(good), "family": "ref2va"}], "fl2va")
            with self.assertRaises(ValueError):
                validate_stack([{"path": str(other)}], "fl2va")
            with self.assertRaises(ValueError):
                validate_stack([{"path": str(good)}] * 5, "fl2va")
            with self.assertRaises(ValueError):
                validate_stack([{"path": str(good), "strength": 5.0}], "fl2va")
            # Metadata dichiara ref2va ma base e fl2va: rigetto reale, non fidato.
            with self.assertRaises(ValueError):
                validate_stack([{"path": str(ref_lora)}], "fl2va")
            self.assertTrue(validate_lora_file(Path(tmp) / "manca.safetensors"))

    def test_build_workflow_lora(self):
        template = {
            "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "x"}},
            "4": {"class_type": "MiniMaxH3ImageToVideo",
                  "inputs": {"prompt": "", "width": 0, "height": 0, "length": 0}},
            "5": {"class_type": "KSampler",
                  "inputs": {"model": ["1", 0], "seed": 0, "steps": 0, "cfg": 0}},
        }
        spec = {"prompt": "HCID_X photo", "width": 1344, "height": 768,
                "length": 39, "seed": 7}
        workflow = build_workflow(template, spec,
                                  [{"file": "sofia_v1.safetensors", "strength": 0.9}], [])
        lora_nodes = [v for v in workflow.values()
                      if isinstance(v, dict) and v.get("class_type") == "LoraLoaderModelOnly"]
        self.assertEqual(len(lora_nodes), 1)
        self.assertEqual(workflow["5"]["inputs"]["model"], [lora_nodes and "6" or "1", 0])
        self.assertEqual(workflow["4"]["inputs"]["prompt"], "HCID_X photo")
        with self.assertRaises(ValueError):
            build_workflow({"1": {"class_type": "X", "inputs": {}}}, spec, [], [])

    def test_stage_references_copies(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = make_photo(Path(tmp) / "r.jpg", pattern=False)
            staged = stage_references([str(src)], Path(tmp) / "dest")
            self.assertEqual(len(staged), 1)
            self.assertFalse((Path(tmp) / "dest" / staged[0]).is_symlink())
            with self.assertRaises(ValueError):
                stage_references([], Path(tmp) / "dest")


class TestTrainingRecipe(unittest.TestCase):
    def test_cache_cmds_flags(self):
        from character_id.training import cache_latents_cmd, cache_text_cmd

        with tempfile.TemporaryDirectory() as tmp:
            toml = Path(tmp) / "i.toml"
            toml.write_text("x")
            latent = " ".join(cache_latents_cmd(toml))
            # Il latent cache usa solo VAE: --dit non esiste (exit 2 live).
            self.assertNotIn("--dit", latent)
            self.assertIn("--task ref2va", latent)
            self.assertIn("--one_frame", latent)
            self.assertIn("--audio_vae", latent)
            text = " ".join(cache_text_cmd(toml))
            self.assertIn("--teacher_conditions subject_ref", text)
            self.assertIn("--task t2va", text)
            self.assertIn("--text_encoder_blocks_to_swap 48", text)

    def test_train_cmd_teacher(self):
        with tempfile.TemporaryDirectory() as tmp:
            toml = Path(tmp) / "i.toml"
            toml.write_text("x")
            cmd = train_cmd(toml, Path(tmp) / "out")
            text = " ".join(cmd)
            for flag in ("--task t2va", "--one_frame", "--video_only",
                         "--h3_teacher_matching", "--h3_teacher_conditions subject_ref",
                         "--h3_teacher_condition_sigma_min 0.15",
                         "--h3_teacher_loss_mag_weight 0.5",
                         "--h3_teacher_loss_dc_weight 0.3",
                         "--network_dim 16", "--optimizer_type adamw8bit",
                         "--sdpa", "--lr_scheduler cosine", "--block_swap_h2d_only",
                         "--blocks_to_swap 48", "--save_every_n_steps 50",
                         # save_last alto: un valore piccolo cancellerebbe 100/250
                         # durante il run (remove_step_no di Musubi).
                         "--save_last_n_steps 600"):
                self.assertIn(flag, text)
            resume_cmd = " ".join(train_cmd(toml, Path(tmp) / "out", resume="/x/state"))
            self.assertIn("--resume /x/state", resume_cmd)

    def test_eval_module_imports_cleanly(self):
        # worker_evaluate: solo stdlib a import time (av/insightface lazy).
        import importlib

        mod = importlib.import_module("character_id.worker_evaluate")
        self.assertTrue(callable(mod.main))
        self.assertTrue(callable(mod.extract_frames))
        # Gate durate: ogni generate eval deve portare il flag sperimentale.
        self.assertIn("--allow_experimental_duration", mod.EVAL_EXTRA_ARGS)
        self.assertEqual(mod.EVAL_TE_SWAP, "50")

    def test_dataset_toml(self):
        with tempfile.TemporaryDirectory() as tmp:
            char_dir = Path(tmp)
            (char_dir / "training").mkdir()
            toml = dataset_toml(char_dir, char_dir / "d.jsonl", char_dir / "cache")
            text = toml.read_text()
            self.assertIn("image_jsonl_file", text)
            self.assertIn("cache_directory", text)

    def test_ckpt_parsing(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "character-step00000100.safetensors").write_bytes(b"x")
            (out / "character-step00000250.safetensors").write_bytes(b"x")
            (out / "character-last.safetensors").write_bytes(b"x")
            (out / "character.safetensors").write_bytes(b"x")
            self.assertEqual(ckpt_steps_in(out), [100, 250])
            self.assertEqual(ckpt_steps_in(Path(tmp) / "vuota"), [])
            self.assertEqual(ckpt_path(out, "character", 100),
                             out / "character-step00000100.safetensors")

    def test_eval_suite_fixed(self):
        self.assertEqual(len(EVAL_SUITE), 8)
        seeds = [seed for _, seed in EVAL_SUITE]
        self.assertEqual(len(set(seeds)), 8)
        self.assertEqual(tuple(sorted(CHECKPOINT_STEPS)), (100, 250, 500))

    def test_pid_alive(self):
        import os as _os

        # 0/negativi: morti per definizione, senza syscall (sicuro ovunque).
        self.assertFalse(pid_alive(0))
        self.assertFalse(pid_alive(-5))
        if _os.name == "posix":
            # Solo POSIX: kill(pid, 0) reale (su Windows puo uccidere il runner).
            self.assertTrue(pid_alive(_os.getpid()))
            self.assertFalse(pid_alive(2 ** 30))


class TestRecoveryExport(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = CharacterStore(Path(self.tmp.name) / "hcid")

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def test_scan_interruptions(self):
        m = self.store.create_character("Sofia")
        job = self.store.create_job(m["id"], "train")
        self.store.update_job(job["id"], pid=0)  # pid 0 = morto per definizione
        actions = scan_interruptions(self.store)
        self.assertTrue(any(a.get("job_id") == job["id"] for a in actions))
        self.assertEqual(self.store.get_job(job["id"])["status"], "failed")
        self.assertEqual(self.store.get_character(m["id"])["status"], "interrupted")
        # Secondo giro: niente piu azioni.
        self.assertEqual(scan_interruptions(self.store), [])

    def test_export_import_roundtrip(self):
        m = self.store.create_character("Sofia")
        lora = make_lora(Path(self.tmp.name) / "l.safetensors")
        self.store.add_model(m["id"], 1, "fl2va", "character_lora", str(lora),
                             rank=16, alpha=16, strength=0.9)
        self.store.record_engine(m["id"], "lora", 0.9, lora_score=0.8, ref_score=0.4)
        dest = Path(self.tmp.name) / "sofia.hcid"
        export_character(self.store.char_dir(m["id"]), dest)
        self.assertTrue(dest.is_file())
        store2 = CharacterStore(Path(self.tmp.name) / "hcid2")
        try:
            imported = import_package(dest, store2)
            self.assertEqual(imported["status"], "ready")
            self.assertIsNotNone(imported["models"]["fl2va"])
            self.assertEqual(imported["identity"]["recommended_engine"], "lora")
        finally:
            store2.close()

    def test_import_rejects_zip_slip(self):
        import zipfile

        evil = Path(self.tmp.name) / "evil.hcid"
        with zipfile.ZipFile(evil, "w") as archive:
            archive.writestr("manifest.json", '{"schema_version": 1, "name": "x", "models": {}}')
            archive.writestr("../../evil.txt", "pwned")
        store2 = CharacterStore(Path(self.tmp.name) / "hcid3")
        try:
            with self.assertRaises(ValueError):
                import_package(evil, store2)
            self.assertFalse((Path(self.tmp.name) / "evil.txt").exists())
        finally:
            store2.close()

    def test_add_model_validates_family(self):
        m = self.store.create_character("Sofia")
        with self.assertRaises(ValueError):
            self.store.add_model(m["id"], 1, "sdxl", "x", "/tmp/x.safetensors")
        self.assertIsNone(self.store.latest_model(m["id"]))
        self.store.set_current_version(m["id"], 3)
        self.assertEqual(self.store.get_character(m["id"])["training_version"], 3)

    def test_patch_strength_keeps_engine(self):
        m = self.store.create_character("Sofia")
        self.store.record_engine(m["id"], "reference", 0.9, lora_score=0.4, ref_score=0.8)
        patched = self.store.patch_character(m["id"], {"lora_strength": 1.0})
        self.assertEqual(patched["identity"]["recommended_engine"], "reference")
        self.assertAlmostEqual(patched["identity"]["lora_strength"], 1.0)
        # Il modello mostrato segue current_version, non l'ultimo in assoluto.
        with tempfile.TemporaryDirectory() as tmp:
            first = make_lora(Path(tmp) / "v1.safetensors")
            second = make_lora(Path(tmp) / "v2.safetensors")
            self.store.add_model(m["id"], 1, "fl2va", "character_lora", str(first))
            self.store.add_model(m["id"], 2, "fl2va", "character_lora", str(second))
            shown = self.store.get_character(m["id"])["models"]["fl2va"]
            self.assertEqual(shown["path"], str(second))
            self.store.set_current_version(m["id"], 1)
            shown = self.store.get_character(m["id"])["models"]["fl2va"]
            self.assertEqual(shown["path"], str(first))


class TestBountyHardening(unittest.TestCase):
    """Regression bounty: lock GPU, traversal, trusted roots, link, integrazione."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = CharacterStore(Path(self.tmp.name) / "hcid")

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def test_training_lock_lifecycle(self):
        from character_id import training as _t

        self.assertIsNone(training_active(Path(self.tmp.name)))
        # Lock stale (pid morto) = inattivo, mai blocco fantasma.
        _t.write_lock(Path(self.tmp.name), {"job_id": "j", "pid": 0})
        self.assertIsNone(training_active(Path(self.tmp.name)))
        # terminate_tree: pgid invalidi mai toccati (sicuro ovunque, niente syscall).
        self.assertFalse(_t.terminate_tree(0))
        self.assertFalse(_t.terminate_tree(-5))
        self.assertFalse(_t.terminate_tree(1))

    def test_claim_lock_exclusive(self):
        from character_id import training as _t

        root = Path(self.tmp.name) / "claimroot"
        root.mkdir()
        never_active = lambda jid: False  # noqa: E731
        # Primo vince.
        self.assertTrue(_t.try_claim_lock(root, {"job_id": "a", "pid": 0}, never_active))
        # Secondo perde (lock "vivo"? pid 0 = morto + job inattivo = stale -> rubato).
        # Con job attivo invece perde davvero:
        always_active = lambda jid: True  # noqa: E731
        _t.write_lock(root, {"job_id": "a", "pid": 0})
        real = _t.pid_alive
        _t.pid_alive = lambda pid: True
        try:
            self.assertFalse(_t.try_claim_lock(root, {"job_id": "b", "pid": 0}, always_active))
        finally:
            _t.pid_alive = real
        # Stale rubato: pid morto + job inattivo.
        self.assertTrue(_t.try_claim_lock(root, {"job_id": "c", "pid": 0}, never_active))
        data = _t.read_lock(root)
        self.assertIsNotNone(data)
        self.assertEqual(data["job_id"], "c")
        # Lock vivo solo con pid vivo + job attivo (monkeypatch, sicuro ovunque).
        real = _t.pid_alive
        _t.pid_alive = lambda pid: pid == 424242
        try:
            _t.write_lock(Path(self.tmp.name), {"job_id": "j", "pid": 424242})
            self.assertIsNone(training_active(Path(self.tmp.name),
                                              lambda jid: False))
            locked = training_active(Path(self.tmp.name), lambda jid: True)
            self.assertIsNotNone(locked)
            self.assertEqual(locked["pid"], 424242)
        finally:
            _t.pid_alive = real

    def test_stale_lock_cleaned_by_scan(self):
        from character_id import training as _t

        _t.write_lock(self.store.root, {"job_id": "fantasma", "pid": 0})
        actions = scan_interruptions(self.store)
        self.assertTrue(any("stale" in str(a) for a in actions))
        self.assertIsNone(_t.read_lock(self.store.root))

    def test_preview_name_guard(self):
        self.assertTrue(is_safe_preview_name("portrait.png"))
        self.assertTrue(is_safe_preview_name("clip.mp4"))
        for bad in ("", "../x.png", "a/b.png", "..\\x.png", "x.exe", "x"):
            self.assertFalse(is_safe_preview_name(bad), msg=bad)
        self.assertTrue(is_character_worker_cmdline(
            "/opt/hermes/character-id/trainer/venv/bin/python\x00-m\x00character_id.worker_train\x00x"))
        self.assertFalse(is_character_worker_cmdline("/usr/bin/python TabbyAPI"))

    def test_trusted_lora_roots(self):
        import os as _os

        # Negativi portabili ovunque.
        self.assertFalse(is_trusted_lora_path("/etc/passwd"))
        self.assertFalse(is_trusted_lora_path("/opt/hermes/../etc/x"))
        self.assertFalse(is_trusted_lora_path(""))
        if _os.name != "posix":
            self.skipTest("root fidate sono path server POSIX")
        self.assertTrue(is_trusted_lora_path("/opt/hermes/models/minimax-h3/x.safetensors"))
        self.assertTrue(is_trusted_lora_path("/tmp/x.safetensors"))

    def test_lora_links_lifecycle(self):
        import os as _os

        if _os.name != "posix":
            self.skipTest("symlink richiedono privilegi su Windows (server: Linux)")
        with tempfile.TemporaryDirectory() as tmp:
            loras = Path(tmp) / "loras"
            target = make_lora(Path(tmp) / "real.safetensors")
            name = publish_lora_link(loras, "sofia", 1, target)
            self.assertEqual(name, "sofia_v1.safetensors")
            self.assertTrue((loras / name).is_symlink())
            extra = publish_extra_link(loras, "sofia", target)
            self.assertTrue((loras / extra).is_symlink())
            # remove_character_links toglie solo i link <slug>_v*, mai file veri.
            real_file = loras / "sofia_v9.safetensors"
            real_file.write_bytes(b"no-link")
            removed = remove_character_links(loras, "sofia")
            self.assertEqual(removed, 2)  # _v1 + _x extra
            self.assertTrue(real_file.is_file())
            self.assertFalse((loras / name).exists())
            self.assertFalse((loras / extra).exists())

    def test_integration_fake_training_to_generate(self):
        """create -> modello fake -> engine -> resolve lora -> workflow valido."""
        m = self.store.create_character("Sofia")
        lora = make_lora(Path(self.tmp.name) / "char.safetensors")
        self.store.add_model(m["id"], 1, "fl2va", "character_lora", str(lora),
                             rank=16, alpha=16, strength=0.9)
        self.store.record_engine(m["id"], "lora", 0.9, lora_score=0.8, ref_score=0.4)
        self.store.set_status(m["id"], "ready")
        manifest = self.store.get_character(m["id"])
        self.assertEqual(manifest["status"], "ready")
        spec = resolve_request(manifest, {"prompt": "@Sofia walking in Tokyo",
                                          "identity_mode": "auto",
                                          "duration": 5, "aspect_ratio": "16:9",
                                          "seed": 7})
        self.assertEqual(spec["engine"], "lora")
        self.assertTrue(spec["prompt"].startswith(manifest["trigger_token"]))
        self.assertNotIn("@Sofia", spec["prompt"])
        template = {
            "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "x"}},
            "4": {"class_type": "MiniMaxH3ImageToVideo",
                  "inputs": {"prompt": "", "width": 0, "height": 0, "length": 0}},
            "5": {"class_type": "KSampler",
                  "inputs": {"model": ["1", 0], "seed": 0, "steps": 0, "cfg": 0}},
        }
        workflow = build_workflow(template, spec,
                                  [{"file": "sofia_v1.safetensors", "strength": 0.9}], [])
        sampler = workflow["5"]["inputs"]
        self.assertEqual(sampler["seed"], 7)
        self.assertEqual(sampler["steps"], 25)
        node = workflow[sampler["model"][0]]
        self.assertEqual(node["class_type"], "LoraLoaderModelOnly")
        self.assertEqual(node["inputs"]["lora_name"], "sofia_v1.safetensors")


if __name__ == "__main__":
    unittest.main()
