"""STT UniNote-first: il gateway trascrive via UniNote locale (primario),
fallback al modello faster-whisper in-process. Senza faster_whisper
installato il path primario deve funzionare comunque (prova che non
serve la VRAM duplicata nel gateway)."""

from __future__ import annotations

import io
import json
import os
import tempfile
import threading
import unittest
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parent.parent
PATCHER_MOD = (
    REPO / "scripts" / "hermes_hub_gateway" / "adapters" / "hermes"
    / "legacy_patcher.py"
)


def load_stt_helpers():
    """Esegue il blocco helper STT (vive dentro stringhe-template del patcher,
    come il resto del runtime iniettato): ns con os + shim env_int."""
    source = PATCHER_MOD.read_text(encoding="utf-8")
    start = source.index("def _hermes_hub_transcribe_uninnote(path")
    end = source.index("def _hermes_hub_cached_hardware_snapshot", start)
    block = source[start:end]

    def _hermes_hub_env_int(name, default, lo, hi):
        try:
            value = int(os.environ.get(name, default))
        except (TypeError, ValueError):
            value = default
        return max(lo, min(hi, value))

    ns = {"os": os, "_hermes_hub_env_int": _hermes_hub_env_int}
    exec(compile(block, "<stt-helpers>", "exec"), ns)
    return ns


class StubUniNoteHandler(BaseHTTPRequestHandler):
    bodies: list = []
    hits: int = 0

    def log_message(self, *args):  # silenzioso
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        type(self).bodies.append(body)
        type(self).hits += 1
        payload = json.dumps({"text": "ciao mondo"}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


class UniNoteSttTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ns = load_stt_helpers()
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), StubUniNoteHandler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        fd, cls.wav = tempfile.mkstemp(suffix=".wav")
        os.close(fd)
        with wave.open(cls.wav, "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(16000)
            handle.writeframes(b"\x00\x00" * 16000)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join(timeout=5)
        cls.server.server_close()
        try:
            os.unlink(cls.wav)
        except OSError:
            pass

    def setUp(self):
        StubUniNoteHandler.bodies = []
        StubUniNoteHandler.hits = 0

    def stub_url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def test_uninnote_returns_text(self):
        with mock.patch.dict(os.environ, {"HERMES_STT_UNINOTE_URL": self.stub_url()}):
            text = self.ns["_hermes_hub_transcribe_uninnote"](self.wav)
        self.assertEqual("ciao mondo", text)
        self.assertEqual(1, StubUniNoteHandler.hits)
        body = StubUniNoteHandler.bodies[0]
        self.assertIn(b'name="model"', body)
        self.assertIn(b'name="language"', body)
        self.assertIn(b'name="file"', body)
        self.assertIn(b"audio.wav", body)

    def test_file_prefers_uninnote_without_local_dep(self):
        # Niente faster_whisper qui: se torna il testo dello stub, il path
        # primario non tocca il modello locale (niente VRAM duplicata).
        env = {
            "HERMES_STT_PREFER": "uninote",
            "HERMES_STT_UNINOTE_URL": self.stub_url(),
        }
        with mock.patch.dict(os.environ, env):
            text = self.ns["_hermes_hub_transcribe_file"](self.wav)
        self.assertEqual("ciao mondo", text)
        self.assertEqual(1, StubUniNoteHandler.hits)

    def test_file_falls_back_when_uninnote_down(self):
        env = {
            "HERMES_STT_PREFER": "uninote",
            "HERMES_STT_UNINOTE_URL": "http://127.0.0.1:9",
            "HERMES_STT_UNINOTE_TIMEOUT_SECONDS": "2",
        }
        with mock.patch.dict(os.environ, env):
            with self.assertRaises(Exception):
                self.ns["_hermes_hub_transcribe_file"](self.wav)

    def test_prefer_local_skips_uninnote(self):
        env = {
            "HERMES_STT_PREFER": "local",
            "HERMES_STT_UNINOTE_URL": self.stub_url(),
        }
        with mock.patch.dict(os.environ, env):
            with self.assertRaises(Exception):
                self.ns["_hermes_hub_transcribe_file"](self.wav)
        self.assertEqual(0, StubUniNoteHandler.hits)

    def test_injected_runtime_copy_updated(self):
        source = PATCHER_MOD.read_text(encoding="utf-8")
        start = source.index("whisper_runtime = r'''")
        end = source.index("def _hermes_hub_cached_hardware_snapshot", start)
        runtime = source[start:end]
        self.assertIn("def _hermes_hub_transcribe_uninnote", runtime)
        self.assertIn("HERMES_STT_PREFER", runtime)
        self.assertIn("HERMES_STT_UNINOTE_URL", runtime)


if __name__ == "__main__":
    unittest.main()
