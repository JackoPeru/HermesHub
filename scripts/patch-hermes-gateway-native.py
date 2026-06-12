#!/usr/bin/env python3
"""Patch Hermes Gateway API server for Hermes Hub native mode.

This is intentionally idempotent. It is used by the Ubuntu/headless launcher
until the same contract lands upstream in Hermes.
"""

from __future__ import annotations

import argparse
import importlib
import inspect
import os
import py_compile
import re
import shutil
import sys
import time
from pathlib import Path


def _candidate_paths() -> list[Path]:
    candidates: list[Path] = []

    explicit = os.environ.get("HERMES_GATEWAY_API_SERVER_PATH")
    if explicit:
        candidates.append(Path(explicit).expanduser())

    try:
        module = importlib.import_module("gateway.platforms.api_server")
        candidates.append(Path(inspect.getfile(module)))
    except Exception:
        pass

    home = Path.home()
    hermes_home = Path(os.environ.get("HERMES_HOME", home / ".hermes")).expanduser()
    roots = [
        hermes_home,
        home / ".local",
        home / ".cache",
        Path.cwd(),
    ]
    rels = [
        Path("hermes-agent/gateway/platforms/api_server.py"),
        Path("gateway/platforms/api_server.py"),
        Path("src/hermes_agent/gateway/platforms/api_server.py"),
    ]
    for root in roots:
        for rel in rels:
            candidates.append(root / rel)

    # Small bounded fallback for common local source installs.
    for root in (hermes_home, Path.cwd()):
        if root.exists():
            try:
                candidates.extend(root.glob("**/gateway/platforms/api_server.py"))
            except Exception:
                pass

    unique: list[Path] = []
    seen: set[str] = set()
    for path in candidates:
        resolved = str(path.expanduser())
        if resolved not in seen:
            unique.append(path.expanduser())
            seen.add(resolved)
    return unique


def _find_target(explicit: str | None) -> Path:
    if explicit:
        path = Path(explicit).expanduser()
        if path.is_file():
            return path
        raise FileNotFoundError(f"Gateway api_server.py not found: {path}")

    for path in _candidate_paths():
        if path.is_file():
            return path

    raise FileNotFoundError(
        "Gateway api_server.py not found. Set HERMES_GATEWAY_API_SERVER_PATH "
        "to the full path of gateway/platforms/api_server.py."
    )


def _replace_once(text: str, old: str, new: str, label: str) -> tuple[str, bool]:
    if old not in text:
        raise RuntimeError(f"Patch anchor not found: {label}")
    return text.replace(old, new, 1), True


def _replace_regex_once(text: str, pattern: str, repl: str, label: str) -> tuple[str, bool]:
    patched, count = re.subn(pattern, repl, text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise RuntimeError(f"Patch anchor not found: {label}")
    return patched, True


def _patch_text(text: str) -> tuple[str, list[str]]:
    changes: list[str] = []

    if '"native_protocol": {' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+"version": _hermes_version\(\),\n)(^\s+"gateway_state": runtime\.get\("gateway_state"\),)',
            r'\1'
            r'            "native_protocol": {' "\n"
            r'                "name": "hermes-native",' "\n"
            r'                "transport": "responses",' "\n"
            r'                "endpoint": "/v1/responses",' "\n"
            r'                "alias": "/v1/hermes/native",' "\n"
            r'                "context_owner": "hermes-agent",' "\n"
            r'                "raw_event_passthrough": True,' "\n"
            r'            },' "\n"
            r'\2',
            "health_detailed native_protocol",
        )
        changes.append("health_detailed native_protocol")

    if '"hermes_native": True,' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+"features": \{\n)(^\s+"chat_completions": True,)',
            r'\1'
            r'                "hermes_native": True,' "\n"
            r'                "native_responses": True,' "\n"
            r'                "native_endpoint": "/v1/hermes/native",' "\n"
            r'                "native_event_passthrough": True,' "\n"
            r'                "raw_hermes_events": True,' "\n"
            r'                "strict_native_compatible": True,' "\n"
            r'                "context_owner": "hermes-agent",' "\n"
            r'                "planner_events": True,' "\n"
            r'                "memory_events": True,' "\n"
            r'                "retrieval_events": True,' "\n"
            r'                "artifact_events": True,' "\n"
            r'\2',
            "capabilities hermes_native",
        )
        changes.append("capabilities hermes_native")

    if '"hermes_native": {"method": "POST", "path": "/v1/hermes/native"}' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+"responses": \{"method": "POST", "path": "/v1/responses"\},\n)(^\s+"runs": \{"method": "POST", "path": "/v1/runs"\},)',
            r'\1'
            r'                "hermes_native": {"method": "POST", "path": "/v1/hermes/native"},' "\n"
            r'\2',
            "capabilities hermes_native endpoint",
        )
        changes.append("capabilities hermes_native endpoint")

    if 'await _write_event("hermes.native.protocol"' not in text:
        text, _ = _replace_once(
            text,
            '            await _write_event("response.created", {\n'
            '                "type": "response.created",\n'
            '                "response": created_env,\n'
            '            })\n'
            '            _persist_response_snapshot(created_env)',
            '            await _write_event("response.created", {\n'
            '                "type": "response.created",\n'
            '                "response": created_env,\n'
            '            })\n'
            '            await _write_event("hermes.native.protocol", {\n'
            '                "type": "hermes.native.protocol",\n'
            '                "protocol": "hermes-native",\n'
            '                "transport": "responses",\n'
            '                "endpoint": "/v1/responses",\n'
            '                "native_endpoint": "/v1/hermes/native",\n'
            '                "context_owner": "hermes-agent",\n'
            '                "raw_event_passthrough": True,\n'
            '                "strict_native_compatible": True,\n'
            '                "session_id": session_id,\n'
            '            })\n'
            '            _persist_response_snapshot(created_env)',
            "responses stream hermes.native.protocol",
        )
        changes.append("responses stream hermes.native.protocol")

    if "def _emit_raw_hermes_event" not in text:
        text, _ = _replace_once(
            text,
            "            async def _dispatch(it) -> None:",
            '            async def _emit_raw_hermes_event(payload: Dict[str, Any]) -> None:\n'
            '                """Forward Hermes-native metadata without forcing OpenAI shape."""\n'
            '                event_type = str(payload.get("type") or payload.get("event") or "hermes.event")\n'
            '                if not event_type.startswith("hermes."):\n'
            '                    event_type = "hermes." + event_type\n'
            '                payload["type"] = event_type\n'
            '                payload.setdefault("session_id", session_id)\n'
            '                await _write_event(event_type, payload)\n'
            "\n"
            "            async def _dispatch(it) -> None:",
            "responses stream raw hermes emitter",
        )
        changes.append("responses stream raw hermes emitter")

    if "def _agent_context_usage" not in text:
        text, _ = _replace_once(
            text,
            '                await _write_event(event_type, payload)\n'
            "\n"
            "            async def _dispatch(it) -> None:",
            '                await _write_event(event_type, payload)\n'
            "\n"
            '            def _agent_context_usage() -> Optional[Dict[str, Any]]:\n'
            "                agent = agent_ref[0] if agent_ref else None\n"
            '                compressor = getattr(agent, "context_compressor", None) if agent is not None else None\n'
            "                if compressor is None:\n"
            "                    return None\n"
            '                context_tokens = int(getattr(compressor, "last_prompt_tokens", 0) or 0)\n'
            '                context_length = int(getattr(compressor, "context_length", 0) or 0)\n'
            "                if context_tokens <= 0 and context_length <= 0:\n"
            "                    return None\n"
            "                payload: Dict[str, Any] = {\n"
            '                    "type": "hermes.context.usage",\n'
            '                    "source": "hermes-cli-status",\n'
            '                    "context_tokens": context_tokens,\n'
            '                    "context_length": context_length,\n'
            '                    "threshold_tokens": int(getattr(compressor, "threshold_tokens", 0) or 0),\n'
            '                    "compressions": int(getattr(compressor, "compression_count", 0) or 0),\n'
            "                }\n"
            "                if context_length > 0:\n"
            '                    payload["context_percent"] = max(0, min(100, round((context_tokens / context_length) * 100)))\n'
            "                return payload\n"
            "\n"
            "            async def _dispatch(it) -> None:",
            "responses stream cli context usage helper",
        )
        changes.append("responses stream cli context usage helper")

    if "context_usage = _agent_context_usage()" not in text:
        text, _ = _replace_once(
            text,
            "                result, agent_usage = await agent_task\n"
            "                usage = agent_usage or usage\n"
            "                # If the agent produced a final_response but no text",
            "                result, agent_usage = await agent_task\n"
            "                usage = agent_usage or usage\n"
            "                context_usage = _agent_context_usage()\n"
            "                if context_usage is not None:\n"
            "                    await _emit_raw_hermes_event(context_usage)\n"
            "                # If the agent produced a final_response but no text",
            "responses stream emit cli context usage",
        )
        changes.append("responses stream emit cli context usage")

    if "chat completions final_response fallback" not in text:
        text, _ = _replace_once(
            text,
            "            async def _emit(item):\n"
            "                \"\"\"Write a single queue item to the SSE stream.\n",
            "            emitted_text = False\n"
            "            # Hermes Hub patch: Chat Completions may receive only a final_response.\n"
            "            # In that case emit it as one delta instead of returning an empty stream.\n"
            "\n"
            "            async def _emit(item):\n"
            "                \"\"\"Write a single queue item to the SSE stream.\n",
            "chat completions emitted_text tracker",
        )
        text, _ = _replace_once(
            text,
            "                    content_chunk = {\n"
            "                        \"id\": completion_id, \"object\": \"chat.completion.chunk\",\n"
            "                        \"created\": created, \"model\": model,\n"
            "                        \"choices\": [{\"index\": 0, \"delta\": {\"content\": item}, \"finish_reason\": None}],\n"
            "                    }\n"
            "                    await response.write(f\"data: {json.dumps(content_chunk)}\\n\\n\".encode())",
            "                    nonlocal emitted_text\n"
            "                    if isinstance(item, str) and item:\n"
            "                        emitted_text = True\n"
            "                    content_chunk = {\n"
            "                        \"id\": completion_id, \"object\": \"chat.completion.chunk\",\n"
            "                        \"created\": created, \"model\": model,\n"
            "                        \"choices\": [{\"index\": 0, \"delta\": {\"content\": item}, \"finish_reason\": None}],\n"
            "                    }\n"
            "                    await response.write(f\"data: {json.dumps(content_chunk)}\\n\\n\".encode())",
            "chat completions mark emitted_text",
        )
        text, _ = _replace_once(
            text,
            "            try:\n"
            "                result, agent_usage = await agent_task\n"
            "                usage = agent_usage or usage\n"
            "            except Exception as exc:",
            "            try:\n"
            "                result, agent_usage = await agent_task\n"
            "                usage = agent_usage or usage\n"
            "                agent_final = result.get(\"final_response\", \"\") if isinstance(result, dict) else \"\"\n"
            "                if agent_final and not emitted_text:\n"
            "                    # chat completions final_response fallback\n"
            "                    last_activity = await _emit(agent_final)\n"
            "            except Exception as exc:",
            "chat completions final_response fallback",
        )
        changes.append("chat completions final_response fallback")

    if 'tag == "__hermes_raw_event__"' not in text:
        text, _ = _replace_once(
            text,
            '                    elif tag == "__tool_completed__":\n'
            '                        await _emit_tool_completed(payload)',
            '                    elif tag == "__tool_completed__":\n'
            '                        await _emit_tool_completed(payload)\n'
            '                    elif tag == "__hermes_raw_event__":\n'
            '                        await _emit_raw_hermes_event(payload)',
            "responses stream raw hermes dispatch",
        )
        changes.append("responses stream raw hermes dispatch")

    old_progress = (
        '            def _on_tool_progress(event_type, name, preview, args, **kwargs):\n'
        '                """Queue non-start tool progress events if needed in future.\n'
        "\n"
        '                The structured Responses stream uses ``tool_start_callback``\n'
        '                and ``tool_complete_callback`` for exact call-id correlation,\n'
        '                so progress events are currently ignored here.\n'
        '                """\n'
        "                return"
    )
    if old_progress in text:
        text = text.replace(
            old_progress,
            '            def _on_tool_progress(event_type, name, preview, args, **kwargs):\n'
            '                """Pass through Hermes-native tool/progress metadata."""\n'
            '                if str(name).startswith("_"):\n'
            "                    return\n"
            "                payload = {\n"
            '                    "type": str(event_type or "hermes.tool.progress"),\n'
            '                    "event": str(event_type or "hermes.tool.progress"),\n'
            '                    "tool": name,\n'
            '                    "label": preview,\n'
            '                    "arguments": args or {},\n'
            "                }\n"
            "                payload.update(kwargs or {})\n"
            '                _stream_q.put(("__hermes_raw_event__", payload))',
            1,
        )
        changes.append("responses tool_progress raw passthrough")
    elif '"""Pass through Hermes-native tool/progress metadata."""' not in text:
        raise RuntimeError("Patch anchor not found: responses tool_progress callback")

    if '"event": "tool.started",' not in text:
        text, _ = _replace_once(
            text,
            '            def _on_tool_start(tool_call_id, function_name, function_args):\n'
            '                """Queue a started tool for live function_call streaming."""\n'
            '                _stream_q.put(("__tool_started__", {',
            '            def _on_tool_start(tool_call_id, function_name, function_args):\n'
            '                """Queue a started tool for live function_call streaming."""\n'
            '                _stream_q.put(("__hermes_raw_event__", {\n'
            '                    "type": "hermes.tool.progress",\n'
            '                    "event": "tool.started",\n'
            '                    "tool": function_name,\n'
            '                    "toolCallId": tool_call_id,\n'
            '                    "status": "running",\n'
            '                    "arguments": function_args or {},\n'
            "                }))\n"
            '                _stream_q.put(("__tool_started__", {',
            "responses tool_start raw passthrough",
        )
        changes.append("responses tool_start raw passthrough")

    if '"event": "tool.completed",' not in text:
        text, _ = _replace_once(
            text,
            '            def _on_tool_complete(tool_call_id, function_name, function_args, function_result):\n'
            '                """Queue a completed tool result for live function_call_output streaming."""\n'
            '                _stream_q.put(("__tool_completed__", {',
            '            def _on_tool_complete(tool_call_id, function_name, function_args, function_result):\n'
            '                """Queue a completed tool result for live function_call_output streaming."""\n'
            '                _stream_q.put(("__hermes_raw_event__", {\n'
            '                    "type": "hermes.tool.progress",\n'
            '                    "event": "tool.completed",\n'
            '                    "tool": function_name,\n'
            '                    "toolCallId": tool_call_id,\n'
            '                    "status": "completed",\n'
            '                    "arguments": function_args or {},\n'
            '                    "result": function_result,\n'
            "                }))\n"
            '                _stream_q.put(("__tool_completed__", {',
            "responses tool_complete raw passthrough",
        )
        changes.append("responses tool_complete raw passthrough")

    if 'add_post("/v1/hermes/native", self._handle_responses)' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+self\._app\.router\.add_post\("/v1/responses", self\._handle_responses\)\n)(^\s+self\._app\.router\.add_get\("/v1/responses/\{response_id\}", self\._handle_get_response\))',
            r'\1'
            r'            self._app.router.add_post("/v1/hermes/native", self._handle_responses)' "\n"
            r'\2',
            "router hermes_native alias",
        )
        changes.append("router hermes_native alias")

    return text, changes


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", help="Path to gateway/platforms/api_server.py")
    parser.add_argument("--check", action="store_true", help="Validate patchability without writing")
    args = parser.parse_args()

    target = _find_target(args.target)
    original = target.read_text(encoding="utf-8")
    patched, changes = _patch_text(original)

    if args.check:
        state = "already patched" if not changes else "patchable"
        print(f"Hermes native gateway patch {state}: {target}")
        if changes:
            for change in changes:
                print(f"- {change}")
        return 0

    if not changes:
        print(f"Hermes native gateway already patched: {target}")
        return 0

    backup = target.with_suffix(target.suffix + f".bak-hermes-native-{int(time.time())}")
    shutil.copy2(target, backup)
    target.write_text(patched, encoding="utf-8", newline="")
    py_compile.compile(str(target), doraise=True)
    print(f"Hermes native gateway patched: {target}")
    print(f"Backup: {backup}")
    for change in changes:
        print(f"- {change}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
