"""Integrazioni avifenesh*: activity gruppi, fleet, git PR, pulse, wake.

(*da analisi repo esterno: activity dettagliata, fleet, git diff/PR,
dashboard pulse, wake word locale.)
"""

import unittest
from pathlib import Path

from android_sources import read_android_bots_ui, read_android_server_ui

REPO = Path(__file__).resolve().parent.parent

GIT = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/git/GitHubPrs.kt"
VOICE = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/VoiceModeScreen.kt"
SETTINGS = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/settings/SettingsFeature.kt"
APPROOT = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/navigation/AppRoot.kt"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class FleetTests(unittest.TestCase):
    def test_fleet_card(self) -> None:
        src = read_android_bots_ui()
        for marker in (
            "FleetCard",
            "fleetSummaryLine",
            "refreshFleet",
            "loadGpuAppSecret",
            "turnHistory",
        ):
            self.assertIn(marker, src)


class GitPrTests(unittest.TestCase):
    def test_pr_card(self) -> None:
        src = text(GIT)
        for marker in (
            "parseGitHubRepo",
            "listOpenPrs",
            "fetchPrDiff",
            "mergePr",
            "diffLineKind",
            "api.github.com",
        ):
            self.assertIn(marker, src)

    def test_token_store(self) -> None:
        store = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/core/auth/GatewaySecretStore.kt"
        self.assertIn("loadGithubToken", text(store))
        self.assertIn("saveGithubToken", text(store))


class PulseWakeTests(unittest.TestCase):
    def test_pulse(self) -> None:
        src = read_android_server_ui()
        for marker in (
            "PulseCard",
            "bucketPulseDays",
            "PULSE",
        ):
            self.assertIn(marker, src)

    def test_wake_sensitivity_and_stats(self) -> None:
        voice = text(VOICE)
        for marker in (
            "wakeSensitivity",
            "wakeVoiceThreshold",
            "recordWakeListen",
            "recordWakeActivation",
            "loadWakeStatsToday",
            "wakeStatsLine",
        ):
            self.assertIn(marker, voice)
        settings = text(SETTINGS)
        self.assertIn("voiceWakeSensitivity", settings)
        self.assertIn("SENSIBILIT", settings)
        approot = text(APPROOT)
        self.assertIn("wake.wakeSensitivity", approot)


if __name__ == "__main__":
    unittest.main()
