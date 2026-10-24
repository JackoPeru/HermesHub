"""Follow live del turno staccato: rientrando in chat con un turno vivo sul
gateway, la chat mostra l'output che cresce (SSE per i run, poll per le
Responses) invece di restare cieca fino al terminale.
"""

import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

CHAT_FEATURE = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/chat/ChatFeature.kt"
AND_TEST = REPO / "src/NemoclawChat.Android/app/src/test/java/com/nemoclaw/chat/LiveFollowTest.kt"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class LiveFollowTests(unittest.TestCase):
    def test_follow_engine_wires_both_transports(self) -> None:
        src = text(CHAT_FEATURE)
        for marker in (
            "runLiveFollow",
            "liveFollowFlow",
            "LiveFollowBanner",
            "foldLiveFollowText",
            "foldLiveFollowThinking",
            "client.events(binding.runId)",
            "parseSseData(name, data)",
            "httpGetResponseQuick(url, botApiKey)",
            "parseFullBody(body)",
            "applyResumeOutcome(resumeResponseBinding(binding))",
            "ApprovalRequest",
            "live-follow",
        ):
            self.assertIn(marker, src)

    def test_follow_renders_streaming_bubble_and_banner(self) -> None:
        src = text(CHAT_FEATURE)
        self.assertIn('item(key = "live-follow"', src)
        self.assertIn("Seguo il turno live", src)
        self.assertIn("Ferma", src)

    def test_background_never_appends_to_live_chat(self) -> None:
        # Chat standard intoccabile: niente addAll/add diretti dal background,
        # solo staging + Mostra esplicita.
        src = text(CHAT_FEATURE)
        self.assertNotIn("state.messages.addAll(missing)", src)
        self.assertIn("fun stageServerMessages(", src)
        self.assertIn("fun showStagedServerMessages()", src)
        self.assertIn("ServerStagedBanner(", src)
        self.assertIn("stagedServerMessages", src)
        banners = text(
            REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/chat/ChatBanners.kt"
        )
        self.assertIn("internal fun ServerStagedBanner(", banners)
        self.assertIn("Mostra", banners)

    def test_approval_never_touches_live_turn(self) -> None:
        src = text(CHAT_FEATURE)
        self.assertIn("idleForApproval", src)
        self.assertIn("state.streamingState == null && !state.sending", src)

    def test_resume_recovery_stages_instead_of_appending(self) -> None:
        src = text(CHAT_FEATURE)
        self.assertNotIn(
            'state.messages.add(ChatMessage("Hermes", replacement', src
        )
        self.assertIn('"srv_resp_${respId}"', src)

    def test_android_unit_test_covers_fold(self) -> None:
        self.assertIn("snapshot wins over previous text", text(AND_TEST))


if __name__ == "__main__":
    unittest.main()
