"""Adapters for the upstream Hermes Agent runtime and patch surface."""

from .agent_runtime import (
    AgentRunRequest,
    AgentRunResult,
    AgentRuntime,
    AgentRuntimeEvent,
    HermesAgentPort,
    HermesAgentRuntimeAdapter,
    LegacyHermesAgentPort,
    adapter_for_legacy_owner,
)

__all__ = [
    "AgentRunRequest",
    "AgentRunResult",
    "AgentRuntime",
    "AgentRuntimeEvent",
    "HermesAgentPort",
    "HermesAgentRuntimeAdapter",
    "LegacyHermesAgentPort",
    "adapter_for_legacy_owner",
]
