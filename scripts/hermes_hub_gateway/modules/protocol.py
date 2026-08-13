"""Protocol boundary consumed by gateway modules.

The wire primitives remain in the package root for compatibility with the
checkpoint-1 validator.  This module gives feature code an explicit modules
boundary without creating a second protocol implementation.
"""

from ..protocol import (
    PROTOCOL_VERSION,
    CorrelationContext,
    EventEnvelope,
    ProtocolError,
    build_error,
    build_event_envelope,
    correlation_context_from_headers,
    correlation_headers,
    enrich_sse_event,
    new_correlation_context,
    parse_event_envelope,
)

__all__ = [
    "PROTOCOL_VERSION",
    "CorrelationContext",
    "EventEnvelope",
    "ProtocolError",
    "build_error",
    "build_event_envelope",
    "correlation_context_from_headers",
    "correlation_headers",
    "enrich_sse_event",
    "new_correlation_context",
    "parse_event_envelope",
]
