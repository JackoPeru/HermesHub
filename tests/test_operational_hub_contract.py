import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class OperationalHubContractTests(unittest.TestCase):
    def read(self, relative: str) -> str:
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_windows_exposes_all_operational_surfaces(self):
        shell = self.read("src/NemoclawChat.Windows/MainWindow.xaml.cs")
        for page in (
            "ProjectsPage",
            "ArtifactsPage",
            "SearchPage",
            "ContinuityPage",
            "AuditPage",
        ):
            with self.subTest(page=page):
                self.assertIn(f"typeof({page})", shell)

        archive = self.read("src/NemoclawChat.Windows/Pages/ArchivePage.xaml.cs")
        self.assertIn("ConversationManagerPage", archive)

    def test_gateway_exposes_typed_server_control_and_audit(self):
        patcher = self.read("scripts/patch-hermes-gateway-native.py")
        for route in (
            "/v1/hub/server/control",
            "/v1/hub/server/action",
            "/v1/hub/server/maintenance",
            "/v1/hub/audit",
        ):
            with self.subTest(route=route):
                self.assertIn(route, patcher)
        self.assertIn("HERMES_HUB_UPDATE_COMMAND", patcher)
        self.assertIn("_hermes_hub_audit_event", patcher)

    def test_advanced_conversation_and_export_contracts_exist(self):
        archive = self.read("src/NemoclawChat.Windows/Services/ChatArchiveStore.cs")
        manager = self.read("src/NemoclawChat.Windows/Pages/ConversationManagerPage.xaml.cs")
        exporter = self.read("src/NemoclawChat.Windows/Services/ConversationExportService.cs")
        self.assertIn("CreateBranch", archive)
        self.assertIn("ToggleBookmark", archive)
        self.assertIn("DeleteMessages", archive)
        self.assertIn("Rigenera alternativa", manager)
        for format_name in ("ToMarkdown", '"json"', '"html"', '"pdf"'):
            with self.subTest(format_name=format_name):
                self.assertIn(format_name, exporter)

    def test_android_system_integrations_and_operational_tabs_exist(self):
        manifest = self.read("src/NemoclawChat.Android/app/src/main/AndroidManifest.xml")
        main = self.read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt"
        )
        for component in (
            "HermesWidgetProvider",
            "HermesVoiceTileService",
            "HermesNotificationReplyReceiver",
            "VoiceCallService",
        ):
            with self.subTest(component=component):
                self.assertIn(component, manifest)
        self.assertIn("android.intent.action.SEND", manifest)
        self.assertIn("hermes-hub", manifest)
        for tab in ("Tab.Continuity", "Tab.Audit", "Tab.Artifacts", "Tab.Search"):
            with self.subTest(tab=tab):
                self.assertIn(tab, main)

    def test_voice_controls_live_in_settings_and_only_supported_voices_are_exposed(self):
        windows_voice = self.read("src/NemoclawChat.Windows/Pages/VoicePage.xaml")
        windows_settings = self.read("src/NemoclawChat.Windows/Pages/SettingsPage.xaml")
        android_voice = self.read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/VoiceModeScreen.kt"
        )
        android_settings = self.read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt"
        )
        for label in ("if_sara", "im_nicola", "Push-to-talk", "Trascrizione"):
            with self.subTest(client="windows-settings", label=label):
                self.assertIn(label, windows_settings)
        for label in ("SupportedVoiceNames", "Push-to-talk", "Mostra trascrizione"):
            with self.subTest(client="android-settings", label=label):
                self.assertIn(label, android_settings)
        for source in (windows_voice, android_voice):
            self.assertNotIn("if_alba", source)
            self.assertNotIn("Salva profilo", source)
            self.assertNotIn("Anteprima", source)
        self.assertEqual(android_voice.count('listOf("if_sara", "im_nicola")'), 1)

    def test_wake_phrase_is_configurable_and_starts_both_clients(self):
        windows_settings = self.read("src/NemoclawChat.Windows/Pages/SettingsPage.xaml")
        windows_store = self.read("src/NemoclawChat.Windows/Services/VoicePreferencesStore.cs")
        windows_listener = self.read("src/NemoclawChat.Windows/Services/WakeWordListener.cs")
        windows_shell = self.read("src/NemoclawChat.Windows/MainWindow.xaml.cs")
        android_settings = self.read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt"
        )
        android_voice = self.read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/VoiceModeScreen.kt"
        )
        self.assertIn('x:Name="VoiceWakePhraseBox"', windows_settings)
        self.assertIn("SupportedWakePhrases", windows_store)
        self.assertIn("TryStripWakePhrase(transcript, profile.WakePhrase", windows_listener)
        self.assertIn("new VoiceNavigationRequest(AutoStart: true)", windows_shell)
        self.assertIn('SettingsField("Wake word personalizzata"', android_settings)
        self.assertIn("awaitWakePhrase(", android_voice)
        self.assertIn("stripWakePhrase(transcript, wakePhrase)", android_voice)

    def test_quick_actions_are_above_or_exclude_empty_message_layers(self):
        windows = self.read("src/NemoclawChat.Windows/Pages/HomePage.xaml")
        windows_code = self.read("src/NemoclawChat.Windows/Pages/HomePage.xaml.cs")
        android = self.read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt"
        )
        self.assertIn('x:Name="MessagesList"', windows)
        self.assertIn('IsHitTestVisible="False"', windows)
        self.assertIn("MessagesList.IsHitTestVisible = visibility != Visibility.Visible", windows_code)
        chat_box = android[android.index("Box(modifier = Modifier.weight(1f))") :]
        self.assertLess(chat_box.index("LazyColumn("), chat_box.index("EmptyState(onPrompt"))

    def test_reasoning_is_persistent_and_collapsible_on_both_clients(self):
        windows = self.read("src/NemoclawChat.Windows/Pages/StreamingBubble.cs")
        windows_home = self.read("src/NemoclawChat.Windows/Pages/HomePage.xaml.cs")
        windows_archive = self.read("src/NemoclawChat.Windows/Services/ChatArchiveStore.cs")
        windows_gateway = self.read("src/NemoclawChat.Windows/Services/GatewayService.cs")
        android_ui = self.read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/ChatStreamUi.kt"
        )
        android_stream = self.read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/ChatStream.kt"
        )
        gateway = self.read("scripts/patch-hermes-gateway-native.py")
        self.assertIn('Text = "Ragionamento"', windows)
        self.assertIn("_thinkingExpander.Visibility = Visibility.Visible", windows)
        self.assertIn("ThinkingExpander(", android_ui)
        self.assertIn("ThinkingSnapshot", android_stream)
        self.assertIn("hermes.reasoning.available", gateway)
        self.assertIn("_hermes_hub_reasoning_text", gateway)
        self.assertIn("reasoning.available", gateway)
        self.assertIn("public string Thinking", windows_archive)
        self.assertIn("Thinking = finalThinkingBuilder.ToString()", windows_home)
        self.assertIn("RenderThinkingExpander(thinking)", windows_home)
        self.assertIn("thinking = message.Thinking", windows_gateway)

    def test_prompt_progress_is_server_reported_and_ui_hides_backend_name(self):
        windows_stream = self.read("src/NemoclawChat.Windows/Services/ChatStream.cs")
        windows_ui = self.read("src/NemoclawChat.Windows/Pages/StreamingBubble.cs")
        android_stream = self.read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/ChatStream.kt"
        )
        android_ui = self.read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/ChatStreamUi.kt"
        )
        gateway = self.read("scripts/patch-hermes-gateway-native.py")
        for source in (windows_stream, windows_ui, android_stream, android_ui):
            for visible_label in (
                '"llama.cpp: prefill prompt"',
                '"llama.cpp: attesa primo token"',
                '"llama.cpp: elaborazione prompt"',
                '"llama.cpp: generazione risposta"',
            ):
                self.assertNotIn(visible_label, source)
        self.assertIn("if (progress.Estimated)", windows_ui)
        self.assertIn("if (state.promptProgressEstimated) return null", android_ui)
        self.assertIn('setdefault(\\"return_progress\\", True)', gateway)
        self.assertIn('setdefault(\\"timings_per_token\\", True)', gateway)
        self.assertIn('payload[\\"percent\\"] = round(progress * 100.0)', gateway)


if __name__ == "__main__":
    unittest.main()
