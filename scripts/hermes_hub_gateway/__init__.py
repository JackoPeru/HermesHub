"""Hermes Hub gateway protocol primitives.

Business modules are added incrementally; this package is the stable import
boundary for the canonical wire contract.
"""

from .protocol import (
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
from .adapters.hermes.agent_runtime import (
    AgentRunRequest,
    AgentRunResult,
    AgentRuntime,
    AgentRuntimeEvent,
    HermesAgentPort,
    HermesAgentRuntimeAdapter,
    LegacyHermesAgentPort,
    adapter_for_legacy_owner,
)
from .infrastructure.sqlite import SQLiteHubStateStore
from .infrastructure.runtime_store import HubRuntimeStore, MigrationReport
from .modules.jarvis import JarvisFrame, JarvisLifecycle, JarvisPhase, SingleModelGpuGate
from .modules.storage import HubChange, HubRecord, HubStateStore, HubStorageError
from .modules.sync import RevisionSyncEngine, SyncWake

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
    "AgentRunRequest",
    "AgentRunResult",
    "AgentRuntime",
    "AgentRuntimeEvent",
    "HermesAgentPort",
    "HermesAgentRuntimeAdapter",
    "LegacyHermesAgentPort",
    "adapter_for_legacy_owner",
    "HubChange",
    "HubRecord",
    "HubStateStore",
    "HubStorageError",
    "JarvisFrame",
    "JarvisLifecycle",
    "JarvisPhase",
    "RevisionSyncEngine",
    "SQLiteHubStateStore",
    "HubRuntimeStore",
    "MigrationReport",
    "SingleModelGpuGate",
    "SyncWake",
]
