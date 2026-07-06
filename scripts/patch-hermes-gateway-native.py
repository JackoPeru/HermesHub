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

    if "HERMES_GATEWAY_MAX_REQUEST_MB" not in text:
        text, _ = _replace_regex_once(
            text,
            r'^MAX_REQUEST_BYTES\s*=\s*[0-9_]+\s*#.*$',
            '_HERMES_GATEWAY_MAX_REQUEST_MB = int(os.environ.get("HERMES_GATEWAY_MAX_REQUEST_MB", "0"))\nMAX_REQUEST_BYTES = (_HERMES_GATEWAY_MAX_REQUEST_MB if _HERMES_GATEWAY_MAX_REQUEST_MB > 0 else 102400) * 1024 * 1024  # 0 maps to a 100GB practical ceiling for aiohttp',
            "gateway max request bytes env",
        )
        changes.append("gateway max request bytes env")

    if 'MAX_REQUEST_BYTES = int(os.environ.get("HERMES_GATEWAY_MAX_REQUEST_MB", "0")) * 1024 * 1024  # 0 disables gateway body limit' in text:
        text = text.replace(
            'MAX_REQUEST_BYTES = int(os.environ.get("HERMES_GATEWAY_MAX_REQUEST_MB", "0")) * 1024 * 1024  # 0 disables gateway body limit',
            '_HERMES_GATEWAY_MAX_REQUEST_MB = int(os.environ.get("HERMES_GATEWAY_MAX_REQUEST_MB", "0"))\nMAX_REQUEST_BYTES = (_HERMES_GATEWAY_MAX_REQUEST_MB if _HERMES_GATEWAY_MAX_REQUEST_MB > 0 else 102400) * 1024 * 1024  # 0 maps to a 100GB practical ceiling for aiohttp',
        )
        changes.append("gateway zero request limit maps to practical ceiling")

    if "if MAX_REQUEST_BYTES > 0 and int(cl) > MAX_REQUEST_BYTES:" not in text:
        text = text.replace(
            "if int(cl) > MAX_REQUEST_BYTES:",
            "if MAX_REQUEST_BYTES > 0 and int(cl) > MAX_REQUEST_BYTES:",
            1,
        )
        changes.append("gateway body limit disable support")

    if "def _hermes_hub_api_keys" not in text:
        text, _ = _replace_once(
            text,
            "def _multimodal_validation_error(exc: ValueError, *, param: str) -> \"web.Response\":",
            'def _hermes_hub_api_keys(primary: str = "") -> List[str]:\n'
            '    """Accepted bearer keys for Hermes Hub Linux gateway deployments."""\n'
            '    keys: List[str] = []\n'
            '    for value in [\n'
            '        primary,\n'
            '        os.environ.get("API_SERVER_KEY", ""),\n'
            '        os.environ.get("HERMES_API_KEY", ""),\n'
            '        os.environ.get("HERMESAPIKEY", ""),\n'
            '        os.environ.get("HERMES_HUB_API_KEY", ""),\n'
            '        os.environ.get("HERMES_GATEWAY_API_KEY", ""),\n'
            '        "hermes-hub",\n'
            '    ]:\n'
            '        for part in str(value or "").replace(";", ",").split(","):\n'
            '            key = part.strip()\n'
            '            if key and key not in keys:\n'
            '                keys.append(key)\n'
            '    return keys\n'
            "\n"
            "\n"
            "def _multimodal_validation_error(exc: ValueError, *, param: str) -> \"web.Response\":",
            "hermes hub api key aliases",
        )
        changes.append("hermes hub api key aliases")

    if 'os.environ.get("HERMES_HUB_MAX_UPLOAD_MB", "150")' in text:
        text = text.replace(
            'os.environ.get("HERMES_HUB_MAX_UPLOAD_MB", "150")',
            'os.environ.get("HERMES_HUB_MAX_UPLOAD_MB", "0")',
        )
        changes.append("unlimited hub upload default")

    if "if not payload or len(payload) > max_bytes:" in text:
        text = text.replace(
            "if not payload or len(payload) > max_bytes:",
            "if not payload or (max_bytes > 0 and len(payload) > max_bytes):",
            1,
        )
        changes.append("unlimited hub upload guard")

    if "def _hermes_hub_save_upload" not in text:
        text, _ = _replace_once(
            text,
            "def _multimodal_validation_error(exc: ValueError, *, param: str) -> \"web.Response\":",
            '''def _hermes_hub_upload_root() -> "Path":
    from pathlib import Path as _Path

    root = _Path(os.environ.get("HERMES_HUB_UPLOAD_PATH", str(_Path.home() / ".hermes" / "hub_uploads"))).expanduser()
    root.mkdir(parents=True, exist_ok=True)
    return root.resolve()


def _hermes_hub_safe_upload_name(filename: str, mime_type: str) -> str:
    import re as _re

    name = _re.sub(r"[^A-Za-z0-9._-]+", "_", str(filename or "attachment")).strip("._")
    if not name:
        name = "attachment"
    if "." not in name:
        ext = {
            "image/png": ".png",
            "image/jpeg": ".jpg",
            "image/webp": ".webp",
            "image/gif": ".gif",
            "image/bmp": ".bmp",
        }.get(str(mime_type or "").lower(), ".bin")
        name += ext
    return name[:160]


def _hermes_hub_save_upload(filename: str, mime_type: str, data_url: str) -> Dict[str, Any]:
    import base64 as _base64
    import hashlib as _hashlib
    import time as _time
    import urllib.parse as _urlparse

    raw = str(data_url or "")
    if "," not in raw or not raw.startswith("data:"):
        raise ValueError("data_url must be a data: URL")
    meta, encoded = raw.split(",", 1)
    detected_mime = meta[5:].split(";", 1)[0].strip() or str(mime_type or "application/octet-stream")
    mime = str(mime_type or detected_mime or "application/octet-stream")
    payload = _base64.b64decode(encoded, validate=False)
    max_bytes = int(os.environ.get("HERMES_HUB_MAX_UPLOAD_MB", "0")) * 1024 * 1024
    if not payload or (max_bytes > 0 and len(payload) > max_bytes):
        raise ValueError("upload empty or over max size")

    root = _hermes_hub_upload_root()
    safe = _hermes_hub_safe_upload_name(filename, mime)
    digest = _hashlib.sha256(payload).hexdigest()[:16]
    target = root / f"{int(_time.time())}-{digest}-{safe}"
    target.write_bytes(payload)
    rel = target.relative_to(root).as_posix()
    media_id = _urlparse.quote(rel, safe="")
    return {
        "object": "hermes.media.upload",
        "filename": safe,
        "mime_type": mime,
        "size_bytes": len(payload),
        "path": str(target),
        "server_path": str(target),
        "media_id": rel,
        "media_url": f"/v1/media/{media_id}",
        "url": f"/v1/media/{media_id}",
    }


def _multimodal_validation_error(exc: ValueError, *, param: str) -> "web.Response":''',
            "media upload helpers",
        )
        changes.append("media upload helpers")

    if "def _collect_hardware_snapshot" not in text:
        text, _ = _replace_once(
            text,
            "def _multimodal_validation_error(exc: ValueError, *, param: str) -> \"web.Response\":",
            'def _collect_hardware_snapshot() -> Dict[str, Any]:\n'
            '    """Return physical host telemetry for Hermes Hub clients.\n'
            "\n"
            "    Uses psutil when available. Without psutil the endpoint still returns\n"
            "    stable host/spec data and marks live counters as unavailable.\n"
            '    """\n'
            "    import datetime as _datetime\n"
            "    import platform as _platform\n"
            "    import socket as _socket_local\n"
            "\n"
            "    now = time.time()\n"
            "    snapshot: Dict[str, Any] = {\n"
            '        "object": "hermes.hardware.snapshot",\n'
            '        "status": "ok",\n'
            '        "timestamp": now,\n'
            '        "timestamp_iso": _datetime.datetime.fromtimestamp(now, _datetime.timezone.utc).isoformat(),\n'
            '        "sample_interval_seconds": 1,\n'
            '        "host": {\n'
            '            "hostname": _socket_local.gethostname(),\n'
            '            "os": _platform.system(),\n'
            '            "platform": _platform.platform(),\n'
            '            "kernel": _platform.release(),\n'
            '            "architecture": _platform.machine(),\n'
            '            "processor": _platform.processor(),\n'
            "        },\n"
            '        "cpu": {},\n'
            '        "memory": {},\n'
            '        "swap": {},\n'
            '        "disks": [],\n'
            '        "network": {},\n'
            '        "temperatures": [],\n'
            '        "temperature_support": "unavailable",\n'
            '        "gpus": [],\n'
            '        "gpu_support": "unavailable",\n'
            '        "notes": [],\n'
            "    }\n"
            "\n"
            "    try:\n"
            "        import psutil  # type: ignore\n"
            "    except Exception as exc:\n"
            '        snapshot["status"] = "degraded"\n'
            '        snapshot["notes"].append(f"psutil unavailable: {exc}")\n'
            "        return snapshot\n"
            "\n"
            "    try:\n"
            "        boot_time = float(psutil.boot_time())\n"
            '        snapshot["host"]["boot_time"] = boot_time\n'
            '        snapshot["host"]["uptime_seconds"] = max(0, int(now - boot_time))\n'
            "    except Exception:\n"
            "        pass\n"
            "\n"
            "    try:\n"
            "        freq = psutil.cpu_freq()\n"
            "    except Exception:\n"
            "        freq = None\n"
            "    try:\n"
            "        load_avg = list(os.getloadavg()) if hasattr(os, \"getloadavg\") else []\n"
            "    except Exception:\n"
            "        load_avg = []\n"
            '    snapshot["cpu"] = {\n'
            '        "percent": float(psutil.cpu_percent(interval=0.05)),\n'
            '        "per_core_percent": [float(x) for x in psutil.cpu_percent(interval=None, percpu=True)],\n'
            '        "physical_cores": psutil.cpu_count(logical=False) or 0,\n'
            '        "logical_cores": psutil.cpu_count(logical=True) or 0,\n'
            '        "load_average": load_avg,\n'
            '        "current_mhz": float(getattr(freq, "current", 0.0) or 0.0) if freq else None,\n'
            '        "min_mhz": float(getattr(freq, "min", 0.0) or 0.0) if freq else None,\n'
            '        "max_mhz": float(getattr(freq, "max", 0.0) or 0.0) if freq else None,\n'
            "    }\n"
            "\n"
            "    mem = psutil.virtual_memory()\n"
            '    snapshot["memory"] = {\n'
            '        "total_bytes": int(mem.total),\n'
            '        "available_bytes": int(mem.available),\n'
            '        "used_bytes": int(mem.used),\n'
            '        "free_bytes": int(getattr(mem, "free", 0) or 0),\n'
            '        "percent": float(mem.percent),\n'
            "    }\n"
            "    swap = psutil.swap_memory()\n"
            '    snapshot["swap"] = {\n'
            '        "total_bytes": int(swap.total),\n'
            '        "used_bytes": int(swap.used),\n'
            '        "free_bytes": int(swap.free),\n'
            '        "percent": float(swap.percent),\n'
            "    }\n"
            "\n"
            "    disks: List[Dict[str, Any]] = []\n"
            "    for part in psutil.disk_partitions(all=False):\n"
            "        try:\n"
            "            usage = psutil.disk_usage(part.mountpoint)\n"
            "        except Exception:\n"
            "            continue\n"
            "        disks.append({\n"
            '            "device": part.device,\n'
            '            "mountpoint": part.mountpoint,\n'
            '            "fstype": part.fstype,\n'
            '            "total_bytes": int(usage.total),\n'
            '            "used_bytes": int(usage.used),\n'
            '            "free_bytes": int(usage.free),\n'
            '            "percent": float(usage.percent),\n'
            "        })\n"
            '    snapshot["disks"] = disks\n'
            "\n"
            "    net = psutil.net_io_counters()\n"
            '    snapshot["network"] = {\n'
            '        "bytes_sent": int(net.bytes_sent),\n'
            '        "bytes_recv": int(net.bytes_recv),\n'
            '        "packets_sent": int(net.packets_sent),\n'
            '        "packets_recv": int(net.packets_recv),\n'
            "    }\n"
            "\n"
            "    try:\n"
            "        temps = psutil.sensors_temperatures(fahrenheit=False)\n"
            "    except Exception as exc:\n"
            "        temps = {}\n"
            '        snapshot["notes"].append(f"temperature sensors unavailable: {exc}")\n'
            "    flattened: List[Dict[str, Any]] = []\n"
            "    for name, entries in (temps or {}).items():\n"
            "        for entry in entries:\n"
            "            current = getattr(entry, \"current\", None)\n"
            "            if current is None:\n"
            "                continue\n"
            "            try:\n"
            "                current_c = float(current)\n"
            "            except Exception:\n"
            "                continue\n"
            "            if current_c < 0 or current_c > 150:\n"
            "                continue\n"
            "            high = getattr(entry, \"high\", None)\n"
            "            critical = getattr(entry, \"critical\", None)\n"
            "            high_c = None if high is None else float(high)\n"
            "            critical_c = None if critical is None else float(critical)\n"
            "            if high_c is not None and (high_c < 1 or high_c > 150):\n"
            "                high_c = None\n"
            "            if critical_c is not None and (critical_c < 1 or critical_c > 150):\n"
            "                critical_c = None\n"
            "            flattened.append({\n"
            '                "name": name,\n'
            '                "label": getattr(entry, "label", "") or name,\n'
            '                "current_c": current_c,\n'
            '                "high_c": high_c,\n'
            '                "critical_c": critical_c,\n'
            "            })\n"
            '    snapshot["temperatures"] = flattened\n'
            '    snapshot["temperature_support"] = "available" if flattened else "no_sensors_reported"\n'
            "\n"
            "    try:\n"
            "        import csv as _csv\n"
            "        import subprocess as _subprocess\n"
            "        query = \"index,name,utilization.gpu,utilization.memory,memory.used,memory.total,temperature.gpu,power.draw,power.limit,driver_version\"\n"
            "        result = _subprocess.run(\n"
            "            [\"nvidia-smi\", f\"--query-gpu={query}\", \"--format=csv,noheader,nounits\"],\n"
            "            capture_output=True,\n"
            "            text=True,\n"
            "            timeout=2,\n"
            "        )\n"
            "        if result.returncode == 0:\n"
            "            gpu_rows = []\n"
            "            for row in _csv.reader(result.stdout.splitlines()):\n"
            "                if len(row) < 10:\n"
            "                    continue\n"
            "                def _gpu_float(value: Any) -> Optional[float]:\n"
            "                    try:\n"
            "                        raw = str(value).strip()\n"
            "                        if not raw or raw.upper() in {\"N/A\", \"[N/A]\"}:\n"
            "                            return None\n"
            "                        return float(raw)\n"
            "                    except Exception:\n"
            "                        return None\n"
            "                def _gpu_int(value: Any, fallback: int = 0) -> int:\n"
            "                    parsed = _gpu_float(value)\n"
            "                    return fallback if parsed is None else int(parsed)\n"
            "                gpu_rows.append({\n"
            '                    "index": _gpu_int(row[0], len(gpu_rows)),\n'
            '                    "name": str(row[1]).strip() or "GPU",\n'
            '                    "utilization_gpu_percent": _gpu_float(row[2]) or 0.0,\n'
            '                    "utilization_memory_percent": _gpu_float(row[3]) or 0.0,\n'
            '                    "memory_used_mb": _gpu_float(row[4]) or 0.0,\n'
            '                    "memory_total_mb": _gpu_float(row[5]) or 0.0,\n'
            '                    "temperature_c": _gpu_float(row[6]),\n'
            '                    "power_draw_watts": _gpu_float(row[7]),\n'
            '                    "power_limit_watts": _gpu_float(row[8]),\n'
            '                    "driver_version": str(row[9]).strip() or "-",\n'
            "                })\n"
            '            snapshot["gpus"] = gpu_rows\n'
            '            snapshot["gpu_support"] = "available" if gpu_rows else "no_gpus_reported"\n'
            "        else:\n"
            '            snapshot["gpu_support"] = "nvidia_smi_error"\n'
            "    except FileNotFoundError:\n"
            '        snapshot["gpu_support"] = "nvidia_smi_unavailable"\n'
            "    except Exception as exc:\n"
            '        snapshot["gpu_support"] = "error"\n'
            '        snapshot["notes"].append(f"gpu telemetry unavailable: {exc}")\n'
            "\n"
            "    try:\n"
            "        snapshot[\"process_count\"] = len(psutil.pids())\n"
            "    except Exception:\n"
            "        pass\n"
            "\n"
            "    return snapshot\n"
            "\n"
            "\n"
            "def _multimodal_validation_error(exc: ValueError, *, param: str) -> \"web.Response\":",
            "hardware telemetry collector",
        )
        changes.append("hardware telemetry collector")

    if "def _hermes_hub_storage_path" not in text:
        text, _ = _replace_once(
            text,
            "def _multimodal_validation_error(exc: ValueError, *, param: str) -> \"web.Response\":",
            '''def _hermes_hub_storage_path(env_name: str, default_name: str) -> "Path":
    """Return a Hermes Hub JSON store path under HERMES_HOME by default."""
    from pathlib import Path as _Path
    home = _Path(os.environ.get("HERMES_HOME", str(_Path.home() / ".hermes"))).expanduser()
    raw = os.environ.get(env_name) or str(home / default_name)
    return _Path(raw).expanduser()


def _hermes_hub_read_json(path: "Path", default: Dict[str, Any]) -> Dict[str, Any]:
    import json as _json

    try:
        if path.is_file():
            with path.open("r", encoding="utf-8") as handle:
                loaded = _json.load(handle)
            if isinstance(loaded, dict):
                return loaded
    except Exception:
        try:
            logger.exception("Failed to read Hermes Hub store: %s", path)
        except Exception:
            pass
    return dict(default)


def _hermes_hub_write_json(path: "Path", payload: Dict[str, Any]) -> None:
    import json as _json

    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8") as handle:
        _json.dump(payload, handle, ensure_ascii=False, indent=2)
    tmp.replace(path)


def _hermes_hub_memory_payload() -> Dict[str, Any]:
    path = _hermes_hub_storage_path("HERMES_HUB_MEMORY_PATH", "hub_memory.json")
    payload = _hermes_hub_read_json(path, {"categories": {}})
    categories = payload.get("categories")
    if not isinstance(categories, dict):
        categories = {}
    for key in ("video_preferences", "news_preferences", "response_style", "project_rules", "general_notes"):
        categories.setdefault(key, "")
    payload["categories"] = categories
    payload["object"] = "hermes.hub.memory"
    payload["status"] = "ok"
    payload["path"] = str(path)
    payload["description"] = "Preferenze e note persistenti lato Hermes Agent; non RAM del telefono."
    return payload


def _hermes_hub_patch_memory(payload: Dict[str, Any]) -> Dict[str, Any]:
    current = _hermes_hub_memory_payload()
    incoming = payload.get("categories") if isinstance(payload, dict) else None
    if isinstance(incoming, dict):
        categories = current.setdefault("categories", {})
        for key, value in incoming.items():
            categories[str(key)] = "" if value is None else str(value)
    current["updated_at"] = time.time()
    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_MEMORY_PATH", "hub_memory.json"), current)
    return current


def _hermes_hub_state_payload() -> Dict[str, Any]:
    path = _hermes_hub_storage_path("HERMES_HUB_STATE_PATH", "hub_state.json")
    payload = _hermes_hub_read_json(path, {"items": []})
    items = payload.get("items")
    if not isinstance(items, list):
        items = []
    payload["items"] = items
    payload["object"] = "hermes.hub.state"
    payload["status"] = "ok"
    payload["path"] = str(path)
    payload["description"] = "Stato operativo sincronizzato da Hermes Hub: feedback video/news, letture e riferimenti progetto."
    return payload


def _hermes_hub_add_state(payload: Dict[str, Any]) -> Dict[str, Any]:
    current = _hermes_hub_state_payload()
    items = current.setdefault("items", [])
    if not isinstance(items, list):
        items = []
        current["items"] = items
    item = dict(payload or {})
    item.setdefault("id", f"hub_state_{int(time.time() * 1000)}")
    item.setdefault("created_at", time.time())
    items.append(item)
    current["updated_at"] = time.time()
    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_STATE_PATH", "hub_state.json"), current)
    return item


def _hermes_hub_delete_state(state_id: str) -> Dict[str, Any]:
    current = _hermes_hub_state_payload()
    before = len(current.get("items", []))
    current["items"] = [item for item in current.get("items", []) if str(item.get("id", "")) != str(state_id)]
    current["updated_at"] = time.time()
    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_STATE_PATH", "hub_state.json"), current)
    return {"object": "hermes.hub.state.delete", "deleted": before - len(current["items"]), "id": state_id}


def _hermes_hub_conversations_payload() -> Dict[str, Any]:
    path = _hermes_hub_storage_path("HERMES_HUB_CONVERSATIONS_PATH", "hub_conversations.json")
    payload = _hermes_hub_read_json(path, {"items": []})
    items = payload.get("items")
    if not isinstance(items, list):
        items = []
    normalized = []
    seen = set()
    for item in items:
        conv = _hermes_hub_normalize_conversation(item)
        if not conv:
            continue
        cid = str(conv.get("id") or "")
        if cid in seen:
            continue
        seen.add(cid)
        normalized.append(conv)
    normalized.sort(key=lambda x: float(x.get("updatedAt") or x.get("updated_at") or 0), reverse=True)
    payload["items"] = normalized[:500]
    payload["object"] = "hermes.hub.conversations"
    payload["status"] = "ok"
    payload["path"] = str(path)
    payload["description"] = "Archivio chat Hermes Hub condiviso tra Windows, Android e reinstallazioni app."
    return payload


def _hermes_hub_number(value: Any, fallback: float = 0.0) -> float:
    try:
        if value is None:
            return fallback
        return float(value)
    except Exception:
        return fallback


def _hermes_hub_normalize_message(raw: Any) -> Optional[Dict[str, Any]]:
    if not isinstance(raw, dict):
        return None
    text = raw.get("text")
    if text is None:
        text = raw.get("content") or raw.get("message") or ""
    author = str(raw.get("author") or raw.get("role") or ("Tu" if raw.get("fromUser") else "Hermes"))
    mid = str(raw.get("id") or f"msg_{int(time.time() * 1000)}")
    return {
        "id": mid,
        "author": author,
        "text": "" if text is None else str(text),
        "fromUser": bool(raw.get("fromUser", author.lower() in {"tu", "user"})),
        "isAction": bool(raw.get("isAction", False)),
        "thinking": "" if raw.get("thinking") is None else str(raw.get("thinking")),
        "timestamp": raw.get("timestamp") or raw.get("createdAt") or raw.get("created_at") or int(time.time() * 1000),
        "visualBlocksVersion": raw.get("visualBlocksVersion"),
        "visualBlocks": raw.get("visualBlocks") if isinstance(raw.get("visualBlocks"), list) else [],
        "stats": raw.get("stats") if isinstance(raw.get("stats"), dict) else None,
        "rawEvents": raw.get("rawEvents") if isinstance(raw.get("rawEvents"), list) else [],
    }


def _hermes_hub_normalize_conversation(raw: Any) -> Optional[Dict[str, Any]]:
    if not isinstance(raw, dict):
        return None
    cid = str(raw.get("id") or raw.get("conversationId") or raw.get("conversation_id") or "").strip()
    if not cid:
        cid = f"conv_{int(time.time() * 1000)}"
    messages = []
    raw_messages = raw.get("messages")
    if isinstance(raw_messages, list):
        for msg in raw_messages:
            normalized = _hermes_hub_normalize_message(msg)
            if normalized:
                messages.append(normalized)
    title = str(raw.get("title") or raw.get("name") or raw.get("prompt") or "Nuova chat")
    prompt = "" if raw.get("prompt") is None else str(raw.get("prompt"))
    updated = raw.get("updatedAt", raw.get("updated_at", raw.get("modified_at", 0)))
    updated_num = _hermes_hub_number(updated, time.time() * 1000)
    deleted = raw.get("deletedAt", raw.get("deleted_at", None))
    deleted_num = _hermes_hub_number(deleted, 0.0)
    return {
        "id": cid,
        "title": title[:180],
        "kind": str(raw.get("kind") or "Chat"),
        "description": "" if raw.get("description") is None else str(raw.get("description")),
        "prompt": prompt,
        "updatedAt": updated_num,
        "deletedAt": deleted_num if deleted_num > 0 else None,
        "previousResponseId": raw.get("previousResponseId") or raw.get("previous_response_id") or None,
        "serverConversationId": raw.get("serverConversationId") or raw.get("server_conversation_id") or None,
        "messages": [] if deleted_num > 0 else messages,
    }


def _hermes_hub_extract_backup_conversations(payload: Dict[str, Any]) -> List[Dict[str, Any]]:
    candidates: List[Any] = []
    for key in ("conversations", "items"):
        value = payload.get(key)
        if isinstance(value, list):
            candidates.extend(value)
    archive = payload.get("archive")
    if isinstance(archive, dict):
        for key in ("items", "conversations"):
            value = archive.get(key)
            if isinstance(value, list):
                candidates.extend(value)
    settings = payload.get("settings")
    if isinstance(settings, dict):
        for key in ("items", "conversations"):
            raw = settings.get(key)
            if isinstance(raw, str):
                try:
                    import json as _json
                    parsed = _json.loads(raw)
                    if isinstance(parsed, list):
                        candidates.extend(parsed)
                except Exception:
                    pass
    out = []
    for item in candidates:
        normalized = _hermes_hub_normalize_conversation(item)
        if normalized:
            out.append(normalized)
    return out


def _hermes_hub_merge_conversations(incoming: List[Dict[str, Any]]) -> Dict[str, Any]:
    current = _hermes_hub_conversations_payload()
    by_id: Dict[str, Dict[str, Any]] = {
        str(item.get("id")): dict(item)
        for item in current.get("items", [])
        if isinstance(item, dict) and item.get("id")
    }
    changed = 0
    for raw in incoming:
        conv = _hermes_hub_normalize_conversation(raw)
        if not conv:
            continue
        cid = str(conv.get("id"))
        existing = by_id.get(cid)
        if existing is None:
            by_id[cid] = conv
            changed += 1
            continue
        if _hermes_hub_number(conv.get("updatedAt")) >= _hermes_hub_number(existing.get("updatedAt")):
            by_id[cid] = conv
            changed += 1
    active = [item for item in by_id.values() if not item.get("deletedAt")]
    deleted = [item for item in by_id.values() if item.get("deletedAt")]
    items = (
        sorted(active, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)[:200] +
        sorted(deleted, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)[:300]
    )
    items = sorted(items, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)
    payload = {"items": items, "updated_at": time.time()}
    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_CONVERSATIONS_PATH", "hub_conversations.json"), payload)
    payload["object"] = "hermes.hub.conversations"
    payload["status"] = "ok"
    payload["merged"] = changed
    return payload


def _hermes_hub_delete_conversation(conversation_id: str) -> Dict[str, Any]:
    current = _hermes_hub_conversations_payload()
    items = current.get("items", [])
    if not isinstance(items, list):
        items = []
    now_ms = int(time.time() * 1000)
    found = False
    out = []
    for item in items:
        if isinstance(item, dict) and str(item.get("id", "")) == str(conversation_id):
            found = True
            out.append({
                "id": str(conversation_id),
                "title": "Chat eliminata",
                "kind": "Deleted",
                "description": "",
                "prompt": "",
                "updatedAt": now_ms,
                "deletedAt": now_ms,
                "previousResponseId": None,
                "serverConversationId": None,
                "messages": [],
            })
        else:
            out.append(item)
    if not found:
        out.append({
            "id": str(conversation_id),
            "title": "Chat eliminata",
            "kind": "Deleted",
            "description": "",
            "prompt": "",
            "updatedAt": now_ms,
            "deletedAt": now_ms,
            "previousResponseId": None,
            "serverConversationId": None,
            "messages": [],
        })
    current["items"] = sorted(out, key=lambda x: float(x.get("updatedAt") or 0) if isinstance(x, dict) else 0, reverse=True)[:500]
    current["updated_at"] = time.time()
    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_CONVERSATIONS_PATH", "hub_conversations.json"), current)
    return {"object": "hermes.hub.conversations.delete", "deleted": 1 if found else 0, "id": conversation_id, "tombstone": True}


def _hermes_hub_notifications_payload(unread_only: bool = False) -> Dict[str, Any]:
    path = _hermes_hub_storage_path("HERMES_HUB_NOTIFICATIONS_PATH", "hub_notifications.json")
    payload = _hermes_hub_read_json(path, {"items": []})
    items = payload.get("items")
    if not isinstance(items, list):
        items = []
    normalized = []
    for item in items:
        if not isinstance(item, dict):
            continue
        item.setdefault("id", f"hub_notice_{int(time.time() * 1000)}")
        item.setdefault("title", "Hermes")
        item.setdefault("message", "")
        item.setdefault("created_at", time.time())
        item.setdefault("read_at", None)
        if unread_only and item.get("read_at"):
            continue
        normalized.append(item)
    normalized.sort(key=lambda x: float(x.get("created_at") or 0), reverse=True)
    payload["items"] = normalized
    payload["object"] = "hermes.hub.notifications"
    payload["status"] = "ok"
    payload["path"] = str(path)
    payload["description"] = "Inbox notifiche Hermes Hub: messaggi autonomi da cron/agente verso app Windows/Android."
    return payload


def _hermes_hub_add_notification(payload: Dict[str, Any]) -> Dict[str, Any]:
    current = _hermes_hub_notifications_payload(False)
    path = _hermes_hub_storage_path("HERMES_HUB_NOTIFICATIONS_PATH", "hub_notifications.json")
    items = current.setdefault("items", [])
    if not isinstance(items, list):
        items = []
        current["items"] = items
    title = str((payload or {}).get("title") or (payload or {}).get("subject") or "Hermes")
    message = str((payload or {}).get("message") or (payload or {}).get("body") or (payload or {}).get("text") or "")
    item = {
        "id": str((payload or {}).get("id") or f"hub_notice_{int(time.time() * 1000)}"),
        "title": title[:180],
        "message": message[:8000],
        "kind": str((payload or {}).get("kind") or "agent_message"),
        "severity": str((payload or {}).get("severity") or "info"),
        "source": str((payload or {}).get("source") or "hermes-agent"),
        "conversation_prompt": str((payload or {}).get("conversation_prompt") or message[:4000]),
        "payload": (payload or {}).get("payload") if isinstance((payload or {}).get("payload"), dict) else {},
        "created_at": float((payload or {}).get("created_at") or time.time()),
        "read_at": None,
    }
    items.append(item)
    items.sort(key=lambda x: float(x.get("created_at") or 0), reverse=True)
    del items[500:]
    current["updated_at"] = time.time()
    _hermes_hub_write_json(path, current)
    return item


def _hermes_hub_patch_notification(notification_id: str, payload: Dict[str, Any]) -> Dict[str, Any]:
    current = _hermes_hub_notifications_payload(False)
    path = _hermes_hub_storage_path("HERMES_HUB_NOTIFICATIONS_PATH", "hub_notifications.json")
    now = time.time()
    updated = None
    for item in current.get("items", []):
        if str(item.get("id", "")) != str(notification_id):
            continue
        if bool((payload or {}).get("read", True)):
            item["read_at"] = now
        if "archived" in (payload or {}):
            item["archived"] = bool(payload.get("archived"))
        updated = item
        break
    current["updated_at"] = now
    _hermes_hub_write_json(path, current)
    return updated or {"id": notification_id, "missing": True}


def _hermes_hub_video_library_payload(request: Optional["web.Request"] = None) -> Dict[str, Any]:
    import mimetypes as _mimetypes
    import urllib.parse as _urlparse
    from pathlib import Path as _Path

    raw = os.environ.get("HERMES_VIDEO_LIBRARY_PATH")
    if not raw:
        raw = "/home/matteo/video"
    root = _Path(raw).expanduser()
    extensions = {".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi", ".wmv", ".flv", ".mpg", ".mpeg", ".ts", ".m2ts", ".3gp", ".ogv"}
    items: List[Dict[str, Any]] = []
    if root.is_dir():
        for path in sorted(root.rglob("*"), key=lambda p: p.stat().st_mtime if p.exists() else 0, reverse=True):
            if not path.is_file() or path.suffix.lower() not in extensions:
                continue
            try:
                stat = path.stat()
                rel = path.relative_to(root).as_posix()
            except Exception:
                continue
            media_id = _urlparse.quote(rel, safe="")
            items.append({
                "id": rel,
                "title": path.stem,
                "filename": path.name,
                "path": str(path),
                "media_url": f"/v1/media/{media_id}",
                "playback_url": f"/v1/media/{media_id}",
                "compat_url": f"/v1/media/{media_id}?format=mp4",
                "thumbnail_url": "",
                "mime_type": _mimetypes.guess_type(path.name)[0] or "video/*",
                "size_bytes": int(stat.st_size),
                "modified_at": float(stat.st_mtime),
                "duration_ms": 0,
            })
    media_roots = [
        str(_Path(part).expanduser())
        for part in os.environ.get("HERMES_MEDIA_ROOTS", "").split(os.pathsep)
        if part.strip()
    ]
    if str(root) not in media_roots:
        media_roots.append(str(root))
    return {
        "object": "hermes.video.library",
        "status": "ok",
        "configured": bool(raw),
        "video_library_path": str(root),
        "library_path": str(root),
        "media_roots": media_roots,
        "items": items,
        "count": len(items),
        "description": "Feed Video Hermes Hub: file video presenti nella cartella monitorata dal server.",
    }


def _hermes_hub_media_roots() -> List["Path"]:
    from pathlib import Path as _Path

    roots: List[_Path] = []
    raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or "/home/matteo/video"
    raw_upload = os.environ.get("HERMES_HUB_UPLOAD_PATH") or str(_Path.home() / ".hermes" / "hub_uploads")
    raw_news = os.environ.get("HERMES_NEWS_LIBRARY_PATH") or "/home/matteo/news"
    for part in os.environ.get("HERMES_MEDIA_ROOTS", "").split(os.pathsep):
        if part.strip():
            roots.append(_Path(part.strip()).expanduser())
    roots.append(_Path(raw_upload).expanduser())
    roots.append(_Path(raw_video).expanduser())
    roots.append(_Path(raw_news).expanduser())
    unique: List[_Path] = []
    seen = set()
    for root in roots:
        try:
            resolved = root.resolve()
        except Exception:
            resolved = root
        key = str(resolved)
        if key not in seen:
            seen.add(key)
            unique.append(resolved)
    return unique


def _hermes_hub_resolve_media_path(media_id: str, extra_root: Optional[str] = None) -> Optional["Path"]:
    import urllib.parse as _urlparse
    from pathlib import Path as _Path

    decoded = _urlparse.unquote(str(media_id or "")).replace(chr(92), "/").lstrip("/")
    if not decoded:
        return None
    roots = _hermes_hub_media_roots()
    if extra_root:
        roots.insert(0, _Path(str(extra_root).strip()).expanduser())
    for root in roots:
        try:
            candidate = (root / decoded).resolve()
            root_resolved = root.resolve()
            if root_resolved == candidate or root_resolved in candidate.parents:
                if candidate.is_file():
                    return candidate
        except Exception:
            continue

    # Backward-compatible fallback: older clients may send just basename.
    basename = _Path(decoded).name
    if basename and basename == decoded:
        for root in roots:
            try:
                for candidate in root.rglob(basename):
                    if candidate.is_file():
                        return candidate.resolve()
            except Exception:
                continue
    return None


def _hermes_hub_media_cache_path(source: "Path") -> "Path":
    import hashlib as _hashlib
    from pathlib import Path as _Path

    stat = source.stat()
    home = _Path(os.environ.get("HERMES_HOME", str(_Path.home() / ".hermes"))).expanduser()
    digest = _hashlib.sha256(f"{source.resolve()}:{stat.st_size}:{stat.st_mtime_ns}:compatv2".encode("utf-8")).hexdigest()[:24]
    cache_dir = _Path(os.environ.get("HERMES_HUB_MEDIA_CACHE", str(home / "hub_media_cache"))).expanduser()
    cache_dir.mkdir(parents=True, exist_ok=True)
    return cache_dir / f"{source.stem}.{digest}.compat.mp4"


def _hermes_hub_transcode_mp4(source: "Path") -> "Path":
    import subprocess as _subprocess

    target = _hermes_hub_media_cache_path(source)
    if target.is_file() and target.stat().st_size > 0:
        return target
    tmp = target.with_suffix(target.suffix + ".tmp")
    probe = _subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=index", "-of", "csv=p=0", str(source)],
        capture_output=True,
        text=True,
        timeout=30,
    )
    has_audio = probe.returncode == 0 and bool((probe.stdout or "").strip())
    cmd = [
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        str(source),
    ]
    if not has_audio:
        cmd += ["-f", "lavfi", "-i", "anullsrc=channel_layout=mono:sample_rate=44100"]
    cmd += [
        "-map",
        "0:v:0",
        "-map",
        "0:a:0" if has_audio else "1:a:0",
        "-c:v",
        "libx264",
        "-profile:v",
        "main",
        "-level:v",
        os.environ.get("HERMES_HUB_TRANSCODE_H264_LEVEL", "4.2"),
        "-preset",
        os.environ.get("HERMES_HUB_TRANSCODE_PRESET", "veryfast"),
        "-pix_fmt",
        "yuv420p",
        "-tag:v",
        "avc1",
        "-movflags",
        "+faststart",
        "-c:a",
        "aac",
        "-b:a",
        os.environ.get("HERMES_HUB_TRANSCODE_AUDIO_BITRATE", "160k"),
        "-f",
        "mp4",
        str(tmp),
    ]
    if not has_audio:
        cmd.insert(len(cmd) - 3, "-shortest")
    result = _subprocess.run(cmd, capture_output=True, text=True, timeout=int(os.environ.get("HERMES_HUB_TRANSCODE_TIMEOUT", "900")))
    if result.returncode != 0:
        try:
            tmp.unlink(missing_ok=True)
        except Exception:
            pass
        raise RuntimeError((result.stderr or result.stdout or "ffmpeg transcode failed").strip())
    tmp.replace(target)
    return target


def _multimodal_validation_error(exc: ValueError, *, param: str) -> "web.Response":''',
            "hub support endpoint helpers",
        )
        changes.append("hub support endpoint helpers")

    if "def _hermes_hub_resolve_media_path" not in text:
        text, _ = _replace_once(
            text,
            "def _multimodal_validation_error(exc: ValueError, *, param: str) -> \"web.Response\":",
            '''def _hermes_hub_media_roots() -> List["Path"]:
    from pathlib import Path as _Path

    roots: List[_Path] = []
    raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or "/home/matteo/video"
    raw_upload = os.environ.get("HERMES_HUB_UPLOAD_PATH") or str(_Path.home() / ".hermes" / "hub_uploads")
    raw_news = os.environ.get("HERMES_NEWS_LIBRARY_PATH") or "/home/matteo/news"
    for part in os.environ.get("HERMES_MEDIA_ROOTS", "").split(os.pathsep):
        if part.strip():
            roots.append(_Path(part.strip()).expanduser())
    roots.append(_Path(raw_upload).expanduser())
    roots.append(_Path(raw_video).expanduser())
    roots.append(_Path(raw_news).expanduser())
    unique: List[_Path] = []
    seen = set()
    for root in roots:
        try:
            resolved = root.resolve()
        except Exception:
            resolved = root
        key = str(resolved)
        if key not in seen:
            seen.add(key)
            unique.append(resolved)
    return unique


def _hermes_hub_resolve_media_path(media_id: str, extra_root: Optional[str] = None) -> Optional["Path"]:
    import urllib.parse as _urlparse
    from pathlib import Path as _Path

    decoded = _urlparse.unquote(str(media_id or "")).replace(chr(92), "/").lstrip("/")
    if not decoded:
        return None
    roots = _hermes_hub_media_roots()
    if extra_root:
        roots.insert(0, _Path(str(extra_root).strip()).expanduser())
    for root in roots:
        try:
            candidate = (root / decoded).resolve()
            root_resolved = root.resolve()
            if root_resolved == candidate or root_resolved in candidate.parents:
                if candidate.is_file():
                    return candidate
        except Exception:
            continue

    basename = _Path(decoded).name
    if basename and basename == decoded:
        for root in roots:
            try:
                for candidate in root.rglob(basename):
                    if candidate.is_file():
                        return candidate.resolve()
            except Exception:
                continue
    return None


def _hermes_hub_media_cache_path(source: "Path") -> "Path":
    import hashlib as _hashlib
    from pathlib import Path as _Path

    stat = source.stat()
    home = _Path(os.environ.get("HERMES_HOME", str(_Path.home() / ".hermes"))).expanduser()
    digest = _hashlib.sha256(f"{source.resolve()}:{stat.st_size}:{stat.st_mtime_ns}:compatv2".encode("utf-8")).hexdigest()[:24]
    cache_dir = _Path(os.environ.get("HERMES_HUB_MEDIA_CACHE", str(home / "hub_media_cache"))).expanduser()
    cache_dir.mkdir(parents=True, exist_ok=True)
    return cache_dir / f"{source.stem}.{digest}.compat.mp4"


def _hermes_hub_transcode_mp4(source: "Path") -> "Path":
    import subprocess as _subprocess

    target = _hermes_hub_media_cache_path(source)
    if target.is_file() and target.stat().st_size > 0:
        return target
    tmp = target.with_suffix(target.suffix + ".tmp")
    probe = _subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=index", "-of", "csv=p=0", str(source)],
        capture_output=True,
        text=True,
        timeout=30,
    )
    has_audio = probe.returncode == 0 and bool((probe.stdout or "").strip())
    cmd = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(source),
    ]
    if not has_audio:
        cmd += ["-f", "lavfi", "-i", "anullsrc=channel_layout=mono:sample_rate=44100"]
    cmd += [
        "-map", "0:v:0", "-map", "0:a:0" if has_audio else "1:a:0",
        "-c:v", "libx264",
        "-profile:v", "main",
        "-level:v", os.environ.get("HERMES_HUB_TRANSCODE_H264_LEVEL", "4.2"),
        "-preset", os.environ.get("HERMES_HUB_TRANSCODE_PRESET", "veryfast"),
        "-pix_fmt", "yuv420p",
        "-tag:v", "avc1",
        "-movflags", "+faststart",
        "-c:a", "aac",
        "-b:a", os.environ.get("HERMES_HUB_TRANSCODE_AUDIO_BITRATE", "160k"),
        "-f", "mp4",
        str(tmp),
    ]
    if not has_audio:
        cmd.insert(len(cmd) - 3, "-shortest")
    result = _subprocess.run(cmd, capture_output=True, text=True, timeout=int(os.environ.get("HERMES_HUB_TRANSCODE_TIMEOUT", "900")))
    if result.returncode != 0:
        try:
            tmp.unlink(missing_ok=True)
        except Exception:
            pass
        raise RuntimeError((result.stderr or result.stdout or "ffmpeg transcode failed").strip())
    tmp.replace(target)
    return target


def _multimodal_validation_error(exc: ValueError, *, param: str) -> "web.Response":''',
            "media proxy helpers",
        )
        changes.append("media proxy helpers")

    if "def _hermes_hub_conversations_payload" not in text and "def _hermes_hub_notifications_payload" in text:
        text, _ = _replace_once(
            text,
            "def _hermes_hub_notifications_payload(unread_only: bool = False) -> Dict[str, Any]:",
            '''def _hermes_hub_conversations_payload() -> Dict[str, Any]:
    path = _hermes_hub_storage_path("HERMES_HUB_CONVERSATIONS_PATH", "hub_conversations.json")
    payload = _hermes_hub_read_json(path, {"items": []})
    items = payload.get("items")
    if not isinstance(items, list):
        items = []
    normalized = []
    seen = set()
    for item in items:
        conv = _hermes_hub_normalize_conversation(item)
        if not conv:
            continue
        cid = str(conv.get("id") or "")
        if cid in seen:
            continue
        seen.add(cid)
        normalized.append(conv)
    normalized.sort(key=lambda x: float(x.get("updatedAt") or x.get("updated_at") or 0), reverse=True)
    payload["items"] = normalized[:500]
    payload["object"] = "hermes.hub.conversations"
    payload["status"] = "ok"
    payload["path"] = str(path)
    payload["description"] = "Archivio chat Hermes Hub condiviso tra Windows, Android e reinstallazioni app."
    return payload


def _hermes_hub_number(value: Any, fallback: float = 0.0) -> float:
    try:
        if value is None:
            return fallback
        return float(value)
    except Exception:
        return fallback


def _hermes_hub_normalize_message(raw: Any) -> Optional[Dict[str, Any]]:
    if not isinstance(raw, dict):
        return None
    text = raw.get("text")
    if text is None:
        text = raw.get("content") or raw.get("message") or ""
    author = str(raw.get("author") or raw.get("role") or ("Tu" if raw.get("fromUser") else "Hermes"))
    mid = str(raw.get("id") or f"msg_{int(time.time() * 1000)}")
    return {
        "id": mid,
        "author": author,
        "text": "" if text is None else str(text),
        "fromUser": bool(raw.get("fromUser", author.lower() in {"tu", "user"})),
        "isAction": bool(raw.get("isAction", False)),
        "thinking": "" if raw.get("thinking") is None else str(raw.get("thinking")),
        "timestamp": raw.get("timestamp") or raw.get("createdAt") or raw.get("created_at") or int(time.time() * 1000),
        "visualBlocksVersion": raw.get("visualBlocksVersion"),
        "visualBlocks": raw.get("visualBlocks") if isinstance(raw.get("visualBlocks"), list) else [],
        "stats": raw.get("stats") if isinstance(raw.get("stats"), dict) else None,
        "rawEvents": raw.get("rawEvents") if isinstance(raw.get("rawEvents"), list) else [],
    }


def _hermes_hub_normalize_conversation(raw: Any) -> Optional[Dict[str, Any]]:
    if not isinstance(raw, dict):
        return None
    cid = str(raw.get("id") or raw.get("conversationId") or raw.get("conversation_id") or "").strip()
    if not cid:
        cid = f"conv_{int(time.time() * 1000)}"
    messages = []
    raw_messages = raw.get("messages")
    if isinstance(raw_messages, list):
        for msg in raw_messages:
            normalized = _hermes_hub_normalize_message(msg)
            if normalized:
                messages.append(normalized)
    title = str(raw.get("title") or raw.get("name") or raw.get("prompt") or "Nuova chat")
    prompt = "" if raw.get("prompt") is None else str(raw.get("prompt"))
    updated = raw.get("updatedAt", raw.get("updated_at", raw.get("modified_at", 0)))
    updated_num = _hermes_hub_number(updated, time.time() * 1000)
    deleted = raw.get("deletedAt", raw.get("deleted_at", None))
    deleted_num = _hermes_hub_number(deleted, 0.0)
    return {
        "id": cid,
        "title": title[:180],
        "kind": str(raw.get("kind") or "Chat"),
        "description": "" if raw.get("description") is None else str(raw.get("description")),
        "prompt": prompt,
        "updatedAt": updated_num,
        "deletedAt": deleted_num if deleted_num > 0 else None,
        "previousResponseId": raw.get("previousResponseId") or raw.get("previous_response_id") or None,
        "serverConversationId": raw.get("serverConversationId") or raw.get("server_conversation_id") or None,
        "messages": [] if deleted_num > 0 else messages,
    }


def _hermes_hub_extract_backup_conversations(payload: Dict[str, Any]) -> List[Dict[str, Any]]:
    candidates: List[Any] = []
    for key in ("conversations", "items"):
        value = payload.get(key)
        if isinstance(value, list):
            candidates.extend(value)
    archive = payload.get("archive")
    if isinstance(archive, dict):
        for key in ("items", "conversations"):
            value = archive.get(key)
            if isinstance(value, list):
                candidates.extend(value)
            elif isinstance(value, str):
                try:
                    import json as _json
                    parsed = _json.loads(value)
                    if isinstance(parsed, list):
                        candidates.extend(parsed)
                except Exception:
                    pass
    out = []
    for item in candidates:
        normalized = _hermes_hub_normalize_conversation(item)
        if normalized:
            out.append(normalized)
    return out


def _hermes_hub_merge_conversations(incoming: List[Dict[str, Any]]) -> Dict[str, Any]:
    current = _hermes_hub_conversations_payload()
    by_id: Dict[str, Dict[str, Any]] = {
        str(item.get("id")): dict(item)
        for item in current.get("items", [])
        if isinstance(item, dict) and item.get("id")
    }
    changed = 0
    for raw in incoming:
        conv = _hermes_hub_normalize_conversation(raw)
        if not conv:
            continue
        cid = str(conv.get("id"))
        existing = by_id.get(cid)
        if existing is None:
            by_id[cid] = conv
            changed += 1
            continue
        if _hermes_hub_number(conv.get("updatedAt")) >= _hermes_hub_number(existing.get("updatedAt")):
            by_id[cid] = conv
            changed += 1
    active = [item for item in by_id.values() if not item.get("deletedAt")]
    deleted = [item for item in by_id.values() if item.get("deletedAt")]
    items = (
        sorted(active, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)[:200] +
        sorted(deleted, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)[:300]
    )
    items = sorted(items, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)
    payload = {"items": items, "updated_at": time.time()}
    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_CONVERSATIONS_PATH", "hub_conversations.json"), payload)
    payload["object"] = "hermes.hub.conversations"
    payload["status"] = "ok"
    payload["merged"] = changed
    return payload


def _hermes_hub_delete_conversation(conversation_id: str) -> Dict[str, Any]:
    current = _hermes_hub_conversations_payload()
    items = current.get("items", [])
    if not isinstance(items, list):
        items = []
    now_ms = int(time.time() * 1000)
    found = False
    out = []
    for item in items:
        if isinstance(item, dict) and str(item.get("id", "")) == str(conversation_id):
            found = True
            out.append({
                "id": str(conversation_id),
                "title": "Chat eliminata",
                "kind": "Deleted",
                "description": "",
                "prompt": "",
                "updatedAt": now_ms,
                "deletedAt": now_ms,
                "previousResponseId": None,
                "serverConversationId": None,
                "messages": [],
            })
        else:
            out.append(item)
    if not found:
        out.append({
            "id": str(conversation_id),
            "title": "Chat eliminata",
            "kind": "Deleted",
            "description": "",
            "prompt": "",
            "updatedAt": now_ms,
            "deletedAt": now_ms,
            "previousResponseId": None,
            "serverConversationId": None,
            "messages": [],
        })
    current["items"] = sorted(out, key=lambda x: float(x.get("updatedAt") or 0) if isinstance(x, dict) else 0, reverse=True)[:500]
    current["updated_at"] = time.time()
    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_CONVERSATIONS_PATH", "hub_conversations.json"), current)
    return {"object": "hermes.hub.conversations.delete", "deleted": 1 if found else 0, "id": conversation_id, "tombstone": True}


def _hermes_hub_notifications_payload(unread_only: bool = False) -> Dict[str, Any]:''',
            "hub conversations storage helpers",
        )
        changes.append("hub conversations storage helpers")

    if 'def _hermes_hub_normalize_conversation' in text and '"deletedAt": deleted_num if deleted_num > 0 else None' not in text:
        text, _ = _replace_regex_once(
            text,
            r'def _hermes_hub_normalize_conversation\(raw: Any\) -> Optional\[Dict\[str, Any\]\]:[\s\S]*?(?=\n\ndef _hermes_hub_extract_backup_conversations)',
            '''def _hermes_hub_normalize_conversation(raw: Any) -> Optional[Dict[str, Any]]:
    if not isinstance(raw, dict):
        return None
    cid = str(raw.get("id") or raw.get("conversationId") or raw.get("conversation_id") or "").strip()
    if not cid:
        cid = f"conv_{int(time.time() * 1000)}"
    messages = []
    raw_messages = raw.get("messages")
    if isinstance(raw_messages, list):
        for msg in raw_messages:
            normalized = _hermes_hub_normalize_message(msg)
            if normalized:
                messages.append(normalized)
    title = str(raw.get("title") or raw.get("name") or raw.get("prompt") or "Nuova chat")
    prompt = "" if raw.get("prompt") is None else str(raw.get("prompt"))
    updated = raw.get("updatedAt", raw.get("updated_at", raw.get("modified_at", 0)))
    updated_num = _hermes_hub_number(updated, time.time() * 1000)
    deleted = raw.get("deletedAt", raw.get("deleted_at", None))
    deleted_num = _hermes_hub_number(deleted, 0.0)
    return {
        "id": cid,
        "title": title[:180],
        "kind": str(raw.get("kind") or "Chat"),
        "description": "" if raw.get("description") is None else str(raw.get("description")),
        "prompt": prompt,
        "updatedAt": updated_num,
        "deletedAt": deleted_num if deleted_num > 0 else None,
        "previousResponseId": raw.get("previousResponseId") or raw.get("previous_response_id") or None,
        "serverConversationId": raw.get("serverConversationId") or raw.get("server_conversation_id") or None,
        "messages": [] if deleted_num > 0 else messages,
    }
''',
            "hub conversations tombstone normalize upgrade",
        )
        changes.append("hub conversations tombstone normalize upgrade")

    if 'def _hermes_hub_merge_conversations' in text and 'active = [item for item in by_id.values() if not item.get("deletedAt")]' not in text:
        text, _ = _replace_regex_once(
            text,
            r'def _hermes_hub_merge_conversations\(incoming: List\[Dict\[str, Any\]\]\) -> Dict\[str, Any\]:[\s\S]*?(?=\n\ndef _hermes_hub_delete_conversation)',
            '''def _hermes_hub_merge_conversations(incoming: List[Dict[str, Any]]) -> Dict[str, Any]:
    current = _hermes_hub_conversations_payload()
    by_id: Dict[str, Dict[str, Any]] = {
        str(item.get("id")): dict(item)
        for item in current.get("items", [])
        if isinstance(item, dict) and item.get("id")
    }
    changed = 0
    for raw in incoming:
        conv = _hermes_hub_normalize_conversation(raw)
        if not conv:
            continue
        cid = str(conv.get("id"))
        existing = by_id.get(cid)
        if existing is None:
            by_id[cid] = conv
            changed += 1
            continue
        if _hermes_hub_number(conv.get("updatedAt")) >= _hermes_hub_number(existing.get("updatedAt")):
            by_id[cid] = conv
            changed += 1
    active = [item for item in by_id.values() if not item.get("deletedAt")]
    deleted = [item for item in by_id.values() if item.get("deletedAt")]
    items = (
        sorted(active, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)[:200] +
        sorted(deleted, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)[:300]
    )
    items = sorted(items, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)
    payload = {"items": items, "updated_at": time.time()}
    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_CONVERSATIONS_PATH", "hub_conversations.json"), payload)
    payload["object"] = "hermes.hub.conversations"
    payload["status"] = "ok"
    payload["merged"] = changed
    return payload
''',
            "hub conversations tombstone merge upgrade",
        )
        changes.append("hub conversations tombstone merge upgrade")

    if 'def _hermes_hub_delete_conversation' in text and '"tombstone": True' not in text:
        text, _ = _replace_regex_once(
            text,
            r'def _hermes_hub_delete_conversation\(conversation_id: str\) -> Dict\[str, Any\]:[\s\S]*?(?=\n\ndef _hermes_hub_notifications_payload)',
            '''def _hermes_hub_delete_conversation(conversation_id: str) -> Dict[str, Any]:
    current = _hermes_hub_conversations_payload()
    items = current.get("items", [])
    if not isinstance(items, list):
        items = []
    now_ms = int(time.time() * 1000)
    found = False
    out = []
    for item in items:
        if isinstance(item, dict) and str(item.get("id", "")) == str(conversation_id):
            found = True
            out.append({
                "id": str(conversation_id),
                "title": "Chat eliminata",
                "kind": "Deleted",
                "description": "",
                "prompt": "",
                "updatedAt": now_ms,
                "deletedAt": now_ms,
                "previousResponseId": None,
                "serverConversationId": None,
                "messages": [],
            })
        else:
            out.append(item)
    if not found:
        out.append({
            "id": str(conversation_id),
            "title": "Chat eliminata",
            "kind": "Deleted",
            "description": "",
            "prompt": "",
            "updatedAt": now_ms,
            "deletedAt": now_ms,
            "previousResponseId": None,
            "serverConversationId": None,
            "messages": [],
        })
    current["items"] = sorted(out, key=lambda x: float(x.get("updatedAt") or 0) if isinstance(x, dict) else 0, reverse=True)[:500]
    current["updated_at"] = time.time()
    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_CONVERSATIONS_PATH", "hub_conversations.json"), current)
    return {"object": "hermes.hub.conversations.delete", "deleted": 1 if found else 0, "id": conversation_id, "tombstone": True}
''',
            "hub conversations tombstone delete upgrade",
        )
        changes.append("hub conversations tombstone delete upgrade")

    if 'def _hermes_hub_resolve_media_path(media_id: str) -> Optional["Path"]:' in text:
        text = text.replace(
            'def _hermes_hub_resolve_media_path(media_id: str) -> Optional["Path"]:',
            'def _hermes_hub_resolve_media_path(media_id: str, extra_root: Optional[str] = None) -> Optional["Path"]:',
            1,
        )
        text = text.replace(
            '    if not decoded:\n'
            '        return None\n'
            '    for root in _hermes_hub_media_roots():\n',
            '    if not decoded:\n'
            '        return None\n'
            '    roots = _hermes_hub_media_roots()\n'
            '    if extra_root:\n'
            '        roots.insert(0, _Path(str(extra_root).strip()).expanduser())\n'
            '    for root in roots:\n',
            1,
        )
        text = text.replace(
            '        for root in _hermes_hub_media_roots():\n'
            '            try:\n'
            '                for candidate in root.rglob(basename):\n',
            '        for root in roots:\n'
            '            try:\n'
            '                for candidate in root.rglob(basename):\n',
            1,
        )
        changes.append("media proxy optional root")

    if 'path = _hermes_hub_resolve_media_path(media_id)' in text:
        text = text.replace(
            'path = _hermes_hub_resolve_media_path(media_id)',
            'path = _hermes_hub_resolve_media_path(media_id, request.query.get("root"))',
            1,
        )
        changes.append("media proxy root query")

    if '"playback_url": f"/v1/media/{media_id}?format=mp4",' in text and '"compat_url": f"/v1/media/{media_id}?format=mp4",' not in text:
        text = text.replace(
            '                "playback_url": f"/v1/media/{media_id}?format=mp4",\n',
            '                "playback_url": f"/v1/media/{media_id}",\n'
            '                "compat_url": f"/v1/media/{media_id}?format=mp4",\n',
            1,
        )
        changes.append("video library fast playback url")

    if '"playback_url": f"/v1/media/{media_id}",' not in text and '"media_url": f"/v1/media/{media_id}",' in text:
        text, _ = _replace_once(
            text,
            '                "media_url": f"/v1/media/{media_id}",\n',
            '                "media_url": f"/v1/media/{media_id}",\n'
            '                "playback_url": f"/v1/media/{media_id}",\n'
            '                "compat_url": f"/v1/media/{media_id}?format=mp4",\n',
            "video library playback url",
        )
        changes.append("video library playback url")

    if '{".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi"}' in text:
        text = text.replace(
            '{".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi"}',
            '{".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi", ".wmv", ".flv", ".mpg", ".mpeg", ".ts", ".m2ts", ".3gp", ".ogv"}',
            1,
        )
        changes.append("video library broad extensions")

    if "def _hermes_hub_news_library_payload" not in text:
        text, _ = _replace_once(
            text,
            "\n\ndef _hermes_hub_media_roots() -> List[\"Path\"]:",
            '''

def _hermes_hub_news_library_payload(request: Optional["web.Request"] = None) -> Dict[str, Any]:
    import mimetypes as _mimetypes
    import urllib.parse as _urlparse
    from pathlib import Path as _Path

    query_path = ""
    if request is not None:
        query_path = (request.query.get("path") or request.query.get("library_path") or "").strip()
    raw = query_path or os.environ.get("HERMES_NEWS_LIBRARY_PATH") or "/home/matteo/news"
    root_query = f"?root={_urlparse.quote(str(_Path(raw).expanduser()), safe='')}" if query_path else ""
    roots: List[_Path] = [_Path(raw).expanduser()]

    unique_roots: List[_Path] = []
    seen_roots = set()
    for root in roots:
        try:
            resolved = root.resolve()
        except Exception:
            resolved = root
        key = str(resolved)
        if key not in seen_roots:
            seen_roots.add(key)
            unique_roots.append(resolved)

    extensions = {".html", ".htm"}
    seen_files = set()
    items: List[Dict[str, Any]] = []
    for root in unique_roots:
        if not root.is_dir():
            continue
        try:
            candidates = sorted(root.rglob("*"), key=lambda p: p.stat().st_mtime if p.exists() else 0, reverse=True)
        except Exception:
            continue
        for path in candidates:
            if not path.is_file() or path.suffix.lower() not in extensions:
                continue
            try:
                stat = path.stat()
                resolved_file = str(path.resolve())
                if resolved_file in seen_files:
                    continue
                seen_files.add(resolved_file)
                rel = path.relative_to(root).as_posix()
            except Exception:
                continue
            media_id = _urlparse.quote(rel, safe="")
            items.append({
                "id": rel,
                "title": path.stem.replace("_", " ").replace("-", " ").strip() or path.name,
                "filename": path.name,
                "path": str(path),
                "media_url": f"/v1/media/{media_id}{root_query}",
                "url": f"/v1/media/{media_id}{root_query}",
                "mime_type": _mimetypes.guess_type(path.name)[0] or "text/html",
                "size_bytes": int(stat.st_size),
                "modified_at": float(stat.st_mtime),
            })

    return {
        "object": "hermes.news.library",
        "status": "ok",
        "configured": bool(raw),
        "news_library_path": str(_Path(raw).expanduser()),
        "library_path": str(_Path(raw).expanduser()),
        "media_roots": [str(root) for root in unique_roots],
        "items": items,
        "count": len(items),
        "description": "Feed News Hermes Hub: file HTML presenti solo nella cartella news monitorata dal server.",
    }


def _hermes_hub_media_roots() -> List["Path"]:''',
            "news library payload helper",
        )
        changes.append("news library payload helper")

    if "def _hermes_hub_news_library_payload" in text and "query_path = \"\"" not in text:
        text = text.replace(
            '    raw = os.environ.get("HERMES_NEWS_LIBRARY_PATH") or "/home/matteo/news"\n',
            '    query_path = ""\n'
            '    if request is not None:\n'
            '        query_path = (request.query.get("path") or request.query.get("library_path") or "").strip()\n'
            '    raw = query_path or os.environ.get("HERMES_NEWS_LIBRARY_PATH") or "/home/matteo/news"\n'
            '    root_query = f"?root={_urlparse.quote(str(_Path(raw).expanduser()), safe=\'\')}" if query_path else ""\n',
            1,
        )
        text = text.replace(
            '                "media_url": f"/v1/media/{media_id}",\n'
            '                "url": f"/v1/media/{media_id}",\n'
            '                "mime_type": _mimetypes.guess_type(path.name)[0] or "text/html",\n',
            '                "media_url": f"/v1/media/{media_id}{root_query}",\n'
            '                "url": f"/v1/media/{media_id}{root_query}",\n'
            '                "mime_type": _mimetypes.guess_type(path.name)[0] or "text/html",\n',
            1,
        )
        changes.append("news library custom path query")

    if "def _hermes_hub_news_library_payload" in text:
        text = text.replace(
            '    roots: List[_Path] = [_Path(raw).expanduser()]\n'
            '    for part in os.environ.get("HERMES_MEDIA_ROOTS", "").split(os.pathsep):\n'
            '        if part.strip():\n'
            '            roots.append(_Path(part.strip()).expanduser())\n',
            '    roots: List[_Path] = [_Path(raw).expanduser()]\n',
            1,
        )
        text = text.replace(
            '"description": "Feed News Hermes Hub: file HTML presenti nella cartella news/media monitorata dal server.",',
            '"description": "Feed News Hermes Hub: file HTML presenti solo nella cartella news monitorata dal server.",',
            1,
        )
        changes.append("news library folder only")

    if 'raw_news = os.environ.get("HERMES_NEWS_LIBRARY_PATH") or "/home/matteo/news"' not in text and 'raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or "/home/matteo/video"' in text:
        text = text.replace(
            '    raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or "/home/matteo/video"\n'
            '    for part in os.environ.get("HERMES_MEDIA_ROOTS", "").split(os.pathsep):\n',
            '    raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or "/home/matteo/video"\n'
            '    raw_news = os.environ.get("HERMES_NEWS_LIBRARY_PATH") or "/home/matteo/news"\n'
            '    for part in os.environ.get("HERMES_MEDIA_ROOTS", "").split(os.pathsep):\n',
            1,
        )
        text = text.replace(
            '    roots.append(_Path(raw_video).expanduser())\n'
            '    unique: List[_Path] = []\n',
            '    roots.append(_Path(raw_video).expanduser())\n'
            '    roots.append(_Path(raw_news).expanduser())\n'
            '    unique: List[_Path] = []\n',
            1,
        )
        changes.append("media roots include news library")

    media_roots_match = re.search(
        r'def _hermes_hub_media_roots\(\) -> List\["Path"\]:(?P<body>.*?)(?=\n\ndef _hermes_hub_resolve_media_path)',
        text,
        re.S,
    )
    if media_roots_match:
        media_roots_body = media_roots_match.group("body")
        if (
            'roots.append(_Path(raw_news).expanduser())' in media_roots_body
            and 'raw_news = os.environ.get("HERMES_NEWS_LIBRARY_PATH") or "/home/matteo/news"' not in media_roots_body
            and 'raw_upload = os.environ.get("HERMES_HUB_UPLOAD_PATH")' in media_roots_body
        ):
            start, end = media_roots_match.span("body")
            fixed_body = media_roots_body.replace(
                '    raw_upload = os.environ.get("HERMES_HUB_UPLOAD_PATH") or str(_Path.home() / ".hermes" / "hub_uploads")\n',
                '    raw_upload = os.environ.get("HERMES_HUB_UPLOAD_PATH") or str(_Path.home() / ".hermes" / "hub_uploads")\n'
                '    raw_news = os.environ.get("HERMES_NEWS_LIBRARY_PATH") or "/home/matteo/news"\n',
                1,
            )
            if fixed_body != media_roots_body:
                text = text[:start] + fixed_body + text[end:]
                changes.append("media roots raw_news repair")

    if 'raw_upload = os.environ.get("HERMES_HUB_UPLOAD_PATH")' not in text and 'raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or "/home/matteo/video"' in text:
        text = text.replace(
            '    raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or "/home/matteo/video"\n'
            '    for part in os.environ.get("HERMES_MEDIA_ROOTS", "").split(os.pathsep):\n',
            '    raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or "/home/matteo/video"\n'
            '    raw_upload = os.environ.get("HERMES_HUB_UPLOAD_PATH") or str(_Path.home() / ".hermes" / "hub_uploads")\n'
            '    for part in os.environ.get("HERMES_MEDIA_ROOTS", "").split(os.pathsep):\n',
            1,
        )
        text = text.replace(
            '    roots.append(_Path(raw_video).expanduser())\n'
            '    unique: List[_Path] = []\n',
            '    roots.append(_Path(raw_upload).expanduser())\n'
            '    roots.append(_Path(raw_video).expanduser())\n'
            '    unique: List[_Path] = []\n',
            1,
        )
        changes.append("media roots include upload library")

    if '"-f",\n        "mp4",' not in text and '"-b:a",\n        os.environ.get("HERMES_HUB_TRANSCODE_AUDIO_BITRATE", "160k"),\n        str(tmp),' in text:
        text = text.replace(
            '"-b:a",\n        os.environ.get("HERMES_HUB_TRANSCODE_AUDIO_BITRATE", "160k"),\n        str(tmp),',
            '"-b:a",\n        os.environ.get("HERMES_HUB_TRANSCODE_AUDIO_BITRATE", "160k"),\n        "-f",\n        "mp4",\n        str(tmp),',
            1,
        )
        changes.append("media transcode explicit mp4 muxer")

    if '"-f", "mp4",' not in text and '"-b:a", os.environ.get("HERMES_HUB_TRANSCODE_AUDIO_BITRATE", "160k"),\n        str(tmp),' in text:
        text = text.replace(
            '"-b:a", os.environ.get("HERMES_HUB_TRANSCODE_AUDIO_BITRATE", "160k"),\n        str(tmp),',
            '"-b:a", os.environ.get("HERMES_HUB_TRANSCODE_AUDIO_BITRATE", "160k"),\n        "-f", "mp4",\n        str(tmp),',
            1,
        )
        changes.append("media transcode explicit mp4 muxer")

    if ":compatv2" not in text and "def _hermes_hub_transcode_mp4" in text:
        text, replaced = _replace_regex_once(
            text,
            r'(?s)def _hermes_hub_media_cache_path\(source: "Path"\) -> "Path":\n.*?\n\n\ndef _hermes_hub_transcode_mp4\(source: "Path"\) -> "Path":\n.*?\n(?=\n\ndef _multimodal_validation_error)',
            '''def _hermes_hub_media_cache_path(source: "Path") -> "Path":
    import hashlib as _hashlib
    from pathlib import Path as _Path

    stat = source.stat()
    home = _Path(os.environ.get("HERMES_HOME", str(_Path.home() / ".hermes"))).expanduser()
    digest = _hashlib.sha256(f"{source.resolve()}:{stat.st_size}:{stat.st_mtime_ns}:compatv2".encode("utf-8")).hexdigest()[:24]
    cache_dir = _Path(os.environ.get("HERMES_HUB_MEDIA_CACHE", str(home / "hub_media_cache"))).expanduser()
    cache_dir.mkdir(parents=True, exist_ok=True)
    return cache_dir / f"{source.stem}.{digest}.compat.mp4"


def _hermes_hub_transcode_mp4(source: "Path") -> "Path":
    import subprocess as _subprocess

    target = _hermes_hub_media_cache_path(source)
    if target.is_file() and target.stat().st_size > 0:
        return target
    tmp = target.with_suffix(target.suffix + ".tmp")
    probe = _subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=index", "-of", "csv=p=0", str(source)],
        capture_output=True,
        text=True,
        timeout=30,
    )
    has_audio = probe.returncode == 0 and bool((probe.stdout or "").strip())
    cmd = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(source),
    ]
    if not has_audio:
        cmd += ["-f", "lavfi", "-i", "anullsrc=channel_layout=mono:sample_rate=44100"]
    cmd += [
        "-map", "0:v:0", "-map", "0:a:0" if has_audio else "1:a:0",
        "-c:v", "libx264",
        "-profile:v", "main",
        "-level:v", os.environ.get("HERMES_HUB_TRANSCODE_H264_LEVEL", "4.2"),
        "-preset", os.environ.get("HERMES_HUB_TRANSCODE_PRESET", "veryfast"),
        "-pix_fmt", "yuv420p",
        "-tag:v", "avc1",
        "-movflags", "+faststart",
        "-c:a", "aac",
        "-b:a", os.environ.get("HERMES_HUB_TRANSCODE_AUDIO_BITRATE", "160k"),
        "-f", "mp4",
        str(tmp),
    ]
    if not has_audio:
        cmd.insert(len(cmd) - 3, "-shortest")
    result = _subprocess.run(cmd, capture_output=True, text=True, timeout=int(os.environ.get("HERMES_HUB_TRANSCODE_TIMEOUT", "900")))
    if result.returncode != 0:
        try:
            tmp.unlink(missing_ok=True)
        except Exception:
            pass
        raise RuntimeError((result.stderr or result.stdout or "ffmpeg transcode failed").strip())
    tmp.replace(target)
    return target''',
            "media transcode browser-compatible mp4",
        )
        if replaced:
            changes.append("media transcode browser-compatible mp4")

    if '"current_c": current_c,' not in text and '"current_c": float(current),' in text:
        text, _ = _replace_once(
            text,
            '            current = getattr(entry, "current", None)\n'
            '            if current is None:\n'
            '                continue\n'
            '            flattened.append({\n'
            '                "name": name,\n'
            '                "label": getattr(entry, "label", "") or name,\n'
            '                "current_c": float(current),\n'
            '                "high_c": None if getattr(entry, "high", None) is None else float(entry.high),\n'
            '                "critical_c": None if getattr(entry, "critical", None) is None else float(entry.critical),\n'
            '            })\n',
            '            current = getattr(entry, "current", None)\n'
            '            if current is None:\n'
            '                continue\n'
            '            try:\n'
            '                current_c = float(current)\n'
            '            except Exception:\n'
            '                continue\n'
            '            if current_c < 0 or current_c > 150:\n'
            '                continue\n'
            '            high = getattr(entry, "high", None)\n'
            '            critical = getattr(entry, "critical", None)\n'
            '            high_c = None if high is None else float(high)\n'
            '            critical_c = None if critical is None else float(critical)\n'
            '            if high_c is not None and (high_c < 1 or high_c > 150):\n'
            '                high_c = None\n'
            '            if critical_c is not None and (critical_c < 1 or critical_c > 150):\n'
            '                critical_c = None\n'
            '            flattened.append({\n'
            '                "name": name,\n'
            '                "label": getattr(entry, "label", "") or name,\n'
            '                "current_c": current_c,\n'
            '                "high_c": high_c,\n'
            '                "critical_c": critical_c,\n'
            '            })\n',
            "hardware temperature sanity filter",
        )
        changes.append("hardware temperature sanity filter")

    if '"gpus": []' not in text and '"temperature_support": "unavailable",' in text:
        text, _ = _replace_once(
            text,
            '        "temperatures": [],\n'
            '        "temperature_support": "unavailable",\n'
            '        "notes": [],\n',
            '        "temperatures": [],\n'
            '        "temperature_support": "unavailable",\n'
            '        "gpus": [],\n'
            '        "gpu_support": "unavailable",\n'
            '        "notes": [],\n',
            "hardware gpu telemetry fields",
        )
        changes.append("hardware gpu telemetry fields")

    if '"gpu_support"] = "available" if gpu_rows else "no_gpus_reported"' not in text and 'snapshot["temperature_support"] = "available" if flattened else "no_sensors_reported"' in text:
        text, _ = _replace_once(
            text,
            '    snapshot["temperatures"] = flattened\n'
            '    snapshot["temperature_support"] = "available" if flattened else "no_sensors_reported"\n'
            '\n'
            '    try:\n'
            '        snapshot["process_count"] = len(psutil.pids())\n',
            '    snapshot["temperatures"] = flattened\n'
            '    snapshot["temperature_support"] = "available" if flattened else "no_sensors_reported"\n'
            '\n'
            '    try:\n'
            '        import csv as _csv\n'
            '        import subprocess as _subprocess\n'
            '        query = "index,name,utilization.gpu,utilization.memory,memory.used,memory.total,temperature.gpu,power.draw,power.limit,driver_version"\n'
            '        result = _subprocess.run(\n'
            '            ["nvidia-smi", f"--query-gpu={query}", "--format=csv,noheader,nounits"],\n'
            '            capture_output=True,\n'
            '            text=True,\n'
            '            timeout=2,\n'
            '        )\n'
            '        if result.returncode == 0:\n'
            '            gpu_rows = []\n'
            '            for row in _csv.reader(result.stdout.splitlines()):\n'
            '                if len(row) < 10:\n'
            '                    continue\n'
            '                def _gpu_float(value: Any) -> Optional[float]:\n'
            '                    try:\n'
            '                        raw = str(value).strip()\n'
            '                        if not raw or raw.upper() in {"N/A", "[N/A]"}:\n'
            '                            return None\n'
            '                        return float(raw)\n'
            '                    except Exception:\n'
            '                        return None\n'
            '                def _gpu_int(value: Any, fallback: int = 0) -> int:\n'
            '                    parsed = _gpu_float(value)\n'
            '                    return fallback if parsed is None else int(parsed)\n'
            '                gpu_rows.append({\n'
            '                    "index": _gpu_int(row[0], len(gpu_rows)),\n'
            '                    "name": str(row[1]).strip() or "GPU",\n'
            '                    "utilization_gpu_percent": _gpu_float(row[2]) or 0.0,\n'
            '                    "utilization_memory_percent": _gpu_float(row[3]) or 0.0,\n'
            '                    "memory_used_mb": _gpu_float(row[4]) or 0.0,\n'
            '                    "memory_total_mb": _gpu_float(row[5]) or 0.0,\n'
            '                    "temperature_c": _gpu_float(row[6]),\n'
            '                    "power_draw_watts": _gpu_float(row[7]),\n'
            '                    "power_limit_watts": _gpu_float(row[8]),\n'
            '                    "driver_version": str(row[9]).strip() or "-",\n'
            '                })\n'
            '            snapshot["gpus"] = gpu_rows\n'
            '            snapshot["gpu_support"] = "available" if gpu_rows else "no_gpus_reported"\n'
            '        else:\n'
            '            snapshot["gpu_support"] = "nvidia_smi_error"\n'
            '    except FileNotFoundError:\n'
            '        snapshot["gpu_support"] = "nvidia_smi_unavailable"\n'
            '    except Exception as exc:\n'
            '        snapshot["gpu_support"] = "error"\n'
            '        snapshot["notes"].append(f"gpu telemetry unavailable: {exc}")\n'
            '\n'
            '    try:\n'
            '        snapshot["process_count"] = len(psutil.pids())\n',
            "hardware gpu telemetry collector",
        )
        changes.append("hardware gpu telemetry collector")

    if "def _is_hermes_hub_request" not in text:
        text, _ = _replace_once(
            text,
            "def _multimodal_validation_error(exc: ValueError, *, param: str) -> \"web.Response\":",
            'def _is_hermes_hub_request(request: "web.Request", body: Optional[Dict[str, Any]] = None) -> bool:\n'
            '    """Detect Hermes Hub clients so the gateway can stay prompt-transparent."""\n'
            '    user_agent = str(request.headers.get("User-Agent", "")).lower()\n'
            '    if "hermeshub-" in user_agent or "hermes hub" in user_agent:\n'
            "        return True\n"
            "    if isinstance(body, dict):\n"
            '        metadata = body.get("metadata")\n'
            "        if isinstance(metadata, dict):\n"
            '            surface = str(metadata.get("client") or metadata.get("client_surface") or metadata.get("source") or "").lower()\n'
            '            if "hermes" in surface and "hub" in surface:\n'
            "                return True\n"
            '            if str(metadata.get("hub_client") or "").lower() in {"true", "1", "yes"}:\n'
            "                return True\n"
            "    return False\n"
            "\n"
            "\n"
            "def _multimodal_validation_error(exc: ValueError, *, param: str) -> \"web.Response\":",
            "hermes hub transparent request detector",
        )
        changes.append("hermes hub transparent request detector")

    if "accepted_api_keys = _hermes_hub_api_keys(self._api_key)" not in text:
        text, _ = _replace_once(
            text,
            '        if not self._api_key:\n'
            '            return None\n'
            '\n'
            '        auth_header = request.headers.get("Authorization", "")\n'
            '        if auth_header.startswith("Bearer "):\n'
            '            token = auth_header[7:].strip()\n'
            '            if hmac.compare_digest(token, self._api_key):\n'
            '                return None  # Auth OK\n',
            '        accepted_api_keys = _hermes_hub_api_keys(self._api_key)\n'
            '        if not accepted_api_keys:\n'
            '            return None\n'
            '\n'
            '        auth_header = request.headers.get("Authorization", "")\n'
            '        if auth_header.startswith("Bearer "):\n'
            '            token = auth_header[7:].strip()\n'
            '            if any(hmac.compare_digest(token, api_key) for api_key in accepted_api_keys):\n'
            '                return None  # Auth OK\n',
            "auth accept hermes hub key aliases",
        )
        changes.append("auth accept hermes hub key aliases")

    if "if not _hermes_hub_api_keys(self._api_key):" not in text:
        startup_anchor = (
            '            if not self._api_key:\n'
            '                logger.error(\n'
            '                    "[%s] Refusing to start: API_SERVER_KEY is required for the API server, "'
        )
        if startup_anchor in text:
            text, _ = _replace_once(
                text,
                startup_anchor,
                '            if not _hermes_hub_api_keys(self._api_key):\n'
                '                logger.error(\n'
                '                    "[%s] Refusing to start: API_SERVER_KEY is required for the API server, "',
                "startup auth accepts hermes hub key aliases",
            )
            changes.append("startup auth accepts hermes hub key aliases")

    if 'system_prompt = None if _is_hermes_hub_request(request, body) else (body.get("system_message") or body.get("instructions"))' not in text:
        text = text.replace(
            'system_prompt = body.get("system_message") or body.get("instructions")',
            'system_prompt = None if _is_hermes_hub_request(request, body) else (body.get("system_message") or body.get("instructions"))',
        )
        changes.append("session chat ignore hermes hub system prompts")

    if "allow_client_system_prompt = not _is_hermes_hub_request(request, body)" not in text:
        text, _ = _replace_once(
            text,
            '        # Extract system message (becomes ephemeral system prompt layered ON TOP of core)\n'
            '        system_prompt = None\n'
            '        conversation_messages: List[Dict[str, str]] = []',
            '        # Extract system message (becomes ephemeral system prompt layered ON TOP of core)\n'
            '        system_prompt = None\n'
            '        allow_client_system_prompt = not _is_hermes_hub_request(request, body)\n'
            '        conversation_messages: List[Dict[str, str]] = []',
            "chat completions hermes hub system gate",
        )
        text, _ = _replace_once(
            text,
            '            if role == "system":\n'
            '                # System messages don\'t support images',
            '            if role == "system":\n'
            '                if not allow_client_system_prompt:\n'
            '                    continue\n'
            '                # System messages don\'t support images',
            "chat completions ignore hermes hub system prompts",
        )
        changes.append("chat completions ignore hermes hub system prompts")

    if 'instructions = None if _is_hermes_hub_request(request, body) else body.get("instructions")' not in text:
        text = text.replace(
            'instructions = body.get("instructions")',
            'instructions = None if _is_hermes_hub_request(request, body) else body.get("instructions")',
            1,
        )
        changes.append("responses ignore hermes hub instructions")

    if '"native_protocol": {' not in text:
        native_protocol_block = (
            r'\1'
            r'            "native_protocol": {' "\n"
            r'                "name": "hermes-native",' "\n"
            r'                "transport": "responses",' "\n"
            r'                "endpoint": "/v1/responses",' "\n"
            r'                "alias": "/v1/hermes/native",' "\n"
            r'                "context_owner": "hermes-agent",' "\n"
            r'                "raw_event_passthrough": True,' "\n"
            r'            },' "\n"
            r'\2'
        )
        patched, count = re.subn(
            r'(^\s+"version": _hermes_version\(\),\n)(^\s+"gateway_state": (?:runtime\.get\("gateway_state"\)|gw_state),)',
            native_protocol_block,
            text,
            count=1,
            flags=re.MULTILINE,
        )
        if count == 1:
            text = patched
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

    if '"hardware_monitoring": True,' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+"features": \{\n)',
            r'\1'
            r'                "hardware_monitoring": True,' "\n",
            "capabilities hardware_monitoring",
        )
        changes.append("capabilities hardware_monitoring")

    if '"video_library": True,' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+"features": \{\n)',
            r'\1'
            r'                "video_library": True,' "\n"
            r'                "news_library": True,' "\n"
            r'                "media_proxy": True,' "\n"
            r'                "media_transcode_mp4": True,' "\n"
            r'                "hub_memory": True,' "\n"
            r'                "hub_state": True,' "\n"
            r'                "hub_notifications": True,' "\n",
            "capabilities hub support features",
        )
        changes.append("capabilities hub support features")
    else:
        if '"media_proxy": True,' not in text:
            text, _ = _replace_regex_once(
                text,
                r'(^\s+"features": \{\n)',
                r'\1'
                r'                "media_proxy": True,' "\n"
                r'                "media_transcode_mp4": True,' "\n",
                "capabilities media proxy",
            )
            changes.append("capabilities media proxy")

        if '"news_library": True,' not in text:
            text, _ = _replace_regex_once(
                text,
                r'(^\s+"features": \{\n)',
                r'\1'
                r'                "news_library": True,' "\n",
                "capabilities news library",
            )
            changes.append("capabilities news library")

        if '"hub_notifications": True,' not in text:
            text, _ = _replace_regex_once(
                text,
                r'(^\s+"features": \{\n)',
                r'\1'
                r'                "hub_notifications": True,' "\n",
                "capabilities hub notifications",
            )
            changes.append("capabilities hub notifications")

    if '"max_upload_mb": int(os.environ.get("HERMES_HUB_MAX_UPLOAD_MB", "0")),' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+"features": \{\n)',
            r'\1'
            r'                "max_upload_mb": int(os.environ.get("HERMES_HUB_MAX_UPLOAD_MB", "0")),' "\n",
            "capabilities max upload mb",
        )
        changes.append("capabilities max upload mb")

    if '"max_upload_mb": int(os.environ.get("HERMES_HUB_MAX_UPLOAD_MB", "150")),' in text:
        text = text.replace(
            '"max_upload_mb": int(os.environ.get("HERMES_HUB_MAX_UPLOAD_MB", "150")),',
            '"max_upload_mb": int(os.environ.get("HERMES_HUB_MAX_UPLOAD_MB", "0")),',
            1,
        )
        changes.append("capabilities unlimited upload default")

    if "_RUN_STREAM_TTL = 21600" not in text:
        patched = text.replace("_RUN_STREAM_TTL = 300", "_RUN_STREAM_TTL = 21600", 1)
        if patched != text:
            text = patched
            changes.append("runs stream ttl 6h")

    if "task = self._active_run_tasks.get(run_id)" not in text:
        text, _ = _replace_once(
            text,
            '            for run_id in stale:\n'
            '                logger.debug("[api_server] sweeping orphaned run %s", run_id)\n'
            '                try:\n',
            '            for run_id in stale:\n'
            '                task = self._active_run_tasks.get(run_id)\n'
            '                if task is not None and not task.done():\n'
            '                    self._run_streams_created[run_id] = now\n'
            '                    continue\n'
            '                logger.debug("[api_server] sweeping orphaned run %s", run_id)\n'
            '                try:\n',
            "runs sweep keeps active tasks",
        )
        changes.append("runs sweep keeps active tasks")

    if '"hardware": {"method": "GET", "path": "/v1/hub/hardware"}' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+"health_detailed": \{"method": "GET", "path": "/health/detailed"\},\n)',
            r'\1'
            r'                "hardware": {"method": "GET", "path": "/v1/hub/hardware"},' "\n",
            "capabilities hardware endpoint",
        )
        changes.append("capabilities hardware endpoint")

    if '"video_library": {"method": "GET", "path": "/v1/video/library"}' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+"health_detailed": \{"method": "GET", "path": "/health/detailed"\},\n)',
            r'\1'
            r'                "video_library": {"method": "GET", "path": "/v1/video/library"},' "\n"
            r'                "news_library": {"method": "GET", "path": "/v1/news/library"},' "\n"
            r'                "media_proxy": {"method": "GET", "path": "/v1/media/{media_id}"},' "\n"
            r'                "hub_memory": {"method": "GET/PATCH", "path": "/v1/hub/memory"},' "\n"
            r'                "hub_state": {"method": "GET/POST", "path": "/v1/hub/state"},' "\n"
            r'                "hub_conversations": {"method": "GET/PUT/POST/DELETE", "path": "/v1/hub/conversations"},' "\n"
            r'                "hub_notifications": {"method": "GET/POST/PATCH", "path": "/v1/hub/notifications"},' "\n"
            r'                "audio_transcriptions": {"method": "POST", "path": "/v1/audio/transcriptions"},' "\n",
            "capabilities hub support endpoints",
        )
        changes.append("capabilities hub support endpoints")
    else:
        if '"media_proxy": {"method": "GET", "path": "/v1/media/{media_id}"}' not in text:
            text, _ = _replace_regex_once(
                text,
                r'(^\s+"video_library": \{"method": "GET", "path": "/v1/video/library"\},\n)',
                r'\1'
                r'                "media_proxy": {"method": "GET", "path": "/v1/media/{media_id}"},' "\n",
                "capabilities media proxy endpoint",
            )
            changes.append("capabilities media proxy endpoint")

        if '"news_library": {"method": "GET", "path": "/v1/news/library"}' not in text:
            text, _ = _replace_regex_once(
                text,
                r'(^\s+"video_library": \{"method": "GET", "path": "/v1/video/library"\},\n)',
                r'\1'
                r'                "news_library": {"method": "GET", "path": "/v1/news/library"},' "\n",
                "capabilities news library endpoint",
            )
            changes.append("capabilities news library endpoint")

        if '"hub_notifications": {"method": "GET/POST/PATCH", "path": "/v1/hub/notifications"}' not in text:
            text, _ = _replace_regex_once(
                text,
                r'(^\s+"hub_state": \{"method": "GET/POST", "path": "/v1/hub/state"\},\n)',
                r'\1'
                r'                "hub_notifications": {"method": "GET/POST/PATCH", "path": "/v1/hub/notifications"},' "\n",
                "capabilities hub notifications endpoint",
            )
            changes.append("capabilities hub notifications endpoint")

        if '"hub_conversations": {"method": "GET/PUT/POST/DELETE", "path": "/v1/hub/conversations"}' not in text:
            text, _ = _replace_regex_once(
                text,
                r'(^\s+"hub_state": \{"method": "GET/POST", "path": "/v1/hub/state"\},\n)',
                r'\1'
                r'                "hub_conversations": {"method": "GET/PUT/POST/DELETE", "path": "/v1/hub/conversations"},' "\n",
                "capabilities hub conversations endpoint",
            )
            changes.append("capabilities hub conversations endpoint")

        if '"audio_transcriptions": {"method": "POST", "path": "/v1/audio/transcriptions"}' not in text:
            text, _ = _replace_regex_once(
                text,
                r'(^\s+"hub_notifications": \{"method": "GET/POST/PATCH", "path": "/v1/hub/notifications"\},\n)',
                r'\1'
                r'                "audio_transcriptions": {"method": "POST", "path": "/v1/audio/transcriptions"},' "\n",
                "capabilities audio transcriptions endpoint",
            )
            changes.append("capabilities audio transcriptions endpoint")

    if "async def _handle_audio_transcriptions" not in text:
        text, _ = _replace_once(
            text,
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            "    async def _handle_audio_transcriptions(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        reader = await request.multipart()\n"
            "        field = await reader.next()\n"
            "        audio_data = None\n"
            "        while field is not None:\n"
            "            if field.name == \"file\":\n"
            "                audio_data = await field.read()\n"
            "            field = await reader.next()\n"
            "        if not audio_data:\n"
            "            return web.json_response({\"error\": \"No audio file provided\"}, status=400)\n"
            "\n"
            "        import tempfile\n"
            "        import os\n"
            "        with tempfile.NamedTemporaryFile(delete=False, suffix=\".m4a\") as tmp:\n"
            "            tmp.write(audio_data)\n"
            "            tmp_path = tmp.name\n"
            "        try:\n"
            "            from faster_whisper import WhisperModel\n"
            "            global _hermes_hub_whisper_model\n"
            "            if \"_hermes_hub_whisper_model\" not in globals():\n"
            "                _hermes_hub_whisper_model = WhisperModel(\"large-v3-turbo\", device=\"cuda\", compute_type=\"int8\", device_index=[1])\n"
            "            segments, info = _hermes_hub_whisper_model.transcribe(tmp_path, beam_size=5, language=\"it\")\n"
            "            result_text = \"\".join(segment.text for segment in segments)\n"
            "            return web.json_response({\"text\": result_text.strip()})\n"
            "        except Exception as e:\n"
            "            return web.json_response({\"error\": str(e)}, status=500)\n"
            "        finally:\n"
            "            if os.path.exists(tmp_path):\n"
            "                os.remove(tmp_path)\n"
            "\n"
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            "audio transcriptions endpoint handler",
        )
        changes.append("audio transcriptions endpoint handler")

    if "async def _handle_hub_hardware" not in text:
        text, _ = _replace_once(
            text,
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            "    async def _handle_hub_hardware(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        return web.json_response(_collect_hardware_snapshot())\n"
            "\n"
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            "hardware endpoint handler",
        )
        changes.append("hardware endpoint handler")

    if "async def _handle_video_library" not in text:
        text, _ = _replace_once(
            text,
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            "    async def _handle_video_library(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        return web.json_response(_hermes_hub_video_library_payload(request))\n"
            "\n"
            "    async def _handle_hub_memory(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        return web.json_response(_hermes_hub_memory_payload())\n"
            "\n"
            "    async def _handle_patch_hub_memory(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        try:\n"
            "            body = await request.json()\n"
            "        except Exception:\n"
            "            body = {}\n"
            "        return web.json_response(_hermes_hub_patch_memory(body if isinstance(body, dict) else {}))\n"
            "\n"
            "    async def _handle_get_hub_state(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        return web.json_response(_hermes_hub_state_payload())\n"
            "\n"
            "    async def _handle_post_hub_state(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        try:\n"
            "            body = await request.json()\n"
            "        except Exception:\n"
            "            body = {}\n"
            "        return web.json_response(_hermes_hub_add_state(body if isinstance(body, dict) else {}))\n"
            "\n"
            "    async def _handle_delete_hub_state(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        return web.json_response(_hermes_hub_delete_state(request.match_info.get(\"state_id\", \"\")))\n"
            "\n"
            "    async def _handle_get_hub_conversations(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        return web.json_response(_hermes_hub_conversations_payload())\n"
            "\n"
            "    async def _handle_put_hub_conversation(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        try:\n"
            "            body = await request.json()\n"
            "        except Exception:\n"
            "            body = {}\n"
            "        if not isinstance(body, dict):\n"
            "            body = {}\n"
            "        body[\"id\"] = request.match_info.get(\"conversation_id\", body.get(\"id\", \"\"))\n"
            "        return web.json_response(_hermes_hub_merge_conversations([body]))\n"
            "\n"
            "    async def _handle_post_hub_conversations_import(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        try:\n"
            "            body = await request.json()\n"
            "        except Exception:\n"
            "            body = {}\n"
            "        if not isinstance(body, dict):\n"
            "            body = {}\n"
            "        incoming = body.get(\"items\") if isinstance(body.get(\"items\"), list) else None\n"
            "        if incoming is None:\n"
            "            incoming = _hermes_hub_extract_backup_conversations(body)\n"
            "        merged = _hermes_hub_merge_conversations(incoming if isinstance(incoming, list) else [])\n"
            "        return web.json_response(merged)\n"
            "\n"
            "    async def _handle_delete_hub_conversation(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        return web.json_response(_hermes_hub_delete_conversation(request.match_info.get(\"conversation_id\", \"\")))\n"
            "\n"
            "    async def _handle_get_hub_notifications(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        unread = str(request.query.get(\"unread\", \"\")).lower() in {\"1\", \"true\", \"yes\"}\n"
            "        return web.json_response(_hermes_hub_notifications_payload(unread))\n"
            "\n"
            "    async def _handle_post_hub_notification(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        try:\n"
            "            body = await request.json()\n"
            "        except Exception:\n"
            "            body = {}\n"
            "        return web.json_response(_hermes_hub_add_notification(body if isinstance(body, dict) else {}))\n"
            "\n"
            "    async def _handle_patch_hub_notification(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        try:\n"
            "            body = await request.json()\n"
            "        except Exception:\n"
            "            body = {}\n"
            "        updated = _hermes_hub_patch_notification(request.match_info.get(\"notification_id\", \"\"), body if isinstance(body, dict) else {})\n"
            "        status = 404 if isinstance(updated, dict) and updated.get(\"missing\") else 200\n"
            "        return web.json_response(updated, status=status)\n"
            "\n"
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            "hub support endpoint handlers",
        )
        changes.append("hub support endpoint handlers")

    if "async def _handle_get_hub_conversations" not in text and "async def _handle_get_hub_notifications" in text:
        text, _ = _replace_once(
            text,
            "    async def _handle_get_hub_notifications(self, request: \"web.Request\") -> \"web.Response\":",
            "    async def _handle_get_hub_conversations(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        return web.json_response(_hermes_hub_conversations_payload())\n"
            "\n"
            "    async def _handle_put_hub_conversation(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        try:\n"
            "            body = await request.json()\n"
            "        except Exception:\n"
            "            body = {}\n"
            "        if not isinstance(body, dict):\n"
            "            body = {}\n"
            "        body[\"id\"] = request.match_info.get(\"conversation_id\", body.get(\"id\", \"\"))\n"
            "        return web.json_response(_hermes_hub_merge_conversations([body]))\n"
            "\n"
            "    async def _handle_post_hub_conversations_import(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        try:\n"
            "            body = await request.json()\n"
            "        except Exception:\n"
            "            body = {}\n"
            "        if not isinstance(body, dict):\n"
            "            body = {}\n"
            "        incoming = body.get(\"items\") if isinstance(body.get(\"items\"), list) else None\n"
            "        if incoming is None:\n"
            "            incoming = _hermes_hub_extract_backup_conversations(body)\n"
            "        merged = _hermes_hub_merge_conversations(incoming if isinstance(incoming, list) else [])\n"
            "        return web.json_response(merged)\n"
            "\n"
            "    async def _handle_delete_hub_conversation(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        return web.json_response(_hermes_hub_delete_conversation(request.match_info.get(\"conversation_id\", \"\")))\n"
            "\n"
            "    async def _handle_get_hub_notifications(self, request: \"web.Request\") -> \"web.Response\":",
            "hub conversations endpoint handlers",
        )
        changes.append("hub conversations endpoint handlers")

    if "async def _handle_get_hub_notifications" not in text:
        text, _ = _replace_once(
            text,
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            "    async def _handle_get_hub_notifications(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        unread = str(request.query.get(\"unread\", \"\")).lower() in {\"1\", \"true\", \"yes\"}\n"
            "        return web.json_response(_hermes_hub_notifications_payload(unread))\n"
            "\n"
            "    async def _handle_post_hub_notification(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        try:\n"
            "            body = await request.json()\n"
            "        except Exception:\n"
            "            body = {}\n"
            "        return web.json_response(_hermes_hub_add_notification(body if isinstance(body, dict) else {}))\n"
            "\n"
            "    async def _handle_patch_hub_notification(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        try:\n"
            "            body = await request.json()\n"
            "        except Exception:\n"
            "            body = {}\n"
            "        updated = _hermes_hub_patch_notification(request.match_info.get(\"notification_id\", \"\"), body if isinstance(body, dict) else {})\n"
            "        status = 404 if isinstance(updated, dict) and updated.get(\"missing\") else 200\n"
            "        return web.json_response(updated, status=status)\n"
            "\n"
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            "hub notifications endpoint handlers",
        )
        changes.append("hub notifications endpoint handlers")

    if "async def _handle_get_hub_conversations" not in text and "async def _handle_get_hub_notifications" in text:
        text, _ = _replace_once(
            text,
            "    async def _handle_get_hub_notifications(self, request: \"web.Request\") -> \"web.Response\":",
            "    async def _handle_get_hub_conversations(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        return web.json_response(_hermes_hub_conversations_payload())\n"
            "\n"
            "    async def _handle_put_hub_conversation(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        try:\n"
            "            body = await request.json()\n"
            "        except Exception:\n"
            "            body = {}\n"
            "        if not isinstance(body, dict):\n"
            "            body = {}\n"
            "        body[\"id\"] = request.match_info.get(\"conversation_id\", body.get(\"id\", \"\"))\n"
            "        return web.json_response(_hermes_hub_merge_conversations([body]))\n"
            "\n"
            "    async def _handle_post_hub_conversations_import(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        try:\n"
            "            body = await request.json()\n"
            "        except Exception:\n"
            "            body = {}\n"
            "        if not isinstance(body, dict):\n"
            "            body = {}\n"
            "        incoming = body.get(\"items\") if isinstance(body.get(\"items\"), list) else None\n"
            "        if incoming is None:\n"
            "            incoming = _hermes_hub_extract_backup_conversations(body)\n"
            "        merged = _hermes_hub_merge_conversations(incoming if isinstance(incoming, list) else [])\n"
            "        return web.json_response(merged)\n"
            "\n"
            "    async def _handle_delete_hub_conversation(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        return web.json_response(_hermes_hub_delete_conversation(request.match_info.get(\"conversation_id\", \"\")))\n"
            "\n"
            "    async def _handle_get_hub_notifications(self, request: \"web.Request\") -> \"web.Response\":",
            "hub conversations endpoint handlers",
        )
        changes.append("hub conversations endpoint handlers")

    if "async def _handle_news_library" not in text:
        text, _ = _replace_once(
            text,
            "    async def _handle_video_library(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        return web.json_response(_hermes_hub_video_library_payload(request))\n",
            "    async def _handle_video_library(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        return web.json_response(_hermes_hub_video_library_payload(request))\n"
            "\n"
            "    async def _handle_news_library(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        return web.json_response(_hermes_hub_news_library_payload(request))\n",
            "news library endpoint handler",
        )
        changes.append("news library endpoint handler")

    if "async def _handle_hub_media" not in text:
        text, _ = _replace_once(
            text,
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            "    async def _handle_hub_media(self, request: \"web.Request\") -> \"web.StreamResponse\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            media_token = request.query.get(\"hub_token\") or request.query.get(\"api_key\") or request.query.get(\"token\")\n"
            "            accepted_api_keys = _hermes_hub_api_keys(self._api_key)\n"
            "            if not media_token or not any(hmac.compare_digest(media_token, api_key) for api_key in accepted_api_keys):\n"
            "                return auth_error\n"
            "        media_id = request.match_info.get(\"media_id\", \"\")\n"
            "        path = _hermes_hub_resolve_media_path(media_id, request.query.get(\"root\"))\n"
            "        if path is None:\n"
            "            return web.json_response({\"error\": \"Media not found\"}, status=404)\n"
            "        try:\n"
            "            if request.query.get(\"format\", \"\").lower() == \"mp4\" or request.query.get(\"transcode\", \"\").lower() in {\"1\", \"true\", \"mp4\"}:\n"
            "                path = _hermes_hub_transcode_mp4(path)\n"
            "                return web.FileResponse(path, headers={\"Content-Type\": \"video/mp4\", \"Accept-Ranges\": \"bytes\", \"Cache-Control\": \"public, max-age=3600\"})\n"
            "            import mimetypes as _mimetypes\n"
            "            mime = _mimetypes.guess_type(path.name)[0] or \"application/octet-stream\"\n"
            "            return web.FileResponse(path, headers={\"Content-Type\": mime, \"Accept-Ranges\": \"bytes\", \"Cache-Control\": \"public, max-age=3600\"})\n"
            "        except Exception as exc:\n"
            "            try:\n"
            "                logger.exception(\"Hermes Hub media proxy failed for %s\", path)\n"
            "            except Exception:\n"
            "                pass\n"
            "            return web.json_response({\"error\": str(exc)}, status=500)\n"
            "\n"
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            "media proxy endpoint handler",
        )
        changes.append("media proxy endpoint handler")

    if '{"Content-Type": "video/mp4", "Cache-Control": "public, max-age=3600"}' in text:
        text = text.replace(
            '{"Content-Type": "video/mp4", "Cache-Control": "public, max-age=3600"}',
            '{"Content-Type": "video/mp4", "Accept-Ranges": "bytes", "Cache-Control": "public, max-age=3600"}',
        )
        changes.append("media proxy range headers")

    if '{"Content-Type": mime, "Cache-Control": "public, max-age=3600"}' in text:
        text = text.replace(
            '{"Content-Type": mime, "Cache-Control": "public, max-age=3600"}',
            '{"Content-Type": mime, "Accept-Ranges": "bytes", "Cache-Control": "public, max-age=3600"}',
        )
        changes.append("media proxy original range headers")

    if 'media_token = request.query.get("hub_token")' not in text and 'async def _handle_hub_media' in text:
        text = text.replace(
            '    async def _handle_hub_media(self, request: "web.Request") -> "web.StreamResponse":\n'
            '        auth_error = self._check_auth(request)\n'
            '        if auth_error is not None:\n'
            '            return auth_error\n',
            '    async def _handle_hub_media(self, request: "web.Request") -> "web.StreamResponse":\n'
            '        auth_error = self._check_auth(request)\n'
            '        if auth_error is not None:\n'
            '            media_token = request.query.get("hub_token") or request.query.get("api_key") or request.query.get("token")\n'
            '            accepted_api_keys = _hermes_hub_api_keys(self._api_key)\n'
            '            if not media_token or not any(hmac.compare_digest(media_token, api_key) for api_key in accepted_api_keys):\n'
            '                return auth_error\n',
            1,
        )
        changes.append("media proxy query token auth")

    if "async def _handle_hub_media_upload" not in text:
        text, _ = _replace_once(
            text,
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            "    async def _handle_hub_media_upload(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        try:\n"
            "            body = await request.json()\n"
            "            result = _hermes_hub_save_upload(\n"
            "                str(body.get(\"filename\") or \"attachment\"),\n"
            "                str(body.get(\"mime_type\") or body.get(\"mimeType\") or \"application/octet-stream\"),\n"
            "                str(body.get(\"data_url\") or body.get(\"dataUrl\") or \"\"),\n"
            "            )\n"
            "            return web.json_response(result)\n"
            "        except Exception as exc:\n"
            "            return web.json_response({\"error\": str(exc)}, status=400)\n"
            "\n"
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            "media upload endpoint handler",
        )
        changes.append("media upload endpoint handler")

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

    if 'add_get("/v1/hub/hardware", self._handle_hub_hardware)' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+self\._app\.router\.add_get\("/v1/capabilities", self\._handle_capabilities\)\n)',
            r'\1'
            r'            self._app.router.add_get("/v1/hub/hardware", self._handle_hub_hardware)' "\n",
            "router hardware endpoint",
        )
        changes.append("router hardware endpoint")

    if 'add_get("/v1/video/library", self._handle_video_library)' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+self\._app\.router\.add_get\("/v1/capabilities", self\._handle_capabilities\)\n)',
            r'\1'
            r'            self._app.router.add_get("/v1/video/library", self._handle_video_library)' "\n"
            r'            self._app.router.add_get("/v1/news/library", self._handle_news_library)' "\n"
            r'            self._app.router.add_post("/v1/media/upload", self._handle_hub_media_upload)' "\n"
            r'            self._app.router.add_get("/v1/media/{media_id:.*}", self._handle_hub_media)' "\n"
            r'            self._app.router.add_get("/v1/hub/memory", self._handle_hub_memory)' "\n"
            r'            self._app.router.add_patch("/v1/hub/memory", self._handle_patch_hub_memory)' "\n"
            r'            self._app.router.add_get("/v1/hub/state", self._handle_get_hub_state)' "\n"
            r'            self._app.router.add_post("/v1/hub/state", self._handle_post_hub_state)' "\n"
            r'            self._app.router.add_delete("/v1/hub/state/{state_id}", self._handle_delete_hub_state)' "\n",
            "router hub support endpoints",
        )
        changes.append("router hub support endpoints")
    else:
        if 'add_post("/v1/media/upload", self._handle_hub_media_upload)' not in text:
            text, _ = _replace_regex_once(
                text,
                r'(^\s+self\._app\.router\.add_get\("/v1/video/library", self\._handle_video_library\)\n)',
                r'\1'
                r'            self._app.router.add_post("/v1/media/upload", self._handle_hub_media_upload)' "\n",
                "router media upload endpoint",
            )
            changes.append("router media upload endpoint")

        if 'add_get("/v1/media/{media_id:.*}", self._handle_hub_media)' not in text:
            text, _ = _replace_regex_once(
                text,
                r'(^\s+self\._app\.router\.add_get\("/v1/video/library", self\._handle_video_library\)\n)',
                r'\1'
                r'            self._app.router.add_get("/v1/media/{media_id:.*}", self._handle_hub_media)' "\n",
                "router media proxy endpoint",
            )
            changes.append("router media proxy endpoint")

        if 'add_get("/v1/news/library", self._handle_news_library)' not in text:
            text, _ = _replace_regex_once(
                text,
                r'(^\s+self\._app\.router\.add_get\("/v1/video/library", self\._handle_video_library\)\n)',
                r'\1'
                r'            self._app.router.add_get("/v1/news/library", self._handle_news_library)' "\n",
                "router news library endpoint",
            )
            changes.append("router news library endpoint")

    if 'add_get("/v1/hub/conversations", self._handle_get_hub_conversations)' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+self\._app\.router\.add_get\("/v1/hub/state", self\._handle_get_hub_state\)\n)',
            r'\1'
            r'            self._app.router.add_get("/v1/hub/conversations", self._handle_get_hub_conversations)' "\n"
            r'            self._app.router.add_post("/v1/hub/conversations/import", self._handle_post_hub_conversations_import)' "\n"
            r'            self._app.router.add_put("/v1/hub/conversations/{conversation_id}", self._handle_put_hub_conversation)' "\n"
            r'            self._app.router.add_delete("/v1/hub/conversations/{conversation_id}", self._handle_delete_hub_conversation)' "\n",
            "router hub conversations endpoints",
        )
        changes.append("router hub conversations endpoints")

    if 'add_get("/v1/hub/notifications", self._handle_get_hub_notifications)' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+self\._app\.router\.add_get\("/v1/hub/state", self\._handle_get_hub_state\)\n)',
            r'\1'
            r'            self._app.router.add_get("/v1/hub/notifications", self._handle_get_hub_notifications)' "\n"
            r'            self._app.router.add_post("/v1/hub/notifications", self._handle_post_hub_notification)' "\n"
            r'            self._app.router.add_patch("/v1/hub/notifications/{notification_id}", self._handle_patch_hub_notification)' "\n",
            "router hub notifications endpoints",
        )
        changes.append("router hub notifications endpoints")

    if 'add_post("/v1/audio/transcriptions", self._handle_audio_transcriptions)' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+self\._app\.router\.add_get\("/v1/hub/state", self\._handle_get_hub_state\)\n)',
            r'\1'
            r'            self._app.router.add_post("/v1/audio/transcriptions", self._handle_audio_transcriptions)' "\n",
            "router audio transcriptions endpoints",
        )
        changes.append("router audio transcriptions endpoints")

    if "def _hermes_hub_preload_whisper():" not in text:
        text += (
            "\n\n"
            "def _hermes_hub_preload_whisper():\n"
            "    try:\n"
            "        import os\n"
            "        from faster_whisper import WhisperModel\n"
            "        global _hermes_hub_whisper_model\n"
            "        print('Pre-loading faster-whisper large-v3-turbo on GPU 1...')\n"
            "        _hermes_hub_whisper_model = WhisperModel('large-v3-turbo', device='cuda', compute_type='int8', device_index=[1])\n"
            "        print('Whisper model loaded successfully.')\n"
            "    except Exception as e:\n"
            "        print('Failed to pre-load Whisper model:', e)\n"
            "\n"
            "_hermes_hub_preload_whisper()\n"
        )
        changes.append("pre-load whisper model at startup")

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
