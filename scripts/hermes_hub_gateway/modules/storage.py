"""Hub-owned structured state boundary.

Hermes Agent remains the owner of planning, durable agent memory, tool loops,
policy, retries, and cron state. This module only describes Hub records and
their durable change-log contract; media and other large artifacts stay on
file storage.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Protocol, Sequence


class HubStorageError(RuntimeError):
    """Raised when a Hub state operation cannot be completed safely."""


@dataclass(frozen=True, slots=True)
class HubRecord:
    entity_type: str
    entity_id: str
    payload: Mapping[str, Any]
    updated_at: int
    deleted: bool = False


@dataclass(frozen=True, slots=True)
class HubChange:
    revision: int
    record: HubRecord


def normalize_record(value: HubRecord | Mapping[str, Any]) -> HubRecord:
    if isinstance(value, HubRecord):
        record = value
    elif isinstance(value, Mapping):
        record = HubRecord(
            entity_type=str(value.get("entity_type", "")).strip(),
            entity_id=str(value.get("entity_id", "")).strip(),
            payload=value.get("payload") if isinstance(value.get("payload"), Mapping) else {},
            updated_at=value.get("updated_at", 0),
            deleted=bool(value.get("deleted", False)),
        )
    else:
        raise HubStorageError("invalid Hub record")
    if not record.entity_type or not record.entity_id:
        raise HubStorageError("Hub record identity is required")
    if isinstance(record.updated_at, bool) or not isinstance(record.updated_at, int) or record.updated_at < 0:
        raise HubStorageError("Hub record updated_at must be a non-negative integer")
    return record


class HubStateStore(Protocol):
    """Persistence port for Hub-owned state."""

    def upsert(self, record: HubRecord | Mapping[str, Any]) -> HubChange | None:
        """Apply LWW state and return a change only when it wins."""

    def apply_import(self, records: Sequence[HubRecord | Mapping[str, Any]]) -> tuple[HubChange, ...]:
        """Apply a verified batch transactionally or raise without partial writes."""

    def get(self, entity_type: str, entity_id: str) -> HubRecord | None:
        ...

    def list_records(
        self,
        entity_type: str,
        *,
        include_deleted: bool = True,
        limit: int = 1_000,
    ) -> tuple[HubRecord, ...]:
        """Return a bounded snapshot without exposing storage internals."""

    def get_metadata(self, key: str) -> str | None:
        ...

    def set_metadata(self, key: str, value: str) -> None:
        ...

    def changes_since(self, revision: int, limit: int = 500) -> tuple[HubChange, ...]:
        ...

    def current_revision(self) -> int:
        ...
