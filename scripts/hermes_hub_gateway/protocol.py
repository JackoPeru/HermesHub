"""Canonical Hermes Hub Protocol v1 primitives, stdlib-only."""

from __future__ import annotations

from dataclasses import dataclass
import re
import uuid
from typing import Any, Mapping


PROTOCOL_VERSION = 1
_WIRE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


def _wire_id(value: str, field: str) -> str:
    normalized = str(value).strip()
    if not _WIRE_ID.fullmatch(normalized):
        raise ValueError(f"invalid {field}")
    return normalized


@dataclass(frozen=True, slots=True)
class CorrelationContext:
    request_id: str
    correlation_id: str


def new_correlation_context() -> CorrelationContext:
    token = uuid.uuid4().hex
    return CorrelationContext(f"req_{token}", f"corr_{token}")


def correlation_context_from_headers(headers: Mapping[str, Any] | None) -> CorrelationContext:
    """Return a validated context from HTTP headers, or generate one.

    Client supplied IDs are metadata only.  Invalid or overlong values are
    discarded as a pair so a malformed request can never poison a later
    request through a shared context variable.
    """

    values = headers or {}
    request_id = values.get("X-Hermes-Request-Id") or values.get("X-Request-Id")
    correlation_id = values.get("X-Hermes-Correlation-Id") or request_id
    try:
        return CorrelationContext(
            _wire_id(str(request_id), "request_id"),
            _wire_id(str(correlation_id), "correlation_id"),
        )
    except (TypeError, ValueError):
        return new_correlation_context()


def correlation_headers(context: CorrelationContext) -> dict[str, str]:
    """Headers emitted on every compatible response, including SSE."""

    return {
        "X-Hermes-Request-Id": _wire_id(context.request_id, "request_id"),
        "X-Hermes-Correlation-Id": _wire_id(context.correlation_id, "correlation_id"),
        "X-Request-Id": _wire_id(context.request_id, "request_id"),
    }


@dataclass(frozen=True, slots=True)
class EventEnvelope:
    protocol_version: int
    event_id: str
    sequence: int
    request_id: str
    correlation_id: str
    event_type: str
    payload: Any
    source_type: str
    run_id: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "protocol_version": self.protocol_version,
            "event_id": self.event_id,
            "sequence": self.sequence,
            "request_id": self.request_id,
            "correlation_id": self.correlation_id,
            "run_id": self.run_id,
            "type": self.event_type,
            "payload": self.payload,
            "source_type": self.source_type,
        }


@dataclass(frozen=True, slots=True)
class ProtocolError:
    code: str
    message: str
    request_id: str
    correlation_id: str
    run_id: str | None = None
    retryable: bool = False
    details: Mapping[str, Any] | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "protocol_version": PROTOCOL_VERSION,
            "code": self.code,
            "message": self.message,
            "request_id": self.request_id,
            "correlation_id": self.correlation_id,
            "run_id": self.run_id,
            "retryable": self.retryable,
            "details": dict(self.details or {}),
        }


def build_event_envelope(
    *,
    event_type: str,
    payload: Any,
    sequence: int,
    context: CorrelationContext,
    source_type: str,
    event_id: str | None = None,
    run_id: str | None = None,
) -> dict[str, Any]:
    if isinstance(sequence, bool) or not isinstance(sequence, int) or sequence < 0:
        raise ValueError("sequence must be a non-negative integer")
    return EventEnvelope(
        protocol_version=PROTOCOL_VERSION,
        event_id=_wire_id(event_id or f"evt_{uuid.uuid4().hex}", "event_id"),
        sequence=sequence,
        request_id=_wire_id(context.request_id, "request_id"),
        correlation_id=_wire_id(context.correlation_id, "correlation_id"),
        event_type=str(event_type).strip()[:160] or "hermes.event",
        payload=payload,
        source_type=source_type if source_type in {"hermes-agent", "hermes-gateway", "hermes-hub", "unknown"} else "unknown",
        run_id=_wire_id(run_id, "run_id") if run_id else None,
    ).as_dict()


def parse_event_envelope(value: Mapping[str, Any]) -> EventEnvelope | None:
    if value.get("protocol_version") != PROTOCOL_VERSION:
        return None
    required = ("event_id", "sequence", "request_id", "correlation_id", "type", "payload", "source_type")
    if any(key not in value for key in required):
        return None
    try:
        raw_sequence = value["sequence"]
        if isinstance(raw_sequence, bool) or not isinstance(raw_sequence, int) or raw_sequence < 0:
            return None
        sequence = raw_sequence
        if any(not isinstance(value.get(field), str) for field in ("event_id", "request_id", "correlation_id", "type", "source_type")):
            return None
        source_type = value["source_type"]
        if source_type not in {"hermes-agent", "hermes-gateway", "hermes-hub", "unknown"}:
            return None
        event_type = value["type"].strip()
        if not event_type or len(event_type) > 160:
            return None
        run_value = value.get("run_id")
        if run_value is not None and not isinstance(run_value, str):
            return None
        return EventEnvelope(
            protocol_version=PROTOCOL_VERSION,
            event_id=_wire_id(value["event_id"], "event_id"),
            sequence=sequence,
            request_id=_wire_id(value["request_id"], "request_id"),
            correlation_id=_wire_id(value["correlation_id"], "correlation_id"),
            event_type=event_type,
            payload=value["payload"],
            source_type=source_type,
            run_id=_wire_id(run_value, "run_id") if run_value else None,
        )
    except (TypeError, ValueError):
        return None


def build_error(
    *,
    code: str,
    message: str,
    context: CorrelationContext,
    retryable: bool = False,
    run_id: str | None = None,
    details: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    return ProtocolError(
        code=_wire_id(code, "code"),
        message=str(message).strip()[:1000] or "Hermes Hub request failed.",
        request_id=_wire_id(context.request_id, "request_id"),
        correlation_id=_wire_id(context.correlation_id, "correlation_id"),
        run_id=_wire_id(run_id, "run_id") if run_id else None,
        retryable=bool(retryable),
        details=details,
    ).as_dict()


def _event_type(raw: Mapping[str, Any], event_name: str | None) -> str:
    for candidate in (raw.get("type"), event_name, raw.get("event"), raw.get("object")):
        if isinstance(candidate, str) and candidate.strip():
            return candidate.strip()[:160]
    return "hermes.event"


def enrich_sse_event(
    *,
    raw: Any,
    event_name: str | None,
    sequence: int,
    context: CorrelationContext,
    source_type: str = "hermes-agent",
    run_id: str | None = None,
) -> dict[str, Any]:
    """Wrap an upstream SSE item while retaining its legacy top-level shape.

    The complete upstream mapping is copied into ``payload`` and then merged
    back at the top level.  This keeps existing OpenAI/Hermes consumers able
    to read ``choices``/``delta`` while native clients can use one envelope.
    Values such as whitespace-only deltas are never normalized.
    """

    existing = parse_event_envelope(raw) if isinstance(raw, Mapping) else None
    if existing is not None:
        return dict(raw)
    if isinstance(raw, Mapping):
        legacy = dict(raw)
        effective_run_id = run_id or (raw.get("run_id") if isinstance(raw.get("run_id"), str) else None)
        effective_source = raw.get("source_type") if raw.get("source_type") in {
            "hermes-agent", "hermes-gateway", "hermes-hub", "unknown"
        } else source_type
        envelope = build_event_envelope(
            event_type=_event_type(raw, event_name),
            payload=legacy,
            sequence=sequence,
            context=context,
            source_type=effective_source,
            run_id=effective_run_id,
        )
        return {**legacy, **envelope}
    envelope = build_event_envelope(
        event_type=event_name or "hermes.event",
        payload=raw,
        sequence=sequence,
        context=context,
        source_type=source_type,
        run_id=run_id,
    )
    return envelope
