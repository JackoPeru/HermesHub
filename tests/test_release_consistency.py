from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_VERSION = "0.6.175"
EXPECTED_ANDROID_VERSION_CODE = 179


def read(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


class ReleaseConsistencyTests(unittest.TestCase):
    def test_gateway_launcher_file_transfer_defaults_are_unlimited(self) -> None:
        launcher = read("scripts/hermes-hub-linux.sh")
        self.assertIn('HERMES_GATEWAY_MAX_REQUEST_MB="${HERMES_GATEWAY_MAX_REQUEST_MB:-0}"', launcher)
        self.assertIn('HERMES_HUB_MAX_UPLOAD_MB="${HERMES_HUB_MAX_UPLOAD_MB:-0}"', launcher)

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

    def test_official_android_release_is_meta_dat_only_and_fail_closed(self) -> None:
        package_script = read("scripts/package-android-release.ps1")
        android_project = read("src/NemoclawChat.Android/app/build.gradle.kts")
        workflow = read(".github/workflows/quality.yml")
        agents = read("AGENTS.md")

        self.assertIn("-PenableMetaDat=true", package_script)
        self.assertNotIn("allowStandardReleaseForDevelopment", package_script)
        self.assertIn("[switch]$CiValidation", package_script)
        self.assertIn("android-DAT-validation-only.apk", package_script)
        self.assertIn("asset CI validation-only, non pubblicabile", package_script)
        for required_guard in (
            "GITHUB_TOKEN",
            "githubPackagesToken",
            "META_DAT_APPLICATION_ID",
            "META_DAT_CLIENT_TOKEN",
            "META_DAT_ENABLED=true",
            "minSdkVersion:'29'",
            "MetaWearablesFrameSource",
            "MetaWearablesSetupBridgeImpl",
            "certificate SHA-256 digest",
            "HermesHub-$Version-android.apk",
        ):
            self.assertIn(required_guard, package_script)
        self.assertIn(
            "7be7c380f31c81c050a86ea8cefd4ec3bd41972ddd864a8edb97b1e20c84823f",
            package_script,
        )
        self.assertIn(
            "else {\n    $certificateMatch = [regex]::Match($signatureOutput",
            package_script,
        )
        self.assertIn(
            "digest storico non richiesto per artefatto non pubblicabile",
            package_script,
        )

        self.assertIn("allowStandardReleaseForDevelopment", android_project)
        self.assertIn("containsReleaseOutput", android_project)
        self.assertIn("!enableMetaDat && !allowStandardReleaseForDevelopment", android_project)
        self.assertIn("L'APK standard non deve essere pubblicato", android_project)

        self.assertIn("packages: read", workflow)
        self.assertIn("./scripts/package-android-release.ps1", workflow)
        self.assertIn("-CiValidation", workflow)
        self.assertIn("META_DAT_PACKAGES_TOKEN", workflow)
        self.assertNotIn("META_DAT_APPLICATION_ID:", workflow)
        self.assertNotIn("META_DAT_CLIENT_TOKEN:", workflow)
        self.assertNotIn(
            "run: ./gradlew --no-daemon lintRelease testDebugUnitTest assembleRelease",
            workflow,
        )

        self.assertIn(".\\scripts\\package-android-release.ps1", agents)
        self.assertIn("nessun fallback standard", agents)

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

    def test_android_backup_never_exports_credentials(self) -> None:
        exporter = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/LocalBackupExporter.kt"
        )
        main = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt"
        )
        self.assertIn("if (isSensitiveBackupKey(key)) return@forEach", exporter)
        for marker in ("apikey", "token", "secret", "password", "credential", "authorization"):
            self.assertIn(f'"{marker}"', exporter)
        self.assertNotIn('.put("gatewayApiKey"', exporter)
        self.assertNotIn("apiKey: String?", exporter)
        self.assertIn("exportLocalBackup(context)", main)
        self.assertNotIn("exportLocalBackup(context, apiKey)", main)

    def test_android_gateway_secret_storage_fails_closed(self) -> None:
        main = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt"
        )
        self.assertIn("private fun saveGatewaySecret(context: Context, secret: String?): Boolean", main)
        self.assertIn("}.getOrNull() ?: return false", main)
        self.assertIn("if (!saveGatewaySecret(context, apiKey))", main)
        self.assertIn("Credenziale non scritta in chiaro", main)
        self.assertNotIn("}.getOrDefault(normalized)", main)

    def test_android_media_auth_is_scoped_to_configured_hermes_origin(self) -> None:
        main = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt"
        )
        self.assertIn(
            "val needsHermesAuth = shouldAuthenticateHermesUrl(settings, candidateUrl)",
            main,
        )
        self.assertIn(
            "val needsHermesAuth = parsed != null && shouldAuthenticateHermesUrl(settings, url)",
            main,
        )
        self.assertIn(
            "if (token.isBlank() || !shouldAuthenticateHermesUrl(settings, url))",
            main,
        )
        security = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/HermesUrlSecurity.kt"
        )
        self.assertIn("sameHttpOrigin(target, URI(configured", security)
        self.assertIn("effectivePort(left) == effectivePort(right)", security)
        self.assertIn('ClipData.newPlainText("hermes-media-url", url)', main)

        windows_home = read("src/NemoclawChat.Windows/Pages/HomePage.xaml.cs")
        self.assertIn("ResolveMediaUri(value, includeQueryToken: false)", windows_home)


if __name__ == "__main__":
    unittest.main()
