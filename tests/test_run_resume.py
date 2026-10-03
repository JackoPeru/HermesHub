"""Automated tests for the persistent run-resume engine (no gateway needed)."""

import importlib.util
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "scripts" / "hermes_hub_gateway"))

import run_resume as rr


def load_legacy_patcher():
    path = REPO / "scripts" / "hermes_hub_gateway" / "adapters" / "hermes" / "legacy_patcher.py"
    spec = importlib.util.spec_from_file_location("hermes_gateway_patcher", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


RUNS_FIXTURE = textwrap.dedent('''\
    import asyncio
    import logging
    import os
    import threading
    import time

    logger = logging.getLogger("x")

    def _make_run_event_callback(self, run_id, loop, *, _api_server):
        def _callback(event_type: str, tool_name: str = None, preview: str = None, args=None, **kwargs):
            pass
        return _callback

    def _execute_run(self, run, *, _api_server):
        run_id = run.run_id
        def _finish(status, extra=None, **fields):
            self._set_run_status(run_id, status, **fields, last_event=f"run.{status}", **extra)
        if admitted is not None:
            task = self._active_run_tasks[run_id] = 1
        else:
            task = self._active_run_tasks[run_id] = asyncio.create_task(_execute_run(self, launch, _api_server=_api_server))
    ''')


class FakeDeps(rr.ReconcileDeps):
    def __init__(self, statuses=None, counts=None, post_code=202):
        super().__init__(api_base="http://127.0.0.1:9", api_key="test")
        self.statuses = dict(statuses or {})
        self.counts = dict(counts or {})
        self.post_code = post_code
        self.posts = []
        self.child_seq = 0

    def upstream_status(self, run_id):
        return self.statuses.get(run_id)

    def transcript_count(self, session_id):
        return self.counts.get(session_id)

    def post_resume(self, body, idempotency_key):
        self.posts.append({"body": body, "key": idempotency_key})
        self.child_seq += 1
        if self.post_code in (200, 201, 202):
            child = f"run_child{self.child_seq:03d}"
            self.statuses.setdefault(child, "running")
            return self.post_code, child
        return self.post_code, None


def make_store():
    tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
    tmp.close()
    return rr.ResumeStore(tmp.name), tmp.name


def simulate_reboot(store):
    """Rewrite stored boot ids: models a fresh process adopting orphaned rows."""
    with store._lock:
        store._conn.execute("UPDATE run_resume SET boot_id = 'boot-old'")
        store._conn.commit()


class LifecycleTests(unittest.TestCase):
    def test_track_event_finish_cycle(self):
        store, _ = make_store()
        store.track_run("run_1", "sess_1", "goal", 3, {"route": "r", "model": "m"})
        row = store.get("run_1")
        self.assertEqual(row["hub_state"], "running")
        self.assertEqual(row["session_id"], "sess_1")
        store.record_started("run_1", "exec_command", {"command": "ls"})
        store.record_completed("run_1", "exec_command", {"command": "ls"},
                               result="ok", duration=1.2, is_error=False, history_count=5)
        row = store.get("run_1")
        self.assertEqual(row["hub_state"], "checkpointed")
        self.assertEqual(row["history_count"], 5)
        completed = json.loads(row["completed_json"])
        self.assertEqual(len(completed), 1)
        self.assertEqual(completed[0]["tool"], "exec_command")
        self.assertEqual(json.loads(row["inflight_json"]), [])
        store.finish("run_1", "completed")
        self.assertEqual(store.get("run_1")["hub_state"], "completed")

    def test_terminal_states_are_sticky(self):
        store, _ = make_store()
        store.track_run("run_1", "sess_1", "goal", 0, {})
        for terminal in ("completed", "failed", "cancelled"):
            store.finish("run_1", terminal)
            # finish() always mirrors upstream truthfully, but reconcile must skip them;
            # here assert the stored mirror is exact.
            self.assertEqual(store.get("run_1")["hub_state"], terminal)

    def test_untracked_run_gets_stub_row(self):
        store, _ = make_store()
        store.record_started("run_ghost", "web_search", {"query": "x"})
        row = store.get("run_ghost")
        self.assertIsNotNone(row)
        self.assertEqual(row["session_id"], "")

    def test_fifo_pairing_prefers_args_match(self):
        store, _ = make_store()
        store.track_run("run_1", "sess_1", "goal", 0, {})
        store.record_started("run_1", "exec_command", {"command": "a"})
        store.record_started("run_1", "exec_command", {"command": "b"})
        store.record_completed("run_1", "exec_command", {"command": "b"}, result="B")
        completed = json.loads(store.get("run_1")["completed_json"])
        inflight = json.loads(store.get("run_1")["inflight_json"])
        self.assertEqual(completed[0]["args_hash"], rr.args_hash({"command": "b"}))
        self.assertEqual(len(inflight), 1)
        self.assertEqual(inflight[0]["args_hash"], rr.args_hash({"command": "a"}))

    def test_subagent_tracking(self):
        store, _ = make_store()
        store.track_run("run_1", "sess_1", "goal", 0, {})
        store.record_subagent("run_1", "subagent.start", "working")
        self.assertEqual(len(json.loads(store.get("run_1")["sub_inflight_json"])), 1)
        store.record_subagent("run_1", "subagent.complete", "")
        self.assertEqual(json.loads(store.get("run_1")["sub_inflight_json"]), [])


class ClassifierTests(unittest.TestCase):
    def test_defaults(self):
        self.assertEqual(rr.tool_class("web_search"), "readonly")
        self.assertEqual(rr.tool_class("exec_command"), "strict")
        self.assertEqual(rr.tool_class("apply_patch"), "strict")
        self.assertEqual(rr.tool_class("mcp.foo.bar"), "strict")
        self.assertEqual(rr.tool_class("something_unknown"), "strict")
        self.assertEqual(rr.tool_class(""), "strict")

    def test_env_overrides(self):
        os.environ["HERMES_RESUME_READONLY_TOOLS"] = "read, glob"
        os.environ["HERMES_RESUME_NOTED_TOOLS"] = "dispatch_subagent"
        try:
            self.assertEqual(rr.tool_class("read"), "readonly")
            self.assertEqual(rr.tool_class("dispatch_subagent"), "noted")
            self.assertEqual(rr.tool_class("exec_command"), "strict")
        finally:
            os.environ.pop("HERMES_RESUME_READONLY_TOOLS", None)
            os.environ.pop("HERMES_RESUME_NOTED_TOOLS", None)


class ReconcileTests(unittest.TestCase):
    def _tracked(self, store, run_id="run_1", session="sess_1", count=4):
        store.track_run(run_id, session, "build the site", count, {"model": "m"})

    def test_completed_upstream_mirrors_and_never_posts(self):
        store, _ = make_store()
        self._tracked(store)
        simulate_reboot(store)
        simulate_reboot(store)
        deps = FakeDeps(statuses={"run_1": "completed"}, counts={"sess_1": 4})
        self.assertEqual(rr.reconcile_row(store, store.get("run_1"), deps), "completed")
        self.assertEqual(deps.posts, [])

    def test_failed_and_cancelled_never_resume(self):
        for terminal in ("failed", "cancelled"):
            store, _ = make_store()
            self._tracked(store)
            simulate_reboot(store)
            deps = FakeDeps(statuses={"run_1": terminal}, counts={"sess_1": 4})
            self.assertEqual(rr.reconcile_row(store, store.get("run_1"), deps), terminal)
            self.assertEqual(deps.posts, [])

    def test_waiting_for_approval_needs_recovery(self):
        store, _ = make_store()
        self._tracked(store)
        simulate_reboot(store)
        simulate_reboot(store)
        deps = FakeDeps(statuses={"run_1": "waiting_for_approval"}, counts={"sess_1": 4})
        self.assertEqual(rr.reconcile_row(store, store.get("run_1"), deps), "recovery_required")
        self.assertIn("approval", store.get("run_1")["recovery_reason"])
        self.assertEqual(deps.posts, [])

    def test_clean_resume_posts_once_with_stable_key(self):
        store, _ = make_store()
        self._tracked(store)
        store.record_started("run_1", "web_search", {"query": "x"})
        store.record_completed("run_1", "web_search", {"query": "x"}, result="y", history_count=6)
        simulate_reboot(store)
        deps = FakeDeps(statuses={"run_1": "interrupted"}, counts={"sess_1": 6})
        self.assertEqual(rr.reconcile_row(store, store.get("run_1"), deps), "resuming")
        self.assertEqual(len(deps.posts), 1)
        post = deps.posts[0]
        self.assertEqual(post["body"]["session_id"], "sess_1")
        self.assertIn("do NOT re-execute", post["body"]["input"])
        self.assertTrue(post["key"].startswith("resume-run_1-"))
        child = store.get("run_1")["resumed_child"]
        self.assertTrue(child.startswith("run_child"))
        # Second cycle must not duplicate: child still alive.
        deps.statuses[child] = "running"
        self.assertEqual(rr.reconcile_row(store, store.get("run_1"), deps), "resuming")
        self.assertEqual(len(deps.posts), 1)

    def test_failed_child_blocks_loop_resume(self):
        store, _ = make_store()
        self._tracked(store)
        simulate_reboot(store)
        simulate_reboot(store)
        deps = FakeDeps(statuses={"run_1": "interrupted"}, counts={"sess_1": 4})
        rr.reconcile_row(store, store.get("run_1"), deps)
        child = store.get("run_1")["resumed_child"]
        deps.statuses[child] = "failed"
        self.assertEqual(rr.reconcile_row(store, store.get("run_1"), deps), "recovery_required")
        self.assertEqual(len(deps.posts), 1)

    def test_unknown_child_assumed_alive_no_duplicate(self):
        store, _ = make_store()
        self._tracked(store)
        simulate_reboot(store)
        simulate_reboot(store)
        deps = FakeDeps(statuses={"run_1": "interrupted"}, counts={"sess_1": 4})
        rr.reconcile_row(store, store.get("run_1"), deps)
        child = store.get("run_1")["resumed_child"]
        # Upstream forgot the child (TTL sweep): must not downgrade nor repost.
        del deps.statuses[child]
        self.assertEqual(rr.reconcile_row(store, store.get("run_1"), deps), "resuming")
        self.assertEqual(len(deps.posts), 1)
        self.assertEqual(store.get("run_1")["resumed_child"], child)

    def test_interrupted_child_triggers_fresh_resume(self):
        store, _ = make_store()
        self._tracked(store)
        simulate_reboot(store)
        simulate_reboot(store)
        deps = FakeDeps(statuses={"run_1": "interrupted"}, counts={"sess_1": 4})
        rr.reconcile_row(store, store.get("run_1"), deps)
        child = store.get("run_1")["resumed_child"]
        deps.statuses[child] = "interrupted"
        self.assertEqual(rr.reconcile_row(store, store.get("run_1"), deps), "resuming")
        self.assertEqual(len(deps.posts), 2)
        self.assertNotEqual(store.get("run_1")["resumed_child"], child)

    def test_strict_inflight_needs_recovery(self):
        store, _ = make_store()
        self._tracked(store)
        store.record_started("run_1", "exec_command", {"command": "rm -rf /tmp/x"})
        store.record_completed("run_1", "web_search", {"query": "x"}, result="y", history_count=5)
        simulate_reboot(store)
        deps = FakeDeps(statuses={"run_1": "running"}, counts={"sess_1": 5})
        self.assertEqual(rr.reconcile_row(store, store.get("run_1"), deps), "recovery_required")
        self.assertIn("exec_command", store.get("run_1")["recovery_reason"])
        self.assertEqual(deps.posts, [])

    def test_noted_and_subagent_inflight_resume_with_notes(self):
        store, _ = make_store()
        os.environ["HERMES_RESUME_NOTED_TOOLS"] = "dispatch_media"
        try:
            self._tracked(store)
            store.record_started("run_1", "dispatch_media", {"kind": "video"})
            store.record_subagent("run_1", "subagent.start", "rendering part 2")
            simulate_reboot(store)
            deps = FakeDeps(statuses={"run_1": None}, counts={"sess_1": 4})
            self.assertEqual(rr.reconcile_row(store, store.get("run_1"), deps), "resuming")
            body = deps.posts[0]["body"]["input"]
            self.assertIn("dispatch_media", body)
            self.assertIn("subagent was interrupted", body)
        finally:
            os.environ.pop("HERMES_RESUME_NOTED_TOOLS", None)

    def test_rewound_or_unreadable_transcript_needs_recovery(self):
        store, _ = make_store()
        self._tracked(store, count=10)
        simulate_reboot(store)
        deps = FakeDeps(statuses={}, counts={"sess_1": 7})
        self.assertEqual(rr.reconcile_row(store, store.get("run_1"), deps), "recovery_required")
        store2, _ = make_store()
        self._tracked(store2)
        simulate_reboot(store2)
        simulate_reboot(store2)
        deps2 = FakeDeps(statuses={}, counts={})
        self.assertEqual(rr.reconcile_row(store2, store2.get("run_1"), deps2), "recovery_required")

    def test_missing_session_needs_recovery(self):
        store, _ = make_store()
        store.record_started("run_ghost", "web_search", {"query": "x"})
        simulate_reboot(store)
        simulate_reboot(store)
        deps = FakeDeps(statuses={}, counts={})
        self.assertEqual(rr.reconcile_row(store, store.get("run_ghost"), deps), "recovery_required")
        self.assertEqual(deps.posts, [])

    def test_post_failure_stays_interrupted(self):
        store, _ = make_store()
        self._tracked(store)
        simulate_reboot(store)
        simulate_reboot(store)
        deps = FakeDeps(statuses={}, counts={"sess_1": 4}, post_code=500)
        self.assertEqual(rr.reconcile_row(store, store.get("run_1"), deps), "interrupted")

    def test_terminal_rows_skipped(self):
        store, _ = make_store()
        self._tracked(store)
        store.finish("run_1", "completed")
        simulate_reboot(store)
        deps = FakeDeps(statuses={}, counts={"sess_1": 4})
        self.assertEqual(rr.reconcile_all(store, deps), {})

    def test_same_boot_rows_never_reconciled(self):
        # Live run in this process: the periodic loop must not touch it,
        # even with a strict tool in flight.
        store, _ = make_store()
        self._tracked(store)
        store.record_started("run_1", "exec_command", {"command": "sleep 60"})
        deps = FakeDeps(statuses={}, counts={"sess_1": 4})
        self.assertEqual(rr.reconcile_row(store, store.get("run_1"), deps), "checkpointed")
        self.assertEqual(deps.posts, [])
        self.assertEqual(store.get("run_1")["recovery_reason"], "")

    def test_checkpoint_writes_do_not_regress_decided_states(self):
        store, _ = make_store()
        self._tracked(store)
        store.set_state("run_1", "recovery_required", "strict inflight")
        store.record_started("run_1", "web_search", {"query": "x"})
        store.record_completed("run_1", "web_search", {"query": "x"}, result="y")
        row = store.get("run_1")
        self.assertEqual(row["hub_state"], "recovery_required")
        self.assertEqual(len(json.loads(row["completed_json"])), 1)

    def test_continuation_content(self):
        row = {"run_id": "run_9", "user_goal": "make site",
               "completed_json": "[]", "updated_at": 123.0}
        text = rr.build_continuation(
            row,
            [{"tool": "web_search", "seq": 0}, {"tool": "exec_command", "seq": 1}],
            ["verify X before re-running"])
        self.assertIn("run_9", text)
        self.assertIn("web_search#0", text)
        self.assertIn("verify X before re-running", text)
        self.assertIn("do NOT re-execute", text)

    def test_idempotency_key_stable(self):
        self.assertEqual(rr.resume_idempotency_key("run_1", 1700000000.7), "resume-run_1-1700000000")
        self.assertEqual(rr.resume_idempotency_key("run_1", 1700000000.7),
                         rr.resume_idempotency_key("run_1", 1700000000.2))


class CrashAtomicityTests(unittest.TestCase):
    def test_kill_during_checkpoint_storm_keeps_valid_db(self):
        tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        tmp.close()
        writer = REPO / "tests" / "fixtures" / "resume_writer.py"
        env = os.environ.copy()
        env["RESUME_TEST_DB"] = tmp.name
        env["RESUME_TEST_ITERS"] = "200000"
        proc = subprocess.Popen([sys.executable, str(writer)], env=env,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            time.sleep(1.0)
        finally:
            proc.kill()
            proc.wait()
        conn = sqlite3.connect(tmp.name)
        try:
            integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
            self.assertEqual(integrity, "ok")
            rows = conn.execute("SELECT run_id, hub_state, completed_json, inflight_json,"
                                " sub_inflight_json, created_at, updated_at FROM run_resume").fetchall()
            self.assertGreater(len(rows), 0)
            for row in rows:
                self.assertTrue(row[0].startswith("run_"))
                self.assertIn(row[1], ("running", "checkpointed", "interrupted",
                                       "resuming", "recovery_required",
                                       "completed", "failed", "cancelled"))
                for blob in row[2:5]:
                    parsed = json.loads(blob)
                    self.assertIsInstance(parsed, list)
                self.assertLessEqual(row[5], row[6])
        finally:
            conn.close()
            for suffix in ("", "-wal", "-shm"):
                try:
                    os.unlink(tmp.name + suffix)
                except OSError:
                    pass


if __name__ == "__main__":
    unittest.main()


class PatchEmbeddingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.patcher = load_legacy_patcher()

    def test_runs_patch_injects_hooks_and_compiles(self):
        patched, changes = self.patcher._patch_runs_resume(RUNS_FIXTURE)
        self.assertEqual(
            changes, ["run resume checkpoint hooks (track/event/finish) + reconcile engine"])
        self.assertIn("# HERMES_HUB_RUN_RESUME_V1_BEGIN", patched)
        self.assertIn("_hermes_hub_rr_event(run_id, event_type, tool_name, preview, args, kwargs)", patched)
        self.assertIn("_hermes_hub_rr_track(run_id, session_id, user_message, conversation_history, launch)", patched)
        self.assertIn("_hermes_hub_rr_finish(run_id, status)", patched)
        self.assertIn("reconcile_loop_forever", patched)
        self.assertIn("run_resume", patched)
        compile(patched, "api_server_runs.py", "exec")

    def test_runs_patch_is_idempotent(self):
        patched, _ = self.patcher._patch_runs_resume(RUNS_FIXTURE)
        repached, changes = self.patcher._patch_runs_resume(patched)
        self.assertEqual(changes, [])
        self.assertEqual(repached, patched)

    def test_runs_patch_fails_closed_on_missing_anchor(self):
        with self.assertRaises(RuntimeError):
            self.patcher._patch_runs_resume("def nothing_here():\n    pass\n")

    def test_engine_source_matches_repo_module(self):
        engine_path = REPO / "scripts" / "hermes_hub_gateway" / "run_resume.py"
        engine_src = engine_path.read_text(encoding="utf-8")
        compile(engine_src, str(engine_path), "exec")
        patched, _ = self.patcher._patch_runs_resume(RUNS_FIXTURE)
        # The embedded engine must be the repo module verbatim (single source of truth).
        self.assertIn(repr(engine_src), patched)

    def test_runs_patch_refreshes_stale_engine_only(self):
        patched, _ = self.patcher._patch_runs_resume(RUNS_FIXTURE)
        stale = patched.replace("def reconcile_loop_forever", "def reconcile_loop_never")
        self.assertNotEqual(stale, patched)
        self.assertIn("# HERMES_HUB_RUN_RESUME_V1_BEGIN", stale)
        refreshed, changes = self.patcher._patch_runs_resume(stale)
        self.assertEqual(changes, ["run resume engine refreshed"])
        self.assertIn("def reconcile_loop_forever", refreshed)
        # Hooks survive the refresh untouched.
        self.assertIn("_hermes_hub_rr_finish(run_id, status)", refreshed)
        compile(refreshed, "api_server_runs.py", "exec")
        repached, changes = self.patcher._patch_runs_resume(refreshed)
        self.assertEqual(changes, [])
        self.assertEqual(repached, refreshed)
