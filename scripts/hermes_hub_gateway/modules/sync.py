"""Revision-based Hub synchronization and push-assisted SSE wakeups."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, Mapping, Sequence

from .protocol import CorrelationContext, build_event_envelope
from .storage import HubChange, HubRecord, HubStateStore


@dataclass(frozen=True, slots=True)
class SyncWake:
    """Metadata-only notification; clients pull the authoritative change log."""

    revision: int
    reason: str
    changed_entities: tuple[tuple[str, str], ...]

    def as_dict(self) -> dict[str, object]:
        return {
            "revision": self.revision,
            "reason": self.reason,
            "changed_entities": [
                {"entity_type": entity_type, "entity_id": entity_id}
                for entity_type, entity_id in self.changed_entities
            ],
        }


class RevisionSyncEngine:
    """Coordinate transactional state changes and advisory client wakeups.

    The callback is deliberately metadata-only.  It must not receive or log
    record payloads; REST ``changes_since`` remains the source of truth after
    an SSE wakeup.
    """

    def __init__(
        self,
        store: HubStateStore,
        *,
        wake_callback: Callable[[SyncWake], None] | None = None,
    ) -> None:
        self.store = store
        self.wake_callback = wake_callback

    @staticmethod
    def _reason(reason: str) -> str:
        value = str(reason).strip()
        return value[:120] or "mutation"

    def _wake(self, changes: Sequence[HubChange], reason: str) -> SyncWake | None:
        if not changes:
            return None
        wake = SyncWake(
            revision=changes[-1].revision,
            reason=self._reason(reason),
            changed_entities=tuple(
                (change.record.entity_type, change.record.entity_id) for change in changes
            ),
        )
        if self.wake_callback is not None:
            # A failed advisory notification must not turn a committed write
            # into a retryable mutation.  The next REST pull remains safe.
            try:
                self.wake_callback(wake)
            except Exception:
                pass
        return wake

    def upsert(self, record: HubRecord | Mapping[str, object], *, reason: str = "mutation") -> HubChange | None:
        change = self.store.upsert(record)
        self._wake((change,) if change is not None else (), reason)
        return change

    def apply_import(
        self,
        records: Sequence[HubRecord | Mapping[str, object]],
        *,
        reason: str = "import",
    ) -> tuple[HubChange, ...]:
        changes = self.store.apply_import(records)
        self._wake(changes, reason)
        return changes

    def changes_since(self, revision: int, *, limit: int = 500) -> tuple[HubChange, ...]:
        return self.store.changes_since(revision, limit)

    def current_revision(self) -> int:
        return self.store.current_revision()

    @staticmethod
    def wake_event(
        wake: SyncWake,
        *,
        context: CorrelationContext,
        event_id: str | None = None,
        run_id: str | None = None,
    ) -> dict[str, object]:
        """Build the canonical envelope without embedding record contents."""

        return build_event_envelope(
            event_type="hub.sync.wake",
            payload=wake.as_dict(),
            sequence=wake.revision,
            context=context,
            source_type="hermes-gateway",
            event_id=event_id,
            run_id=run_id,
        )


__all__ = ["RevisionSyncEngine", "SyncWake"]
