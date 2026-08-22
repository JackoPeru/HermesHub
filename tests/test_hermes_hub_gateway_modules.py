import asyncio
import json
import sys
import tempfile
import time
import types
import unittest
from contextlib import contextmanager
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from hermes_hub_gateway.adapters.hermes.agent_runtime import (  # noqa: E402
    AgentRunRequest,
    AgentRunResult,
    HermesAgentRuntimeAdapter,
)
from hermes_hub_gateway.adapters.hermes.correlation_runtime import _enrich_sse_chunk  # noqa: E402
from hermes_hub_gateway import bot_profiles  # noqa: E402
from hermes_hub_gateway.infrastructure.sqlite import SQLiteHubStateStore  # noqa: E402
from hermes_hub_gateway.infrastructure.runtime_store import HubRuntimeStore  # noqa: E402
from hermes_hub_gateway.modules.jarvis import (  # noqa: E402
    JarvisLifecycle,
    JarvisPhase,
    SingleModelGpuGate,
)
from hermes_hub_gateway.modules.storage import HubRecord, HubStorageError  # noqa: E402
from hermes_hub_gateway.modules.sync import RevisionSyncEngine  # noqa: E402
from hermes_hub_gateway.protocol import (  # noqa: E402
    CorrelationContext,
    correlation_context_from_headers,
    parse_event_envelope,
)


class SQLiteHubStateStoreTests(unittest.TestCase):
    def test_migrations_wal_foreign_keys_and_lww_tombstones(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "hub.sqlite"
            store = SQLiteHubStateStore(path)

            with store._connection() as connection:
                self.assertEqual(1, connection.execute("PRAGMA foreign_keys").fetchone()[0])
                self.assertEqual("wal", connection.execute("PRAGMA journal_mode").fetchone()[0].lower())
                self.assertGreaterEqual(connection.execute("PRAGMA busy_timeout").fetchone()[0], 5_000)

            created = store.upsert(
                HubRecord("conversation", "c-1", {"title": "Prima"}, updated_at=10)
            )
            self.assertIsNotNone(created)
            self.assertEqual(1, created.revision)
            self.assertIsNone(
                store.upsert(HubRecord("conversation", "c-1", {"title": "Obsoleta"}, updated_at=9))
            )

            deleted = store.upsert(
                HubRecord("conversation", "c-1", {"title": "Prima"}, updated_at=11, deleted=True)
            )
            self.assertIsNotNone(deleted)
            self.assertEqual(2, deleted.revision)
            self.assertEqual(2, store.current_revision())
            self.assertTrue(store.get("conversation", "c-1").deleted)
            changes = store.changes_since(0)
            self.assertEqual([1, 2], [change.revision for change in changes])
            self.assertTrue(changes[-1].record.deleted)


class HubRuntimeStoreMigrationTests(unittest.TestCase):
    def test_json_migration_preserves_source_and_imports_tombstones_and_revisions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "hub_conversations.json"
            now_ms = int(time.time() * 1000)
            source_payload = {
                "items": [
                    {"id": "c-1", "title": "Prima", "updatedAt": now_ms - 1000},
                    {"id": "c-deleted", "title": "Rimossa", "updatedAt": now_ms, "deletedAt": now_ms},
                ]
            }
            source.write_text(json.dumps(source_payload), encoding="utf-8")
            original = source.read_bytes()
            runtime = HubRuntimeStore(root / "hub.sqlite3")

            report = runtime.migrate_legacy_json("conversation", source)
            self.assertTrue(report.migrated)
            self.assertFalse(report.failed)
            self.assertEqual(2, report.source_count)
            self.assertEqual(2, report.imported_count)
            self.assertEqual(1, report.tombstone_count)
            self.assertEqual(2, report.revision)
            self.assertEqual(original, source.read_bytes())

            payload = runtime.conversations_payload(source)
            self.assertEqual(2, len(payload["items"]))
            self.assertEqual(2, payload["revision"])
            self.assertTrue(any(item.get("deletedAt") for item in payload["items"]))
            self.assertEqual(2, len(runtime.changes_since(0)["changes"]))
            repeated = runtime.migrate_legacy_json("conversation", source)
            self.assertEqual(2, repeated.revision)
            self.assertEqual(2, runtime.sqlite.current_revision())

    def test_failed_json_migration_rolls_back_and_leaves_legacy_file_unchanged(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "hub_conversations.json"
            source.write_text(
                json.dumps(
                    {
                        "items": [
                            {"id": "valid", "updatedAt": 1, "title": "ok"},
                            {"id": "bad", "updatedAt": 2, "title": "bad"},
                        ]
                    }
                ),
                encoding="utf-8",
            )
            original = source.read_bytes()

            class BrokenRuntimeStore(HubRuntimeStore):
                def _record_from_legacy(self, entity_type, item):
                    record = super()._record_from_legacy(entity_type, item)
                    if record is not None and record.entity_id == "bad":
                        return HubRecord(entity_type, "bad", {"value": {1, 2}}, record.updated_at)
                    return record

            runtime = BrokenRuntimeStore(root / "hub.sqlite3")
            report = runtime.migrate_legacy_json("conversation", source, raise_on_error=False)
            self.assertTrue(report.failed)
            self.assertEqual(0, runtime.sqlite.current_revision())
            self.assertEqual(original, source.read_bytes())

            source.write_text("{not-json", encoding="utf-8")
            failed_source = source.read_bytes()
            report = runtime.migrate_legacy_json("conversation", source, raise_on_error=False)
            self.assertTrue(report.failed)
            self.assertEqual(0, runtime.sqlite.current_revision())
            self.assertEqual(failed_source, source.read_bytes())

    def test_import_rolls_back_all_records_when_a_later_payload_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = SQLiteHubStateStore(Path(temporary) / "hub.sqlite")
            with self.assertRaises(TypeError):
                store.apply_import(
                    (
                        HubRecord("conversation", "valid", {"ok": True}, updated_at=1),
                        HubRecord("conversation", "invalid", {"bad": {1, 2}}, updated_at=1),
                    )
                )
            self.assertIsNone(store.get("conversation", "valid"))
            self.assertEqual(0, store.current_revision())

    def test_invalid_import_is_rejected_before_any_write(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = SQLiteHubStateStore(Path(temporary) / "hub.sqlite")
            with self.assertRaises(HubStorageError):
                store.apply_import(
                    (
                        HubRecord("conversation", "valid", {"ok": True}, updated_at=1),
                        {"entity_type": "", "entity_id": "missing", "updated_at": 1},
                    )
                )
            self.assertEqual(0, store.current_revision())


class RevisionSyncEngineTests(unittest.TestCase):
    def test_sse_wake_is_canonical_metadata_only_and_rest_pulls_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = SQLiteHubStateStore(Path(temporary) / "hub.sqlite")
            wakes = []
            engine = RevisionSyncEngine(store, wake_callback=wakes.append)
            change = engine.upsert(
                HubRecord("conversation", "c-1", {"private": "not in wake"}, updated_at=1),
                reason="archive-save",
            )
            self.assertIsNotNone(change)
            self.assertEqual(1, len(wakes))
            self.assertEqual(("conversation", "c-1"), wakes[0].changed_entities[0])

            event = engine.wake_event(
                wakes[0],
                context=CorrelationContext("req_test", "corr_test"),
                event_id="evt_test",
            )
            self.assertEqual("hub.sync.wake", event["type"])
            self.assertNotIn("private", json.dumps(event))
            parsed = parse_event_envelope(event)
            self.assertIsNotNone(parsed)
            self.assertEqual(1, parsed.sequence)
            self.assertEqual(("conversation", "c-1"), (parsed.payload["changed_entities"][0]["entity_type"], parsed.payload["changed_entities"][0]["entity_id"]))
            pulled = engine.changes_since(0)
            self.assertEqual(1, len(pulled))
            self.assertEqual("not in wake", pulled[0].record.payload["private"])

    def test_stale_write_does_not_emit_a_wake(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = SQLiteHubStateStore(Path(temporary) / "hub.sqlite")
            wakes = []
            engine = RevisionSyncEngine(store, wake_callback=wakes.append)
            engine.upsert(HubRecord("note", "n-1", {}, updated_at=2))
            self.assertIsNone(engine.upsert(HubRecord("note", "n-1", {"stale": True}, updated_at=1)))
            self.assertEqual(1, len(wakes))


class AgentRuntimeAdapterTests(unittest.TestCase):
    def test_adapter_preserves_hub_correlation_and_agent_ownership(self):
        calls = []

        class FakeHermesAgent:
            async def execute(self, **kwargs):
                calls.append(kwargs)
                return AgentRunResult(
                    request_id=kwargs["request_id"],
                    correlation_id=kwargs["correlation_id"],
                    run_id=kwargs["run_id"],
                    text="agent result",
                )

            async def cancel(self, request_id, *, correlation_id=None):
                calls.append({"cancel": request_id, "correlation_id": correlation_id})

        async def exercise():
            adapter = HermesAgentRuntimeAdapter(FakeHermesAgent())
            request = AgentRunRequest(
                request_id="req_test",
                correlation_id="corr_test",
                run_id="run_test",
                prompt="private prompt",
            )
            result = await adapter.run(request)
            await adapter.cancel(request.request_id, correlation_id=request.correlation_id)
            return result

        result = asyncio.run(exercise())
        self.assertEqual("agent result", result.text)
        self.assertEqual("req_test", calls[0]["request_id"])
        self.assertEqual("corr_test", calls[0]["correlation_id"])
        self.assertEqual({"cancel": "req_test", "correlation_id": "corr_test"}, calls[1])


class CorrelationSseGoldenTests(unittest.TestCase):
    def test_chat_tool_complete_error_and_cancel_events_keep_legacy_payloads(self):
        context = CorrelationContext("req_golden", "corr_golden")

        class Response:
            _hermes_hub_correlation_context = context

        response = Response()
        events = (
            ("chat.completion.chunk", {"choices": [{"delta": {"content": "  "}}]}),
            ("response.function_call_arguments.delta", {"type": "tool.delta", "delta": "  "}),
            ("response.completed", {"type": "response.completed", "response": {"id": "resp_test"}}),
            ("error", {"type": "error", "error": {"code": "cancelled", "message": "stopped"}}),
            ("run.cancelled", {"event": "run.cancelled", "run_id": "run_test"}),
        )
        for sequence, (name, payload) in enumerate(events, start=1):
            raw = f"event: {name}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n".encode()
            data = _enrich_sse_chunk(response, raw).decode("utf-8")
            envelope = json.loads(data.split("data: ", 1)[1].split("\n", 1)[0])
            parsed = parse_event_envelope(envelope)
            self.assertIsNotNone(parsed)
            assert parsed is not None
            self.assertEqual(sequence, parsed.sequence)
            self.assertEqual("req_golden", parsed.request_id)
            self.assertEqual("corr_golden", parsed.correlation_id)
            self.assertEqual(payload, parsed.payload)
            if name == "chat.completion.chunk":
                self.assertEqual("  ", parsed.payload["choices"][0]["delta"]["content"])
            if name == "run.cancelled":
                self.assertEqual("run_test", parsed.run_id)

    def test_invalid_ingress_ids_are_replaced_as_a_pair(self):
        context = correlation_context_from_headers(
            {
                "X-Hermes-Request-Id": "bad id with spaces",
                "X-Hermes-Correlation-Id": "corr_client",
            }
        )
        self.assertNotEqual("bad id with spaces", context.request_id)
        self.assertNotEqual("corr_client", context.correlation_id)


class JarvisLifecycleTests(unittest.TestCase):
    def test_single_worker_latest_frame_voice_priority_and_cleanup_order(self):
        lifecycle = JarvisLifecycle(max_frame_bytes=32)
        lifecycle.start()
        self.assertEqual(JarvisPhase.STARTING, lifecycle.phase)
        with self.assertRaises(RuntimeError):
            lifecycle.start()
        lifecycle.mark_streaming()
        self.assertTrue(lifecycle.submit_frame("f1", b"one"))
        self.assertTrue(lifecycle.submit_frame("f2", b"two"))
        self.assertTrue(lifecycle.submit_frame("f3", b"three"))
        self.assertEqual("f1", lifecycle.begin_frame().frame_id)
        lifecycle.complete_frame()
        self.assertEqual("f3", lifecycle.active_frame.frame_id)
        self.assertTrue(lifecycle.submit_voice_request("  speak first  "))
        self.assertEqual("speak first", lifecycle.next_voice_request())
        lifecycle.stop()
        self.assertEqual(JarvisPhase.IDLE, lifecycle.phase)
        self.assertFalse(lifecycle.worker_started)
        self.assertEqual(list(JarvisLifecycle.CLEANUP_ORDER), lifecycle.cleanup_log)
        self.assertIsNone(lifecycle.active_frame)
        self.assertIsNone(lifecycle.pending_frame)

    def test_failure_is_visible_and_cleanup_is_idempotent(self):
        lifecycle = JarvisLifecycle()
        lifecycle.start()
        lifecycle.mark_streaming()
        lifecycle.fail("stream closed")
        self.assertEqual(JarvisPhase.FAILED, lifecycle.phase)
        self.assertEqual("stream closed", lifecycle.last_error)
        lifecycle.fail("second cause")
        self.assertEqual(list(JarvisLifecycle.CLEANUP_ORDER), lifecycle.cleanup_log)
        lifecycle.stop()
        self.assertEqual(JarvisPhase.IDLE, lifecycle.phase)

    def test_model_gate_allows_one_owner(self):
        gate = SingleModelGpuGate()
        with gate:
            self.assertFalse(gate.acquire(timeout=0.001))
        self.assertTrue(gate.acquire(timeout=0.001))
        gate.release()


class GatewayBootstrapTests(unittest.TestCase):
    def test_public_patcher_is_a_thin_bootstrap_with_legacy_adapter(self):
        patcher = ROOT / "scripts" / "patch-hermes-gateway-native.py"
        legacy = SCRIPTS / "hermes_hub_gateway" / "adapters" / "hermes" / "legacy_patcher.py"
        self.assertLess(len(patcher.read_text(encoding="utf-8").splitlines()), 100)
        self.assertTrue(legacy.is_file())
        self.assertIn("from hermes_hub_gateway.api.bootstrap import run_patcher", patcher.read_text(encoding="utf-8"))


class BotProfilesContractTests(unittest.TestCase):
    def test_profile_names_are_normalized_and_reject_path_segments(self):
        self.assertEqual("helper_2", bot_profiles.validate_profile_name(" Helper_2 "))
        for invalid in ("", ".", "..", "../outside", "a/b", "a\\b", "A B", "x" * 65):
            with self.subTest(invalid=invalid):
                with self.assertRaises(bot_profiles.BotProfileError):
                    bot_profiles.validate_profile_name(invalid)

    def test_readiness_and_canonical_chat_fail_closed_without_multiplexing(self):
        profiles = types.SimpleNamespace(
            profiles_to_serve=lambda multiplex=True: [("helper", Path("/srv/helper"))],
            get_profile_dir=lambda name: Path("/srv") / name,
            create_profile=lambda **kwargs: None,
        )
        adapter = types.SimpleNamespace(
            gateway_runner=types.SimpleNamespace(
                config=types.SimpleNamespace(multiplex_profiles=False)
            )
        )
        with mock.patch.object(bot_profiles, "_profiles_module", return_value=profiles):
            readiness = bot_profiles.readiness(adapter)
            self.assertFalse(readiness["bot_mode_protocol"])
            self.assertFalse(readiness["bot_mode_protocol_known"])
            self.assertFalse(readiness["multiplex_enabled"])
            self.assertFalse(readiness["chat_supported"])
            self.assertTrue(readiness["capabilities"]["create"])
            self.assertFalse(readiness["capabilities"]["delete"])
            self.assertFalse(readiness["capabilities"]["canonical_chat"])
            with self.assertRaises(bot_profiles.BotProfileError) as raised:
                bot_profiles.canonical_chat(adapter, "helper")
        self.assertEqual("profile_multiplexing_disabled", raised.exception.code)
        self.assertEqual(503, raised.exception.status)

    def test_profile_primitives_do_not_overclaim_bot_mode_protocol(self):
        profiles = types.SimpleNamespace(
            profiles_to_serve=lambda multiplex=True: [("helper", Path("/srv/helper"))],
            get_profile_dir=lambda name: Path("/srv") / name,
            create_profile=lambda **kwargs: None,
        )
        adapter = types.SimpleNamespace(
            gateway_runner=types.SimpleNamespace(
                config=types.SimpleNamespace(multiplex_profiles=True)
            )
        )
        with mock.patch.object(bot_profiles, "_profiles_module", return_value=profiles):
            readiness = bot_profiles.readiness(adapter)
        self.assertFalse(readiness["bot_mode_protocol"])
        self.assertFalse(readiness["bot_mode_protocol_known"])
        self.assertTrue(readiness["capabilities"]["roster"])
        self.assertTrue(readiness["capabilities"]["create"])
        self.assertTrue(readiness["chat_supported"])

    def test_create_profile_never_enables_clone_all(self):
        captured = {}

        def create_profile(
            name,
            clone_from=None,
            clone_all=True,
            clone_config=False,
            no_skills=False,
            description=None,
        ):
            captured.update(
                name=name,
                clone_from=clone_from,
                clone_all=clone_all,
                clone_config=clone_config,
                no_skills=no_skills,
                description=description,
            )

        profiles = types.SimpleNamespace(
            profiles_to_serve=lambda multiplex=True: [],
            get_profile_dir=lambda name: Path("/srv") / name,
            create_profile=create_profile,
        )
        with mock.patch.object(bot_profiles, "_profiles_module", return_value=profiles):
            bot_profiles._call_create_profile("child", "source", False, "Desc")

        self.assertFalse(captured["clone_all"])
        self.assertTrue(captured["clone_config"])

    def test_upstream_validator_rejects_reserved_name_with_safe_code(self):
        calls = []

        def normalize_profile_name(name):
            calls.append(("normalize", name))
            return name

        def validate_profile_name(name):
            calls.append(("validate", name))
            if name == "reserved":
                raise ValueError("reserved upstream profile")
            return name

        profiles = types.SimpleNamespace(
            profiles_to_serve=lambda multiplex=True: [],
            get_profile_dir=lambda name: Path("/srv") / name,
            create_profile=lambda **kwargs: None,
            normalize_profile_name=normalize_profile_name,
            validate_profile_name=validate_profile_name,
        )
        with mock.patch.object(bot_profiles, "_profiles_module", return_value=profiles):
            with self.assertRaises(bot_profiles.BotProfileError) as raised:
                bot_profiles.validate_profile_name("reserved")
        self.assertEqual("invalid_bot_name", raised.exception.code)
        self.assertEqual([("normalize", "reserved"), ("validate", "reserved")], calls)

    def test_public_record_prefers_friendly_ui_title_over_profile_display_name(self):
        record = bot_profiles._public_record(
            "helper",
            Path("/srv/profiles/helper"),
            {
                "display_name": "Top-level profile name",
                "description": "Official description",
                "ui_meta": {"hermes-bots": {"title": "Friendly bot title"}},
            },
        )
        self.assertEqual("Friendly bot title", record["display_name"])
        self.assertEqual("Official description", record["description"])

    def test_create_update_round_trip_keeps_official_fields_and_unknown_ui_meta(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            helper = root / "profiles" / "helper"
            created = []

            def profiles_to_serve(multiplex=True):
                rows = [("default", root)]
                if helper.exists():
                    rows.append(("helper", helper))
                return rows

            def create_profile(
                name,
                clone_from=None,
                clone_all=False,
                clone_config=False,
                no_skills=False,
                description=None,
            ):
                del clone_from, clone_all, clone_config, no_skills, description
                helper.mkdir(parents=True, exist_ok=False)
                (helper / "profile.yaml").write_text(
                    "upstream_keep: true\nui_meta:\n  unknown: preserve\n",
                    encoding="utf-8",
                )
                created.append(name)

            profiles = types.SimpleNamespace(
                profiles_to_serve=profiles_to_serve,
                get_profile_dir=lambda name: root if name == "default" else root / "profiles" / name,
                create_profile=create_profile,
            )
            with (
                mock.patch.object(bot_profiles, "_profiles_module", return_value=profiles),
                mock.patch.object(bot_profiles, "_hermes_home", return_value=root),
            ):
                created_record = bot_profiles.create_bot(
                    "helper",
                    display_name="Friendly Helper",
                    description="Initial description",
                )
                created_document = bot_profiles._read_document(helper / "profile.yaml")
                updated_record = bot_profiles.update_bot(
                    "helper",
                    display_name="Updated Helper",
                    description="Updated description",
                )
                updated_document = bot_profiles._read_document(helper / "profile.yaml")

        self.assertEqual(["helper"], created)
        self.assertEqual("Friendly Helper", created_record["display_name"])
        self.assertEqual("Initial description", created_record["description"])
        self.assertEqual("Friendly Helper", created_document["display_name"])
        self.assertEqual("Initial description", created_document["description"])
        self.assertEqual("Friendly Helper", created_document["ui_meta"]["hermes-bots"]["title"])
        self.assertEqual("preserve", created_document["ui_meta"]["unknown"])
        self.assertEqual("Updated Helper", updated_record["display_name"])
        self.assertEqual("Updated description", updated_record["description"])
        self.assertEqual("Updated Helper", updated_document["display_name"])
        self.assertEqual("Updated description", updated_document["description"])
        self.assertEqual("Updated Helper", updated_document["ui_meta"]["hermes-bots"]["title"])
        self.assertEqual("preserve", updated_document["ui_meta"]["unknown"])
        self.assertTrue(updated_document["upstream_keep"])

    def test_delete_uses_official_profile_primitive_without_direct_removal(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            helper = root / "profiles" / "helper"
            helper.mkdir(parents=True)
            calls = []

            def delete_profile(name, yes=False):
                calls.append((name, yes))

            profiles = types.SimpleNamespace(
                profiles_to_serve=lambda multiplex=True: [("helper", helper)],
                get_profile_dir=lambda name: helper,
                create_profile=lambda **kwargs: None,
                delete_profile=delete_profile,
            )
            with (
                mock.patch.object(bot_profiles, "_profiles_module", return_value=profiles),
                mock.patch.object(bot_profiles, "_hermes_home", return_value=root),
                mock.patch.object(bot_profiles.shutil, "rmtree") as remove,
            ):
                result = bot_profiles.delete_bot("helper", "helper")

        self.assertEqual({"profile": "helper", "deleted": True}, result)
        self.assertEqual([("helper", True)], calls)
        remove.assert_not_called()

    def test_delete_without_official_primitive_is_update_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            helper = root / "profiles" / "helper"
            helper.mkdir(parents=True)
            profiles = types.SimpleNamespace(
                profiles_to_serve=lambda multiplex=True: [("helper", helper)],
                get_profile_dir=lambda name: helper,
                create_profile=lambda **kwargs: None,
            )
            with (
                mock.patch.object(bot_profiles, "_profiles_module", return_value=profiles),
                mock.patch.object(bot_profiles, "_hermes_home", return_value=root),
            ):
                with self.assertRaises(bot_profiles.BotProfileUnsupported) as raised:
                    bot_profiles.delete_bot("helper", "helper")
        self.assertEqual("update_required", raised.exception.code)

    def test_explicit_upstream_bot_mode_signal_is_preserved(self):
        profiles = types.SimpleNamespace(
            profiles_to_serve=lambda multiplex=True: [],
            get_profile_dir=lambda name: Path("/srv") / name,
            create_profile=lambda **kwargs: None,
            bot_mode_protocol=True,
        )
        adapter = types.SimpleNamespace(
            gateway_runner=types.SimpleNamespace(
                config=types.SimpleNamespace(multiplex_profiles=True)
            )
        )
        with mock.patch.object(bot_profiles, "_profiles_module", return_value=profiles):
            readiness = bot_profiles.readiness(adapter)
        self.assertTrue(readiness["bot_mode_protocol"])
        self.assertTrue(readiness["bot_mode_protocol_known"])

    def test_default_profile_cannot_be_deleted_and_confirmation_is_exact(self):
        with self.assertRaises(bot_profiles.BotProfileError) as default_error:
            bot_profiles.delete_bot("default", "default")
        self.assertEqual("default_bot_protected", default_error.exception.code)

        with self.assertRaises(bot_profiles.BotProfileError) as confirmation_error:
            bot_profiles.delete_bot("helper", "Helper")
        self.assertEqual("delete_confirmation_required", confirmation_error.exception.code)

    def test_roster_has_public_metadata_only_and_no_secret_or_path_fields(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            helper = root / "profiles" / "helper"
            helper.mkdir(parents=True)
            profiles = types.SimpleNamespace(
                profiles_to_serve=lambda multiplex=True: [
                    ("default", root),
                    ("helper", helper),
                ],
                get_profile_dir=lambda name: root / "profiles" / name,
                create_profile=lambda **kwargs: None,
            )
            documents = {
                root / "profile.yaml": {
                    "ui_meta": {"hermes-bots": {"display_name": "Default"}},
                    "api_key": "default-secret",
                },
                helper / "profile.yaml": {
                    "ui_meta": {
                        "hermes-bots": {
                            "display_name": "Helper",
                            "description": "Supporto",
                            "chat": "session-helper",
                        }
                    },
                    "auth_token": "helper-secret",
                },
            }

            def read_document(path):
                return documents.get(path, {})

            with (
                mock.patch.object(bot_profiles, "_profiles_module", return_value=profiles),
                mock.patch.object(bot_profiles, "_hermes_home", return_value=root),
                mock.patch.object(bot_profiles, "_read_document", side_effect=read_document),
            ):
                roster = bot_profiles.list_bots()

        self.assertEqual(["default", "helper"], [item["profile"] for item in roster])
        self.assertEqual("Helper", roster[1]["display_name"])
        self.assertEqual("session-helper", roster[1]["chat_id"])
        for item in roster:
            self.assertEqual(
                {
                    "profile",
                    "display_name",
                    "description",
                    "hidden",
                    "chat_id",
                    "chat_title",
                    "is_default",
                },
                set(item),
            )
        encoded = json.dumps(roster, ensure_ascii=False)
        self.assertNotIn("secret", encoded)
        self.assertNotIn(str(root), encoded)

    def test_canonical_bot_chat_is_idempotent_and_preserves_unknown_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            helper = root / "profiles" / "helper"
            helper.mkdir(parents=True)
            profiles = types.SimpleNamespace(
                profiles_to_serve=lambda multiplex=True: [("helper", helper)],
                get_profile_dir=lambda name: helper,
                create_profile=lambda **kwargs: None,
            )
            document = {
                "ui_meta": {
                    "unknown-upstream-key": {"keep": True},
                    "hermes-bots": {
                        "title": "Friendly Helper",
                        "hidden": False,
                        "appearance": {"hidden": False, "theme": "blue"},
                    },
                }
            }

            class FakeSessionDb:
                def __init__(self):
                    self.sessions = {}
                    self.created = []
                    self.flags = []

                def get_session(self, session_id):
                    return self.sessions.get(session_id)

                def list_sessions_rich(self, **kwargs):
                    return list(self.sessions.values())

                def create_session(self, session_id, source, model=None):
                    self.created.append((session_id, source, model))
                    self.sessions[session_id] = {"id": session_id, "title": ""}

                def set_session_title(self, session_id, title):
                    self.sessions[session_id]["title"] = title

                def set_session_pinned(self, session_id, value):
                    self.flags.append(("pinned", session_id, value))

                def set_session_hidden(self, session_id, value):
                    self.flags.append(("hidden", session_id, value))

            database = FakeSessionDb()

            class FakeAdapter:
                gateway_runner = types.SimpleNamespace(
                    config=types.SimpleNamespace(multiplex_profiles=True)
                )
                _model_name = "hermes-agent"

                @contextmanager
                def _profile_scope(self, profile):
                    self.active_profile = profile
                    yield

                def _ensure_session_db(self):
                    return database

            adapter = FakeAdapter()
            writes = []

            def write_document(path, value):
                writes.append(path)

            with (
                mock.patch.object(bot_profiles, "_profiles_module", return_value=profiles),
                mock.patch.object(bot_profiles, "_hermes_home", return_value=root),
                mock.patch.object(bot_profiles, "_read_document", return_value=document),
                mock.patch.object(bot_profiles, "_write_document", side_effect=write_document),
            ):
                first = bot_profiles.canonical_chat(adapter, "helper")
                second = bot_profiles.canonical_chat(adapter, "helper")

        self.assertEqual(first, second)
        self.assertEqual("Bot Chat", first["title"])
        self.assertEqual(1, len(database.created))
        self.assertEqual(2, len(writes))
        self.assertTrue(all(value[2] is True for value in database.flags))
        self.assertEqual({"keep": True}, document["ui_meta"]["unknown-upstream-key"])
        self.assertEqual("Friendly Helper", document["ui_meta"]["hermes-bots"]["title"])
        self.assertFalse(document["ui_meta"]["hermes-bots"]["hidden"])
        self.assertEqual(
            {"hidden": False, "theme": "blue"},
            document["ui_meta"]["hermes-bots"]["appearance"],
        )
        self.assertTrue(first["pinned"])
        self.assertTrue(first["hidden"])

    def test_group_pass_forms_are_silent_only_when_the_whole_reply_matches(self):
        for value in ("", "(pass)", "PASS", "Pass.", "  (PASS)  "):
            with self.subTest(value=value):
                self.assertTrue(bot_profiles.is_exact_pass(value))
        for value in ("please pass", "passenger", "(pass) later", "PASS: done"):
            with self.subTest(value=value):
                self.assertFalse(bot_profiles.is_exact_pass(value))

    def test_group_round_finishes_all_scheduled_members_and_counts_only_non_pass(self):
        members = [
            {"connection_id": "pc", "profile": "alpha", "display_name": "Alpha", "handle": "alpha"},
            {"connection_id": "pc", "profile": "beta", "display_name": "Beta", "handle": "beta"},
            {"connection_id": "watch", "profile": "gamma", "display_name": "Gamma", "handle": "gamma"},
        ]
        calls = []
        replies = {"alpha": "(PASS)", "beta": "Risposta beta", "gamma": "Risposta gamma"}

        async def fake_run(_adapter, member, _group, _prompt, _transcript):
            calls.append(member["profile"])
            return f"session-{member['profile']}", replies[member["profile"]]

        with mock.patch.object(bot_profiles, "_run_group_member", side_effect=fake_run):
            result = asyncio.run(bot_profiles.group_turn(types.SimpleNamespace(), "Test", members, "Ciao", max_rounds=1))

        self.assertEqual(["alpha", "beta", "gamma"], calls)
        self.assertEqual(2, result["bot_messages"])
        self.assertEqual(3, len(result["member_results"]))
        self.assertTrue(result["member_results"][0]["silent"])

    def test_group_user_mentions_select_first_round_and_bot_mentions_select_next_round(self):
        members = [
            {"connection_id": "pc", "profile": "alpha", "display_name": "Alpha", "handle": "alpha"},
            {"connection_id": "pc", "profile": "beta", "display_name": "Beta", "handle": "beta"},
            {"connection_id": "pc", "profile": "gamma", "display_name": "Gamma", "handle": "gamma"},
        ]
        calls = []
        replies = {"alpha": "@beta controlla", "beta": "pass", "gamma": "non dovrebbe partire"}

        async def fake_run(_adapter, member, _group, _prompt, _transcript):
            calls.append(member["profile"])
            return f"session-{member['profile']}", replies[member["profile"]]

        with mock.patch.object(bot_profiles, "_run_group_member", side_effect=fake_run):
            result = asyncio.run(bot_profiles.group_turn(types.SimpleNamespace(), "Test", members, "Aiuta @alpha", max_rounds=3))

        self.assertEqual(["alpha", "beta"], calls)
        self.assertEqual("pass", result["outcome"])
        self.assertEqual(1, result["bot_messages"])

    def test_group_next_round_preserves_original_user_request_for_mentioned_member(self):
        members = [
            {"connection_id": "pc", "profile": "alpha", "display_name": "Alpha", "handle": "alpha"},
            {"connection_id": "watch", "profile": "beta", "display_name": "Beta", "handle": "beta"},
        ]
        prompts = []

        async def preserve_original(_adapter, member, _group, prompt, _transcript):
            prompts.append((member["profile"], prompt))
            return f"session-{member['profile']}", "@beta controlla" if member["profile"] == "alpha" else "pass"

        with mock.patch.object(bot_profiles, "_run_group_member", side_effect=preserve_original):
            result = asyncio.run(bot_profiles.group_turn(types.SimpleNamespace(), "Test", members, "Verifica il piano originale con @alpha", max_rounds=3))

        self.assertEqual("pass", result["outcome"])
        self.assertEqual(["alpha", "beta"], [profile for profile, _prompt in prompts])
        self.assertEqual("Verifica il piano originale con @alpha", prompts[0][1])
        self.assertIn("Richiesta originale dell'utente:", prompts[1][1])
        self.assertIn("Verifica il piano originale con @alpha", prompts[1][1])

    def test_group_cap_bounds_posted_replies_and_user_escalation_is_immediate(self):
        members = [
            {"connection_id": "pc", "profile": "alpha", "display_name": "Alpha", "handle": "alpha"},
            {"connection_id": "pc", "profile": "beta", "display_name": "Beta", "handle": "beta"},
            {"connection_id": "pc", "profile": "gamma", "display_name": "Gamma", "handle": "gamma"},
        ]
        calls = []

        async def capped(_adapter, member, _group, _prompt, _transcript):
            calls.append(member["profile"])
            return f"session-{member['profile']}", "reply"

        with mock.patch.object(bot_profiles, "_run_group_member", side_effect=capped):
            result = asyncio.run(bot_profiles.group_turn(types.SimpleNamespace(), "Test", members, "Ciao", max_rounds=3, max_messages=2))
        self.assertEqual(2, result["bot_messages"])
        self.assertEqual("bounded", result["outcome"])
        self.assertEqual(["alpha", "beta"], calls)

        calls.clear()

        async def escalate(_adapter, member, _group, _prompt, _transcript):
            calls.append(member["profile"])
            return f"session-{member['profile']}", "Serve @user subito"

        with mock.patch.object(bot_profiles, "_run_group_member", side_effect=escalate):
            result = asyncio.run(bot_profiles.group_turn(types.SimpleNamespace(), "Test", members, "Ciao", max_rounds=3))
        self.assertEqual("escalation", result["outcome"])
        self.assertEqual("Serve @user subito", result["reply"])
        self.assertEqual(["alpha"], calls)

    def test_group_member_failure_is_explicit_and_healthy_reply_is_preserved(self):
        members = [
            {"connection_id": "pc", "profile": "alpha", "display_name": "Alpha", "handle": "alpha"},
            {"connection_id": "pc", "profile": "beta", "display_name": "Beta", "handle": "beta"},
        ]

        async def partial(_adapter, member, _group, _prompt, _transcript):
            if member["profile"] == "alpha":
                raise RuntimeError("upstream failure")
            return "session-beta", "Risposta sana"

        calls = []

        async def partial_and_track(_adapter, member, _group, _prompt, _transcript):
            calls.append(member["profile"])
            return await partial(_adapter, member, _group, _prompt, _transcript)

        with mock.patch.object(bot_profiles, "_run_group_member", side_effect=partial_and_track):
            result = asyncio.run(bot_profiles.group_turn(types.SimpleNamespace(), "Test", members, "Ciao", max_rounds=3))
        self.assertEqual("partial", result["outcome"])
        self.assertEqual(1, len(result["member_failures"]))
        self.assertEqual("alpha", result["member_failures"][0]["profile"])
        self.assertEqual("Risposta sana", result["reply"])
        self.assertEqual(1, calls.count("alpha"))


if __name__ == "__main__":
    unittest.main()
