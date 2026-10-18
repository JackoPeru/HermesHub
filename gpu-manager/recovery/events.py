"""Event store append-only del supervisore (Fase 0).

Storia operativa del GPU manager (transizioni di stato, job, LLM, media,
errori) in una tabella sqlite dedicata `events`. Vive in un file separato dal
package manager per non creare cicli: manager.py importa `recovery`, mai il
contrario.

Invariante: `record` e BEST-EFFORT e non solleva MAI. Il supervisore non deve
poter rompere il manager ne il boot, quindi ogni errore (path non scrivibile,
DB corrotto, JSON non serializzabile) degrada a un warning e a `None`.
"""

from __future__ import annotations

import json
import logging
import sqlite3
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

from .types import SEVERITIES

log = logging.getLogger("hermes-gpu-manager.recovery")

_DEFAULT_SEVERITY = "info"
_DEFAULT_MAX_EVENTS = 5000


@dataclass(frozen=True)
class Event:
    """Un fatto registrato dal supervisore. Immutabile e serializzabile."""

    ts: float
    kind: str
    severity: str
    message: str
    data: dict = field(default_factory=dict)


class EventStore:
    """Store eventi su sqlite: connessione propria, lock proprio, best-effort."""

    def __init__(self, db_path: str, *, max_events: int = _DEFAULT_MAX_EVENTS) -> None:
        self.db_path = str(db_path)
        try:
            self.max_events = max(1, int(max_events))
        except (TypeError, ValueError):
            self.max_events = _DEFAULT_MAX_EVENTS
        self._lock = threading.Lock()
        self._conn: sqlite3.Connection | None = None
        try:
            parent = Path(self.db_path).parent
            if str(parent):
                parent.mkdir(parents=True, exist_ok=True)
            conn = sqlite3.connect(self.db_path, check_same_thread=False)
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA busy_timeout=5000")
            conn.execute(
                "CREATE TABLE IF NOT EXISTS events ("
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL, kind TEXT, "
                "severity TEXT, message TEXT, data TEXT)"
            )
            conn.commit()
            self._conn = conn
        except Exception as exc:  # noqa: BLE001 - store assente: il manager prosegue
            log.warning("event store non disponibile su %s: %r", self.db_path, exc)
            self._conn = None

    def record(
        self,
        kind: str,
        message: str,
        severity: str = _DEFAULT_SEVERITY,
        data: dict | None = None,
    ) -> Event | None:
        """Registra un evento. Non solleva MAI: su errore logga e ritorna None."""
        conn = self._conn
        if conn is None:
            return None
        sev = severity if severity in SEVERITIES else _DEFAULT_SEVERITY
        payload = data if isinstance(data, dict) else {}
        ts = time.time()
        try:
            blob = json.dumps(payload, default=str)
        except (TypeError, ValueError):
            blob = "{}"
        try:
            with self._lock:
                cur = conn.execute(
                    "INSERT INTO events(ts,kind,severity,message,data) "
                    "VALUES(?,?,?,?,?)",
                    (ts, str(kind), sev, str(message), blob),
                )
                conn.commit()
                self._prune_locked(conn)
            _ = cur.lastrowid
            return Event(ts=ts, kind=str(kind), severity=sev,
                         message=str(message), data=payload)
        except Exception as exc:  # noqa: BLE001 - best-effort, mai fatale
            log.warning("event record failed: %r", exc)
            return None

    def _prune_locked(self, conn: sqlite3.Connection) -> None:
        """Auto-prune oltre max_events (il chiamante tiene gia il lock)."""
        try:
            total = int(conn.execute("SELECT COUNT(*) FROM events").fetchone()[0])
            if total > self.max_events:
                conn.execute(
                    "DELETE FROM events WHERE id NOT IN ("
                    "SELECT id FROM events ORDER BY id DESC LIMIT ?)",
                    (self.max_events,),
                )
                conn.commit()
        except sqlite3.Error as exc:  # pragma: no cover - prune best-effort
            log.warning("event prune failed: %r", exc)

    def recent(
        self,
        limit: int = 100,
        kind: str | None = None,
        since: float | None = None,
    ) -> list[Event]:
        """Eventi piu recenti prima; `since` e un ts float (ts >= since)."""
        conn = self._conn
        if conn is None:
            return []
        try:
            limit = max(1, int(limit))
        except (TypeError, ValueError):
            limit = 100
        sql = "SELECT ts,kind,severity,message,data FROM events"
        clauses: list[str] = []
        params: list = []
        if kind is not None:
            clauses.append("kind=?")
            params.append(str(kind))
        if since is not None:
            clauses.append("ts>=?")
            params.append(float(since))
        if clauses:
            sql += " WHERE " + " AND ".join(clauses)
        sql += " ORDER BY id DESC LIMIT ?"
        params.append(limit)
        try:
            with self._lock:
                rows = conn.execute(sql, params).fetchall()
        except Exception as exc:  # noqa: BLE001 - lettura best-effort
            log.warning("event recent failed: %r", exc)
            return []
        events: list[Event] = []
        for ts, ev_kind, sev, msg, blob in rows:
            try:
                parsed = json.loads(blob) if blob else {}
            except (json.JSONDecodeError, TypeError):
                parsed = {}
            if not isinstance(parsed, dict):
                parsed = {"value": parsed}
            events.append(
                Event(
                    ts=float(ts or 0.0),
                    kind=str(ev_kind or ""),
                    severity=str(sev or ""),
                    message=str(msg or ""),
                    data=parsed,
                )
            )
        return events

    def count(self) -> int:
        conn = self._conn
        if conn is None:
            return 0
        try:
            with self._lock:
                return int(conn.execute("SELECT COUNT(*) FROM events").fetchone()[0])
        except Exception as exc:  # noqa: BLE001 - best-effort
            log.warning("event count failed: %r", exc)
            return 0

    def clear(self) -> int:
        """Svuota la tabella; ritorna quanti eventi sono stati rimossi."""
        conn = self._conn
        if conn is None:
            return 0
        try:
            with self._lock:
                removed = int(conn.execute("SELECT COUNT(*) FROM events").fetchone()[0])
                conn.execute("DELETE FROM events")
                conn.commit()
                return removed
        except Exception as exc:  # noqa: BLE001 - best-effort
            log.warning("event clear failed: %r", exc)
            return 0

    def close(self) -> None:
        conn = self._conn
        if conn is None:
            return
        try:
            with self._lock:
                conn.close()
        except Exception:  # noqa: BLE001 - chiusura best-effort
            pass
        self._conn = None
