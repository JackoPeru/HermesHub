"""Contract test gruppi bot sincronizzati (richiesta utente 0.6.280).

Lato Android i gruppi devono passare dal gateway (server = fonte di verita
quando raggiungibile, cache locale come fallback offline) via
load/upsert/deleteHermesBotGroupSynced su /v1/hub/bot-groups con il secret
del gateway primario. La UI di BotsFeature non deve piu scrivere gruppi
solo-locale.
"""

import pathlib
import sys
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tests"))

from android_sources import read_android_bots_ui  # noqa: E402

BOT_CONNECTIONS = ROOT / "src" / "NemoclawChat.Android" / "app" / "src" / "main" / "java" / "com" / "nemoclaw" / "chat" / "features" / "bots" / "BotConnections.kt"


class BotGroupsAndroidContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.connections = BOT_CONNECTIONS.read_text(encoding="utf-8")
        cls.feature = read_android_bots_ui()

    def test_synced_operations_target_primary_gateway(self):
        count = self.connections.count("/v1/hub/bot-groups")
        self.assertEqual(3, count)  # GET, PUT, DELETE
        self.assertIn("internal suspend fun loadHermesBotGroupsSynced(context: Context, settings: AppSettings)", self.connections)
        self.assertIn("internal suspend fun upsertHermesBotGroupSynced(context: Context, settings: AppSettings, group: HermesBotGroup): String", self.connections)
        self.assertIn("internal suspend fun deleteHermesBotGroupSynced(context: Context, settings: AppSettings, name: String): String", self.connections)
        for token in ("loadGatewaySecret(context)",):
            for signature in ("hermesBotGroupsGet(context: Context, settings: AppSettings)", "hermesBotGroupsPut(context: Context, settings: AppSettings, item: HermesBotGroup)"):
                start = self.connections.index(signature)
                self.assertIn(token, self.connections[start:start + 800], signature)

    def test_offline_pending_repairs_never_lose_user_actions(self):
        self.assertIn('BOT_GROUPS_PENDING_PUSH_KEY = "pending_push"', self.connections)
        self.assertIn('BOT_GROUPS_PENDING_DELETE_KEY = "pending_delete"', self.connections)
        self.assertIn('BOT_GROUPS_MIGRATED_KEY = "migrated_v1"', self.connections)
        # Push differito al primo load online, prima di sovrascrivere la cache.
        runtime = self.connections.split("internal suspend fun loadHermesBotGroupsSynced", 1)[1]
        self.assertIn("hermesBotGroupsDelete(context, settings, name)", runtime)
        self.assertIn("hermesBotGroupsPut(context, settings, group)", runtime)
        # Prima sincronizzazione: i gruppi solo-locali vengono migrati sul
        # server invece di essere cancellati; poi vale il server.
        self.assertIn("if (!botGroupsMigrated(context))", runtime)
        self.assertIn("markBotGroupsMigrated(context)", runtime)
        # I rifiuti definitivi (4xx) escono dai pending, 0/5xx restano.
        self.assertIn("keepPendingPush(putStatus)", runtime)
        # Upsert online aggiorna la cache speculare; offline solo locale + pending.
        upsert = self.connections.split("internal suspend fun upsertHermesBotGroupSynced", 1)[1]
        self.assertIn("addPendingGroupName(context, BOT_GROUPS_PENDING_PUSH_KEY, normalized.name)", upsert)
        self.assertIn('error("Gateway ha rifiutato il gruppo (HTTP $status).")', upsert)
        # Delete: 404 = gia assente, offline = pending delete, 5xx = errore senza perdita dati.
        delete = self.connections.split("internal suspend fun deleteHermesBotGroupSynced", 1)[1]
        self.assertIn("status != 0 && status != 404 && status !in 200..299", delete)
        self.assertIn("addPendingGroupName(context, BOT_GROUPS_PENDING_DELETE_KEY, normalized)", delete)

    def test_feature_ui_calls_synced_endpoints(self):
        self.assertIn("groups = loadHermesBotGroupsSynced(context, settings)", self.feature)
        self.assertIn("upsertHermesBotGroupSynced(", self.feature)
        self.assertIn("deleteHermesBotGroupSynced(", self.feature)
        # Nessuna scrittura gruppo solo-locale rimasta nella scheda Bot.
        for forbidden in (
            "groups = loadHermesBotGroups(context)",
            "upsertHermesBotGroup(context,",
            "deleteHermesBotGroup(context,",
        ):
            self.assertNotIn(forbidden, self.feature)

    def test_server_and_client_normalizers_stay_in_lockstep(self):
        runtime = self.connections.split("# HERMES_HUB_BOT_GROUPS", 1)
        # Android: 2..6 membri, nome 1..160 senza newline.
        self.assertIn("require(group.members.size in 2..6)", self.connections)
        self.assertIn("name.length <= 160", self.connections)
        deployed = (ROOT / "scripts" / "hermes_hub_gateway" / "adapters" / "hermes" / "legacy_patcher.py").read_text(encoding="utf-8")
        self.assertIn("if not 2 <= len(members_raw) <= 6:", deployed)
        self.assertIn("if not name or len(name) > 160", deployed)
        self.assertIn('raise ValueError("group name must be 1..160 chars without newlines")', deployed)


if __name__ == "__main__":
    unittest.main()
