"""Correlation and SSE compatibility bridge for generated Hermes servers."""

from __future__ import annotations

import json
from typing import Any, Awaitable, Callable, Mapping

from ...protocol import (
    CorrelationContext,
    correlation_context_from_headers,
    correlation_headers,
    enrich_sse_event,
    new_correlation_context,
)

try:  # pragma: no cover - exercised by generated aiohttp integration tests
    from aiohttp import web
except Exception:  # pragma: no cover
    web = None  # type: ignore[assignment]

_context_var: Any = None


def request_context(request: Any = None) -> CorrelationContext:
    global _context_var
    import contextvars

    if _context_var is None:
        _context_var = contextvars.ContextVar("hermes_hub_correlation_context", default=None)
    if request is not None:
        value = correlation_context_from_headers(getattr(request, "headers", {}))
        _context_var.set(value)
        return value
    value = _context_var.get()
    if value is None:
        value = new_correlation_context()
        _context_var.set(value)
    return value


def bind_request_context(request: Any) -> CorrelationContext:
    return request_context(request)


def response_headers(request: Any = None) -> dict[str, str]:
    return correlation_headers(request_context(request))


async def correlation_middleware(request: Any, handler: Callable[[Any], Awaitable[Any]]) -> Any:
    bind_request_context(request)
    try:
        response = await handler(request)
    except Exception as exc:
        if web is not None and isinstance(exc, web.HTTPException):
            exc.headers.update(response_headers())
        raise
    if response is not None and hasattr(response, "headers"):
        response.headers.update(response_headers())
    return response


def _enrich_sse_chunk(response: Any, data: bytes | bytearray) -> bytes:
    pending = getattr(response, "_hermes_hub_sse_buffer", "")
    try:
        pending += bytes(data).decode("utf-8")
    except UnicodeDecodeError:
        return bytes(data)
    frames = pending.split("\n\n")
    if len(frames) == 1:
        response._hermes_hub_sse_buffer = pending
        return b""
    response._hermes_hub_sse_buffer = frames.pop()
    output: list[str] = []
    for frame in frames:
        if not frame or frame.startswith(":"):
            output.append(frame)
            continue
        event_name: str | None = None
        data_lines: list[str] = []
        other_lines: list[str] = []
        for line in frame.replace("\r\n", "\n").split("\n"):
            if line.startswith("event:"):
                event_name = line[6:].lstrip(" ")
            elif line.startswith("data:"):
                value = line[5:]
                if value.startswith(" "):
                    value = value[1:]
                data_lines.append(value)
            else:
                other_lines.append(line)
        if not data_lines:
            output.append(frame)
            continue
        raw_data = "\n".join(data_lines)
        if raw_data.strip() == "[DONE]":
            output.append(frame)
            continue
        try:
            raw: Any = json.loads(raw_data)
        except Exception:
            raw = raw_data
        response._hermes_hub_sse_sequence = int(
            getattr(response, "_hermes_hub_sse_sequence", 0)
        ) + 1
        context = getattr(response, "_hermes_hub_correlation_context", request_context())
        event = enrich_sse_event(
            raw=raw,
            event_name=event_name,
            sequence=response._hermes_hub_sse_sequence,
            context=context,
            source_type="hermes-agent",
            run_id=raw.get("run_id") if isinstance(raw, Mapping) else None,
        )
        encoded = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
        output.append(
            "\n".join(
                other_lines
                + ([f"event: {event_name}"] if event_name else [])
                + [f"data: {encoded}"]
            )
        )
    return "\n\n".join(output).encode("utf-8")


def install(web_module: Any = None) -> None:
    """Install idempotent prepare/write hooks on the generated aiohttp app."""

    module = web_module or web
    if module is None:
        return
    response_type = module.StreamResponse
    if not getattr(response_type, "_hermes_hub_prepare_v1", False):
        original_prepare = response_type.prepare

        async def prepare(response: Any, request: Any) -> Any:
            response._hermes_hub_correlation_context = request_context(request)
            response.headers.update(response_headers())
            return await original_prepare(response, request)

        response_type.prepare = prepare
        response_type._hermes_hub_prepare_v1 = True
    if not getattr(response_type, "_hermes_hub_write_v1", False):
        original_write = response_type.write

        async def write(response: Any, data: Any) -> Any:
            content_type = str(response.headers.get("Content-Type", ""))
            if "text/event-stream" in content_type.lower() and isinstance(data, (bytes, bytearray)):
                data = _enrich_sse_chunk(response, data)
                if not data:
                    return None
            return await original_write(response, data)

        response_type.write = write
        response_type._hermes_hub_write_v1 = True


__all__ = [
    "bind_request_context",
    "correlation_middleware",
    "install",
    "request_context",
    "response_headers",
]
