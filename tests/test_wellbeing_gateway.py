import importlib.util
import pathlib
import typing
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
PATCHER_PATH = ROOT / "scripts" / "patch-hermes-gateway-native.py"
FIXTURE_PATH = ROOT / "tests" / "fixtures" / "hermes-agent-v2026.7.7.2-api_server.py"


def load_patcher():
    spec = importlib.util.spec_from_file_location("wellbeing_gateway_patcher", PATCHER_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def wellbeing_runtime(patched: str):
    begin = patched.index("# WELLBEING_HERMES_HUB_RUNTIME_BEGIN")
    end = patched.index("# WELLBEING_HERMES_HUB_RUNTIME_END", begin) + len("# WELLBEING_HERMES_HUB_RUNTIME_END")
    namespace = {"Any": typing.Any, "Dict": typing.Dict, "Optional": typing.Optional, "time": __import__("time")}
    exec(compile(patched[begin:end], "<wellbeing-runtime>", "exec"), namespace)
    return namespace


class WellbeingGatewayPatchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.patcher = load_patcher()
        upstream = FIXTURE_PATH.read_text(encoding="utf-8")
        cls.patched, cls.changes = cls.patcher._patch_text(upstream)
        cls.runtime = wellbeing_runtime(cls.patched)

    def test_patch_is_idempotent_and_uses_authenticated_daily_routes(self):
        second, changes = self.patcher._patch_text(self.patched)
        self.assertEqual(self.patched, second)
        self.assertEqual([], changes)
        for route in (
            "/v1/hub/wellbeing",
            "/v1/hub/wellbeing/daily/{date}",
        ):
            self.assertIn(route, self.patched)
        handlers = self.patched.split("# HERMES_HUB_WELLBEING_HANDLERS_BEGIN", 1)[1].split(
            "# HERMES_HUB_WELLBEING_HANDLERS_END", 1
        )[0]
        self.assertEqual(5, handlers.count("auth_error = self._check_auth(request)"))
        self.assertIn('Content-Length required for wellbeing payload', handlers)
        self.assertIn('_hermes_hub_wellbeing_io, "put", date, body', handlers)
        self.assertIn('"hub_wellbeing": {"method": "GET/PUT/DELETE", "path": "/v1/hub/wellbeing"}', self.patched)

    def test_only_valid_daily_aggregates_are_accepted(self):
        normalize = self.runtime["_hermes_hub_normalize_wellbeing"]
        item = normalize(
            "2026-07-26",
            {
                "date": "2026-07-26",
                "source": "health_connect",
                "summary": {
                    "steps": 8123,
                    "sleep_minutes": 451,
                    "heart_rate_bpm": {"average": 62, "min": 48, "max": 121},
                },
                "raw_records": [],
            },
        )
        self.assertEqual(8123, item["summary"]["steps"])
        self.assertTrue(item["wellness_only"])
        with self.assertRaisesRegex(ValueError, "raw_records"):
            normalize("2026-07-26", {"date": "2026-07-26", "summary": {}, "raw_records": [{"heart": 61}]})
        with self.assertRaisesRegex(ValueError, "minimum"):
            normalize(
                "2026-07-26",
                {"date": "2026-07-26", "summary": {"heart_rate_bpm": {"min": 100, "max": 50}}},
            )

    def test_android_contract_uses_health_connect_background_permission_and_no_raw_upload(self):
        manifest = (ROOT / "src" / "NemoclawChat.Android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
        source = (ROOT / "src" / "NemoclawChat.Android" / "app" / "src" / "main" / "java" / "com" / "nemoclaw" / "chat" / "HealthSync.kt").read_text(encoding="utf-8")
        activity = (ROOT / "src" / "NemoclawChat.Android" / "app" / "src" / "main" / "java" / "com" / "nemoclaw" / "chat" / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("READ_HEALTH_DATA_IN_BACKGROUND", manifest)
        self.assertIn("HealthPermission.getReadPermission", source)
        self.assertIn('"raw_records", JSONArray()', source)
        self.assertIn('"${wellbeingCollectionUrl(settings)}/daily/$date"', source)
        self.assertIn("HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND", source)
        self.assertIn("HealthEraseResult.Success", source)
        self.assertIn("HealthHistoryResult", source)
        self.assertIn("Rate limited request quota", source)
        self.assertNotIn("error.code == 429 && error.body.contains", source)
        self.assertIn("Gateway Hermes senza endpoint Salute", source)
        self.assertIn("HealthConnectClient.getSdkStatus(context)", source)
        self.assertIn("PermissionController.createRequestPermissionResultContract()", source)
        self.assertNotIn("com.google.android.apps.healthdata", source)
        self.assertIn("aggregateGroupByPeriod", source)
        self.assertIn("HEALTH_QUOTA_COOLDOWN_MS", source)
        self.assertIn("isHealthConnectRateLimit", source)
        self.assertIn("HealthDashboardScreen", activity)
        self.assertIn("Passi · ultimi 7 giorni", activity)
        self.assertIn("Watch → Samsung Health → Health Connect → Hermes", activity)


if __name__ == "__main__":
    unittest.main()
