"""Blocchi interattivi quiz/form: l'agente puo' emettere moduli toccabili
(choice/form) e i client li renderizzano con invio della risposta in chat.

Contratto v1 invariato (tipi aggiunti, mai rimossi): Windows e Android
parsano, validano, renderizzano e rispondono con lo stesso formato che il
protocollo insegna all'agente ([risposta quiz ...] / [dati modulo ...]).
"""

import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

WIN_VISUAL = REPO / "src/NemoclawChat.Windows/Services/VisualBlocks.cs"
WIN_HOME = REPO / "src/NemoclawChat.Windows/Pages/HomePage.xaml.cs"
WIN_PROTOCOL = REPO / "src/NemoclawChat.Windows/Services/HermesHubProtocol.cs"
AND_MODELS = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/chat/ChatModels.kt"
AND_OPS = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/core/HubOperations.kt"
AND_UI = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/chat/ChatMediaBlocks.kt"
AND_BUBBLE = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/chat/ChatMessageBubble.kt"
AND_FEATURE = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/chat/ChatFeature.kt"
AND_ARCHIVE = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/core/storage/ConversationArchiveRepository.kt"
AND_TEST = REPO / "src/NemoclawChat.Android/app/src/test/java/com/nemoclaw/chat/VisualBlocksContractTest.kt"

QUIZ_REPLY = '[risposta quiz \\"'
FORM_REPLY = '[dati modulo \\"'


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class InteractiveBlocksTests(unittest.TestCase):
    def test_windows_contract_has_choice_and_form(self) -> None:
        src = text(WIN_VISUAL)
        for marker in (
            '"choice",', '"form",',
            '"choice" =>', '"form" =>',
            "VisualChoiceOption", "VisualFormField",
            "FormFieldKinds", "MaxChoiceOptions", "MaxFormFields",
            "IsValidFormField",
        ):
            self.assertIn(marker, src)

    def test_windows_chat_renders_and_replies(self) -> None:
        src = text(WIN_HOME)
        for marker in (
            '"choice" => RenderChoice(block)',
            '"form" => RenderForm(block)',
            "RenderChoice(VisualBlockRecord block)",
            "RenderForm(VisualBlockRecord block)",
            "SendBlockReplyAsync",
            "_answeredBlockIds",
            QUIZ_REPLY,
            FORM_REPLY,
        ):
            self.assertIn(marker, src)

    def test_android_contract_has_choice_and_form(self) -> None:
        models = text(AND_MODELS)
        for marker in ("VisualChoiceOption", "VisualFormField", "val options", "val fields", "val submitLabel"):
            self.assertIn(marker, models)
        ops = text(AND_OPS)
        for marker in ("readVisualOptions", "readVisualFields", '"choice" ->', '"form" ->', "isValidVisualFormField"):
            self.assertIn(marker, ops)
        archive = text(AND_ARCHIVE)
        self.assertIn('"choice" -> obj.put', archive)
        self.assertIn('"form" -> obj.put', archive)

    def test_android_chat_renders_and_replies(self) -> None:
        ui = text(AND_UI)
        for marker in ("ChoiceBlock(", "FormBlock(", "onSendText", QUIZ_REPLY, FORM_REPLY):
            self.assertIn(marker, ui)
        self.assertIn("onSendText", text(AND_BUBBLE))
        self.assertIn("onSendText = { quickPrompt = it }", text(AND_FEATURE))

    def test_android_unit_test_covers_new_types(self) -> None:
        self.assertIn("choice and form blocks parse and validate", text(AND_TEST))

    def test_protocol_teaches_agent_both_modules(self) -> None:
        protocol = text(WIN_PROTOCOL)
        for marker in ('choice = "quiz', 'form = "modulo', QUIZ_REPLY, FORM_REPLY):
            self.assertIn(marker, protocol)


if __name__ == "__main__":
    unittest.main()
