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


if __name__ == "__main__":
    unittest.main()
