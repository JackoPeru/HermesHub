"""Contract test server-synced bot groups (richiesta utente 0.6.280).

La patch gateway (legacy_patcher.py::_patch_bot_groups_v1) deve registrare
GET/POST/DELETE /v1/hub/bot-groups con auth identica agli altri endpoint hub,
storage JSON limitato e normalizzazione identica alla lista Android
(nome 1..160 senza newline, 2..6 membri, identity_key unica
"connection_id::profile"). Il runtime viene eseguito davvero su un tempdir.
"""

import importlib.util
import json
import pathlib
import tempfile
import typing
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
PATCHER_PATH = ROOT / "scripts" / "patch-hermes-gateway-native.py"
FIXTURE_PATH = ROOT / "tests" / "fixtures" / "hermes-agent-v2026.7.7.2-api_server.py"


def load_patcher():
    spec = importlib.util.spec_from_file_location("bot_groups_gateway_patcher", PATCHER_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def bot_groups_runtime(patched: str, storage_root: pathlib.Path):
    begin = patched.index("# HERMES_HUB_BOT_GROUPS_RUNTIME_BEGIN")
    end = patched.index("# HERMES_HUB_BOT_GROUPS_RUNTIME_END", begin)
    namespace = {
        "Any": typing.Any,
        "Dict": typing.Dict,
        "Path": pathlib.Path,
        "time": __import__("time"),
        "re": __import__("re"),
    }

    def storage_path(env_name, default_name):
        assert env_name == "HERMES_HUB_BOT_GROUPS_PATH", env_name
        return storage_root / default_name

    namespace["_hermes_hub_storage_path"] = storage_path
    namespace["_hermes_hub_audit_event"] = lambda event: None
    durable: dict[pathlib.Path, dict] = {}

    def read_json(path, default):
        parsed = durable.get(path)
        if parsed is None:
            return dict(default)
        return parsed

    def write_json(path, payload):
        durable[path] = payload

    namespace["_hermes_hub_read_json"] = read_json
    namespace["_hermes_hub_write_json"] = write_json
    exec(compile(patched[begin:end], "<bot-groups-runtime>", "exec"), namespace)
    return namespace, durable


class BotGroupsGatewayPatchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.patcher = load_patcher()
        upstream = FIXTURE_PATH.read_text(encoding="utf-8")
        cls.patched, cls.changes = cls.patcher._patch_text(upstream)

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        root = pathlib.Path(self._tmp.name)
        self.runtime, self.durable = bot_groups_runtime(self.patched, root)
        self.addCleanup(self._tmp.cleanup)

    def test_patch_is_idempotent_with_authenticated_routes(self):
        second, changes = self.patcher._patch_text(self.patched)
        self.assertEqual(self.patched, second)
        self.assertEqual([], changes)
        self.assertIn("/v1/hub/bot-groups", self.patched)
        self.assertIn("/v1/hub/bot-groups/{group_name}", self.patched)
        handlers = self.patched.split("# HERMES_HUB_BOT_GROUPS_HANDLERS_BEGIN", 1)[1].split(
            "# HERMES_HUB_BOT_GROUPS_HANDLERS_END", 1
        )[0]
        # Tutti e tre i metodi autenticati, stesso pattern degli endpoint hub.
        self.assertEqual(3, handlers.count("auth_error = self._check_auth(request)"))
        self.assertIn("Content-Length required for bot group payload", handlers)
        self.assertIn('_hermes_hub_bot_groups_io, "put", "", group', handlers)
        self.assertIn('"hub_bot_groups": {"method": "GET/POST/DELETE", "path": "/v1/hub/bot-groups"}', self.patched)

    def test_upsert_list_and_delete_roundtrip(self):
        put = self.runtime["_hermes_hub_bot_groups_io"]
        group = {
            "name": "Team  =  Test",
            "members": [
                {
                    "connection_id": "primary",
                    "profile": "Laya",
                    "display_name": "Laya",
                    "handle": "@laya",
                },
                {
                    "connectionId": "connection-abc",
                    "profile": "NEMO_2",
                    "displayName": "Nemo Due",
                    "handle": "@nemo2",
                },
            ],
        }
        result = put("put", "", group)
        self.assertEqual("hermes.hub.bot-group", result["object"])
        details = self.runtime["_hermes_hub_bot_groups_payload"]()
        self.assertEqual(1, len(details["items"]))
        stored = details["items"][0]
        self.assertEqual("Team  =  Test", stored["name"])
        self.assertEqual(["primary::laya", "connection-abc::nemo_2"], [m["identity_key"] for m in stored["members"]])
        upserted = put("put", "", dict(group, name="different name"))
        self.assertEqual(2, len(self.runtime["_hermes_hub_bot_groups_payload"]()["items"]))
        # upsert con lo stesso nome (casefold) sostituisce, non duplica
        put("put", "", dict(group, name="team  =  test"))
        self.assertEqual(2, len(self.runtime["_hermes_hub_bot_groups_payload"]()["items"]))
        deleted = put("delete", "TEAM  =  TEST")
        self.assertTrue(deleted["deleted"])
        self.assertEqual(1, len(self.runtime["_hermes_hub_bot_groups_payload"]()["items"]))
        with self.assertRaises(KeyError):
            put("delete", "never-existed")

    def test_group_validation_fail_closed(self):
        put = self.runtime["_hermes_hub_bot_groups_io"]
        good = [
            {"connection_id": "primary", "profile": "laya"},
            {"connection_id": "connection-abc", "profile": "nemo_2"},
        ]
        with self.assertRaises(ValueError):  # 1 membro
            put("put", "", {"name": "a", "members": good[:1]})
        with self.assertRaises(ValueError):  # 7 membri
            put("put", "", {"name": "a", "members": good * 3 + good})
        with self.assertRaises(ValueError):  # profilo diaper
            put(
                "put",
                "",
                {
                    "name": "a",
                    "members": good + [{"connection_id": "c", "profile": "ILLEGAL NAME  ${x}"}],
                },
            )
        with self.assertRaises(ValueError):  # name con newline
            put("put", "", {"name": "riga1\nriga2", "members": good})
        with self.assertRaises(ValueError):  # duplicate identity
            put(
                "put",
                "",
                {
                    "name": "a",
                    "members": [{"connection_id": "p", "profile": "laya"}, {"connection_id": "p", "profile": "Laya"}],
                },
            )
        self.assertEqual([], self.runtime["_hermes_hub_bot_groups_payload"]()["items"])


if __name__ == "__main__":
    unittest.main()
