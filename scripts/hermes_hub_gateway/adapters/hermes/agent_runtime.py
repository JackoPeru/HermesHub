"""AgentRuntime port and Hermes Agent adapter.

Hub code speaks in request/correlation/run concepts.  The adapter is the only
place where those concepts are translated into the Hermes Agent execution
port, preserving Hermes ownership of planning, memory, tools, policy, and
retries.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping, Protocol


@dataclass(frozen=True, slots=True)
class AgentRunRequest:
    request_id: str
    correlation_id: str
    prompt: str
    model: str = "hermes-agent"
    preferred_protocol: str = "hermes-native"
    run_id: str | None = None
    metadata: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class AgentRuntimeEvent:
    event_type: str
    payload: Any
    sequence: int = 0
    run_id: str | None = None


@dataclass(frozen=True, slots=True)
class AgentRunResult:
    request_id: str
    correlation_id: str
    run_id: str | None
    text: str
    events: tuple[AgentRuntimeEvent, ...] = ()
    result: Any = None
    usage: Mapping[str, Any] = field(default_factory=dict)


class AgentRuntime(Protocol):
    """Hub-facing runtime port; implementations may stream internally."""

    async def run(self, request: AgentRunRequest) -> AgentRunResult:
        ...

    async def cancel(self, request_id: str, *, correlation_id: str | None = None) -> None:
        ...


class HermesAgentPort(Protocol):
    """Hermes Agent-facing execution port used only by the adapter."""

    async def execute(
        self,
        *,
        prompt: str,
        model: str,
        preferred_protocol: str,
        request_id: str,
        correlation_id: str,
        run_id: str | None,
        metadata: Mapping[str, Any],
    ) -> AgentRunResult:
        ...

    async def cancel(self, request_id: str, *, correlation_id: str | None = None) -> None:
        ...


class HermesAgentRuntimeAdapter:
    """Translate Hub requests to the Hermes Agent port without policy logic."""

    def __init__(self, port: HermesAgentPort) -> None:
        self.port = port

    async def run(self, request: AgentRunRequest) -> AgentRunResult:
        return await self.port.execute(
            prompt=request.prompt,
            model=request.model,
            preferred_protocol=request.preferred_protocol,
            request_id=request.request_id,
            correlation_id=request.correlation_id,
            run_id=request.run_id,
            metadata=request.metadata,
        )

    async def cancel(self, request_id: str, *, correlation_id: str | None = None) -> None:
        await self.port.cancel(request_id, correlation_id=correlation_id)


class LegacyHermesAgentPort:
    """Adapter port backed by an existing upstream ``_run_agent`` method.

    This deliberately delegates the complete legacy call, including all
    callbacks and session state, so the Hub boundary cannot acquire a second
    planning or tool loop.
    """

    def __init__(self, owner: Any) -> None:
        self.owner = owner

    async def execute(
        self,
        *,
        prompt: str,
        model: str,
        preferred_protocol: str,
        request_id: str,
        correlation_id: str,
        run_id: str | None,
        metadata: Mapping[str, Any],
    ) -> AgentRunResult:
        del prompt, model, preferred_protocol
        legacy_kwargs = dict(metadata.get("_hermes_hub_legacy_kwargs") or {})
        value = await self.owner._hermes_hub_legacy_run_agent(**legacy_kwargs)
        if isinstance(value, tuple) and len(value) == 2:
            result, usage = value
        else:
            result, usage = value, {}
        text = result.get("final_response", "") if isinstance(result, dict) else str(result or "")
        return AgentRunResult(
            request_id=request_id,
            correlation_id=correlation_id,
            run_id=run_id,
            text=text,
            result=(result, usage),
            usage=usage if isinstance(usage, Mapping) else {},
        )

    async def cancel(self, request_id: str, *, correlation_id: str | None = None) -> None:
        del request_id, correlation_id


def adapter_for_legacy_owner(owner: Any) -> HermesAgentRuntimeAdapter:
    """Return one adapter per upstream server instance."""

    runtime = getattr(owner, "_hermes_hub_agent_runtime_instance", None)
    if runtime is None:
        runtime = HermesAgentRuntimeAdapter(LegacyHermesAgentPort(owner))
        try:
            setattr(owner, "_hermes_hub_agent_runtime_instance", runtime)
        except Exception:
            pass
    return runtime


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
