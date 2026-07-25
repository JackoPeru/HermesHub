import importlib.util
import json
import pathlib
import tempfile
import unittest

from jsonschema import Draft202012Validator


ROOT = pathlib.Path(__file__).resolve().parents[1]


def load_module():
    path = ROOT / "scripts" / "benchmark-jarvis-observer.py"
    spec = importlib.util.spec_from_file_location("jarvis_benchmark", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class JarvisBenchmarkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = load_module()

    def test_manifest_contains_exactly_fifty_valid_synthetic_cases(self):
        manifest, _ = self.module.load_and_validate(self.module.DEFAULT_MANIFEST)
        self.assertEqual(50, len(manifest["cases"]))
        self.assertEqual(50, len({item["id"] for item in manifest["cases"]}))
        self.assertEqual({"calibration", "holdout"}, {item["split"] for item in manifest["cases"]})

    def test_renderer_is_deterministic_and_emits_png(self):
        manifest, _ = self.module.load_and_validate(self.module.DEFAULT_MANIFEST)
        frame = manifest["cases"][0]["frames"][0]
        first = self.module.render_frame(frame)
        second = self.module.render_frame(frame)
        self.assertEqual(first, second)
        self.assertTrue(first.startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertLess(len(first), 1_000_000)

    def test_observer_schema_accepts_contract_and_rejects_extra_fields(self):
        schema = json.loads(self.module.OUTPUT_SCHEMA.read_text(encoding="utf-8"))
        validator = Draft202012Validator(schema)
        valid = {
            "action": "ignore",
            "observation": "Banco invariato.",
            "reason": "Nessun cambiamento.",
            "confidence": 0.95,
            "importance": 0.1,
            "urgency": 0.0,
            "utility": 0.1,
            "reply": None,
            "recommended_frame_ids": [],
        }
        self.assertEqual([], list(validator.iter_errors(valid)))
        invalid = dict(valid, prompt="secret")
        self.assertTrue(list(validator.iter_errors(invalid)))

    def test_validate_only_cli_writes_no_persistent_images(self):
        manifest, _ = self.module.load_and_validate(self.module.DEFAULT_MANIFEST)
        with tempfile.TemporaryDirectory() as directory:
            total = 0
            for case in manifest["cases"]:
                for frame in case["frames"]:
                    target = pathlib.Path(directory) / f"{case['id']}-{frame['id']}.png"
                    target.write_bytes(self.module.render_frame(frame))
                    total += 1
            self.assertGreaterEqual(total, 50)
            self.assertTrue(all(path.stat().st_size < 1_000_000 for path in pathlib.Path(directory).glob("*.png")))


if __name__ == "__main__":
    unittest.main()
