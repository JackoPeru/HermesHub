"""Activity tracker gruppi: riepiloghi persistiti + attivita' strutturata."""

import unittest
from pathlib import Path

from android_sources import read_android_bots_ui

REPO = Path(__file__).resolve().parent.parent

TURN = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/bots/BotGroupTurn.kt"
AND_TEST = REPO / "src/NemoclawChat.Android/app/src/test/java/com/nemoclaw/chat/features/bots/GroupTurnActivityTest.kt"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class GroupActivityTests(unittest.TestCase):
    def test_history_store(self) -> None:
        src = text(TURN)
        for marker in (
            "GroupTurnSummary",
            "summarizeGroupTurn",
            "pruneGroupTurnHistory",
            "loadGroupTurnHistory",
            "recordGroupTurn",
            "GROUP_TURN_HISTORY_CAP",
        ):
            self.assertIn(marker, src)

    def test_structured_activity_ui(self) -> None:
        src = read_android_bots_ui()
        for marker in (
            "GroupTurnActivity",
            "formatGroupTurnTime",
            "turnHistory",
            "Turni recenti",
        ):
            self.assertIn(marker, src)

    def test_android_unit_test_covers_summary(self) -> None:
        self.assertIn("summary counts speaking members", text(AND_TEST))


if __name__ == "__main__":
    unittest.main()
