"""Contratto anti-drift per le copie byte-identiche di triage.py."""

import hashlib
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COPIES = [
    ROOT / "scripts" / "hermes_hub_gateway" / "triage.py",
    ROOT / "gpu-manager" / "triage.py",
]


class TriageCopyDriftTests(unittest.TestCase):
    def test_gateway_and_manager_triage_copies_stay_byte_identical(self):
        digests = {}
        for path in COPIES:
            self.assertTrue(path.exists(), f"manca {path}")
            digests[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.assertEqual(len(set(digests.values())), 1, f"triage.py ha drift: {digests}")


if __name__ == "__main__":
    unittest.main()
