import importlib.util
import json
import re
import sys
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from hermes_hub_gateway.protocol import (  # noqa: E402
    build_error,
    build_event_envelope,
    new_correlation_context,
    parse_event_envelope,
)


SCHEMA_PATH = ROOT / "config" / "hermes-hub-protocol.schema.json"
FIXTURE_PATH = ROOT / "tests" / "contracts" / "hermes-hub-protocol-fixture.json"


class ProtocolContractTests(unittest.TestCase):
    def test_schema_and_golden_fixture_validate(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        fixture = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        errors = list(Draft202012Validator(schema).iter_errors(fixture))
        self.assertEqual([], errors)

    def test_golden_fixture_covers_unknown_event_and_stable_correlation(self):
        fixture = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
        known = fixture["event_envelope"]
        unknown = fixture["unknown_event_envelope"]
        self.assertNotEqual(known["type"], unknown["type"])
        self.assertEqual(known["request_id"], unknown["request_id"])
        self.assertEqual(known["correlation_id"], unknown["correlation_id"])
        self.assertEqual(1, fixture["protocol_version"])
        self.assertEqual(
            {
                "request_id": "X-Hermes-Request-Id",
                "correlation_id": "X-Hermes-Correlation-Id",
                "compatibility_request_id": "X-Request-Id",
            },
            fixture["correlation_headers"],
        )

    def test_client_wires_keep_legacy_routes_and_correlation_headers(self):
        windows_protocol = (
            ROOT / "src" / "NemoclawChat.Windows" / "Services" / "HermesHubProtocol.cs"
        ).read_text(encoding="utf-8")
        windows_gateway = (
            ROOT / "src" / "NemoclawChat.Windows" / "Services" / "GatewayService.cs"
        ).read_text(encoding="utf-8")
        windows_stream = (
            ROOT / "src" / "NemoclawChat.Windows" / "Services" / "ChatStream.cs"
        ).read_text(encoding="utf-8")
        android_protocol = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "HermesHubProtocol.kt"
        ).read_text(encoding="utf-8")
        android_stream = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "ChatStream.kt"
        ).read_text(encoding="utf-8")

        for source in (windows_protocol, android_protocol):
            self.assertIn("X-Hermes-Request-Id", source)
            self.assertIn("X-Hermes-Correlation-Id", source)
        self.assertIn("AddCorrelationHeaders", windows_gateway)
        self.assertIn("AddCorrelationHeaders", windows_stream)
        self.assertIn("addCorrelationHeaders", android_stream)
        self.assertIn("StreamEnvelopeMetadata", windows_stream)
        self.assertIn("EnvelopeMetadata", android_stream)
        self.assertIn("hermes.future.event.v2", json.dumps(json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))))
        self.assertRegex(windows_protocol, re.compile(r"ProtocolVersion\s*=\s*1"))

    def test_bot_profile_routing_is_encoded_and_fail_closed_on_both_clients(self):
        windows_gateway = (
            ROOT / "src" / "NemoclawChat.Windows" / "Services" / "GatewayService.cs"
        ).read_text(encoding="utf-8")
        windows_protocol = (
            ROOT / "src" / "NemoclawChat.Windows" / "Services" / "HermesHubProtocol.cs"
        ).read_text(encoding="utf-8")
        android_operations = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "core"
            / "HubOperations.kt"
        ).read_text(encoding="utf-8")
        gateway_module = (
            ROOT / "scripts" / "hermes_hub_gateway" / "bot_profiles.py"
        ).read_text(encoding="utf-8")

        self.assertIn("ProfileScopedUri", windows_protocol)
        self.assertIn("ProfileScopedApiUri", windows_protocol)
        self.assertIn("Uri.EscapeDataString", windows_protocol)
        self.assertIn("OpenBotChatAsync", windows_gateway)
        self.assertIn("CreateBotAsync", windows_gateway)
        self.assertIn("UpdateBotAsync", windows_gateway)
        self.assertIn("DeleteBotAsync", windows_gateway)
        self.assertIn('confirm_name', windows_gateway)
        self.assertIn("NormalizeBotRoutineName", windows_gateway)
        self.assertIn("/api/jobs", windows_gateway)
        self.assertIn("resolveHermesProfileUrl", android_operations)
        self.assertIn("resolveHermesProfileApiUrl", android_operations)
        self.assertIn("normalizeBotRoutineName", android_operations)
        self.assertIn("URLEncoder.encode", android_operations)
        self.assertIn("profile_multiplexing_disabled", gateway_module)
        self.assertIn('BOT_CHAT_TITLE = "Bot Chat"', gateway_module)
        self.assertNotIn("auth.json", gateway_module)

        android_bots = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "features"
            / "bots"
            / "BotsFeature.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("createHermesBot", android_bots)
        self.assertIn("updateHermesBot", android_bots)
        self.assertIn("deleteHermesBot", android_bots)
        self.assertIn('method = "PATCH"', android_bots)
        self.assertIn('method = "DELETE"', android_bots)
        self.assertIn('confirm_name', android_bots)

    def test_connections_groups_and_secure_tokens_are_source_qualified(self):
        windows_store = (
            ROOT / "src" / "NemoclawChat.Windows" / "Services" / "HermesBotConnections.cs"
        ).read_text(encoding="utf-8")
        windows_credentials = (
            ROOT / "src" / "NemoclawChat.Windows" / "Services" / "GatewayCredentialStore.cs"
        ).read_text(encoding="utf-8")
        windows_orchestration = (
            ROOT / "src" / "NemoclawChat.Windows" / "Services" / "HermesBotConnectionService.cs"
        ).read_text(encoding="utf-8")
        android_connections = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "features"
            / "bots"
            / "BotConnections.kt"
        ).read_text(encoding="utf-8")
        android_secrets = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "core"
            / "auth"
            / "GatewaySecretStore.kt"
        ).read_text(encoding="utf-8")
        android_chat = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "features"
            / "chat"
            / "ChatFeature.kt"
        ).read_text(encoding="utf-8")
        android_groups = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "features"
            / "bots"
            / "BotGroupTurn.kt"
        ).read_text(encoding="utf-8")
        android_bots = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "features"
            / "bots"
            / "BotsFeature.kt"
        ).read_text(encoding="utf-8")

        self.assertIn("NormalizeEndpoint(settings.GatewayUrl)", windows_store)
        self.assertIn("!string.IsNullOrWhiteSpace(settings.GatewayUrl)", windows_store)
        self.assertIn("ReadCustom(warnings)", windows_store)
        self.assertIn("LastLoadWarnings", windows_store)
        self.assertIn("HashSet<string>(StringComparer.OrdinalIgnoreCase)", windows_store)
        self.assertIn('"bot-connections.json"', windows_store)
        self.assertNotIn("Token", windows_store)
        self.assertIn("PasswordVault", windows_credentials)
        self.assertIn("HermesHub.Connection.", windows_credentials)
        self.assertNotIn("bot-connections.json", windows_credentials)

        self.assertIn("Task.WhenAll", windows_orchestration)
        self.assertIn("MemberLocks", windows_orchestration)
        self.assertIn("EnsureCurrentTurnAsync", windows_orchestration)
        self.assertIn("SendBotGroupTurnOnConnectionAsync", windows_orchestration)
        self.assertIn("original_user_message", windows_orchestration)
        self.assertIn("BuildFollowUpPrompt", windows_orchestration)
        self.assertIn("previous.Cancellation.Dispose()", windows_orchestration)
        self.assertIn("failedIdentities", windows_orchestration)
        self.assertIn('"member_failures"', windows_orchestration)
        self.assertIn("normalizedMembers", windows_store)
        self.assertIn("group_name", windows_orchestration)
        self.assertIn("max_rounds = 1", windows_orchestration)
        self.assertIn("max_messages = 1", windows_orchestration)
        self.assertIn("members.Count is < 2 or > 6", windows_store)

        self.assertIn('"hermes_bot_connections"', android_connections)
        self.assertIn("loadGatewayConnectionSecret", android_connections)
        self.assertIn("saveGatewayConnectionSecret", android_secrets)
        self.assertIn("AndroidKeyStore", android_secrets)
        self.assertNotIn("token", android_connections.lower())
        self.assertIn("loadGatewayConnectionSecret", android_chat)
        self.assertIn("botAllowCompatAuth", android_chat)
        self.assertIn("loadHermesBotGroups", android_bots)
        self.assertIn("upsertHermesBotGroup", android_bots)
        self.assertIn("deleteHermesBotGroup", android_bots)
        self.assertIn("Annulla turno", android_bots)
        self.assertIn("verticalScroll", android_bots)
        self.assertIn("assignStableBotHandles", android_bots)
        self.assertIn('"original_user_message"', android_groups)
        self.assertIn("turnEpochs", android_groups)
        self.assertNotIn("private val turnEpoch =", android_groups)
        self.assertIn('throw CancellationException("Turno gruppo annullato o sostituito")', android_groups)
        self.assertIn("failedIdentities", android_groups)
        self.assertIn('"member_failures"', android_groups)

    def test_cron_profile_scoping_keeps_global_default_and_prefixes_bot_names(self):
        windows_page = (
            ROOT / "src" / "NemoclawChat.Windows" / "Pages" / "CronPage.xaml.cs"
        ).read_text(encoding="utf-8")
        windows_gateway = (
            ROOT / "src" / "NemoclawChat.Windows" / "Services" / "GatewayService.cs"
        ).read_text(encoding="utf-8")
        android_automation = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "features"
            / "automation"
            / "AutomationFeature.kt"
        ).read_text(encoding="utf-8")
        android_operations = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "core"
            / "HubOperations.kt"
        ).read_text(encoding="utf-8")

        self.assertIn("ProfileScopedApiUri", windows_gateway)
        self.assertIn("NormalizeBotRoutineName", windows_gateway)
        self.assertIn("profileMultiplexEnabled", windows_gateway)
        self.assertIn("/api/jobs", windows_gateway)
        self.assertIn("SelectedProfile", windows_page)
        self.assertIn("resolveHermesProfileApiUrl", android_operations)
        self.assertIn("normalizeBotRoutineName", android_operations)
        self.assertIn("selectedBotProfile", android_automation)
        self.assertIn("Routine bot bloccate", android_automation)

    def test_bot_archive_reopen_is_fail_closed_and_resets_client_state(self):
        windows_home = (
            ROOT / "src" / "NemoclawChat.Windows" / "Pages" / "HomePage.xaml.cs"
        ).read_text(encoding="utf-8")
        windows_home_xaml = (
            ROOT / "src" / "NemoclawChat.Windows" / "Pages" / "HomePage.xaml"
        ).read_text(encoding="utf-8")
        android_root = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "features"
            / "navigation"
            / "AppRoot.kt"
        ).read_text(encoding="utf-8")
        android_chat = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "features"
            / "chat"
            / "ChatFeature.kt"
        ).read_text(encoding="utf-8")

        self.assertIn("LoadConversation(bot.LocalConversationId)", windows_home)
        self.assertIn("_previousResponseId = null", windows_home)
        self.assertIn('StartsWith("bot-", StringComparison.OrdinalIgnoreCase)', windows_home)
        self.assertIn("ShowArchivedBotGuard();", windows_home)
        self.assertIn("Chat bot archiviata: riaprila da Bot Hermes", windows_home)
        self.assertIn('Click="OpenBotHermes_Click"', windows_home_xaml)
        self.assertIn("chatState.resetForNewChat()", android_root)
        self.assertIn('startsWith("bot-", ignoreCase = true)', android_chat)
        self.assertIn("return@Composer", android_chat)
        self.assertIn("Chat bot archiviata: riaprila da Bot Hermes", android_chat)

    def test_validator_script_matches_golden_contract(self):
        path = ROOT / "scripts" / "validate-hermes-hub-protocol.py"
        spec = importlib.util.spec_from_file_location("validate_hermes_hub_protocol", path)
        module = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        spec.loader.exec_module(module)
        module.validate_protocol(SCHEMA_PATH, FIXTURE_PATH)

    def test_gateway_primitives_round_trip_envelope_and_error(self):
        context = new_correlation_context()
        envelope = build_event_envelope(
            event_type="hermes.future.event.v2",
            payload={"safe": True},
            sequence=3,
            context=context,
            source_type="hermes-gateway",
        )
        parsed = parse_event_envelope(envelope)
        self.assertIsNotNone(parsed)
        assert parsed is not None
        self.assertEqual(context.request_id, parsed.request_id)
        self.assertEqual(context.correlation_id, parsed.correlation_id)
        self.assertEqual("hermes.future.event.v2", parsed.event_type)

        error = build_error(
            code="gateway_unavailable",
            message="Gateway non raggiungibile.",
            context=context,
            retryable=True,
        )
        self.assertEqual(context.request_id, error["request_id"])
        self.assertTrue(error["retryable"])


if __name__ == "__main__":
    unittest.main()
