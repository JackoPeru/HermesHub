"""Lockdown manager: l'agente non tocca il manager, solo laya ci mette mano.

- Manager: piano controllo (modi/force/reboot/supervisor) solo chiave laya;
  ruolo app (letture + coda + display + characters) con chiave app separata;
  vecchia chiave condivisa rimossa, niente trusted hub.
- Chiavi da file root-only (LoadCredential) o env; mai dallo yaml leggibile.
- laya: ingresso app per NL, agenti solo submit preset via /laya/jobs.
- App: chiave app dedicata, modi/reboot via laya, mai chiavi controllo.
"""

import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

MANAGER = REPO / "gpu-manager/manager.py"
LAYA = REPO / "gpu-manager/laya_bridge.py"
DISPLAY = REPO / "gpu-manager/display_bridge.py"
REFRAME_SCRIPT = REPO / "gpu-manager/client_h3_reframe.py"
AND_SECRET = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/core/auth/GatewaySecretStore.kt"
AND_TOPBAR = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/chat/ChatTopBar.kt"
AND_SERVER = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/server/ServerFeature.kt"
AND_HTTP = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/core/network/HermesHttpClient.kt"
AND_SETTINGS = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/settings/SettingsFeature.kt"
WIN_CRED = REPO / "src/NemoclawChat.Windows/Services/GatewayCredentialStore.cs"
WIN_GW = REPO / "src/NemoclawChat.Windows/Services/GatewayService.cs"
WIN_SETTINGS = REPO / "src/NemoclawChat.Windows/Pages/SettingsPage.xaml.cs"
WIN_PROTOCOL = REPO / "src/NemoclawChat.Windows/Services/HermesHubProtocol.cs"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class ManagerRolesTests(unittest.TestCase):
    def test_control_routes_require_laya(self) -> None:
        src = text(MANAGER)
        for fn in ("mode_llm", "mode_media", "mode_auto", "mode_direct",
                   "force_mode", "system_reboot", "supervisor_ask"):
            self.assertIn(f"async def {fn}", src)
        self.assertEqual(7, src.count("Depends(require_laya)"))

    def test_reads_and_queue_stay_app_role(self) -> None:
        src = text(MANAGER)
        for fn in ("status", "list_jobs", "get_job_api", "cancel_job",
                   "resubmit_job_api", "submit_job", "submit_smart",
                   "browse_library", "get_job_workflow_api", "set_job_workflow_api"):
            self.assertIn(f"Depends(require_key)", src)

    def test_shared_hub_key_is_gone(self) -> None:
        src = text(MANAGER)
        self.assertIn('"app_key": ""', src)
        self.assertIn("def require_laya", src)
        self.assertIn("_credential_file", src)
        self.assertIn("CREDENTIALS_DIRECTORY", src)

    def test_display_accepts_app_role(self) -> None:
        src = text(DISPLAY)
        self.assertIn('get("app_key")', src)


class layaRolesTests(unittest.TestCase):
    def test_ingress_roles_and_submit(self) -> None:
        src = text(LAYA)
        for marker in (
            "def caller_role",
            "def app_keys",
            "def agent_keys",
            "LAYA_SUBMIT_PRESETS",
            "def submit_preset_job",
            "/laya/jobs",
            '"reboot"',
            "_REBOOT_VERBS",
            "credential",
            "CREDENTIALS_DIRECTORY",
        ):
            self.assertIn(marker, src)

    def test_agent_cannot_drive_modes(self) -> None:
        src = text(LAYA)
        # /laya richiede ruolo app; submit solo preset allowlist.
        self.assertIn('!= "app"', src)
        self.assertIn("preset non consentito", src)

    def test_reframe_script_uses_laya(self) -> None:
        src = text(REFRAME_SCRIPT)
        self.assertIn("/laya/jobs", src)
        self.assertIn("HERMES_GATEWAY_API_KEY", src)
        self.assertNotIn("gpu-manager.yaml", src)
        self.assertNotIn("127.0.0.1:8643", src)

    def test_laya_submit_accepts_params_alias(self) -> None:
        # Il client storico manda "parameters": il bridge accetta l'alias,
        # mai params droppati in silenzio.
        bridge = text(LAYA)
        self.assertIn('payload.get("params"', bridge)
        self.assertIn('payload.get("parameters"', bridge)
        client = text(REFRAME_SCRIPT)
        self.assertIn('"params": {', client)


class AppKeysTests(unittest.TestCase):
    def test_android_gpu_app_key(self) -> None:
        self.assertIn("loadGpuAppSecret", text(AND_SECRET))
        self.assertIn("saveGpuAppSecret", text(AND_SECRET))
        settings = text(AND_SETTINGS)
        self.assertIn("gpuAppKey", settings)
        self.assertIn("Chiave app GPU/manager", settings)

    def test_android_modes_and_reboot_via_laya(self) -> None:
        topbar = text(AND_TOPBAR)
        self.assertIn("postLayaCommand", topbar)
        self.assertNotIn("/mode/", topbar)
        server = text(AND_SERVER)
        self.assertIn("riavvia il manager", server)
        self.assertIn("RegisteredDevice", server)
        self.assertIn("postLayaCommand", text(AND_HTTP))

    def test_windows_gpu_app_key(self) -> None:
        self.assertIn("LoadGpuAppSecret", text(WIN_CRED))
        self.assertIn("GpuAppKeyBox", text(WIN_SETTINGS))
        gateway = text(WIN_GW)
        self.assertIn("LoadGpuAppSecret()", gateway)
        self.assertNotIn("hubkey = mgrkey", gateway)

    def test_android_image_browse_uses_app_key(self) -> None:
        # Regressione: il browse immagini (:8643/browse, ruolo app) riceveva
        # la chiave gateway -> 401. Solo la chiave app sul manager.
        server = (REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/server/ServerFeature.kt").read_text(encoding="utf-8")
        self.assertIn("loadImageBrowse(settings, managerKey", server)
        self.assertIn("loadGpuAppSecret(context)", server)

    def test_protocol_forbids_manager_to_agent(self) -> None:
        protocol = text(WIN_PROTOCOL)
        self.assertIn("GPU manager off-limits", protocol)
        self.assertIn("/laya/jobs", protocol)


if __name__ == "__main__":
    unittest.main()
