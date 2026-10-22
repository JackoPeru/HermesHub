"""Controllo remoto Android (phone_use): coda gateway + esecutore armato.

- Gateway: accoda comandi tipizzati (Bearer, anche agente per progetto),
  poll, singolo risultato; validazione bounds, TTL 10 min, cap 50, audit.
- Android: AccessibilityService armato esplicitamente, kill switch, niente
  screenshot (solo albero UI), log locale.
- Windows: pannello invio comandi. Agente istruito via protocollo.
"""

import ast
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

PATCHER = REPO / "scripts/hermes_hub_gateway/adapters/hermes/legacy_patcher.py"
FIXTURE = REPO / "tests/fixtures/hermes-agent-0a62610f1-api_server.py"
SERVICE = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/phone/HermesPhoneService.kt"
PHONE_CTL = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/phone/PhoneControl.kt"
MANIFEST = REPO / "src/NemoclawChat.Android/app/src/main/AndroidManifest.xml"
RES_XML = REPO / "src/NemoclawChat.Android/app/src/main/res/xml/phone_control_config.xml"
SETTINGS = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/settings/SettingsFeature.kt"
AND_TEST = REPO / "src/NemoclawChat.Android/app/src/test/java/com/nemoclaw/chat/features/phone/PhoneControlTest.kt"
WIN_PAGE = REPO / "src/NemoclawChat.Windows/Pages/ServerPage.xaml"
WIN_CODE = REPO / "src/NemoclawChat.Windows/Pages/ServerPage.xaml.cs"
PROTOCOL = REPO / "src/NemoclawChat.Windows/Services/HermesHubProtocol.cs"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class PhoneGatewayTests(unittest.TestCase):
    def test_routes_handlers_validators(self) -> None:
        src = text(PATCHER)
        for marker in (
            "_patch_phone_control_v1",
            "_hermes_hub_phone_validate",
            "_handle_hub_phone_commands",
            "_handle_hub_phone_poll",
            "_handle_hub_phone_one",
            "_handle_hub_phone_result",
            '("POST", "/v1/hub/phone/commands", self._handle_hub_phone_commands)',
            '("GET", "/v1/hub/phone/commands", self._handle_hub_phone_poll)',
            '"tap"',
            '"dump_tree"',
            "expires_at",
        ):
            self.assertIn(marker, src)

    def test_generated_gateway_wires_phone(self) -> None:
        import sys
        sys.path.insert(0, str(REPO / "scripts"))
        from hermes_hub_gateway.adapters.hermes.legacy_patcher import _patch_text
        generated, _ = _patch_text(FIXTURE.read_text(encoding="utf-8"))
        compile(generated, str(FIXTURE), "exec")
        for marker in (
            "async def _handle_hub_phone_commands",
            '("POST", "/v1/hub/phone/commands/{command_id}/result"',
        ):
            self.assertIn(marker, generated)

    def test_validation_bounds(self) -> None:
        import sys
        sys.path.insert(0, str(REPO / "scripts"))
        from hermes_hub_gateway.adapters.hermes.legacy_patcher import _patch_text
        generated, _ = _patch_text(FIXTURE.read_text(encoding="utf-8"))
        tree = ast.parse(generated)
        wanted = [n for n in ast.walk(tree)
                  if isinstance(n, ast.FunctionDef) and n.name in {
                      "_hermes_hub_phone_validate", "_hermes_hub_phone_kinds"}]
        self.assertEqual(2, len(wanted))
        ns: dict = {}
        import typing
        ns.update({"Dict": typing.Dict, "Any": typing.Any, "Optional": typing.Optional})
        exec(compile(ast.Module(body=wanted, type_ignores=[]), "phone_validate", "exec"), ns)  # noqa: S102
        validate = ns["_hermes_hub_phone_validate"]
        self.assertIsNone(validate("tap", {"x": 1, "y": 2}))
        self.assertIsNotNone(validate("tap", {"x": 99999, "y": 2}))
        self.assertIsNone(validate("key", {"name": "home"}))
        self.assertIsNotNone(validate("key", {"name": "power"}))
        self.assertIsNone(validate("open_app", {"package": "com.example.app"}))
        self.assertIsNotNone(validate("open_app", {"package": "../evil"}))
        self.assertIsNone(validate("dump_tree", {}))
        self.assertIsNotNone(validate("launch_missiles", {}))
        self.assertIsNotNone(validate("text", {"text": ""}))


class PhoneAndroidTests(unittest.TestCase):
    def test_service_and_manifest(self) -> None:
        service = text(SERVICE)
        for marker in (
            "AccessibilityService",
            "dispatchGesture",
            "ACTION_SET_TEXT",
            "performGlobalAction",
            "Kill switch",
            "ACTION_STOP",
        ):
            self.assertIn(marker, service)
        manifest = text(MANIFEST)
        self.assertIn("HermesPhoneService", manifest)
        self.assertIn("BIND_ACCESSIBILITY", manifest)
        self.assertIn("phone_control_config", manifest)
        # Niente QUERY_ALL_PACKAGES: visibilita' via <queries> LAUNCHER.
        self.assertNotIn("QUERY_ALL_PACKAGES", manifest)
        self.assertIn("android.intent.category.LAUNCHER", manifest)
        self.assertTrue(RES_XML.is_file())

    def test_settings_and_helpers(self) -> None:
        settings = text(SETTINGS)
        self.assertIn("Controllo telefono", settings)
        self.assertIn("Arma controllo remoto", settings)
        ctl = text(PHONE_CTL)
        for marker in (
            "isPhoneArmed",
            "setPhoneArmed",
            "isPhoneAccessibilityEnabled",
            "formatUiTree",
            "collectUiTree",
        ):
            self.assertIn(marker, ctl)

    def test_android_unit_test_covers_formatter(self) -> None:
        self.assertIn("formatter indents and flags clickable", text(AND_TEST))

    def test_windows_panel(self) -> None:
        self.assertIn("TELEFONO REMOTO", text(WIN_PAGE))
        self.assertIn("PhoneKindBox", text(WIN_PAGE))
        code = text(WIN_CODE)
        self.assertIn("PhoneSend_Click", code)
        self.assertIn("/v1/hub/phone/commands", code)

    def test_protocol_documents_phone_use(self) -> None:
        protocol = text(PROTOCOL)
        self.assertIn("phone_use", protocol)
        self.assertIn("dump_tree", protocol)


if __name__ == "__main__":
    unittest.main()
