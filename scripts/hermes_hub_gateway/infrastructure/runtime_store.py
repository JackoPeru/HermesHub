"""Runtime adapter for Hub-owned SQLite state and revision synchronization.

The generated upstream module imports this adapter at runtime.  Keeping the
migration, LWW, tombstone and change-log behavior here lets the patcher remain
an idempotent bootstrap layer while legacy JSON files stay available for
rollback and manual recovery.
"""

from __future__ import annotations

from dataclasses import dataclass
import base64
import hashlib
import json
import os
from pathlib import Path
import time
from typing import Any, Mapping, Sequence

from ..modules.protocol import CorrelationContext
from ..modules.storage import HubChange, HubRecord, HubStorageError
from ..modules.sync import RevisionSyncEngine, SyncWake
from .sqlite import SQLiteHubStateStore


_MAX_RECORDS = 10_000
_MAX_IMPORT_RECORDS = 5_000
_DEFAULT_ARCHIVE_MAX_BYTES = 24 * 1024 * 1024


@dataclass(frozen=True, slots=True)
class MigrationReport:
    """Metadata-only result of a legacy JSON import attempt."""

    entity_type: str
    source_path: str
    database_path: str
    source_count: int = 0
    imported_count: int = 0
    tombstone_count: int = 0
    skipped_count: int = 0
    revision: int = 0
    migrated: bool = False
    failed: bool = False
    error: str | None = None

    def as_dict(self) -> dict[str, object]:
        return {
            "entity_type": self.entity_type,
            "source_path": self.source_path,
            "database_path": self.database_path,
            "source_count": self.source_count,
            "imported_count": self.imported_count,
            "tombstone_count": self.tombstone_count,
            "skipped_count": self.skipped_count,
            "revision": self.revision,
            "migrated": self.migrated,
            "failed": self.failed,
            "error": self.error,
        }


class HubRuntimeStore:
    """Application-facing storage port used by the generated gateway."""

    def __init__(self, database_path: str | Path) -> None:
        self.sqlite = SQLiteHubStateStore(database_path)
        self.sync = RevisionSyncEngine(self.sqlite)
        self._last_prune_monotonic = 0.0

    @classmethod
    def from_environment(cls) -> "HubRuntimeStore":
        configured = os.environ.get("HERMES_HUB_SQLITE_PATH", "").strip()
        if configured:
            database_path = Path(configured).expanduser()
        else:
            home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes"))).expanduser()
            database_path = home / "hub_state.sqlite3"
        try:
            busy_timeout = int(os.environ.get("HERMES_HUB_SQLITE_BUSY_TIMEOUT_MS", "5000"))
        except (TypeError, ValueError):
            busy_timeout = 5_000
        return cls(database_path) if busy_timeout == 5_000 else _ConfiguredRuntimeStore(database_path, busy_timeout)

    @property
    def database_path(self) -> str:
        return self.sqlite.path

    def close(self) -> None:
        self.sqlite.close()

    @staticmethod
    def _metadata_key(entity_type: str, source_path: Path) -> str:
        source = str(source_path.expanduser().resolve())
        digest = hashlib.sha256(source.encode("utf-8")).hexdigest()[:24]
        return f"legacy_import:{entity_type}:{digest}"

    @staticmethod
    def _number(value: object, fallback: float = 0.0) -> float:
        try:
            if value is None or isinstance(value, bool):
                return fallback
            return float(value)
        except (TypeError, ValueError):
            return fallback

    @staticmethod
    def _archive_max_bytes(explicit: object = None) -> float:
        """Resolve the byte budget for bulk payloads (0 disables the cap)."""

        if explicit is None:
            try:
                value = float(os.environ.get("HERMES_HUB_ARCHIVE_MAX_BYTES", "").strip() or _DEFAULT_ARCHIVE_MAX_BYTES)
            except (TypeError, ValueError):
                return _DEFAULT_ARCHIVE_MAX_BYTES
            return max(0.0, value)
        try:
            value = float(explicit)
        except (TypeError, ValueError):
            raise ValueError("archive query max_bytes must be a non-negative number")
        if value < 0:
            raise ValueError("archive query max_bytes must be a non-negative number")
        return value

    @staticmethod
    def _encode_cursor(updated_at: int, entity_id: str) -> str:
        raw = json.dumps([int(updated_at), str(entity_id)], ensure_ascii=False, separators=(",", ":"))
        return base64.urlsafe_b64encode(raw.encode("utf-8")).decode("ascii").rstrip("=")

    @staticmethod
    def _decode_cursor(cursor: str) -> tuple[int, str]:
        """Decode a ``_records_payload`` cursor; raises on garbage so callers 4xx."""

        try:
            padded = str(cursor).strip()
            if not padded:
                raise ValueError
            padded += "=" * (-len(padded) % 4)
            raw = base64.urlsafe_b64decode(padded.encode("ascii"))
            fields = json.loads(raw.decode("utf-8"))
            updated_at, entity_id = int(fields[0]), str(fields[1])
            if updated_at < 0 or not entity_id:
                raise ValueError
            return updated_at, entity_id
        except (UnicodeError, ValueError, TypeError, json.JSONDecodeError, IndexError):
            raise ValueError("invalid archive cursor")

    def _maybe_prune(self) -> None:
        """Opportunistically prune tombstones and the change-log, time-gated."""

        try:
            interval = float(os.environ.get("HERMES_HUB_PRUNE_INTERVAL_SECONDS", "").strip() or 21_600.0)
        except (TypeError, ValueError):
            interval = 21_600.0
        if interval <= 0:
            return
        now = time.monotonic()
        if now - self._last_prune_monotonic < interval:
            return
        self._last_prune_monotonic = now
        try:
            try:
                retention_days = float(os.environ.get("HERMES_HUB_TOMBSTONE_RETENTION_DAYS", "").strip() or 30.0)
            except (TypeError, ValueError):
                retention_days = 30.0
            cutoff = 0
            if retention_days > 0:
                cutoff = int(time.time() * 1000 - max(0.0, retention_days) * 86_400_000) - 1
            try:
                keep_rows = int(os.environ.get("HERMES_HUB_PRUNE_KEEP_CHANGE_ROWS", "").strip() or 100_000)
            except (TypeError, ValueError):
                keep_rows = 100_000
            self.sqlite.prune(tombstone_older_than_ms=cutoff, keep_change_rows=keep_rows)
        except HubStorageError:
            pass

    def _records_payload(
        self,
        entity_type: str,
        source_path: str | Path,
        *,
        item_key: str = "items",
        object_name: str,
        description: str,
        retention_days: float = 30.0,
        cursor: str | None = None,
        max_bytes: object = None,
    ) -> dict[str, object]:
        migration = self._ensure_migrated(entity_type, source_path, item_key)
        anchor = self._decode_cursor(cursor) if cursor else None
        budget = self._archive_max_bytes(max_bytes)
        self._maybe_prune()
        records = self.sqlite.list_records(
            entity_type,
            include_deleted=True,
            limit=_MAX_RECORDS,
            after_updated_at=anchor[0] if anchor is not None else None,
            after_entity_id=anchor[1] if anchor is not None else None,
        )
        cutoff = int(time.time() * 1000 - max(retention_days, 0.0) * 86_400_000)
        items: list[dict[str, Any]] = []
        used_bytes = 0
        last_anchor: tuple[int, str] | None = None
        truncated = False
        for record in records:
            payload = dict(record.payload)
            if record.deleted:
                deleted_at = self._number(payload.get("deletedAt", payload.get("deleted_at")))
                if deleted_at > 0 and deleted_at < cutoff:
                    continue
            if budget > 0:
                size = len(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
                if used_bytes + size > budget:
                    truncated = True
                    break
                used_bytes += size
            items.append(payload)
            last_anchor = (record.updated_at, record.entity_id)
        if truncated and not items:
            raise OverflowError(
                "archive record exceeds the configured response byte budget; "
                "raise HERMES_HUB_ARCHIVE_MAX_BYTES to serve it"
            )
        if not truncated and len(records) >= _MAX_RECORDS and (anchor is not None or budget > 0):
            truncated = True
        paging = anchor is not None or budget > 0
        result: dict[str, object] = {
            "object": object_name,
            "status": "ok",
            "items": items,
            "path": str(Path(source_path).expanduser()),
            "description": description,
            "revision": self.sqlite.current_revision(),
            "storage": "sqlite",
            "migration": migration.as_dict(),
        }
        if paging:
            result["truncated"] = truncated
            result["next_cursor"] = (
                self._encode_cursor(*last_anchor) if truncated and last_anchor is not None else None
            )
        return result

    def conversations_payload(
        self,
        source_path: str | Path,
        *,
        cursor: str | None = None,
        max_bytes: object = None,
    ) -> dict[str, object]:
        return self._records_payload(
            "conversation",
            source_path,
            object_name="hermes.hub.conversations",
            description="Archivio chat Hermes Hub condiviso tra Windows, Android e reinstallazioni app.",
            retention_days=self._number(os.environ.get("HERMES_HUB_DELETED_CONVERSATION_RETENTION_DAYS"), 30.0),
            cursor=cursor,
            max_bytes=max_bytes,
        )

    def merge_conversations(self, source_path: str | Path, incoming: Sequence[object]) -> dict[str, object]:
        self._ensure_migrated("conversation", source_path, "items")
        records = self._records_from_incoming("conversation", incoming)
        changes = self.sync.apply_import(records, reason="conversation-merge")
        payload = self.conversations_payload(source_path, max_bytes=0.0)
        payload["merged"] = len(changes)
        payload["revision"] = self.sqlite.current_revision()
        return payload

    @classmethod
    def _updated_at(cls, item: Mapping[str, Any]) -> int:
        value = next(
            (
                item.get(key)
                for key in ("updatedAt", "updated_at", "modified_at", "created_at", "timestamp")
                if item.get(key) is not None
            ),
            0,
        )
        number = cls._number(value)
        if number <= 0:
            return 0
        # Legacy Hub data is normally milliseconds; preserve second-based
        # values by converting them to the same monotonic millisecond domain.
        return int(number * 1000 if number < 10_000_000_000 else number)

    @staticmethod
    def _entity_id(item: Mapping[str, Any]) -> str:
        return str(
            item.get("id")
            or item.get("conversationId")
            or item.get("conversation_id")
            or item.get("state_id")
            or ""
        ).strip()

    @staticmethod
    def _deleted(item: Mapping[str, Any]) -> bool:
        value = item.get("deletedAt", item.get("deleted_at"))
        return bool(item.get("deleted")) or HubRuntimeStore._number(value) > 0

    @staticmethod
    def _legacy_candidates(payload: object, item_key: str) -> list[object]:
        if not isinstance(payload, dict):
            raise HubStorageError("legacy Hub JSON root must be an object")
        candidates: list[object] = []
        keys = tuple(dict.fromkeys((item_key, "items", "conversations")))
        for key in keys:
            values = payload.get(key)
            if isinstance(values, list):
                candidates.extend(values)
        archive = payload.get("archive")
        if isinstance(archive, dict):
            for key in keys:
                values = archive.get(key)
                if isinstance(values, list):
                    candidates.extend(values)
        return candidates

    def _record_from_legacy(self, entity_type: str, item: object) -> HubRecord | None:
        if not isinstance(item, dict):
            return None
        entity_id = self._entity_id(item)
        if not entity_id:
            return None
        return HubRecord(
            entity_type=entity_type,
            entity_id=entity_id,
            payload=dict(item),
            updated_at=self._updated_at(item),
            deleted=self._deleted(item),
        )

    def migrate_legacy_json(
        self,
        entity_type: str,
        source_path: str | Path,
        *,
        item_key: str = "items",
        raise_on_error: bool = True,
    ) -> MigrationReport:
        """Import one legacy JSON snapshot exactly once, transactionally.

        The source is intentionally never rewritten or deleted.  A malformed
        source or a serialization failure leaves both it and the SQLite
        transaction untouched so callers can fail closed and roll back.
        """

        normalized_type = str(entity_type).strip()
        if not normalized_type:
            raise ValueError("entity_type is required")
        source = Path(source_path).expanduser()
        marker = self._metadata_key(normalized_type, source)
        marker_value = self.sqlite.get_metadata(marker)
        if marker_value is not None:
            try:
                previous = json.loads(marker_value)
            except (TypeError, ValueError):
                previous = {}
            return MigrationReport(
                entity_type=normalized_type,
                source_path=str(source),
                database_path=self.database_path,
                source_count=int(previous.get("source_count", 0) or 0),
                imported_count=int(previous.get("imported_count", 0) or 0),
                tombstone_count=int(previous.get("tombstone_count", 0) or 0),
                skipped_count=int(previous.get("skipped_count", 0) or 0),
                revision=self.sqlite.current_revision(),
                migrated=True,
            )
        if not source.is_file():
            return MigrationReport(
                entity_type=normalized_type,
                source_path=str(source),
                database_path=self.database_path,
                revision=self.sqlite.current_revision(),
            )

        try:
            payload = json.loads(source.read_text(encoding="utf-8"))
            candidates = self._legacy_candidates(payload, item_key)
            records: list[HubRecord] = []
            skipped = 0
            for candidate in candidates[:_MAX_IMPORT_RECORDS]:
                record = self._record_from_legacy(normalized_type, candidate)
                if record is None:
                    skipped += 1
                    continue
                records.append(record)
            skipped += max(0, len(candidates) - _MAX_IMPORT_RECORDS)
            changes = self.sync.apply_import(records, reason=f"legacy-migration:{normalized_type}")
            tombstones = sum(1 for record in records if record.deleted)
            report = MigrationReport(
                entity_type=normalized_type,
                source_path=str(source),
                database_path=self.database_path,
                source_count=len(candidates),
                imported_count=len(changes),
                tombstone_count=tombstones,
                skipped_count=skipped,
                revision=self.sqlite.current_revision(),
                migrated=True,
            )
            self.sqlite.set_metadata(marker, json.dumps(report.as_dict(), sort_keys=True))
            return report
        except Exception as exc:
            report = MigrationReport(
                entity_type=normalized_type,
                source_path=str(source),
                database_path=self.database_path,
                failed=True,
                revision=self.sqlite.current_revision(),
                error=f"{type(exc).__name__}: {str(exc)[:240]}",
            )
            if raise_on_error:
                raise HubStorageError("legacy Hub JSON migration failed") from exc
            return report

    def _ensure_migrated(self, entity_type: str, source_path: str | Path, item_key: str) -> MigrationReport:
        return self.migrate_legacy_json(entity_type, source_path, item_key=item_key, raise_on_error=True)

    def delete_conversation(self, source_path: str | Path, conversation_id: str) -> dict[str, object]:
        self._ensure_migrated("conversation", source_path, "items")
        return self._delete_record("conversation", source_path, conversation_id)

    def state_payload(self, source_path: str | Path) -> dict[str, object]:
        return self._records_payload(
            "state",
            source_path,
            object_name="hermes.hub.state",
            description="Stato operativo sincronizzato da Hermes Hub: feedback video/news, letture e riferimenti progetto.",
            retention_days=0,
            max_bytes=0.0,
        )

    def add_state(self, source_path: str | Path, payload: Mapping[str, object]) -> dict[str, object]:
        self._ensure_migrated("state", source_path, "items")
        item = dict(payload)
        item.setdefault("id", f"hub_state_{int(time.time() * 1000)}")
        item.setdefault("created_at", time.time())
        item["updated_at"] = self._number(item.get("updated_at"), time.time())
        record = HubRecord(
            entity_type="state",
            entity_id=str(item["id"]),
            payload=item,
            updated_at=self._updated_at(item),
        )
        self.sync.upsert(record, reason="state-upsert")
        return dict(record.payload)

    def delete_state(self, source_path: str | Path, state_id: str) -> dict[str, object]:
        self._ensure_migrated("state", source_path, "items")
        return self._delete_record("state", source_path, state_id)

    def _delete_record(self, entity_type: str, source_path: str | Path, entity_id: str) -> dict[str, object]:
        existing = self.sqlite.get(entity_type, str(entity_id))
        now_ms = int(time.time() * 1000)
        payload = dict(existing.payload) if existing is not None else {"id": str(entity_id)}
        payload.update({"id": str(entity_id), "updatedAt": now_ms, "deletedAt": now_ms, "messages": []})
        if entity_type == "conversation":
            payload.setdefault("title", "Chat eliminata")
            payload.setdefault("kind", "Deleted")
        change = self.sync.upsert(
            HubRecord(entity_type, str(entity_id), payload, now_ms, deleted=True),
            reason=f"{entity_type}-delete",
        )
        return {
            "object": f"hermes.hub.{entity_type}.delete",
            "deleted": 1 if existing is None or not existing.deleted else 0,
            "id": str(entity_id),
            "tombstone": True,
            "revision": change.revision if change is not None else self.sqlite.current_revision(),
        }

    def _records_from_incoming(self, entity_type: str, incoming: Sequence[object]) -> tuple[HubRecord, ...]:
        records: list[HubRecord] = []
        for item in list(incoming)[:_MAX_IMPORT_RECORDS]:
            record = self._record_from_legacy(entity_type, item)
            if record is not None:
                if record.updated_at == 0:
                    record = HubRecord(entity_type, record.entity_id, record.payload, int(time.time() * 1000), record.deleted)
                records.append(record)
        return tuple(records)

    def changes_since(self, since: int, limit: int = 500, max_bytes: object = None) -> dict[str, object]:
        """Revision deltas; optional byte budget trims the payload, never the progression."""
        budget = self._archive_max_bytes(max_bytes)
        self._maybe_prune()
        changes = self.sync.changes_since(since, limit=max(1, min(int(limit), 5_000)))
        current = self.sqlite.current_revision()
        included: list[dict[str, object]] = []
        used_bytes = 0
        reached = since
        trimmed_by_bytes = False
        for change in changes:
            item = self._change_dict(change)
            if budget > 0 and included:
                size = len(json.dumps(item, ensure_ascii=False, separators=(",", ":")))
                if used_bytes + size > budget:
                    trimmed_by_bytes = True
                    break
                used_bytes += size
            included.append(item)
            reached = change.revision
        has_more = bool(included and reached < current) or bool(trimmed_by_bytes and included)
        result: dict[str, object] = {
            "object": "hermes.hub.sync",
            "protocol_version": 1,
            "since": since,
            "revision": current,
            "next_revision": reached,
            "has_more": has_more,
            "storage": "sqlite",
            "changes": included,
        }
        if budget > 0:
            result["payload_bytes"] = used_bytes
            result["truncated"] = trimmed_by_bytes
        return result

    def sync_wake_event(self, since: int, context: CorrelationContext) -> dict[str, object]:
        current = self.sqlite.current_revision()
        changed = self.sync.changes_since(since, limit=1)
        wake = SyncWake(
            revision=current,
            reason="revision-pull",
            changed_entities=tuple(
                (change.record.entity_type, change.record.entity_id) for change in changed
            ),
        )
        return self.sync.wake_event(wake, context=context)

    @staticmethod
    def _change_dict(change: HubChange) -> dict[str, object]:
        return {
            "revision": change.revision,
            "entity_type": change.record.entity_type,
            "entity_id": change.record.entity_id,
            "updated_at": change.record.updated_at,
            "deleted": change.record.deleted,
            "payload": dict(change.record.payload),
        }


class _ConfiguredRuntimeStore(HubRuntimeStore):
    """Internal constructor variant retaining the env busy-timeout setting."""

    def __init__(self, database_path: str | Path, busy_timeout_ms: int) -> None:
        self.sqlite = SQLiteHubStateStore(database_path, busy_timeout_ms=busy_timeout_ms)
        self.sync = RevisionSyncEngine(self.sqlite)


__all__ = ["HubRuntimeStore", "MigrationReport"]
