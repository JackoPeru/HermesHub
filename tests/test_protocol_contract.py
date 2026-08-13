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
