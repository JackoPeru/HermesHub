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


if __name__ == "__main__":
    unittest.main()
