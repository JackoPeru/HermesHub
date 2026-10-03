"""Persistent run-resume engine for Hermes Hub gateway runs.

Stdlib only: this module is both imported by the repo test-suite and embedded
(verbatim source, executed in an isolated namespace) into the upstream
``api_server_runs.py`` by the gateway patcher. Keep it free of gateway imports.

Lifecycle (our states, stored per run):
    running -> checkpointed -> interrupted -> resuming -> running -> completed
                                                     \\-> recovery_required
Terminal mirrors from upstream: completed / failed / cancelled. Upstream
``interrupted`` maps to our ``interrupted`` (resumable). ``recovery_required``
is sticky: a human (or a future policy) must clear it, never the reconciler.

Crash-safety contract:
- every checkpoint is one SQLite transaction (BEGIN IMMEDIATE + COMMIT);
  kill -9 mid-write rolls back to the previous complete checkpoint;
- tool results are checkpointed on every ``tool.completed`` event, so the
  only loss window is "tool executed, result not yet recorded";
- a side-effecting tool in-flight at crash time (started, no result) makes
  the run ``recovery_required`` instead of risking a duplicate execution;
- resume never replays history: the child run reloads the session transcript
  (which already contains every completed tool call + result) and continues.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import sqlite3
import threading
import time
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

LOG = logging.getLogger("hermes.run_resume")

# Unique per process: rows stamped with an older boot died with their owner.
# The reconciler never touches rows from the current boot (they are live by
# definition); only a fresh boot may adopt orphaned rows.
_BOOT_ID = uuid.uuid4().hex

# Our lifecycle states.
ST_RUNNING = "running"
ST_CHECKPOINTED = "checkpointed"
ST_INTERRUPTED = "interrupted"
ST_RESUMING = "resuming"
ST_RECOVERY = "recovery_required"
ST_COMPLETED = "completed"
ST_FAILED = "failed"
ST_CANCELLED = "cancelled"

TERMINAL_STATES = frozenset({ST_COMPLETED, ST_FAILED, ST_CANCELLED})
LIVE_STATES = frozenset({ST_RUNNING, ST_CHECKPOINTED, ST_INTERRUPTED, ST_RESUMING})

# Upstream terminal statuses mirrored 1:1 (plus interrupted -> interrupted).
_UPSTREAM_TERMINAL = {"completed", "failed", "cancelled", "interrupted"}

# Tool classification for crash-window decisions. Unknown names default to
# STRICT (fail closed). Operators can relax/extend via env (comma-separated):
#   HERMES_RESUME_READONLY_TOOLS="web_search,read,glob"
#   HERMES_RESUME_NOTED_TOOLS="dispatch_subagent"
READONLY_TOOLS_DEFAULT = frozenset({"web_search"})
NOTED_TOOLS_DEFAULT = frozenset()

SUBAGENT_START = "subagent.start"
SUBAGENT_COMPLETE = "subagent.complete"

_SCHEMA = """
CREATE TABLE IF NOT EXISTS run_resume (
    run_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL DEFAULT '',
    hub_state TEXT NOT NULL DEFAULT 'running',
    user_goal TEXT NOT NULL DEFAULT '',
    history_count INTEGER NOT NULL DEFAULT -1,
    completed_json TEXT NOT NULL DEFAULT '[]',
    inflight_json TEXT NOT NULL DEFAULT '[]',
    sub_inflight_json TEXT NOT NULL DEFAULT '[]',
    route_json TEXT NOT NULL DEFAULT '{}',
    resumed_from TEXT NOT NULL DEFAULT '',
    resumed_child TEXT NOT NULL DEFAULT '',
    recovery_reason TEXT NOT NULL DEFAULT '',
    created_at REAL NOT NULL,
    updated_at REAL NOT NULL
)
"""


def _ensure_schema(conn: sqlite3.Connection) -> None:
    conn.execute(_SCHEMA)
    try:
        columns = {row[1] for row in conn.execute("PRAGMA table_info(run_resume)")}
    except Exception:
        columns = set()
    if "boot_id" not in columns:
        conn.execute("ALTER TABLE run_resume ADD COLUMN boot_id TEXT NOT NULL DEFAULT ''")
    conn.commit()


def _env_set(name: str) -> frozenset:
    raw = os.environ.get(name, "")
    return frozenset(part.strip() for part in raw.split(",") if part.strip())


def tool_class(tool_name: str) -> str:
    """Classify a tool for crash-window decisions: readonly / noted / strict."""
    name = str(tool_name or "")
    if name in _env_set("HERMES_RESUME_READONLY_TOOLS") | READONLY_TOOLS_DEFAULT:
        return "readonly"
    if name in _env_set("HERMES_RESUME_NOTED_TOOLS") | NOTED_TOOLS_DEFAULT:
        return "noted"
    return "strict"


def default_db_path() -> str:
    override = os.environ.get("HERMES_HUB_RUN_RESUME_DB", "").strip()
    if override:
        return override
    try:
        from hermes_cli.config import get_hermes_home  # type: ignore

        return str(Path(get_hermes_home()) / "runs_idempotency.db")
    except Exception:
        return str(Path.home() / ".hermes" / "runs_idempotency.db")


def default_state_db_path() -> str:
    try:
        from hermes_cli.config import get_hermes_home  # type: ignore

        return str(Path(get_hermes_home()) / "state.db")
    except Exception:
        return str(Path.home() / ".hermes" / "state.db")


def args_hash(args: Any) -> str:
    try:
        canonical = json.dumps(args, sort_keys=True, separators=(",", ":"), ensure_ascii=False, default=str)
    except Exception:
        canonical = str(args)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]


def _connect(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path, check_same_thread=False, timeout=30)
    try:
        conn.execute("PRAGMA journal_mode=WAL")
    except Exception:
        pass
    _ensure_schema(conn)
    return conn


class ResumeStore:
    """Thread-safe checkpoint store. One transaction per mutation."""

    def __init__(self, db_path: Optional[str] = None) -> None:
        self._db_path = db_path or default_db_path()
        parent = os.path.dirname(os.path.abspath(self._db_path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        self._lock = threading.RLock()
        self._conn = _connect(self._db_path)

    def _write(self, sql: str, params: Tuple[Any, ...]) -> None:
        with self._lock:
            try:
                self._conn.execute("BEGIN IMMEDIATE")
                self._conn.execute(sql, params)
                self._conn.commit()
            except Exception:
                try:
                    self._conn.rollback()
                except Exception:
                    pass
                raise

    def _read(self, sql: str, params: Tuple[Any, ...] = ()) -> list:
        with self._lock:
            return list(self._conn.execute(sql, params))

    def track_run(self, run_id: str, session_id: str, goal: str, history_count: int,
                  route: Optional[Dict[str, Any]] = None) -> None:
        now = time.time()
        with self._lock:
            try:
                self._conn.execute("BEGIN IMMEDIATE")
                self._conn.execute(
                    "INSERT OR IGNORE INTO run_resume "
                    "(run_id, session_id, hub_state, user_goal, history_count, route_json, "
                    "boot_id, created_at, updated_at)"
                    " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (run_id, session_id or "", ST_RUNNING, str(goal or "")[:2000],
                     int(history_count), json.dumps(route or {}, default=str),
                     _BOOT_ID, now, now),
                )
                self._conn.commit()
            except Exception:
                try:
                    self._conn.rollback()
                except Exception:
                    pass
                raise

    def get(self, run_id: str) -> Optional[Dict[str, Any]]:
        rows = self._read("SELECT * FROM run_resume WHERE run_id = ?", (run_id,))
        return _row_to_dict(rows[0]) if rows else None

    def live_rows(self) -> List[Dict[str, Any]]:
        placeholders = ",".join("?" for _ in LIVE_STATES)
        rows = self._read(
            f"SELECT * FROM run_resume WHERE hub_state IN ({placeholders}) ORDER BY updated_at",
            tuple(LIVE_STATES),
        )
        return [_row_to_dict(row) for row in rows]

    def _load_lists(self, run_id: str) -> Tuple[list, list, list]:
        row = self.get(run_id)
        if row is None:
            return [], [], []
        return (
            _parse_json_list(row["completed_json"]),
            _parse_json_list(row["inflight_json"]),
            _parse_json_list(row["sub_inflight_json"]),
        )

    def _save_lists(self, run_id: str, completed: list, inflight: list, sub_inflight: list,
                    state: Optional[str] = None, history_count: Optional[int] = None) -> None:
        now = time.time()
        if state is None or history_count is None:
            with self._lock:
                try:
                    self._conn.execute("BEGIN IMMEDIATE")
                    if state is not None:
                        self._conn.execute(
                            "UPDATE run_resume SET hub_state = ?, updated_at = ? WHERE run_id = ?",
                            (state, now, run_id))
                    self._conn.execute(
                        "UPDATE run_resume SET completed_json = ?, inflight_json = ?, "
                        "sub_inflight_json = ?, updated_at = ? WHERE run_id = ?",
                        (json.dumps(completed), json.dumps(inflight), json.dumps(sub_inflight), now, run_id))
                    if history_count is not None:
                        self._conn.execute(
                            "UPDATE run_resume SET history_count = ? WHERE run_id = ?", (int(history_count), run_id))
                    self._conn.commit()
                except Exception:
                    try:
                        self._conn.rollback()
                    except Exception:
                        pass
                    raise
        else:
            self._write(
                "UPDATE run_resume SET hub_state = ?, completed_json = ?, inflight_json = ?, "
                "sub_inflight_json = ?, history_count = ?, updated_at = ? WHERE run_id = ?",
                (state, json.dumps(completed), json.dumps(inflight), json.dumps(sub_inflight),
                 int(history_count), now, run_id),
            )

    def ensure_row(self, run_id: str) -> Dict[str, Any]:
        """Return the row, creating a session-less stub for runs admitted before tracking."""
        row = self.get(run_id)
        if row is not None:
            return row
        now = time.time()
        self._write(
            "INSERT OR IGNORE INTO run_resume (run_id, hub_state, created_at, updated_at)"
            " VALUES (?, ?, ?, ?)",
            (run_id, ST_RUNNING, now, now),
        )
        row = self.get(run_id)
        assert row is not None
        return row

    def record_started(self, run_id: str, tool: str, arguments: Any) -> Dict[str, Any]:
        row = self.ensure_row(run_id)
        completed, inflight, sub_inflight = self._load_lists(run_id)
        inflight.append({
            "seq": _next_seq(completed, inflight),
            "tool": str(tool or ""),
            "args_hash": args_hash(arguments),
            "at": time.time(),
        })
        self._save_lists(run_id, completed, inflight, sub_inflight,
                         state=_checkpoint_state(str(row.get("hub_state") or "")))
        return self.get(run_id)  # type: ignore[return-value]

    def record_completed(self, run_id: str, tool: str, arguments: Any, result: Any = None,
                         duration: Optional[float] = None, is_error: bool = False,
                         history_count: int = -1) -> Dict[str, Any]:
        row = self.ensure_row(run_id)
        completed, inflight, sub_inflight = self._load_lists(run_id)
        wanted = args_hash(arguments)
        idx = _match_inflight(inflight, str(tool or ""), wanted)
        started_entry: Dict[str, Any] = {}
        if idx is not None:
            started_entry = inflight.pop(idx)
        completed.append({
            "seq": started_entry.get("seq", _next_seq(completed, inflight)),
            "tool": str(tool or ""),
            "args_hash": wanted,
            "at": time.time(),
            "duration": duration,
            "is_error": bool(is_error),
            "preview": _preview_of(result),
        })
        self._save_lists(run_id, completed, inflight, sub_inflight,
                         state=_checkpoint_state(str(row.get("hub_state") or "")),
                         history_count=history_count)
        return self.get(run_id)  # type: ignore[return-value]

    def record_subagent(self, run_id: str, kind: str, preview: str = "") -> Dict[str, Any]:
        row = self.ensure_row(run_id)
        completed, inflight, sub_inflight = self._load_lists(run_id)
        if kind == SUBAGENT_START:
            sub_inflight.append({"tool": "subagent", "preview": str(preview or "")[:500], "at": time.time()})
        else:
            if sub_inflight:
                sub_inflight.pop(0)
        self._save_lists(run_id, completed, inflight, sub_inflight,
                         state=_checkpoint_state(str(row.get("hub_state") or "")))
        return self.get(run_id)  # type: ignore[return-value]

    def set_state(self, run_id: str, state: str, recovery_reason: str = "",
                  resumed_child: Optional[str] = None) -> None:
        now = time.time()
        with self._lock:
            try:
                self._conn.execute("BEGIN IMMEDIATE")
                if resumed_child is not None:
                    self._conn.execute(
                        "UPDATE run_resume SET hub_state = ?, recovery_reason = ?, "
                        "resumed_child = ?, updated_at = ? WHERE run_id = ?",
                        (state, recovery_reason, resumed_child, now, run_id))
                else:
                    self._conn.execute(
                        "UPDATE run_resume SET hub_state = ?, recovery_reason = ?, updated_at = ? WHERE run_id = ?",
                        (state, recovery_reason, now, run_id))
                self._conn.commit()
            except Exception:
                try:
                    self._conn.rollback()
                except Exception:
                    pass
                raise

    def finish(self, run_id: str, upstream_status: str) -> None:
        mapping = {"completed": ST_COMPLETED, "failed": ST_FAILED,
                   "cancelled": ST_CANCELLED, "interrupted": ST_INTERRUPTED}
        state = mapping.get(str(upstream_status or ""), ST_INTERRUPTED)
        self.ensure_row(run_id)
        self.set_state(run_id, state)

    def close(self) -> None:
        try:
            with self._lock:
                self._conn.close()
        except Exception:
            pass


def _row_to_dict(row: tuple) -> Dict[str, Any]:
    return {
        "run_id": row[0], "session_id": row[1], "hub_state": row[2], "user_goal": row[3],
        "history_count": row[4], "completed_json": row[5], "inflight_json": row[6],
        "sub_inflight_json": row[7], "route_json": row[8], "resumed_from": row[9],
        "resumed_child": row[10], "recovery_reason": row[11],
        "created_at": row[12], "updated_at": row[13],
        "boot_id": row[14] if len(row) > 14 else "",
    }


def _checkpoint_state(current_state: str) -> str:
    """Checkpoint writes must never regress a decided state (recovery/resuming/terminal)."""
    if current_state in (ST_RUNNING, ST_CHECKPOINTED):
        return ST_CHECKPOINTED
    return current_state


def _parse_json_list(raw: str) -> list:
    try:
        parsed = json.loads(raw or "[]")
        return parsed if isinstance(parsed, list) else []
    except Exception:
        return []


def _preview_of(result: Any, limit: int = 500) -> str:
    try:
        text = result if isinstance(result, str) else json.dumps(result, ensure_ascii=False, default=str)
    except Exception:
        text = str(result)
    return str(text or "")[:limit]


def _next_seq(completed: list, inflight: list) -> int:
    peak = -1
    for entry in list(completed) + list(inflight):
        try:
            peak = max(peak, int(entry.get("seq", -1)))
        except (TypeError, ValueError):
            continue
    return peak + 1


def _match_inflight(inflight: list, tool: str, wanted_hash: str) -> Optional[int]:
    for idx, entry in enumerate(inflight):
        if str(entry.get("tool") or "") == tool and str(entry.get("args_hash") or "") == wanted_hash:
            return idx
    for idx, entry in enumerate(inflight):
        if str(entry.get("tool") or "") == tool:
            return idx
    return None


def transcript_message_count(session_id: str, state_db_path: Optional[str] = None) -> Optional[int]:
    """Fail-soft message count for a session (rewind detection). None = unreadable."""
    if not session_id:
        return None
    try:
        conn = sqlite3.connect(f"file:{state_db_path or default_state_db_path()}?mode=ro",
                               uri=True, timeout=5)
        try:
            count = conn.execute(
                "SELECT COUNT(*) FROM messages WHERE session_id = ?", (session_id,)).fetchone()
            return int(count[0]) if count else 0
        finally:
            conn.close()
    except Exception:
        return None


def resume_idempotency_key(run_id: str, checkpoint_ts: float) -> str:
    return f"resume-{run_id}-{int(checkpoint_ts)}"


def build_continuation(row: Dict[str, Any], completed: list, notes: List[str]) -> str:
    lines = [
        "[Resume checkpoint] The gateway restarted while this run was active. "
        f"Continue the task from the checkpoint below; original run {row['run_id']}.",
        f"Goal: {(row.get('user_goal') or '')[:500]}",
    ]
    if completed:
        summary = ", ".join(
            f"{entry.get('tool', '?')}#{entry.get('seq', '?')}" for entry in completed[-20:])
        lines.append(f"Already completed ({len(completed)} tool calls, do NOT re-execute): {summary}.")
    else:
        lines.append("No tool call had completed before the interruption.")
    for note in notes:
        lines.append(f"IMPORTANT: {note}")
    lines.append(
        "Verify side effects by reading current state, never by re-running completed tools. "
        "If anything is ambiguous or unsafe, stop and report instead of guessing.")
    return "\n".join(lines)


class ReconcileDeps:
    """Injectable boundary for reconcile(): real HTTP/SQLite in production, fakes in tests."""

    def __init__(self, api_base: str = "http://127.0.0.1:8642", api_key: str = "",
                 timeout: float = 10.0) -> None:
        self.api_base = api_base.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout

    def upstream_status(self, run_id: str) -> Optional[str]:
        """Durable upstream status for a run, or None when unknown/unreachable."""
        try:
            request = urllib.request.Request(
                f"{self.api_base}/v1/runs/{run_id}",
                headers={"Authorization": f"Bearer {self.api_key}"} if self.api_key else {})
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
            status = str((payload.get("status") or payload.get("run") or {}).get("status", "")
                         if isinstance(payload.get("run"), dict) else payload.get("status") or "")
            return status or None
        except Exception:
            return None

    def transcript_count(self, session_id: str) -> Optional[int]:
        return transcript_message_count(session_id)

    def post_resume(self, body: Dict[str, Any], idempotency_key: str) -> Tuple[Optional[int], Optional[str]]:
        """Submit the continuation run. Returns (http_code, child_run_id)."""
        try:
            data = json.dumps(body).encode("utf-8")
            request = urllib.request.Request(
                f"{self.api_base}/v1/runs", data=data,
                headers={"Content-Type": "application/json",
                         "Authorization": f"Bearer {self.api_key}"} if self.api_key else
                {"Content-Type": "application/json"})
            request.add_header("Idempotency-Key", idempotency_key)
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
            code = response.getcode()
            child = payload.get("run_id") or (payload.get("run") or {}).get("run_id")
            return code, (str(child) if child else None)
        except Exception as exc:
            LOG.debug("resume POST failed: %r", exc)
            return None, None


def reconcile_row(store: ResumeStore, row: Dict[str, Any], deps: ReconcileDeps) -> str:
    """Reconcile one live row. Returns the resulting hub_state. Never raises."""
    run_id = row["run_id"]
    try:
        return _reconcile_row(store, row, deps)
    except Exception as exc:  # noqa: BLE001 - reconciler must survive everything
        LOG.warning("reconcile %s failed: %r", run_id, exc)
        return str(row.get("hub_state") or ST_INTERRUPTED)


def _reconcile_row(store: ResumeStore, row: Dict[str, Any], deps: ReconcileDeps) -> str:
    run_id = row["run_id"]
    if row.get("hub_state") in TERMINAL_STATES:
        return str(row["hub_state"])
    if str(row.get("boot_id") or "") == _BOOT_ID:
        # Same process that tracked it: the run is live by definition (or the
        # process is shutting down, in which case no resume must start here).
        # Only a fresh boot may adopt orphaned rows.
        return str(row.get("hub_state") or ST_RUNNING)

    upstream = deps.upstream_status(run_id)
    if upstream in ("completed", "failed", "cancelled"):
        # Crash landed after upstream persisted terminal state: mirror it.
        # A completed run is therefore never resumed.
        store.finish(run_id, upstream)
        return str(store.get(run_id)["hub_state"])
    if upstream == "waiting_for_approval":
        store.set_state(run_id, ST_RECOVERY, "approval was pending at interruption; human must re-decide")
        return ST_RECOVERY

    child = row.get("resumed_child") or ""
    if child:
        child_status = deps.upstream_status(child)
        if child_status is None:
            # Upstream forgets terminal statuses after its TTL; a missing
            # record proves nothing, so assume the child is still alive and
            # never duplicate the resume POST from this path.
            LOG.debug("resume child %s unknown; assuming alive", child)
            return ST_RESUMING
        if child_status not in ("completed", "failed", "cancelled", "interrupted"):
            return ST_RESUMING  # resume already in flight; do not duplicate
        if child_status == "completed":
            store.finish(run_id, "completed")
            return ST_COMPLETED
        if child_status == "interrupted":
            # The child itself crashed: clear the link and fall through to
            # post a fresh resume (same stable idempotency key family).
            store.set_state(run_id, ST_INTERRUPTED, resumed_child="")
            row = store.get(run_id) or row
        else:
            store.set_state(run_id, ST_RECOVERY, f"previous resume child {child} ended as {child_status}")
            return ST_RECOVERY

    session_id = row.get("session_id") or ""
    if not session_id:
        store.set_state(run_id, ST_RECOVERY, "no session recorded; cannot rebuild history")
        return ST_RECOVERY
    live_count = deps.transcript_count(session_id)
    if live_count is None:
        store.set_state(run_id, ST_RECOVERY, "session transcript unreadable; refusing blind resume")
        return ST_RECOVERY
    recorded = int(row.get("history_count") or -1)
    if recorded >= 0 and live_count < recorded:
        store.set_state(run_id, ST_RECOVERY,
                        f"session rewound ({live_count} < checkpoint {recorded}); history diverged")
        return ST_RECOVERY

    completed = _parse_json_list(row.get("completed_json") or "[]")
    inflight = _parse_json_list(row.get("inflight_json") or "[]")
    sub_inflight = _parse_json_list(row.get("sub_inflight_json") or "[]")

    strict_tools = sorted({str(t.get("tool") or "?") for t in inflight
                           if tool_class(str(t.get("tool") or "")) == "strict"})
    if strict_tools:
        # Crash window: tool executed but no result recorded. Re-running could
        # duplicate the side effect, so a human must decide.
        store.set_state(run_id, ST_RECOVERY,
                        f"side-effecting tool(s) in flight at crash with no recorded result: "
                        f"{', '.join(strict_tools)}")
        return ST_RECOVERY

    notes: List[str] = []
    for entry in inflight:
        if tool_class(str(entry.get("tool") or "")) == "noted":
            notes.append(f"tool {entry.get('tool')} started but has no recorded result; "
                         f"verify before re-running")
    for entry in sub_inflight:
        preview = str(entry.get("preview") or "")[:300]
        notes.append("a subagent was interrupted mid-work"
                     + (f" (last: {preview})" if preview else "")
                     + "; verify its partial outputs before re-dispatching")
    if live_count > recorded >= 0:
        notes.append(f"session advanced since checkpoint ({recorded} -> {live_count}); continuing from live tip")

    body: Dict[str, Any] = {"input": build_continuation(row, completed, notes), "session_id": session_id}
    try:
        route = json.loads(row.get("route_json") or "{}")
    except Exception:
        route = {}
    if isinstance(route, dict) and route.get("model"):
        body["model"] = route["model"]
    code, child_id = deps.post_resume(body, resume_idempotency_key(run_id, float(row.get("updated_at") or 0)))
    if code in (200, 201, 202) and child_id:
        store.set_state(run_id, ST_RESUMING, resumed_child=child_id)
        return ST_RESUMING
    LOG.warning("resume POST for %s failed (code=%r); will retry next cycle", run_id, code)
    if row.get("hub_state") != ST_INTERRUPTED:
        store.set_state(run_id, ST_INTERRUPTED)
    return ST_INTERRUPTED


def reconcile_all(store: ResumeStore, deps: ReconcileDeps) -> Dict[str, str]:
    """Reconcile every live row. Returns {run_id: resulting_state}. Never raises."""
    outcomes: Dict[str, str] = {}
    try:
        rows = store.live_rows()
    except Exception as exc:
        LOG.warning("reconcile list failed: %r", exc)
        return outcomes
    for row in rows:
        outcomes[row["run_id"]] = reconcile_row(store, row, deps)
    return outcomes


def reconcile_loop_forever(api_base: str = "http://127.0.0.1:8642", api_key: str = "",
                            delay_s: float = 120.0, period_s: float = 600.0,
                            db_path: Optional[str] = None) -> None:
    """Boot + periodic reconciler entrypoint (runs in a daemon thread)."""
    try:
        time.sleep(max(0.0, float(delay_s)))
    except Exception:
        return
    store: Optional[ResumeStore] = None
    try:
        store = ResumeStore(db_path)
    except Exception as exc:
        LOG.warning("run-resume store unavailable: %r", exc)
        return
    deps = ReconcileDeps(api_base=api_base, api_key=api_key)
    while True:
        try:
            outcomes = reconcile_all(store, deps)
            resumed = sorted(run for run, state in outcomes.items() if state == ST_RESUMING)
            recovered = sorted(run for run, state in outcomes.items() if state == ST_RECOVERY)
            if resumed or recovered:
                LOG.warning("run-resume cycle: resumed=%s recovery_required=%s", resumed, recovered)
        except Exception as exc:  # noqa: BLE001
            LOG.warning("run-resume cycle failed: %r", exc)
        try:
            time.sleep(max(60.0, float(period_s)))
        except Exception:
            return
