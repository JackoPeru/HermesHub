"""Tests for the media triage decision core (no gateway, no models)."""

import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "scripts" / "hermes_hub_gateway"))

import triage as tg  # noqa: E402 (sys.path setup sopra)


class TriageRulesTest(unittest.TestCase):
    def test_edit_with_image(self):
        d = tg.triage("modificami questa foto: cambia lo sfondo al mare", True)
        self.assertTrue(d.media)
        self.assertEqual(d.preset, tg.PRESET_EDIT_IMAGE)
        self.assertIn("mare", d.prompt_hint)

    def test_edit_english(self):
        d = tg.triage("remove the person in the background", True)
        self.assertTrue(d.media)
        self.assertEqual(d.preset, tg.PRESET_EDIT_IMAGE)

    def test_motion_with_image(self):
        d = tg.triage("anima questa foto, voglio un video", True)
        self.assertTrue(d.media)
        self.assertEqual(d.preset, tg.PRESET_VIDEO_PREVIEW)

    def test_motion_infinitive_with_image(self):
        for text in ("fai muovere le onde in questa foto", "vorrei animare questo scatto"):
            d = tg.triage(text, True)
            self.assertTrue(d.media, text)
            self.assertEqual(d.preset, tg.PRESET_VIDEO_PREVIEW, text)

    def test_question_with_image_is_not_media(self):
        for text in (
            "cosa c'e in questa foto?",
            "spiegami cosa vedi nell'immagine",
            "chi e la persona a sinistra?",
            "describe what you see",
        ):
            d = tg.triage(text, True)
            self.assertFalse(d.media, text)
            self.assertEqual(d.preset, "")

    def test_image_without_instruction_is_not_media(self):
        d = tg.triage("", True)
        self.assertFalse(d.media)
        d = tg.triage("ti mando questa", True)
        self.assertFalse(d.media)

    def test_generate_without_image(self):
        d = tg.triage("generami un'immagine di un faro nella tempesta", False)
        self.assertTrue(d.media)
        self.assertEqual(d.preset, tg.PRESET_CREATE_IMAGE)

    def test_plain_chat_is_not_media(self):
        for text in ("ciao, come va?", "riassumi questo documento", "", "   "):
            d = tg.triage(text, False)
            self.assertFalse(d.media, repr(text))

    def test_ask_wins_over_edit_verbs(self):
        # "dimmi cosa cambieresti" is analysis, not an edit order.
        d = tg.triage("dimmi cosa cambieresti in questa foto", True)
        self.assertFalse(d.media)

    def test_backend_is_swappable(self):
        class Stub:
            def decide(self, text, has_image, history_tail=()):
                return tg.TriageDecision(media=True, preset="x", confidence=1.0)

        d = tg.triage("qualunque cosa", False, backend=Stub())
        self.assertTrue(d.media)
        self.assertEqual(d.preset, "x")

    def test_decision_is_immutable(self):
        d = tg.triage("anima la foto", True)
        with self.assertRaises(Exception):
            d.preset = "other"  # type: ignore[misc]


class LayaBackendTest(unittest.TestCase):
    """LayaBackend contro un finto /api/decide (niente rete vera)."""

    def _server(self, payload=None, status=200):
        import json as _json
        from http.server import BaseHTTPRequestHandler, HTTPServer

        outer = {}

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                outer["body"] = self.rfile.read(length)
                data = _json.dumps(payload).encode() if payload is not None else b"nope"
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        outer["server"] = server
        outer["url"] = f"http://127.0.0.1:{server.server_port}"
        import threading

        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        outer["thread"] = thread
        return outer

    def _answer(self, choice, confidence):
        return {"answers": {"media_task": {"type": "choice", "choice": choice,
                                           "confidence": confidence}}}

    def test_confident_edit_trusted(self):
        ctx = self._server(self._answer("edit_image", 0.97))
        try:
            backend = tg.LayaBackend(url=ctx["url"])
            d = backend.decide("modificami la foto", True)
            self.assertTrue(d.media)
            self.assertEqual(d.preset, tg.PRESET_EDIT_IMAGE)
            self.assertIn("laya:edit_image", d.reason)
        finally:
            ctx["server"].shutdown()

    def test_low_confidence_falls_back_to_rules(self):
        ctx = self._server(self._answer("generate_image", 0.43))
        try:
            backend = tg.LayaBackend(url=ctx["url"])
            # generate_image a 0.43 < 0.75 -> fallback regole (con foto ma
            # senza verbi utili -> non media).
            d = backend.decide("guarda che bella", True)
            self.assertFalse(d.media)
            self.assertIn("laya-fallback", d.reason)
        finally:
            ctx["server"].shutdown()

    def test_error_falls_back_to_rules(self):
        ctx = self._server()
        ctx["server"].shutdown()  # connessione rifiutata
        backend = tg.LayaBackend(url=ctx["url"], timeout=2)
        d = backend.decide("anima questa foto", True)
        self.assertTrue(d.media)
        self.assertEqual(d.preset, tg.PRESET_VIDEO_PREVIEW)

    def test_no_media_maps_to_false(self):
        ctx = self._server(self._answer("no_media", 0.95))
        try:
            backend = tg.LayaBackend(url=ctx["url"])
            d = backend.decide("cosa c'e nella foto?", True)
            self.assertFalse(d.media)
            self.assertEqual(d.preset, "")
        finally:
            ctx["server"].shutdown()

    def test_decision_log_written(self):
        import json as _json
        import os as _os
        import tempfile as _tf
        tmp = _tf.NamedTemporaryFile(suffix=".jsonl", delete=False)
        tmp.close()
        _os.environ["HERMES_TRIAGE_LOG"] = tmp.name
        ctx = self._server(self._answer("edit_image", 0.9))
        try:
            tg.LayaBackend(url=ctx["url"]).decide("cambia sfondo", True)
            with open(tmp.name, encoding="utf-8") as fh:
                rec = _json.loads(fh.read().strip().splitlines()[-1])
            self.assertEqual(rec["backend"], "laya")
            self.assertEqual(rec["choice"], "edit_image")
        finally:
            ctx["server"].shutdown()
            _os.environ.pop("HERMES_TRIAGE_LOG", None)
            _os.unlink(tmp.name)


if __name__ == "__main__":
    unittest.main()
