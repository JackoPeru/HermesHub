import asyncio
import json
import tempfile
import time
import unittest
from pathlib import Path
import sys


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


if __name__ == "__main__":
    unittest.main()
