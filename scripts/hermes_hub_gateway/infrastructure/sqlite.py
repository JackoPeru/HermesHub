"""SQLite infrastructure for Hub-owned structured state."""

from __future__ import annotations

import json
from contextlib import contextmanager
from pathlib import Path
import sqlite3
from threading import RLock
from typing import Mapping, Sequence

from ..modules.storage import HubChange, HubRecord, HubStateStore, HubStorageError, normalize_record


class SQLiteHubStateStore(HubStateStore):
    """Small transactional store with WAL, FK enforcement, and bounded reads."""

    _SCHEMA_VERSION = 2

    def __init__(self, path: str | Path, *, busy_timeout_ms: int = 5_000) -> None:
        self.path = ":memory:" if str(path) == ":memory:" else str(Path(path).expanduser())
        self.busy_timeout_ms = max(100, min(int(busy_timeout_ms), 120_000))
        self._memory_connection: sqlite3.Connection | None = None
        self._memory_lock = RLock()
        if self.path != ":memory:":
            Path(self.path).expanduser().parent.mkdir(parents=True, exist_ok=True)
        self._migrate()

    def _connect(self) -> sqlite3.Connection:
        if self.path == ":memory:" and self._memory_connection is not None:
            return self._memory_connection
        connection = sqlite3.connect(
            self.path,
            timeout=self.busy_timeout_ms / 1000,
            isolation_level=None,
            check_same_thread=self.path != ":memory:",
        )
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute(f"PRAGMA busy_timeout = {self.busy_timeout_ms}")
        if self.path != ":memory:":
            connection.execute("PRAGMA journal_mode = WAL")
        else:
            self._memory_connection = connection
        return connection

    @contextmanager
    def _connection(self):
        if self.path == ":memory:":
            with self._memory_lock:
                yield self._connect()
            return
        connection = self._connect()
        try:
            yield connection
        finally:
            connection.close()

    def close(self) -> None:
        """Release the optional in-memory connection; file operations are short-lived."""

        with self._memory_lock:
            if self._memory_connection is not None:
                self._memory_connection.close()
                self._memory_connection = None

    def _migrate(self) -> None:
        with self._connection() as connection:
            connection.execute(
                "CREATE TABLE IF NOT EXISTS schema_migrations "
                "(version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)"
            )
            current = connection.execute("SELECT MAX(version) FROM schema_migrations").fetchone()[0] or 0
            if current > self._SCHEMA_VERSION:
                raise HubStorageError("unsupported Hub state schema version")
            if current < 1:
                connection.execute("BEGIN IMMEDIATE")
                try:
                    connection.execute(
                        """CREATE TABLE IF NOT EXISTS hub_entities (
                            entity_type TEXT NOT NULL,
                            entity_id TEXT NOT NULL,
                            payload_json TEXT NOT NULL,
                            updated_at INTEGER NOT NULL,
                            deleted INTEGER NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1)),
                            PRIMARY KEY (entity_type, entity_id)
                        )"""
                    )
                    connection.execute(
                        """CREATE TABLE IF NOT EXISTS hub_changes (
                            revision INTEGER PRIMARY KEY AUTOINCREMENT,
                            entity_type TEXT NOT NULL,
                            entity_id TEXT NOT NULL,
                            payload_json TEXT NOT NULL,
                            updated_at INTEGER NOT NULL,
                            deleted INTEGER NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1)),
                            FOREIGN KEY (entity_type, entity_id)
                                REFERENCES hub_entities(entity_type, entity_id)
                                ON DELETE CASCADE
                        )"""
                    )
                    connection.execute(
                        "CREATE INDEX IF NOT EXISTS idx_hub_changes_entity_revision "
                        "ON hub_changes(entity_type, entity_id, revision)"
                    )
                    connection.execute(
                        "INSERT INTO schema_migrations(version, applied_at) VALUES(1, strftime('%s','now'))"
                    )
                    connection.commit()
                except Exception:
                    connection.rollback()
                    raise
                current = 1
            if current < 2:
                connection.execute("BEGIN IMMEDIATE")
                try:
                    connection.execute(
                        "CREATE TABLE IF NOT EXISTS hub_metadata "
                        "(key TEXT PRIMARY KEY, value TEXT NOT NULL)"
                    )
                    connection.execute(
                        "INSERT INTO schema_migrations(version, applied_at) VALUES(2, strftime('%s','now'))"
                    )
                    connection.commit()
                except Exception:
                    connection.rollback()
                    raise

    @staticmethod
    def _payload(record: HubRecord) -> str:
        return json.dumps(dict(record.payload), ensure_ascii=False, sort_keys=True, separators=(",", ":"))

    @staticmethod
    def _record(row: sqlite3.Row) -> HubRecord:
        payload = json.loads(row["payload_json"])
        if not isinstance(payload, dict):
            raise HubStorageError("corrupt Hub state payload")
        return HubRecord(
            entity_type=row["entity_type"],
            entity_id=row["entity_id"],
            payload=payload,
            updated_at=int(row["updated_at"]),
            deleted=bool(row["deleted"]),
        )

    def _apply(self, connection: sqlite3.Connection, record: HubRecord) -> HubChange | None:
        existing = connection.execute(
            "SELECT updated_at FROM hub_entities WHERE entity_type = ? AND entity_id = ?",
            (record.entity_type, record.entity_id),
        ).fetchone()
        if existing is not None and record.updated_at <= int(existing[0]):
            return None
        payload = self._payload(record)
        connection.execute(
            """INSERT INTO hub_entities(entity_type, entity_id, payload_json, updated_at, deleted)
               VALUES(?, ?, ?, ?, ?)
               ON CONFLICT(entity_type, entity_id) DO UPDATE SET
                 payload_json = excluded.payload_json,
                 updated_at = excluded.updated_at,
                 deleted = excluded.deleted""",
            (record.entity_type, record.entity_id, payload, record.updated_at, int(record.deleted)),
        )
        cursor = connection.execute(
            """INSERT INTO hub_changes(entity_type, entity_id, payload_json, updated_at, deleted)
               VALUES(?, ?, ?, ?, ?)""",
            (record.entity_type, record.entity_id, payload, record.updated_at, int(record.deleted)),
        )
        return HubChange(revision=int(cursor.lastrowid), record=record)

    def upsert(self, record: HubRecord | Mapping[str, object]) -> HubChange | None:
        normalized = normalize_record(record)
        with self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                change = self._apply(connection, normalized)
                connection.commit()
                return change
            except Exception:
                connection.rollback()
                raise

    def apply_import(self, records: Sequence[HubRecord | Mapping[str, object]]) -> tuple[HubChange, ...]:
        normalized = tuple(normalize_record(record) for record in records)
        with self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                changes = tuple(change for record in normalized if (change := self._apply(connection, record)) is not None)
                connection.commit()
                return changes
            except Exception:
                connection.rollback()
                raise

    def get(self, entity_type: str, entity_id: str) -> HubRecord | None:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT * FROM hub_entities WHERE entity_type = ? AND entity_id = ?",
                (str(entity_type), str(entity_id)),
            ).fetchone()
        return self._record(row) if row is not None else None

    def list_records(
        self,
        entity_type: str,
        *,
        include_deleted: bool = True,
        limit: int = 1_000,
    ) -> tuple[HubRecord, ...]:
        normalized_type = str(entity_type).strip()
        if not normalized_type:
            raise ValueError("entity_type is required")
        bounded_limit = max(1, min(int(limit), 10_000))
        predicate = "" if include_deleted else " AND deleted = 0"
        with self._connection() as connection:
            rows = connection.execute(
                "SELECT * FROM hub_entities WHERE entity_type = ?"
                f"{predicate} ORDER BY updated_at DESC, entity_id ASC LIMIT ?",
                (normalized_type, bounded_limit),
            ).fetchall()
        return tuple(self._record(row) for row in rows)

    def get_metadata(self, key: str) -> str | None:
        normalized_key = str(key).strip()
        if not normalized_key:
            raise ValueError("metadata key is required")
        with self._connection() as connection:
            row = connection.execute(
                "SELECT value FROM hub_metadata WHERE key = ?", (normalized_key,)
            ).fetchone()
        return str(row[0]) if row is not None else None

    def set_metadata(self, key: str, value: str) -> None:
        normalized_key = str(key).strip()
        if not normalized_key:
            raise ValueError("metadata key is required")
        normalized_value = str(value)
        with self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                connection.execute(
                    "INSERT INTO hub_metadata(key, value) VALUES(?, ?) "
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                    (normalized_key, normalized_value),
                )
                connection.commit()
            except Exception:
                connection.rollback()
                raise

    def changes_since(self, revision: int, limit: int = 500) -> tuple[HubChange, ...]:
        if isinstance(revision, bool) or not isinstance(revision, int) or revision < 0:
            raise ValueError("revision must be a non-negative integer")
        bounded_limit = max(1, min(int(limit), 5_000))
        with self._connection() as connection:
            rows = connection.execute(
                "SELECT * FROM hub_changes WHERE revision > ? ORDER BY revision ASC LIMIT ?",
                (revision, bounded_limit),
            ).fetchall()
        return tuple(HubChange(revision=int(row["revision"]), record=self._record(row)) for row in rows)

    def current_revision(self) -> int:
        with self._connection() as connection:
            row = connection.execute("SELECT COALESCE(MAX(revision), 0) FROM hub_changes").fetchone()
        return int(row[0])
