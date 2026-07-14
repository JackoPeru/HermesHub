from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_VERSION = "0.6.156"
EXPECTED_ANDROID_VERSION_CODE = 160


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

    def test_only_supported_gateway_fallbacks_are_configured(self) -> None:
        defaults = json.loads(read("config/hermes-defaults.json"))
        expected = [
            "http://hermes:8642/v1",
            "http://100.94.223.14:8642/v1",
            "http://hermes.local:8642/v1",
        ]
        self.assertEqual(defaults["hermes"]["autoDiscoveryUrls"], expected)
        self.assertEqual(defaults["hermes"]["apiUrl"], expected[0])

        windows_gateway = read("src/NemoclawChat.Windows/Services/GatewayService.cs")
        self.assertIn(
            '[\n        "hermes",\n        "100.94.223.14",\n        "hermes.local"\n    ]',
            windows_gateway,
        )

        android_roots = (
            'listOf(\n    "http://hermes:8642",\n'
            '    "http://100.94.223.14:8642",\n'
            '    "http://hermes.local:8642"\n)'
        )
        android_main = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt"
        )
        android_stream = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/ChatStream.kt"
        )
        self.assertIn(android_roots, android_main)
        self.assertIn(android_roots, android_stream)

        active_candidates = "\n".join(
            (
                windows_gateway,
                read("src/NemoclawChat.Windows/Pages/HomePage.xaml.cs"),
                android_stream,
            )
        )
        for obsolete in ("100.105.46.6", "http://home-server:8642", "http://server:8642"):
            self.assertNotIn(obsolete, active_candidates)

        self.assertNotIn("http://home-server:8642", android_main)
        self.assertNotIn("http://server:8642", android_main)
        self.assertEqual(android_main.count("100.105.46.6"), 1)
        self.assertIn('gateway.contains("100.105.46.6"', android_main)


if __name__ == "__main__":
    unittest.main()
