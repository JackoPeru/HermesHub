from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_VERSION = "0.6.162"
EXPECTED_ANDROID_VERSION_CODE = 166


def read(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


class ReleaseConsistencyTests(unittest.TestCase):
    def test_application_versions_are_aligned(self) -> None:
        windows_project = read("src/NemoclawChat.Windows/NemoclawChat.Windows.csproj")
        admin_project = read("src/ChatClaw.AdminBridge/ChatClaw.AdminBridge.csproj")
        android_project = read("src/NemoclawChat.Android/app/build.gradle.kts")
        package_manifest = read("src/NemoclawChat.Windows/Package.appxmanifest")

        self.assertIn(f"<Version>{EXPECTED_VERSION}</Version>", windows_project)
        self.assertIn(f"<AssemblyVersion>{EXPECTED_VERSION}.0</AssemblyVersion>", windows_project)
        self.assertIn(f"<FileVersion>{EXPECTED_VERSION}.0</FileVersion>", windows_project)
        self.assertIn(f"<Version>{EXPECTED_VERSION}</Version>", admin_project)
        self.assertIn(f'versionName = "{EXPECTED_VERSION}"', android_project)
        self.assertIn(f"versionCode = {EXPECTED_ANDROID_VERSION_CODE}", android_project)
        self.assertRegex(
            package_manifest,
            rf'<Identity[\s\S]*?Version="{re.escape(EXPECTED_VERSION)}\.0"',
        )

    def test_release_documents_are_current(self) -> None:
        self.assertIn(f"Versione corrente: `{EXPECTED_VERSION}`.", read("README.md"))
        self.assertIn(
            f"Versione corrente: `{EXPECTED_VERSION}`.",
            read("AGENTS.md"),
        )
        self.assertTrue(
            read("release_notes.txt").startswith(f"Hermes Hub {EXPECTED_VERSION} ")
        )
        self.assertIn(f"## {EXPECTED_VERSION} -", read("CHANGELOG.md"))

    def test_github_repository_defaults_target_hermes_hub(self) -> None:
        expected_slug = "JackoPeru/HermesHub"
        obsolete_slug = "app-interazione-nemoclaw"
        files = (
            "AGENTS.md",
            "CHANGELOG.md",
            "scripts/hermes-hub-linux-update.service",
            "scripts/hermes-hub-linux-update.sh",
            "src/NemoclawChat.Windows/Services/AppUpdateService.cs",
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt",
        )

        contents = {path: read(path) for path in files}
        for path, content in contents.items():
            self.assertNotIn(obsolete_slug, content, path)

        self.assertIn(expected_slug, contents["AGENTS.md"])
        self.assertIn(expected_slug, contents["CHANGELOG.md"])
        self.assertIn(expected_slug, contents["scripts/hermes-hub-linux-update.service"])
        self.assertIn(
            'REPO="${HERMES_HUB_REPO:-JackoPeru/HermesHub}"',
            contents["scripts/hermes-hub-linux-update.sh"],
        )
        self.assertIn(
            'public const string RepositoryName = "HermesHub";',
            contents["src/NemoclawChat.Windows/Services/AppUpdateService.cs"],
        )
        self.assertIn(
            "https://api.github.com/repos/JackoPeru/HermesHub/releases/latest",
            contents[
                "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt"
            ],
        )

    def test_android_has_one_canonical_gradle_build(self) -> None:
        for stale_file in (
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
        ):
            self.assertFalse((ROOT / stale_file).exists(), stale_file)

        wrapper = read("src/NemoclawChat.Android/gradle/wrapper/gradle-wrapper.properties")
        self.assertIn("gradle-9.6.1-bin.zip", wrapper)
        plugins = read("src/NemoclawChat.Android/build.gradle.kts")
        self.assertIn('version "9.2.0"', plugins)
        self.assertIn('version "2.3.21"', plugins)

    def test_fresh_install_contains_no_personal_gateway_defaults(self) -> None:
        defaults = json.loads(read("config/hermes-defaults.json"))
        self.assertEqual(defaults["hermes"]["autoDiscoveryUrls"], [])
        self.assertEqual(defaults["hermes"]["apiUrl"], "")
        self.assertEqual(defaults["hermes"]["healthUrl"], "")
        self.assertEqual(defaults["hermes"]["detailedHealthUrl"], "")

        windows_gateway = read("src/NemoclawChat.Windows/Services/GatewayService.cs")
        self.assertIn("PlugAndPlayGatewayHosts = [];", windows_gateway)

        android_main = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt"
        )
        android_stream = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/ChatStream.kt"
        )
        self.assertIn("plugAndPlayGatewayRoots = emptyList<String>()", android_main)
        self.assertIn("plugAndPlayStreamGatewayRoots = emptyList<String>()", android_stream)

        public_runtime = "\n".join(
            (
                read("src/NemoclawChat.Windows/Services/AppSettings.cs"),
                windows_gateway,
                read("src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/HermesAuth.kt"),
                android_stream,
                android_main,
            )
        )
        self.assertNotIn("http://", read("src/NemoclawChat.Windows/Services/AppSettings.cs"))
        self.assertNotIn("http://", read("src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt").split("private object AppDefaults", 1)[1].split("}", 1)[0])
        self.assertNotIn("/home/", public_runtime)


if __name__ == "__main__":
    unittest.main()
