import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class ProjectWorkspaceContractTests(unittest.TestCase):
    def read(self, relative: str) -> str:
        return (ROOT / relative).read_text(encoding="utf-8")

    def read_android_sources(self) -> str:
        source_root = ROOT / "src" / "NemoclawChat.Android" / "app" / "src" / "main" / "java"
        return "\n".join(path.read_text(encoding="utf-8") for path in sorted(source_root.rglob("*.kt")))

    def test_windows_project_navigation_is_not_archive_alias(self):
        shell = self.read("src/NemoclawChat.Windows/MainWindow.xaml")
        code = self.read("src/NemoclawChat.Windows/MainWindow.xaml.cs")
        self.assertIn('Click="Projects_Click"', shell)
        self.assertIn("ContentFrame.Navigate(typeof(ProjectsPage))", code)

    def test_workspace_fields_round_trip_across_clients_and_gateway(self):
        fields = (
            "projectId",
            "workspacePath",
            "repositoryUrl",
            "projectInstructions",
            "projectMemory",
            "authorizedTools",
        )
        windows_gateway = self.read("src/NemoclawChat.Windows/Services/GatewayService.cs")
        android = self.read_android_sources()
        gateway_patch = self.read("scripts/patch-hermes-gateway-native.py")
        for field in fields:
            with self.subTest(field=field):
                self.assertIn(field, windows_gateway)
                self.assertIn(field, android)
                self.assertIn(f'"{field}"', gateway_patch)

    def test_active_project_context_reaches_native_requests(self):
        windows_protocol = self.read("src/NemoclawChat.Windows/Services/HermesHubProtocol.cs")
        android_stream = self.read("src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/ChatStream.kt")
        android_main = self.read_android_sources()
        self.assertIn("project_context = project is null ? null", windows_protocol)
        self.assertIn("ProjectContextInstructions(settings)", windows_protocol)
        self.assertIn('"project_context"', android_stream)
        self.assertIn("projectContextInstructions(settings)", android_stream)
        self.assertIn("projectId = settings.activeProjectId", android_main)

    def test_project_editor_only_exposes_name_and_optional_system_prompt(self):
        windows_xaml = self.read("src/NemoclawChat.Windows/Pages/ProjectsPage.xaml")
        android = self.read_android_sources()
        android_editor = android[android.index("internal fun ProjectsScreen("):android.index("internal fun AppSettings.withActiveProject")]

        self.assertIn('x:Name="TitleBox"', windows_xaml)
        self.assertIn('x:Name="SystemPromptBox"', windows_xaml)
        for removed in ("DescriptionBox", "WorkspacePathBox", "RepositoryUrlBox", "MemoryBox", "ToolsBox"):
            self.assertNotIn(removed, windows_xaml)

        self.assertIn('SettingsField("Nome progetto"', android_editor)
        self.assertIn('SettingsField("System prompt (facoltativo)"', android_editor)
        for removed in ("SettingsField(\"Descrizione\"", "SettingsField(\"Repository\"", "SettingsField(\"Memoria progetto\"", "Tool autorizzati, uno per riga"):
            self.assertNotIn(removed, android_editor)

    def test_project_prompt_is_the_only_user_authored_context(self):
        windows_protocol = self.read("src/NemoclawChat.Windows/Services/HermesHubProtocol.cs")
        android = self.read_android_sources()
        gateway_patch = self.read("scripts/patch-hermes-gateway-native.py")
        self.assertIn("system_prompt = project.ProjectInstructions", windows_protocol)
        self.assertIn('.put("system_prompt", settings.activeProjectInstructions)', android)
        self.assertIn("Applica automaticamente il system prompt", windows_protocol)
        self.assertIn("Applica automaticamente il system prompt", android)
        self.assertIn("def _hermes_hub_project_system_prompt", gateway_patch)
        self.assertIn('project.get("system_prompt")', gateway_patch)
        self.assertIn("prompt[:20000]", gateway_patch)

    def test_quick_actions_send_instead_of_only_filling_composer(self):
        windows = self.read("src/NemoclawChat.Windows/Pages/HomePage.xaml.cs")
        android = self.read_android_sources()
        for handler in ("PromptSetup_Click", "PromptHealth_Click", "PromptAgent_Click"):
            start = windows.index(f"void {handler}")
            body = windows[start:windows.index("\n    }", start)]
            self.assertIn("await SendCurrentPromptAsync();", body)
        self.assertIn("LaunchedEffect(quickPrompt)", android)
        self.assertIn("onValueChange(prompt)\n        onSend()", android)

    def test_chat_title_is_generated_once_by_hermes_after_first_answer(self):
        windows_stream = self.read("src/NemoclawChat.Windows/Services/ChatStream.cs")
        windows_store = self.read("src/NemoclawChat.Windows/Services/ChatArchiveStore.cs")
        android = self.read_android_sources()
        self.assertIn("GenerateConversationTitleAsync", windows_stream)
        self.assertIn("store = false", windows_stream)
        self.assertIn('Title = "Nuova chat"', windows_store)
        self.assertNotIn("Title = MakeTitle(prompt)", windows_store)
        self.assertIn("generateConversationTitle(", android)
        self.assertIn('.put("store", false)', android)
        self.assertIn("title = UNTITLED_CHAT_TITLE", android)
        self.assertIn("initialConversation.title == UNTITLED_CHAT_TITLE", android)


if __name__ == "__main__":
    unittest.main()
