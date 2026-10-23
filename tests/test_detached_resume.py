"""Recupero turno staccato: veloce, con feedback e leggibile.

Il banner non puo' piu' tacere ne' troncare: il resume usa HTTP veloce
(~12s max, non ~1 minuto), ogni esito guida sottotitolo o nota, e il
sottotitolo va su piu' righe senza ellipsis.
"""

import unittest
from pathlib import Path

import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from android_sources import read_android_chat_ui  # noqa: E402

REPO = Path(__file__).resolve().parent.parent

ACTIVE_WORK = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/core/storage/ActiveWorkStore.kt"
AND_TEST = REPO / "src/NemoclawChat.Android/app/src/test/java/com/nemoclaw/chat/DetachedResumeTest.kt"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class DetachedResumeTests(unittest.TestCase):
    def test_outcome_model_covers_every_case(self) -> None:
        src = text(ACTIVE_WORK)
        for marker in (
            "DetachedResumeOutcome",
            "STILL_RUNNING",
            "NET_ERROR",
            "AUTH_GONE",
            "NOT_FOUND_GONE",
            "EXPIRED_GONE",
            "detachedResumeSubtitle",
            "detachedResumeNote",
        ):
            self.assertIn(marker, src)

    def test_resume_is_fast_and_reports_outcome(self) -> None:
        src = read_android_chat_ui()
        self.assertIn("httpGetResponseQuick(url, botApiKey)", src)
        self.assertIn("resumeResponseBinding(binding: ActiveWorkBinding): DetachedResumeOutcome", src)
        self.assertIn("STILL_RUNNING", src)

    def test_banner_is_readable_and_stateful(self) -> None:
        src = read_android_chat_ui()
        self.assertIn("DetachedFollowBanner(", src)
        self.assertIn("subtitle = detachedSubtitle", src)
        self.assertIn("checking = detachedChecking", src)
        self.assertIn("maxLines = 3", src)
        self.assertIn("DetachedResumeNote(", src)
        self.assertIn("detachedNote", src)

    def test_android_unit_test_covers_feedback(self) -> None:
        self.assertIn("DetachedResumeTest", text(AND_TEST))

    def test_legacy_markers_survive(self) -> None:
        # Contratto test_release_consistency: nomi storici invariati.
        src = read_android_chat_ui()
        self.assertIn("DetachedFollowBanner(", src)
        self.assertIn("detachedFollowUp", src)


if __name__ == "__main__":
    unittest.main()
