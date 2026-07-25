#!/usr/bin/env python3
"""Patch Hermes Gateway API server for Hermes Hub native mode.

This is intentionally idempotent. It is used by the Ubuntu/headless launcher
until the same contract lands upstream in Hermes.
"""

from __future__ import annotations

import argparse
import os
import py_compile
import re
import shutil
import sys
import time
from pathlib import Path


class PatchError(RuntimeError):
    """Raised when a supported Hermes source cannot be patched safely."""


_HARDWARE_DISK_FILTER_MARKER = "# HERMES_HUB_HARDWARE_DISK_FILTER_V1"
_MODEL_ROUTE_TOOLSETS_MARKER = "# HERMES_HUB_MODEL_ROUTE_TOOLSETS_V1"
_MODEL_ROUTE_LIMITS_MARKER = "# HERMES_HUB_MODEL_ROUTE_LIMITS_V1"
_MODEL_ROUTE_MAX_TOKENS_MARKER = "# HERMES_HUB_MODEL_ROUTE_MAX_TOKENS_V1"
_MODEL_ROUTE_MAX_TOKENS_FIX_MARKER = "# HERMES_HUB_MODEL_ROUTE_MAX_TOKENS_FIX_V1"
_JARVIS_MARKER = "# HERMES_HUB_JARVIS_MODE_V1"
_JARVIS_RUNTIME_BEGIN = "# HERMES_HUB_JARVIS_RUNTIME_BEGIN"
_JARVIS_RUNTIME_END = "# HERMES_HUB_JARVIS_RUNTIME_END"
_JARVIS_HANDLERS_BEGIN = "    # HERMES_HUB_JARVIS_HANDLERS_BEGIN"
_JARVIS_HANDLERS_END = "    # HERMES_HUB_JARVIS_HANDLERS_END"
_JARVIS_CAPABILITIES_BEGIN = "            # HERMES_HUB_JARVIS_CAPABILITIES_BEGIN"
_JARVIS_CAPABILITIES_END = "            # HERMES_HUB_JARVIS_CAPABILITIES_END"
_JARVIS_ROUTES_BEGIN = "            # HERMES_HUB_JARVIS_ROUTES_BEGIN"
_JARVIS_ROUTES_END = "            # HERMES_HUB_JARVIS_ROUTES_END"
_JARVIS_CLEANUP_BEGIN = "            # HERMES_HUB_JARVIS_CLEANUP_BEGIN"
_JARVIS_CLEANUP_END = "            # HERMES_HUB_JARVIS_CLEANUP_END"
_DYNAMIC_ROUTES_BEGIN = "        # HERMES_HUB_DYNAMIC_ROUTES_BEGIN"
_DYNAMIC_ROUTES_END = "        # HERMES_HUB_DYNAMIC_ROUTES_END"
_HARDWARE_DISK_BLOCK_V1 = f'''    {_HARDWARE_DISK_FILTER_MARKER}
    disks: List[Dict[str, Any]] = []
    ignored_filesystems = {{
        "autofs", "cgroup", "cgroup2", "configfs", "debugfs", "devtmpfs",
        "efivarfs", "fusectl", "hugetlbfs", "mqueue", "nsfs", "overlay",
        "proc", "pstore", "ramfs", "securityfs", "squashfs", "sysfs",
        "tmpfs", "tracefs",
    }}
    for part in psutil.disk_partitions(all=False):
        filesystem = (part.fstype or '').strip().lower()
        device = (part.device or '').strip()
        if filesystem in ignored_filesystems or device.startswith('/dev/loop'):
            continue
        try:
            usage = psutil.disk_usage(part.mountpoint)
        except Exception:
            continue
        disks.append({{
            "device": device,
            "mountpoint": part.mountpoint,
            "fstype": filesystem,
            "total_bytes": int(usage.total),
            "used_bytes": int(usage.used),
            "free_bytes": int(usage.free),
            "percent": float(usage.percent),
        }})
    snapshot["disks"] = disks
'''


def _upgrade_hardware_disk_filter(text: str) -> tuple[str, bool]:
    """Upgrade the injected hardware collector without replacing upstream code."""
    collector_match = re.search(
        r"(?ms)^def _collect_hardware_snapshot\([^\n]*\)[^\n]*:\n.*?(?=^def |\Z)",
        text,
    )
    if collector_match is None:
        return text, False

    collector = collector_match.group(0)
    if _HARDWARE_DISK_FILTER_MARKER in collector:
        return text, False

    disk_block_pattern = re.compile(
        r'(?ms)^    disks: List\[Dict\[str, Any\]\] = \[\]\n'
        r'.*?^    snapshot\["disks"\] = disks\n'
    )
    upgraded_collector, count = disk_block_pattern.subn(
        _HARDWARE_DISK_BLOCK_V1,
        collector,
        count=1,
    )
    if count == 0:
        # A future upstream collector with a different layout remains owned by
        # upstream; only the Hermes Hub collector shape is migrated here.
        return text, False

    return (
        text[:collector_match.start()] + upgraded_collector + text[collector_match.end():],
        True,
    )


def _candidate_paths() -> list[Path]:
    candidates: list[Path] = []

    explicit = os.environ.get("HERMES_GATEWAY_API_SERVER_PATH")
    if explicit:
        candidates.append(Path(explicit).expanduser())

    # Do not import gateway.platforms.api_server here: patched installations
    # contain model preload side effects at module import time.
    for entry in sys.path:
        if entry:
            candidates.append(Path(entry) / "gateway" / "platforms" / "api_server.py")

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

    # Bounded fallback for common editable/source layouts. Avoid recursive **
    # scans across HERMES_HOME, which can include models and large media trees.
    for root in (hermes_home, Path.cwd()):
        if root.exists():
            for pattern in (
                "*/gateway/platforms/api_server.py",
                "*/*/gateway/platforms/api_server.py",
                "src/*/gateway/platforms/api_server.py",
            ):
                try:
                    candidates.extend(root.glob(pattern))
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


def _find_agent_chat_completion_helpers(api_server_path: Path) -> Path | None:
    """Find Hermes Agent chat_completion_helpers.py next to gateway sources."""
    candidates: list[Path] = []
    resolved = api_server_path.expanduser().resolve()
    for parent in [resolved.parent, *resolved.parents]:
        candidates.append(parent / "agent" / "chat_completion_helpers.py")
    candidates.extend([
        Path.home() / ".hermes" / "hermes-agent" / "agent" / "chat_completion_helpers.py",
        Path.cwd() / "agent" / "chat_completion_helpers.py",
    ])
    seen: set[str] = set()
    for path in candidates:
        key = str(path)
        if key in seen:
            continue
        seen.add(key)
        if path.is_file():
            return path
    return None


def _replace_once(text: str, old: str, new: str, label: str) -> tuple[str, bool]:
    if old not in text:
        raise RuntimeError(f"Patch anchor not found: {label}")
    return text.replace(old, new, 1), True


def _replace_regex_once(text: str, pattern: str, repl: str, label: str) -> tuple[str, bool]:
    patched, count = re.subn(pattern, repl, text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise RuntimeError(f"Patch anchor not found: {label}")
    return patched, True


def _write_compiled_transaction(updates: list[tuple[Path, str]]) -> dict[Path, Path]:
    """Compile every replacement first, then atomically replace all targets.

    Python cannot atomically exchange multiple files, so a failure after the
    first os.replace is recovered from durable sibling backups before raising.
    """
    if not updates:
        return {}

    stamp = f"{int(time.time())}-{os.getpid()}-{time.time_ns()}"
    prepared: dict[Path, Path] = {}
    backups: dict[Path, Path] = {}
    bytecode: list[Path] = []
    try:
        for target, content in updates:
            temporary = target.with_name(f".{target.name}.hermes-native-{stamp}.tmp")
            compiled = temporary.with_suffix(temporary.suffix + ".pyc")
            temporary.write_text(content, encoding="utf-8", newline="")
            os.chmod(temporary, target.stat().st_mode & 0o777)
            py_compile.compile(str(temporary), cfile=str(compiled), doraise=True)
            prepared[target] = temporary
            bytecode.append(compiled)

        for target, _ in updates:
            backup = target.with_suffix(target.suffix + f".bak-hermes-native-{stamp}")
            shutil.copy2(target, backup)
            backups[target] = backup

        for target, _ in updates:
            os.replace(prepared[target], target)
        return backups
    except Exception:
        for target, backup in reversed(list(backups.items())):
            try:
                recovery = target.with_name(f".{target.name}.hermes-recovery-{stamp}.tmp")
                shutil.copy2(backup, recovery)
                os.replace(recovery, target)
            except Exception as recovery_error:
                print(f"CRITICAL: failed to restore {target}: {recovery_error}", file=sys.stderr)
        raise
    finally:
        for temporary in prepared.values():
            temporary.unlink(missing_ok=True)
        for compiled in bytecode:
            compiled.unlink(missing_ok=True)


def _harden_runtime(text: str) -> tuple[str, list[str]]:
    """Upgrade injected handlers to bounded, non-blocking runtime behavior."""
    initial_text = text
    changes: list[str] = []

    runtime_helpers = r'''# HERMES_HUB_RUNTIME_HARDENING_V1
def _hermes_hub_env_int(name, default, minimum=1, maximum=None):
    try:
        value = int(os.environ.get(name, str(default)))
    except Exception:
        value = int(default)
    value = max(int(minimum), value)
    if maximum is not None:
        value = min(value, int(maximum))
    return value


def _hermes_hub_io_executor():
    from concurrent.futures import ThreadPoolExecutor

    global _hermes_hub_io_thread_pool
    if "_hermes_hub_io_thread_pool" not in globals():
        workers = _hermes_hub_env_int("HERMES_HUB_IO_WORKERS", 4, 1, 16)
        _hermes_hub_io_thread_pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="hermes-hub-io")
    return _hermes_hub_io_thread_pool


def _hermes_hub_transcode_executor():
    from concurrent.futures import ThreadPoolExecutor

    global _hermes_hub_transcode_thread_pool
    if "_hermes_hub_transcode_thread_pool" not in globals():
        workers = _hermes_hub_env_int("HERMES_HUB_TRANSCODE_WORKERS", 1, 1, 2)
        _hermes_hub_transcode_thread_pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="hermes-transcode")
    return _hermes_hub_transcode_thread_pool


def _hermes_hub_stt_executor():
    from concurrent.futures import ThreadPoolExecutor

    global _hermes_hub_stt_thread_pool
    if "_hermes_hub_stt_thread_pool" not in globals():
        _hermes_hub_stt_thread_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="hermes-stt")
    return _hermes_hub_stt_thread_pool


def _hermes_hub_ensure_whisper_model():
    global _hermes_hub_whisper_model
    if "_hermes_hub_whisper_model" not in globals():
        from faster_whisper import WhisperModel

        model = os.environ.get("HERMES_WHISPER_MODEL", "large-v3-turbo")
        device = os.environ.get("HERMES_WHISPER_DEVICE", "cuda")
        compute_type = os.environ.get("HERMES_WHISPER_COMPUTE_TYPE", "int8")
        device_index = _hermes_hub_env_int("HERMES_WHISPER_DEVICE_INDEX", 1, 0, 32)
        kwargs = {"device": device, "compute_type": compute_type}
        if device.lower() == "cuda":
            kwargs["device_index"] = [device_index]
        _hermes_hub_whisper_model = WhisperModel(model, **kwargs)
    return _hermes_hub_whisper_model


def _hermes_hub_transcribe_file(path):
    model = _hermes_hub_ensure_whisper_model()
    language = os.environ.get("HERMES_WHISPER_LANGUAGE", "it")
    beam_size = _hermes_hub_env_int("HERMES_WHISPER_BEAM_SIZE", 5, 1, 10)
    segments, _ = model.transcribe(path, beam_size=beam_size, language=language)
    return "".join(segment.text for segment in segments).strip()


def _hermes_hub_cached_hardware_snapshot():
    import threading as _threading

    global _hermes_hub_hardware_cache_lock, _hermes_hub_hardware_cache
    if "_hermes_hub_hardware_cache_lock" not in globals():
        _hermes_hub_hardware_cache_lock = _threading.Lock()
        _hermes_hub_hardware_cache = None
    ttl = _hermes_hub_env_int("HERMES_HUB_HARDWARE_CACHE_SECONDS", 2, 1, 60)
    with _hermes_hub_hardware_cache_lock:
        now = time.monotonic()
        if _hermes_hub_hardware_cache is not None and now - _hermes_hub_hardware_cache[0] < ttl:
            return _hermes_hub_hardware_cache[1]
        snapshot = _collect_hardware_snapshot()
        _hermes_hub_hardware_cache = (now, snapshot)
        return snapshot


def _hermes_hub_bounded_media_matches(root, basename, prefix=False):
    from pathlib import Path as _Path

    max_files = _hermes_hub_env_int("HERMES_HUB_MEDIA_SCAN_MAX_FILES", 12000, 100, 200000)
    max_seconds = float(os.environ.get("HERMES_HUB_MEDIA_SCAN_TIMEOUT_SECONDS", "2.0"))
    deadline = time.monotonic() + max(0.1, min(max_seconds, 15.0))
    matches = {}
    scanned = 0
    try:
        resolved_root = _Path(root).expanduser().resolve()
        if not resolved_root.is_dir():
            return []
        for current, directories, files in os.walk(resolved_root, followlinks=False):
            directories[:] = [name for name in directories if not (_Path(current) / name).is_symlink()]
            for filename in files:
                scanned += 1
                if scanned > max_files or time.monotonic() >= deadline:
                    return list(matches.values())
                matched = filename.startswith(basename) if prefix else filename == basename
                if not matched:
                    continue
                candidate = (_Path(current) / filename).resolve()
                if resolved_root != candidate and resolved_root not in candidate.parents:
                    continue
                if candidate.is_file():
                    matches[str(candidate)] = candidate
                    if len(matches) > 1:
                        return list(matches.values())
    except Exception:
        return list(matches.values())
    return list(matches.values())


def _hermes_hub_library_files(root, extensions):
    from pathlib import Path as _Path

    max_scan = _hermes_hub_env_int("HERMES_HUB_LIBRARY_SCAN_MAX_FILES", 50000, 100, 500000)
    max_items = _hermes_hub_env_int("HERMES_HUB_LIBRARY_MAX_ITEMS", 2000, 1, 50000)
    max_seconds = float(os.environ.get("HERMES_HUB_LIBRARY_SCAN_TIMEOUT_SECONDS", "8"))
    deadline = time.monotonic() + max(0.5, min(max_seconds, 30.0))
    found = []
    scanned = 0
    try:
        resolved_root = _Path(root).expanduser().resolve()
        for current, directories, files in os.walk(resolved_root, followlinks=False):
            directories[:] = [name for name in directories if not (_Path(current) / name).is_symlink()]
            for filename in files:
                scanned += 1
                if scanned > max_scan or time.monotonic() >= deadline:
                    found.sort(key=lambda item: item[0], reverse=True)
                    return [item[1] for item in found[:max_items]]
                path = _Path(current) / filename
                if path.suffix.lower() not in extensions:
                    continue
                try:
                    found.append((float(path.stat().st_mtime), path))
                except Exception:
                    continue
    except Exception:
        pass
    found.sort(key=lambda item: item[0], reverse=True)
    return [item[1] for item in found[:max_items]]


def _hermes_hub_conversation_io(action, payload=None):
    import threading as _threading

    global _hermes_hub_conversation_io_lock
    if "_hermes_hub_conversation_io_lock" not in globals():
        _hermes_hub_conversation_io_lock = _threading.RLock()
    with _hermes_hub_conversation_io_lock:
        if action == "get":
            return _hermes_hub_conversations_payload()
        if action in {"put", "import"}:
            result = _hermes_hub_merge_conversations(payload if isinstance(payload, list) else [])
        elif action == "delete":
            result = _hermes_hub_delete_conversation(str(payload or ""))
        elif action == "snapshot":
            return _hermes_hub_conversation_event_payload("snapshot")
        else:
            raise ValueError(f"unsupported conversation operation: {action}")
        event = _hermes_hub_conversation_event_payload(action, result)
        return result, event


def _hermes_hub_prune_media_cache(cache_dir, keep=None):
    max_files = _hermes_hub_env_int("HERMES_HUB_MEDIA_CACHE_MAX_FILES", 96, 1, 10000)
    max_bytes = _hermes_hub_env_int("HERMES_HUB_MEDIA_CACHE_MAX_MB", 10240, 64, 1048576) * 1024 * 1024
    entries = []
    try:
        for path in cache_dir.glob("*.compat.mp4"):
            try:
                stat = path.stat()
                entries.append((float(stat.st_mtime), int(stat.st_size), path))
            except Exception:
                continue
        entries.sort(reverse=True)
        total = 0
        for index, (_, size, path) in enumerate(entries):
            total += size
            if path == keep:
                continue
            if index >= max_files or total > max_bytes:
                try:
                    path.unlink(missing_ok=True)
                    total -= size
                except Exception:
                    pass
    except Exception:
        pass


'''
    if "# HERMES_HUB_RUNTIME_HARDENING_V1" not in text:
        anchor = 'def _multimodal_validation_error(exc: ValueError, *, param: str) -> "web.Response":'
        if anchor not in text:
            raise PatchError("runtime hardening helper anchor not found")
        text = text.replace(anchor, runtime_helpers + anchor, 1)
        changes.append("bounded runtime executors and helpers")

    safe_name = r'''def _hermes_hub_safe_upload_name(filename: str, mime_type: str) -> str:
    import re as _re
    from pathlib import Path as _Path

    name = _re.sub(r"[^A-Za-z0-9._-]+", "_", str(filename or "attachment")).strip("._") or "attachment"
    suffix = _Path(name).suffix
    if not suffix:
        suffix = {
            "image/png": ".png", "image/jpeg": ".jpg", "image/webp": ".webp",
            "image/gif": ".gif", "image/bmp": ".bmp",
        }.get(str(mime_type or "").lower(), ".bin")
    stem = name[:-len(suffix)] if suffix and name.lower().endswith(suffix.lower()) else name
    return (stem[: max(1, 160 - len(suffix))] + suffix)[:160]
'''
    text, count = re.subn(
        r'(?s)def _hermes_hub_safe_upload_name\(filename: str, mime_type: str\) -> str:\n.*?(?=\n\ndef _hermes_hub_save_upload)',
        lambda _: safe_name.rstrip(),
        text,
        count=1,
    )
    if count:
        changes.append("upload filename extension preservation")

    save_upload = r'''def _hermes_hub_save_upload(filename: str, mime_type: str, data_url: str) -> Dict[str, Any]:
    import base64 as _base64
    import hashlib as _hashlib
    import tempfile as _tempfile
    import urllib.parse as _urlparse

    raw = str(data_url or "")
    if "," not in raw or not raw.startswith("data:"):
        raise ValueError("data_url must be a data: URL")
    meta, encoded = raw.split(",", 1)
    detected_mime = meta[5:].split(";", 1)[0].strip() or str(mime_type or "application/octet-stream")
    mime = str(mime_type or detected_mime or "application/octet-stream")
    max_mb = _hermes_hub_env_int("HERMES_HUB_JSON_UPLOAD_MAX_MB", 64, 1, 1024)
    max_bytes = max_mb * 1024 * 1024
    estimated = (len(encoded) * 3) // 4
    if not encoded or estimated > max_bytes + 3:
        raise ValueError(f"upload empty or over JSON upload limit ({max_mb} MB)")

    root = _hermes_hub_upload_root()
    safe = _hermes_hub_safe_upload_name(filename, mime)
    digest = _hashlib.sha256()
    size = 0
    temporary_path = None
    try:
        with _tempfile.NamedTemporaryFile(dir=root, prefix=".upload-", suffix=".tmp", delete=False) as handle:
            temporary_path = handle.name
            chunk_chars = 1024 * 1024
            for offset in range(0, len(encoded), chunk_chars):
                chunk = encoded[offset: offset + chunk_chars]
                try:
                    decoded = _base64.b64decode(chunk, validate=True)
                except Exception as exc:
                    raise ValueError(f"invalid base64 upload: {exc}") from exc
                size += len(decoded)
                if size > max_bytes:
                    raise ValueError(f"upload over JSON upload limit ({max_mb} MB)")
                digest.update(decoded)
                handle.write(decoded)
            handle.flush()
            os.fsync(handle.fileno())
        if size <= 0:
            raise ValueError("upload empty")
        target = root / f"{int(time.time())}-{digest.hexdigest()[:16]}-{safe}"
        os.replace(temporary_path, target)
        temporary_path = None
    finally:
        if temporary_path:
            try:
                os.unlink(temporary_path)
            except Exception:
                pass
    rel = target.relative_to(root).as_posix()
    media_id = _urlparse.quote(rel, safe="")
    return {
        "object": "hermes.media.upload", "filename": safe, "mime_type": mime,
        "size_bytes": size, "path": str(target), "server_path": str(target),
        "media_id": rel, "media_url": f"/v1/media/{media_id}", "url": f"/v1/media/{media_id}",
    }
'''
    text, count = re.subn(
        r'(?s)def _hermes_hub_save_upload\(filename: str, mime_type: str, data_url: str\) -> Dict\[str, Any\]:\n.*?(?=\n\ndef )',
        lambda _: save_upload.rstrip(),
        text,
        count=1,
    )
    if count:
        changes.append("bounded streaming base64 upload decode")

    read_json = r'''def _hermes_hub_read_json(path: "Path", default: Dict[str, Any]) -> Dict[str, Any]:
    import json as _json
    import shutil as _shutil

    if not path.is_file():
        return dict(default)
    try:
        with path.open("r", encoding="utf-8") as handle:
            loaded = _json.load(handle)
        if not isinstance(loaded, dict):
            raise ValueError("JSON store root must be an object")
        return loaded
    except Exception as exc:
        try:
            corrupt = path.with_suffix(path.suffix + ".corrupt")
            _shutil.copy2(path, corrupt)
            logger.exception("Hermes Hub store is corrupt; preserved at %s", corrupt)
        except Exception:
            pass
        raise RuntimeError(f"Failed to read Hermes Hub store {path}: {exc}") from exc
'''
    text, count = re.subn(
        r'(?s)def _hermes_hub_read_json\(path: "Path", default: Dict\[str, Any\]\) -> Dict\[str, Any\]:\n.*?(?=\n\ndef _hermes_hub_write_json)',
        lambda _: read_json.rstrip(), text, count=1,
    )
    if count:
        changes.append("fail-closed JSON store reads")

    write_json = r'''def _hermes_hub_write_json(path: "Path", payload: Dict[str, Any]) -> None:
    import json as _json
    import shutil as _shutil
    import tempfile as _tempfile

    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with _tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, prefix=path.name + ".", delete=False) as handle:
            temporary = handle.name
            _json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, 0o600)
        if path.is_file():
            _shutil.copy2(path, path.with_suffix(path.suffix + ".bak"))
        os.replace(temporary, path)
        temporary = None
        try:
            directory_fd = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except Exception:
            pass
    finally:
        if temporary:
            try:
                os.unlink(temporary)
            except Exception:
                pass
'''
    text, count = re.subn(
        r'(?s)def _hermes_hub_write_json\(path: "Path", payload: Dict\[str, Any\]\) -> None:\n.*?(?=\n\ndef )',
        lambda _: write_json.rstrip(), text, count=1,
    )
    if count:
        changes.append("durable atomic JSON store writes")

    resolver = r'''def _hermes_hub_resolve_media_path(media_id: str, extra_root: Optional[str] = None) -> Optional["Path"]:
    import urllib.parse as _urlparse
    from pathlib import Path as _Path

    decoded = _urlparse.unquote(str(media_id or "")).replace(chr(92), "/").lstrip("/")
    if not decoded:
        return None
    roots = _hermes_hub_media_roots()
    if extra_root:
        try:
            requested = _Path(str(extra_root).strip()).expanduser().resolve()
            if any(requested == root.resolve() or root.resolve() in requested.parents for root in roots):
                roots.insert(0, requested)
        except Exception:
            pass
    for root in roots:
        try:
            root_resolved = root.resolve()
            candidate = (root_resolved / decoded).resolve()
            if (root_resolved == candidate or root_resolved in candidate.parents) and candidate.is_file():
                return candidate
        except Exception:
            continue

    basename = _Path(decoded).name
    if not basename or basename != decoded:
        return None
    upload_root = _Path(os.environ.get("HERMES_HUB_UPLOAD_PATH", str(_Path.home() / ".hermes" / "hub_uploads"))).expanduser()
    ordered_roots = [upload_root] + [root for root in roots if root != upload_root]
    exact = {}
    for root in ordered_roots:
        for candidate in _hermes_hub_bounded_media_matches(root, basename, prefix=False):
            exact[str(candidate)] = candidate
            if len(exact) > 1:
                return None
    if len(exact) == 1:
        return next(iter(exact.values()))
    if len(basename) < 4:
        return None
    prefixed = {}
    for root in ordered_roots:
        for candidate in _hermes_hub_bounded_media_matches(root, basename, prefix=True):
            prefixed[str(candidate)] = candidate
            if len(prefixed) > 1:
                return None
    return next(iter(prefixed.values())) if len(prefixed) == 1 else None
'''
    text, count = re.subn(
        r'(?s)def _hermes_hub_resolve_media_path\(media_id: str, extra_root: Optional\[str\] = None\) -> Optional\["Path"\]:\n.*?(?=\n\ndef _hermes_hub_is_tailnet_peer)',
        lambda _: resolver.rstrip(),
        text,
        count=1,
    )
    if count:
        changes.append("bounded unambiguous media lookup")

    cache_path = r'''def _hermes_hub_media_cache_path(source: "Path") -> "Path":
    import hashlib as _hashlib
    import re as _re
    from pathlib import Path as _Path

    stat = source.stat()
    home = _Path(os.environ.get("HERMES_HOME", str(_Path.home() / ".hermes"))).expanduser()
    digest = _hashlib.sha256(f"{source.resolve()}:{stat.st_size}:{stat.st_mtime_ns}:compatv3".encode("utf-8")).hexdigest()[:24]
    cache_dir = _Path(os.environ.get("HERMES_HUB_MEDIA_CACHE", str(home / "hub_media_cache"))).expanduser()
    cache_dir.mkdir(parents=True, exist_ok=True)
    safe_stem = _re.sub(r"[^A-Za-z0-9._-]+", "_", source.stem).strip("._")[:80] or "media"
    return cache_dir / f"{safe_stem}.{digest}.compat.mp4"
'''
    text, count = re.subn(
        r'(?s)def _hermes_hub_media_cache_path\(source: "Path"\) -> "Path":\n.*?(?=\n\ndef _hermes_hub_transcode_mp4)',
        lambda _: cache_path.rstrip(),
        text,
        count=1,
    )
    if count:
        changes.append("bounded media cache filenames")

    transcode = r'''def _hermes_hub_transcode_mp4(source: "Path") -> "Path":
    import subprocess as _subprocess
    import threading as _threading

    target = _hermes_hub_media_cache_path(source)
    global _hermes_hub_transcode_locks_guard, _hermes_hub_transcode_locks
    if "_hermes_hub_transcode_locks_guard" not in globals():
        _hermes_hub_transcode_locks_guard = _threading.Lock()
        _hermes_hub_transcode_locks = {}
    key = str(target)
    with _hermes_hub_transcode_locks_guard:
        lock = _hermes_hub_transcode_locks.setdefault(key, _threading.Lock())
        if len(_hermes_hub_transcode_locks) > 512:
            _hermes_hub_transcode_locks = {name: item for name, item in _hermes_hub_transcode_locks.items() if item.locked() or name == key}
    with lock:
        if target.is_file() and target.stat().st_size > 0:
            return target
        _hermes_hub_prune_media_cache(target.parent, keep=target)
        tmp = target.with_name(f".{target.name}.{os.getpid()}.{_threading.get_ident()}.tmp")
        try:
            probe = _subprocess.run(
                ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=index", "-of", "csv=p=0", str(source)],
                capture_output=True, text=True, timeout=30,
            )
            has_audio = probe.returncode == 0 and bool((probe.stdout or "").strip())
            cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(source)]
            if not has_audio:
                cmd += ["-f", "lavfi", "-i", "anullsrc=channel_layout=mono:sample_rate=44100"]
            cmd += [
                "-map", "0:v:0", "-map", "0:a:0" if has_audio else "1:a:0",
                "-c:v", "libx264", "-profile:v", "main", "-level:v", os.environ.get("HERMES_HUB_TRANSCODE_H264_LEVEL", "4.2"),
                "-preset", os.environ.get("HERMES_HUB_TRANSCODE_PRESET", "veryfast"),
                "-pix_fmt", "yuv420p", "-tag:v", "avc1", "-movflags", "+faststart",
                "-c:a", "aac", "-b:a", os.environ.get("HERMES_HUB_TRANSCODE_AUDIO_BITRATE", "160k"), "-f", "mp4", str(tmp),
            ]
            if not has_audio:
                cmd.insert(len(cmd) - 3, "-shortest")
            timeout = _hermes_hub_env_int("HERMES_HUB_TRANSCODE_TIMEOUT", 900, 30, 7200)
            result = _subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
            if result.returncode != 0:
                raise RuntimeError((result.stderr or result.stdout or "ffmpeg transcode failed").strip())
            if not tmp.is_file() or tmp.stat().st_size <= 0:
                raise RuntimeError("ffmpeg produced an empty output")
            os.replace(tmp, target)
            _hermes_hub_prune_media_cache(target.parent, keep=target)
            return target
        finally:
            try:
                tmp.unlink(missing_ok=True)
            except Exception:
                pass
'''
    text, count = re.subn(
        r'(?s)def _hermes_hub_transcode_mp4\(source: "Path"\) -> "Path":\n.*?(?=\n\ndef )',
        lambda _: transcode.rstrip(),
        text,
        count=1,
    )
    if count:
        changes.append("single-flight bounded media transcode cache")

    stt_handler = r'''    async def _handle_audio_transcriptions(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        import tempfile as _tempfile
        from pathlib import Path as _Path

        max_mb = _hermes_hub_env_int("HERMES_STT_MAX_UPLOAD_MB", 256, 1, 4096)
        max_bytes = max_mb * 1024 * 1024
        tmp_path = None
        total = 0
        found_file = False
        try:
            reader = await request.multipart()
            with _tempfile.NamedTemporaryFile(delete=False, suffix=".audio") as tmp:
                tmp_path = tmp.name
                field = await reader.next()
                while field is not None:
                    if field.name == "file":
                        found_file = True
                        while True:
                            chunk = await field.read_chunk(size=64 * 1024)
                            if not chunk:
                                break
                            total += len(chunk)
                            if total > max_bytes:
                                return web.json_response({"error": f"Audio upload over limit ({max_mb} MB)"}, status=413)
                            tmp.write(chunk)
                    field = await reader.next()
                tmp.flush()
                os.fsync(tmp.fileno())
            if not found_file or total <= 0:
                return web.json_response({"error": "No audio file provided"}, status=400)
            loop = asyncio.get_running_loop()
            timeout = _hermes_hub_env_int("HERMES_STT_TIMEOUT_SECONDS", 300, 10, 1800)
            global _hermes_hub_stt_async_lock
            if "_hermes_hub_stt_async_lock" not in globals():
                _hermes_hub_stt_async_lock = asyncio.Lock()
            async with _hermes_hub_stt_async_lock:
                result_text = await asyncio.wait_for(
                    loop.run_in_executor(_hermes_hub_stt_executor(), _hermes_hub_transcribe_file, tmp_path),
                    timeout=timeout,
                )
            return web.json_response({"text": result_text})
        except asyncio.TimeoutError:
            return web.json_response({"error": "Speech transcription timed out"}, status=504)
        except Exception as exc:
            return web.json_response({"error": "Invalid or unavailable audio transcription", "detail": str(exc)}, status=400)
        finally:
            if tmp_path:
                try:
                    _Path(tmp_path).unlink(missing_ok=True)
                except Exception:
                    pass
'''
    text, count = re.subn(
        r'(?s)^    async def _handle_audio_transcriptions\(self, request: "web.Request"\) -> "web.Response":\n.*?(?=^    async def )',
        lambda _: stt_handler.rstrip() + "\n\n",
        text,
        count=1,
        flags=re.MULTILINE,
    )
    if count:
        changes.append("streamed bounded STT handler")

    media_handler = r'''    async def _handle_hub_media(self, request: "web.Request") -> "web.StreamResponse":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            if _hermes_hub_is_tailnet_peer(request):
                auth_error = None
            else:
                media_token = request.query.get("hub_token") or request.query.get("api_key") or request.query.get("token")
                accepted_api_keys = _hermes_hub_api_keys(self._api_key)
                if not media_token or not any(hmac.compare_digest(media_token, api_key) for api_key in accepted_api_keys):
                    return auth_error
        media_id = request.match_info.get("media_id", "")
        loop = asyncio.get_running_loop()
        try:
            lookup_timeout = _hermes_hub_env_int("HERMES_HUB_MEDIA_LOOKUP_TIMEOUT", 8, 1, 30)
            path = await asyncio.wait_for(
                loop.run_in_executor(_hermes_hub_io_executor(), _hermes_hub_resolve_media_path, media_id, request.query.get("root")),
                timeout=lookup_timeout,
            )
            if path is None:
                return web.json_response({"error": "Media not found"}, status=404)
            if request.query.get("format", "").lower() == "mp4" or request.query.get("transcode", "").lower() in {"1", "true", "mp4"}:
                transcode_timeout = _hermes_hub_env_int("HERMES_HUB_TRANSCODE_TIMEOUT", 900, 30, 7200) + 10
                path = await asyncio.wait_for(
                    loop.run_in_executor(_hermes_hub_transcode_executor(), _hermes_hub_transcode_mp4, path),
                    timeout=transcode_timeout,
                )
                return web.FileResponse(path, headers={"Content-Type": "video/mp4", "Accept-Ranges": "bytes", "Cache-Control": "public, max-age=3600"})
            import mimetypes as _mimetypes
            mime = _mimetypes.guess_type(path.name)[0] or "application/octet-stream"
            return web.FileResponse(path, headers={"Content-Type": mime, "Accept-Ranges": "bytes", "Cache-Control": "public, max-age=3600"})
        except asyncio.TimeoutError:
            return web.json_response({"error": "Media operation timed out"}, status=504)
        except Exception as exc:
            try:
                logger.exception("Hermes Hub media proxy failed for %s", media_id)
            except Exception:
                pass
            return web.json_response({"error": str(exc)}, status=500)
'''
    text, count = re.subn(
        r'(?s)^    async def _handle_hub_media\(self, request: "web.Request"\) -> "web.StreamResponse":\n.*?(?=^    async def )',
        lambda _: media_handler.rstrip() + "\n\n",
        text,
        count=1,
        flags=re.MULTILINE,
    )
    if count:
        changes.append("non-blocking bounded media handler")

    upload_handler = r'''    async def _handle_hub_media_upload(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        max_mb = _hermes_hub_env_int("HERMES_HUB_JSON_UPLOAD_MAX_MB", 64, 1, 1024)
        encoded_limit = ((max_mb * 1024 * 1024 + 2) // 3) * 4 + 1024 * 1024
        if request.content_length is None:
            return web.json_response({"error": "Content-Length required for JSON media upload"}, status=411)
        if request.content_length > encoded_limit:
            return web.json_response({"error": f"JSON media upload over limit ({max_mb} MB decoded)"}, status=413)
        try:
            body = await request.json()
            if not isinstance(body, dict):
                raise ValueError("JSON body must be an object")
            loop = asyncio.get_running_loop()
            result = await asyncio.wait_for(
                loop.run_in_executor(
                    _hermes_hub_io_executor(),
                    _hermes_hub_save_upload,
                    str(body.get("filename") or "attachment"),
                    str(body.get("mime_type") or body.get("mimeType") or "application/octet-stream"),
                    str(body.get("data_url") or body.get("dataUrl") or ""),
                ),
                timeout=_hermes_hub_env_int("HERMES_HUB_UPLOAD_TIMEOUT_SECONDS", 120, 10, 900),
            )
            return web.json_response(result)
        except asyncio.TimeoutError:
            return web.json_response({"error": "Media upload timed out"}, status=504)
        except Exception as exc:
            return web.json_response({"error": str(exc)}, status=400)
'''
    text, count = re.subn(
        r'(?s)^    async def _handle_hub_media_upload\(self, request: "web.Request"\) -> "web.Response":\n.*?(?=^    async def )',
        lambda _: upload_handler.rstrip() + "\n\n",
        text,
        count=1,
        flags=re.MULTILINE,
    )
    if count:
        changes.append("bounded JSON media upload handler")

    conversation_get = r'''    async def _handle_get_hub_conversations(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        try:
            loop = asyncio.get_running_loop()
            payload = await asyncio.wait_for(
                loop.run_in_executor(_hermes_hub_io_executor(), _hermes_hub_conversation_io, "get", None),
                timeout=_hermes_hub_env_int("HERMES_HUB_STORE_TIMEOUT_SECONDS", 30, 2, 300),
            )
            return web.json_response(payload)
        except asyncio.TimeoutError:
            return web.json_response({"error": "Conversation store timed out"}, status=504)
        except Exception as exc:
            return web.json_response({"error": "Conversation store unavailable", "detail": str(exc)}, status=500)
'''
    text, count = re.subn(
        r'(?s)^    async def _handle_get_hub_conversations\(self, request: "web.Request"\) -> "web.Response":\n.*?(?=^    async def )',
        lambda _: conversation_get.rstrip() + "\n\n", text, count=1, flags=re.MULTILINE,
    )
    if count:
        changes.append("non-blocking conversation GET")

    conversation_put = r'''    async def _handle_put_hub_conversation(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        try:
            body = await request.json()
        except Exception as exc:
            return web.json_response({"error": "Invalid JSON body", "detail": str(exc)}, status=400)
        if not isinstance(body, dict):
            return web.json_response({"error": "JSON body must be an object"}, status=400)
        conversation_id = str(request.match_info.get("conversation_id") or body.get("id") or "").strip()
        if not conversation_id:
            return web.json_response({"error": "Conversation id is required"}, status=400)
        body["id"] = conversation_id
        try:
            loop = asyncio.get_running_loop()
            operation = await asyncio.wait_for(
                loop.run_in_executor(_hermes_hub_io_executor(), _hermes_hub_conversation_io, "put", [body]),
                timeout=_hermes_hub_env_int("HERMES_HUB_STORE_TIMEOUT_SECONDS", 30, 2, 300),
            )
            merged, event = operation
            _hermes_hub_publish_conversation_event_payload(event)
            return web.json_response(merged)
        except asyncio.TimeoutError:
            return web.json_response({"error": "Conversation store timed out"}, status=504)
        except Exception as exc:
            return web.json_response({"error": "Conversation update failed", "detail": str(exc)}, status=500)
'''
    text, count = re.subn(
        r'(?s)^    async def _handle_put_hub_conversation\(self, request: "web.Request"\) -> "web.Response":\n.*?(?=^    async def )',
        lambda _: conversation_put.rstrip() + "\n\n", text, count=1, flags=re.MULTILINE,
    )
    if count:
        changes.append("serialized validated conversation PUT")

    conversation_import = r'''    async def _handle_post_hub_conversations_import(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        try:
            body = await request.json()
        except Exception as exc:
            return web.json_response({"error": "Invalid JSON body", "detail": str(exc)}, status=400)
        if not isinstance(body, dict):
            return web.json_response({"error": "JSON body must be an object"}, status=400)
        incoming = body.get("items") if isinstance(body.get("items"), list) else None
        if incoming is None:
            incoming = _hermes_hub_extract_backup_conversations(body)
        if not isinstance(incoming, list):
            return web.json_response({"error": "Conversation items must be an array"}, status=400)
        try:
            loop = asyncio.get_running_loop()
            operation = await asyncio.wait_for(
                loop.run_in_executor(_hermes_hub_io_executor(), _hermes_hub_conversation_io, "import", incoming),
                timeout=_hermes_hub_env_int("HERMES_HUB_STORE_TIMEOUT_SECONDS", 30, 2, 300),
            )
            merged, event = operation
            _hermes_hub_publish_conversation_event_payload(event)
            return web.json_response(merged)
        except asyncio.TimeoutError:
            return web.json_response({"error": "Conversation import timed out"}, status=504)
        except Exception as exc:
            return web.json_response({"error": "Conversation import failed", "detail": str(exc)}, status=500)
'''
    text, count = re.subn(
        r'(?s)^    async def _handle_post_hub_conversations_import\(self, request: "web.Request"\) -> "web.Response":\n.*?(?=^    async def )',
        lambda _: conversation_import.rstrip() + "\n\n", text, count=1, flags=re.MULTILINE,
    )
    if count:
        changes.append("serialized validated conversation import")

    conversation_delete = r'''    async def _handle_delete_hub_conversation(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        conversation_id = str(request.match_info.get("conversation_id") or "").strip()
        if not conversation_id:
            return web.json_response({"error": "Conversation id is required"}, status=400)
        try:
            loop = asyncio.get_running_loop()
            operation = await asyncio.wait_for(
                loop.run_in_executor(_hermes_hub_io_executor(), _hermes_hub_conversation_io, "delete", conversation_id),
                timeout=_hermes_hub_env_int("HERMES_HUB_STORE_TIMEOUT_SECONDS", 30, 2, 300),
            )
            deleted, event = operation
            _hermes_hub_publish_conversation_event_payload(event)
            return web.json_response(deleted)
        except asyncio.TimeoutError:
            return web.json_response({"error": "Conversation delete timed out"}, status=504)
        except Exception as exc:
            return web.json_response({"error": "Conversation delete failed", "detail": str(exc)}, status=500)
'''
    text, count = re.subn(
        r'(?s)^    async def _handle_delete_hub_conversation\(self, request: "web.Request"\) -> "web.Response":\n.*?(?=^    async def )',
        lambda _: conversation_delete.rstrip() + "\n\n", text, count=1, flags=re.MULTILINE,
    )
    if count:
        changes.append("serialized conversation delete")

    conversation_events = r'''    async def _handle_get_hub_conversations_events(self, request: "web.Request") -> "web.StreamResponse":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        import json as _json

        queue = asyncio.Queue(maxsize=1)
        response = web.StreamResponse(status=200, headers={"Content-Type": "text/event-stream", "Cache-Control": "no-cache", "Connection": "keep-alive"})
        registered = False
        try:
            await response.prepare(request)
            _hermes_hub_conversation_event_subscribers.add(queue)
            registered = True
            await response.write(b": connected\n\n")
            loop = asyncio.get_running_loop()
            snapshot = await asyncio.wait_for(
                loop.run_in_executor(_hermes_hub_io_executor(), _hermes_hub_conversation_io, "snapshot", None),
                timeout=_hermes_hub_env_int("HERMES_HUB_STORE_TIMEOUT_SECONDS", 30, 2, 300),
            )
            queue.put_nowait(snapshot)
            while True:
                try:
                    payload = await asyncio.wait_for(queue.get(), timeout=25)
                except asyncio.TimeoutError:
                    await response.write(b": keepalive\n\n")
                    continue
                data = _json.dumps(payload, ensure_ascii=False)
                await response.write(f"event: conversations.updated\ndata: {data}\n\n".encode("utf-8"))
        except asyncio.CancelledError:
            raise
        except Exception:
            pass
        finally:
            if registered:
                _hermes_hub_conversation_event_subscribers.discard(queue)
        return response
'''
    text, count = re.subn(
        r'(?s)^    async def _handle_get_hub_conversations_events\(self, request: "web.Request"\) -> "web.StreamResponse":\n.*?(?=^    async def )',
        lambda _: conversation_events.rstrip() + "\n\n", text, count=1, flags=re.MULTILINE,
    )
    if count:
        changes.append("bounded leak-free conversation SSE")

    publisher = r'''def _hermes_hub_publish_conversation_event_payload(payload: Dict[str, Any]) -> None:
    if not _hermes_hub_conversation_event_subscribers:
        return
    dead = []
    for queue in list(_hermes_hub_conversation_event_subscribers):
        try:
            if queue.full():
                queue.get_nowait()
            queue.put_nowait(payload)
        except Exception:
            dead.append(queue)
    for queue in dead:
        _hermes_hub_conversation_event_subscribers.discard(queue)


def _hermes_hub_publish_conversation_event(reason: str, result: Optional[Dict[str, Any]] = None) -> None:
    _hermes_hub_publish_conversation_event_payload(_hermes_hub_conversation_event_payload(reason, result))
'''
    if "def _hermes_hub_publish_conversation_event_payload(" not in text:
        text, count = re.subn(
            r'(?s)def _hermes_hub_publish_conversation_event\(reason: str, result: Optional\[Dict\[str, Any\]\] = None\) -> None:\n.*?(?=\n\ndef _hermes_hub_number)',
            lambda _: publisher.rstrip(), text, count=1,
        )
        if count:
            changes.append("coalescing conversation event queues")

    replacements = {
        "        return web.json_response(_collect_hardware_snapshot())": (
            "        loop = asyncio.get_running_loop()\n"
            "        snapshot = await asyncio.wait_for(loop.run_in_executor(_hermes_hub_io_executor(), _hermes_hub_cached_hardware_snapshot), timeout=10)\n"
            "        return web.json_response(snapshot)"
        ),
        "        return web.json_response(_hermes_hub_video_library_payload(request))": (
            "        loop = asyncio.get_running_loop()\n"
            "        payload = await asyncio.wait_for(loop.run_in_executor(_hermes_hub_io_executor(), _hermes_hub_video_library_payload, request), timeout=15)\n"
            "        return web.json_response(payload)"
        ),
        "        return web.json_response(_hermes_hub_news_library_payload(request))": (
            "        loop = asyncio.get_running_loop()\n"
            "        payload = await asyncio.wait_for(loop.run_in_executor(_hermes_hub_io_executor(), _hermes_hub_news_library_payload, request), timeout=15)\n"
            "        return web.json_response(payload)"
        ),
    }
    for old, new in replacements.items():
        if old in text:
            text = text.replace(old, new)
            changes.append("offload blocking support endpoint")

    permissive_json = (
        "        try:\n"
        "            body = await request.json()\n"
        "        except Exception:\n"
        "            body = {}\n"
    )
    strict_json = (
        "        try:\n"
        "            body = await request.json()\n"
        "        except Exception as exc:\n"
        "            return web.json_response({\"error\": \"Invalid JSON body\", \"detail\": str(exc)}, status=400)\n"
        "        if not isinstance(body, dict):\n"
        "            return web.json_response({\"error\": \"JSON body must be an object\"}, status=400)\n"
    )
    if permissive_json in text:
        text = text.replace(permissive_json, strict_json)
        changes.append("strict JSON validation for hub mutations")

    old_tts = "audio = await loop.run_in_executor(_hermes_hub_kokoro_executor(), _hermes_hub_kokoro_speech_bytes, text, voice, lang, speed)"
    if old_tts in text:
        text = text.replace(
            old_tts,
            "audio = await asyncio.wait_for(loop.run_in_executor(_hermes_hub_kokoro_executor(), _hermes_hub_kokoro_speech_bytes, text, voice, lang, speed), timeout=_hermes_hub_env_int(\"HERMES_KOKORO_TTS_TIMEOUT_SECONDS\", 180, 10, 900))",
            1,
        )
        changes.append("bounded TTS handler timeout")

    speed_anchor = (
        "        except Exception:\n"
        "            speed = 1.0\n"
        "        try:\n"
        "            loop = asyncio.get_running_loop()"
    )
    if speed_anchor in text:
        text = text.replace(
            speed_anchor,
            "        except Exception:\n"
            "            return web.json_response({\"error\": \"Invalid speech speed\"}, status=400)\n"
            "        import math as _math\n"
            "        if not _math.isfinite(speed) or speed < 0.5 or speed > 2.0:\n"
            "            return web.json_response({\"error\": \"Speech speed must be between 0.5 and 2.0\"}, status=400)\n"
            "        try:\n"
            "            loop = asyncio.get_running_loop()",
            1,
        )
        changes.append("strict finite TTS speed validation")

    kokoro_preload = "_hermes_hub_kokoro_executor().submit(_hermes_hub_kokoro_speech_bytes, 'ok', voice, 'it', 1.08).result()"
    if kokoro_preload in text:
        text = text.replace(
            kokoro_preload,
            "_hermes_hub_kokoro_executor().submit(_hermes_hub_kokoro_speech_bytes, 'ok', voice, 'it', 1.08).result(timeout=_hermes_hub_env_int('HERMES_KOKORO_PRELOAD_TIMEOUT_SECONDS', 120, 10, 900))",
            1,
        )
        changes.append("bounded Kokoro preload timeout")

    preload_whisper = r'''def _hermes_hub_preload_whisper():
    try:
        enabled = str(os.environ.get("HERMES_WHISPER_PRELOAD", "1")).lower() not in {"0", "false", "no"}
        if enabled:
            _hermes_hub_stt_executor().submit(_hermes_hub_ensure_whisper_model)
            print("Whisper preload scheduled on dedicated executor.")
    except Exception as exc:
        print("Failed to schedule Whisper preload:", exc)


_hermes_hub_preload_whisper()
'''
    text, count = re.subn(
        r'(?s)def _hermes_hub_preload_whisper\(\):\n.*?_hermes_hub_preload_whisper\(\)\n?',
        lambda _: preload_whisper,
        text,
        count=1,
    )
    if count:
        changes.append("non-blocking configurable Whisper preload")

    library_replacements = {
        'for path in sorted(root.rglob("*"), key=lambda p: p.stat().st_mtime if p.exists() else 0, reverse=True):':
            'for path in _hermes_hub_library_files(root, extensions):',
        'candidates = sorted(root.rglob("*"), key=lambda p: p.stat().st_mtime if p.exists() else 0, reverse=True)':
            'candidates = _hermes_hub_library_files(root, extensions)',
    }
    for old, new in library_replacements.items():
        if old in text:
            text = text.replace(old, new)
            changes.append("bounded media library scan")

    merge_tie = '        if _hermes_hub_number(conv.get("updatedAt")) >= _hermes_hub_number(existing.get("updatedAt")):\n'
    if merge_tie in text:
        text = text.replace(
            merge_tie,
            '        incoming_updated = _hermes_hub_number(conv.get("updatedAt"))\n'
            '        existing_updated = _hermes_hub_number(existing.get("updatedAt"))\n'
            '        tombstone_wins_tie = incoming_updated == existing_updated and bool(conv.get("deletedAt")) and not bool(existing.get("deletedAt"))\n'
            '        if incoming_updated > existing_updated or tombstone_wins_tie:\n',
            1,
        )
        changes.append("deterministic tombstone conflict tie")

    return text, changes if text != initial_text else []


def _upsert_versioned_block(
    text: str,
    *,
    begin: str,
    end: str,
    block: str,
    anchor: str,
    label: str,
) -> tuple[str, bool]:
    """Insert or upgrade one generated block while failing closed on torn markers."""
    begin_count = text.count(begin)
    end_count = text.count(end)
    if begin_count != end_count or begin_count > 1:
        raise PatchError(f"{label} markers are missing, duplicated, or unbalanced")

    normalized = block.rstrip("\n")
    if begin_count == 1:
        start = text.index(begin)
        stop = text.index(end, start) + len(end)
        current = text[start:stop]
        if current == normalized:
            return text, False
        return text[:start] + normalized + text[stop:], True

    if text.count(anchor) != 1:
        raise PatchError(f"Patch anchor not found or ambiguous: {label}")
    return text.replace(anchor, normalized + "\n" + anchor, 1), True


def _patch_jarvis_v1(text: str) -> tuple[str, list[str]]:
    """Inject the bounded, ephemeral Jarvis HTTP/SSE runtime into api_server.py."""
    changes: list[str] = []
    runtime = r'''# HERMES_HUB_JARVIS_RUNTIME_BEGIN
# HERMES_HUB_JARVIS_MODE_V1
def _hermes_hub_jarvis_env_bool(name: str, default: bool = False) -> bool:
    raw = str(os.environ.get(name, "1" if default else "0")).strip().lower()
    return raw in {"1", "true", "yes", "on"}


def _hermes_hub_jarvis_env_int(
    name: str,
    default: int,
    minimum: int,
    maximum: int,
) -> int:
    try:
        value = int(os.environ.get(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(maximum, value))


def _hermes_hub_jarvis_env_float(
    name: str,
    default: float,
    minimum: float,
    maximum: float,
) -> float:
    import math as _math

    try:
        value = float(os.environ.get(name, str(default)))
    except (TypeError, ValueError):
        value = default
    if not _math.isfinite(value):
        value = default
    return max(minimum, min(maximum, value))


def _hermes_hub_jarvis_model_config(kind: str) -> Dict[str, Any]:
    single_model = _hermes_hub_jarvis_env_bool("HERMES_JARVIS_SINGLE_MODEL", True)
    effective_kind = "reasoning" if single_model and kind == "fast" else kind
    prefix = "HERMES_JARVIS_FAST" if effective_kind == "fast" else "HERMES_JARVIS_REASONING"
    dedicated_base = str(os.environ.get(f"{prefix}_BASE_URL", "")).strip().rstrip("/")
    shared_base = str(os.environ.get("HERMES_INFERENCE_BASE_URL", "")).strip().rstrip("/")
    dedicated_model = str(os.environ.get(f"{prefix}_MODEL", "")).strip()
    shared_model = str(os.environ.get("HERMES_INFERENCE_MODEL", "")).strip()
    model = dedicated_model if effective_kind == "fast" else (dedicated_model or shared_model)
    return {
        "kind": kind,
        "effective_kind": effective_kind,
        "single_model": single_model,
        "base_url": dedicated_base or shared_base,
        "api_key": str(os.environ.get(f"{prefix}_API_KEY", "")).strip(),
        "model": model,
        "provider": str(os.environ.get(f"{prefix}_PROVIDER", "")).strip(),
        "dedicated": bool(dedicated_base or dedicated_model),
    }


def _hermes_hub_jarvis_capabilities() -> Dict[str, Any]:
    enabled = _hermes_hub_jarvis_env_bool("HERMES_JARVIS_ENABLED", False)
    mock_mode = _hermes_hub_jarvis_env_bool("HERMES_JARVIS_MOCK_MODE", False)
    fast = _hermes_hub_jarvis_model_config("fast")
    reasoning = _hermes_hub_jarvis_model_config("reasoning")
    fast_configured = mock_mode or bool(fast["base_url"] and fast["model"])
    reasoning_configured = mock_mode or bool(reasoning["base_url"] and reasoning["model"])
    single_model = _hermes_hub_jarvis_env_bool("HERMES_JARVIS_SINGLE_MODEL", True)
    return {
        "enabled": enabled,
        "vision": enabled,
        "proactive_events": enabled and fast_configured and reasoning_configured,
        "transport": "http+sse",
        "architecture": "reactor-v1",
        "perception_memory": "ephemeral",
        "incremental_summarizer": True,
        "single_model": single_model,
        "direct_questions_route": "adaptive_single_model" if single_model else "adaptive",
        "observer_model_configured": fast_configured,
        "fast_model_configured": fast_configured,
        "reasoning_model_configured": reasoning_configured,
        "mock_mode": mock_mode,
        "max_frame_bytes": _hermes_hub_jarvis_env_int(
            "HERMES_JARVIS_MAX_FRAME_BYTES", 1_000_000, 65_536, 16_777_216
        ),
        "max_session_seconds": _hermes_hub_jarvis_env_int(
            "HERMES_JARVIS_SESSION_TTL_SECONDS", 3600, 60, 86_400
        ),
        "modes": ["questions_only", "assistive", "proactive"],
        "endpoints": {
            "sessions": "/v1/jarvis/sessions",
            "session": "/v1/jarvis/sessions/{session_id}",
            "frames": "/v1/jarvis/sessions/{session_id}/frames",
            "turns": "/v1/jarvis/sessions/{session_id}/turns",
            "events": "/v1/jarvis/sessions/{session_id}/events",
            "feedback": "/v1/jarvis/sessions/{session_id}/feedback",
        },
    }


def _hermes_hub_jarvis_error(code: str, message: str, status: int = 400) -> "web.Response":
    return web.json_response({"error": {"code": code, "message": message}}, status=status)


def _hermes_hub_jarvis_safe_error(exc: BaseException) -> str:
    try:
        value = redact_sensitive_text(str(exc))
    except Exception:
        value = str(exc)
    value = " ".join(value.replace("\r", " ").replace("\n", " ").split())
    return (value or type(exc).__name__)[:500]


def _hermes_hub_jarvis_runtime(adapter: Any) -> Dict[str, Any]:
    runtime = getattr(adapter, "_hermes_hub_jarvis_runtime_v1", None)
    if runtime is not None:
        return runtime
    from collections import deque as _deque

    runtime = {
        "sessions": {},
        "lock": asyncio.Lock(),
        "fast_sem": asyncio.Semaphore(
            _hermes_hub_jarvis_env_int("HERMES_JARVIS_MAX_CONCURRENT_FAST", 2, 1, 16)
        ),
        "reasoning_sem": asyncio.Semaphore(
            _hermes_hub_jarvis_env_int("HERMES_JARVIS_MAX_CONCURRENT_REASONING", 1, 1, 4)
        ),
        "client": None,
        "sweeper": None,
        "recent_metrics": _deque(maxlen=128),
        "closing": False,
    }
    setattr(adapter, "_hermes_hub_jarvis_runtime_v1", runtime)
    return runtime


def _hermes_hub_jarvis_public_session(session: Dict[str, Any]) -> Dict[str, Any]:
    window = session.get("conversation_window") or {}
    now = time.time()
    return {
        "id": session["id"],
        "status": session["status"],
        "mode": session["mode"],
        "goal": session["goal"],
        "view_paused": session["view_paused"],
        "speaking": session["speaking"],
        "created_at": session["created_at"],
        "updated_at": session["updated_at"],
        "expires_at": session["expires_at"],
        "mute_until": session["mute_until"],
        "last_observation": session.get("last_observation"),
        "last_route": session.get("last_route"),
        "last_latency_ms": session.get("last_latency_ms"),
        "frame_count": len(session["frames"]),
        "event_count": len(session["events"]),
        "perception_count": len(session.get("perceptions") or []),
        "short_term_summary": session.get("short_term_summary") or None,
        "summary_version": int(session.get("summary_version") or 0),
        "situation": session.get("last_situation") or None,
        "conversation_window": {
            "active": float(window.get("active_until") or 0.0) > now,
            "awaiting_followup": bool(window.get("awaiting_followup", False)),
            "topic": str(window.get("topic") or "")[:240] or None,
            "turn_count": int(window.get("turn_count") or 0),
        },
    }


def _hermes_hub_jarvis_new_session(body: Dict[str, Any]) -> Dict[str, Any]:
    from collections import OrderedDict as _OrderedDict, deque as _deque

    now = time.time()
    mode = str(body.get("mode") or "assistive").strip().lower()
    if mode not in {"questions_only", "assistive", "proactive"}:
        raise ValueError("invalid_mode")
    goal = str(body.get("goal") or "").strip()[:2000]
    ttl = _hermes_hub_jarvis_env_int(
        "HERMES_JARVIS_SESSION_TTL_SECONDS", 3600, 60, 86_400
    )
    session_id = uuid.uuid4().hex
    return {
        "id": session_id,
        "status": "active",
        "mode": mode,
        "goal": goal,
        "view_paused": bool(body.get("view_paused", False)),
        "speaking": False,
        "created_at": now,
        "updated_at": now,
        "expires_at": now + ttl,
        "mute_until": 0.0,
        "last_intervention_at": 0.0,
        "last_observation": None,
        "last_route": None,
        "last_latency_ms": None,
        "frames": _OrderedDict(),
        "events": _deque(
            maxlen=_hermes_hub_jarvis_env_int("HERMES_JARVIS_MAX_CONTEXT_EVENTS", 64, 8, 512)
        ),
        "transcript": _deque(
            maxlen=_hermes_hub_jarvis_env_int("HERMES_JARVIS_MAX_CONTEXT_EVENTS", 64, 8, 512)
        ),
        "observations": _deque(
            maxlen=_hermes_hub_jarvis_env_int("HERMES_JARVIS_MAX_CONTEXT_EVENTS", 64, 8, 512)
        ),
        "perceptions": _deque(
            maxlen=_hermes_hub_jarvis_env_int("HERMES_JARVIS_MAX_PERCEPTIONS", 128, 16, 1024)
        ),
        "perception_sequence": 0,
        "short_term_summary": "",
        "working_facts": _deque(maxlen=16),
        "summary_cursor": 0,
        "summary_version": 0,
        "summary_failures": 0,
        "summary_retry_after": 0.0,
        "summarizer_task": None,
        "summarizer_generation": 0,
        "summarizer_suspended": False,
        "last_situation": None,
        "conversation_window": {
            "active_until": 0.0,
            "last_user_at": 0.0,
            "last_assistant_at": 0.0,
            "awaiting_followup": False,
            "turn_count": 0,
            "topic": goal[:240],
            "last_trigger": None,
        },
        "interventions": _deque(maxlen=32),
        "feedback_bias": 0.0,
        "feedback": _deque(maxlen=64),
        "subscribers": set(),
        "tasks": set(),
        "observer_task": None,
        "observer_generation": 0,
        "event_sequence": 0,
        "dedupe": {},
    }


def _hermes_hub_jarvis_publish(
    session: Dict[str, Any], event_type: str, **payload: Any
) -> Dict[str, Any]:
    session["event_sequence"] += 1
    event = {
        "id": str(session["event_sequence"]),
        "type": event_type,
        "session_id": session["id"],
        "timestamp": time.time(),
    }
    event.update(payload)
    session["events"].append(event)
    for queue in list(session["subscribers"]):
        try:
            if queue.full():
                queue.get_nowait()
            queue.put_nowait(event)
        except Exception:
            session["subscribers"].discard(queue)
    return event


async def _hermes_hub_jarvis_cancel_task(task: Any, agent_ref: Optional[List[Any]] = None) -> None:
    if task is None or task.done():
        return
    if agent_ref and agent_ref[0] is not None:
        try:
            agent_ref[0].interrupt()
        except Exception:
            pass
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass
    except Exception:
        pass


async def _hermes_hub_jarvis_drop_session(
    runtime: Dict[str, Any], session_id: str, reason: str
) -> bool:
    session = runtime["sessions"].pop(session_id, None)
    if session is None:
        return False
    session["status"] = "ended"
    session["updated_at"] = time.time()
    _hermes_hub_jarvis_publish(session, "session.ended", reason=reason)
    tasks = set(session["tasks"])
    observer = session.get("observer_task")
    if observer is not None:
        tasks.add(observer)
    summarizer = session.get("summarizer_task")
    if summarizer is not None:
        tasks.add(summarizer)
    current = asyncio.current_task()
    tasks.discard(current)
    for task in tasks:
        if not task.done():
            task.cancel()
    if tasks:
        await asyncio.gather(*tasks, return_exceptions=True)
    session["tasks"].clear()
    session["frames"].clear()
    session["transcript"].clear()
    session["observations"].clear()
    session["perceptions"].clear()
    session["interventions"].clear()
    session["short_term_summary"] = ""
    session["working_facts"].clear()
    session["last_situation"] = None
    for queue in list(session["subscribers"]):
        try:
            if queue.full():
                queue.get_nowait()
            queue.put_nowait(None)
        except Exception:
            pass
    session["subscribers"].clear()
    return True


async def _hermes_hub_jarvis_sweep(adapter: Any) -> None:
    runtime = _hermes_hub_jarvis_runtime(adapter)
    interval = _hermes_hub_jarvis_env_int("HERMES_JARVIS_SWEEP_SECONDS", 15, 2, 300)
    try:
        while not runtime["closing"]:
            await asyncio.sleep(interval)
            now = time.time()
            expired = [
                session_id
                for session_id, session in list(runtime["sessions"].items())
                if session["expires_at"] <= now or session["status"] == "ended"
            ]
            for session_id in expired:
                await _hermes_hub_jarvis_drop_session(runtime, session_id, "ttl_expired")
            for session in runtime["sessions"].values():
                _hermes_hub_jarvis_prune_frames(session, now)
    except asyncio.CancelledError:
        pass


def _hermes_hub_jarvis_ensure_sweeper(adapter: Any) -> None:
    runtime = _hermes_hub_jarvis_runtime(adapter)
    task = runtime.get("sweeper")
    if task is None or task.done():
        runtime["sweeper"] = asyncio.create_task(_hermes_hub_jarvis_sweep(adapter))


async def _hermes_hub_jarvis_shutdown(adapter: Any) -> None:
    runtime = getattr(adapter, "_hermes_hub_jarvis_runtime_v1", None)
    if runtime is None:
        return
    runtime["closing"] = True
    sweeper = runtime.get("sweeper")
    if sweeper is not None and not sweeper.done():
        sweeper.cancel()
        await asyncio.gather(sweeper, return_exceptions=True)
    for session_id in list(runtime["sessions"]):
        await _hermes_hub_jarvis_drop_session(runtime, session_id, "gateway_shutdown")
    client = runtime.get("client")
    if client is not None and not client.closed:
        await client.close()
    runtime["client"] = None


def _hermes_hub_jarvis_prune_frames(session: Dict[str, Any], now: Optional[float] = None) -> None:
    now = now or time.time()
    ttl = _hermes_hub_jarvis_env_int("HERMES_JARVIS_FRAME_TTL_SECONDS", 20, 2, 300)
    stale = [
        frame_id
        for frame_id, frame in session["frames"].items()
        if now - float(frame["received_at"]) > ttl
    ]
    for frame_id in stale:
        session["frames"].pop(frame_id, None)
    max_frames = _hermes_hub_jarvis_env_int("HERMES_JARVIS_MAX_FRAMES_PER_SESSION", 3, 1, 12)
    while len(session["frames"]) > max_frames:
        session["frames"].popitem(last=False)


async def _hermes_hub_jarvis_json_body(
    request: "web.Request", max_bytes: int = 32_768
) -> Dict[str, Any]:
    length = request.content_length
    if length is not None and length > max_bytes:
        raise OverflowError("json_payload_too_large")
    data = bytearray()
    async for chunk in request.content.iter_chunked(8192):
        data.extend(chunk)
        if len(data) > max_bytes:
            raise OverflowError("json_payload_too_large")
    if not data:
        return {}
    try:
        body = json.loads(bytes(data).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("invalid_json") from exc
    if not isinstance(body, dict):
        raise ValueError("invalid_json_object")
    return body


async def _hermes_hub_jarvis_frame_body(
    request: "web.Request",
) -> tuple[bytes, str]:
    allowed = {"image/jpeg", "image/png", "image/webp"}
    max_bytes = _hermes_hub_jarvis_env_int(
        "HERMES_JARVIS_MAX_FRAME_BYTES", 1_000_000, 65_536, 16_777_216
    )
    length = request.content_length
    if length is not None and length > max_bytes + 65_536:
        raise OverflowError("frame_too_large")
    content_type = str(request.content_type or "").lower()
    payload = bytearray()
    mime_type = content_type
    if content_type.startswith("multipart/"):
        reader = await request.multipart()
        field = await reader.next()
        found = False
        while field is not None:
            if field.name in {"frame", "file", "image"}:
                if found:
                    raise ValueError("multiple_frames")
                found = True
                mime_type = str(field.headers.get("Content-Type", "image/jpeg")).lower()
                while True:
                    chunk = await field.read_chunk(size=64 * 1024)
                    if not chunk:
                        break
                    payload.extend(chunk)
                    if len(payload) > max_bytes:
                        raise OverflowError("frame_too_large")
            field = await reader.next()
    else:
        async for chunk in request.content.iter_chunked(64 * 1024):
            payload.extend(chunk)
            if len(payload) > max_bytes:
                raise OverflowError("frame_too_large")
    if not payload:
        raise ValueError("empty_frame")
    if mime_type not in allowed:
        raise TypeError("unsupported_frame_type")
    return bytes(payload), mime_type


def _hermes_hub_jarvis_frame_data_url(frame: Dict[str, Any]) -> str:
    import base64 as _base64

    encoded = _base64.b64encode(frame["data"]).decode("ascii")
    return f"data:{frame['mime_type']};base64,{encoded}"


def _hermes_hub_jarvis_parse_json_object(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, list):
        parts = []
        for item in value:
            if isinstance(item, dict) and isinstance(item.get("text"), str):
                parts.append(item["text"])
        value = "".join(parts)
    if not isinstance(value, str):
        raise ValueError("model_output_not_text")
    text_value = value.strip()
    if text_value.startswith("```"):
        first_newline = text_value.find("\n")
        if first_newline < 0 or not text_value.endswith("```"):
            raise ValueError("model_output_invalid_fence")
        text_value = text_value[first_newline + 1 : -3].strip()
    parsed = json.loads(text_value)
    if not isinstance(parsed, dict):
        raise ValueError("model_output_not_object")
    return parsed


def _hermes_hub_jarvis_number(value: Any, field: str) -> float:
    import math as _math

    try:
        number = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"invalid_{field}") from exc
    if not _math.isfinite(number) or number < 0.0 or number > 1.0:
        raise ValueError(f"invalid_{field}")
    return number


def _hermes_hub_jarvis_validate_fast_result(value: Any) -> Dict[str, Any]:
    raw = _hermes_hub_jarvis_parse_json_object(value)
    action = str(raw.get("action") or "").strip()
    allowed = {"ignore", "respond_simple", "ask_user", "escalate", "urgent_candidate"}
    if action not in allowed:
        raise ValueError("invalid_action")
    reply = raw.get("reply")
    if reply is not None and not isinstance(reply, str):
        raise ValueError("invalid_reply")
    reply = reply.strip()[:800] if isinstance(reply, str) else None
    if action in {"respond_simple", "ask_user"} and not reply:
        raise ValueError("missing_reply")
    frame_ids = raw.get("recommended_frame_ids") or []
    if not isinstance(frame_ids, list):
        raise ValueError("invalid_recommended_frame_ids")
    return {
        "action": action,
        "observation": str(raw.get("observation") or "").strip()[:1000],
        "reason": str(raw.get("reason") or "").strip()[:1000],
        "confidence": _hermes_hub_jarvis_number(raw.get("confidence"), "confidence"),
        "importance": _hermes_hub_jarvis_number(raw.get("importance"), "importance"),
        "urgency": _hermes_hub_jarvis_number(raw.get("urgency"), "urgency"),
        "utility": _hermes_hub_jarvis_number(
            raw.get("utility", raw.get("importance")), "utility"
        ),
        "reply": reply,
        "recommended_frame_ids": [str(item)[:128] for item in frame_ids[:3]],
    }


def _hermes_hub_jarvis_validate_verification(value: Any) -> Dict[str, Any]:
    raw = _hermes_hub_jarvis_parse_json_object(value)
    speak = raw.get("speak")
    if not isinstance(speak, bool):
        raise ValueError("invalid_speak")
    text = raw.get("text")
    if text is not None and not isinstance(text, str):
        raise ValueError("invalid_text")
    text = text.strip()[:1000] if isinstance(text, str) else None
    if speak and not text:
        raise ValueError("missing_text")
    return {
        "speak": speak,
        "text": text,
        "confidence": _hermes_hub_jarvis_number(raw.get("confidence"), "confidence"),
        "importance": _hermes_hub_jarvis_number(raw.get("importance"), "importance"),
        "urgency": _hermes_hub_jarvis_number(raw.get("urgency"), "urgency"),
        "utility": _hermes_hub_jarvis_number(
            raw.get("utility", raw.get("importance")), "utility"
        ),
        "event_key": str(raw.get("event_key") or "").strip().lower()[:200],
    }


def _hermes_hub_jarvis_openai_endpoint(base_url: str) -> str:
    base = str(base_url or "").strip().rstrip("/")
    if not base:
        raise ValueError("model_base_url_missing")
    if base.endswith("/chat/completions"):
        return base
    if base.endswith("/v1"):
        return base + "/chat/completions"
    return base + "/v1/chat/completions"


async def _hermes_hub_jarvis_http_json(
    runtime: Dict[str, Any], config: Dict[str, Any], payload: Dict[str, Any], timeout_seconds: float
) -> Dict[str, Any]:
    import aiohttp as _aiohttp

    client = runtime.get("client")
    if client is None or client.closed:
        client = _aiohttp.ClientSession()
        runtime["client"] = client
    headers = {"Content-Type": "application/json"}
    if config.get("api_key"):
        headers["Authorization"] = f"Bearer {config['api_key']}"
    endpoint = _hermes_hub_jarvis_openai_endpoint(config["base_url"])
    timeout = _aiohttp.ClientTimeout(total=timeout_seconds)
    async with client.post(endpoint, json=payload, headers=headers, timeout=timeout) as response:
        data = bytearray()
        async for chunk in response.content.iter_chunked(16 * 1024):
            data.extend(chunk)
            if len(data) > 512 * 1024:
                raise OverflowError("model_response_too_large")
        if response.status < 200 or response.status >= 300:
            raise RuntimeError(f"model_http_{response.status}")
    try:
        decoded = json.loads(bytes(data).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("invalid_model_response") from exc
    if not isinstance(decoded, dict):
        raise ValueError("invalid_model_response")
    return decoded


def _hermes_hub_jarvis_fast_content(response: Dict[str, Any]) -> Any:
    choices = response.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
        raise ValueError("missing_model_choice")
    message = choices[0].get("message")
    if not isinstance(message, dict):
        raise ValueError("missing_model_message")
    return message.get("content")


def _hermes_hub_jarvis_record_perception(
    session: Dict[str, Any],
    source: str,
    kind: str,
    text: str,
    *,
    importance: float = 0.0,
    urgency: float = 0.0,
    frame_id: Optional[str] = None,
    summarizable: bool = True,
) -> Dict[str, Any]:
    session["perception_sequence"] = int(session.get("perception_sequence") or 0) + 1
    item = {
        "id": session["perception_sequence"],
        "timestamp": time.time(),
        "source": str(source or "system")[:32],
        "kind": str(kind or "event")[:48],
        "text": " ".join(str(text or "").split())[:1000],
        "importance": max(0.0, min(1.0, float(importance))),
        "urgency": max(0.0, min(1.0, float(urgency))),
        "frame_id": str(frame_id or "")[:128] or None,
        "summarizable": bool(summarizable),
    }
    session["perceptions"].append(item)
    session["updated_at"] = time.time()
    return item


def _hermes_hub_jarvis_touch_conversation(
    session: Dict[str, Any], role: str, text: str, now: Optional[float] = None
) -> None:
    now = now or time.time()
    window = session["conversation_window"]
    duration = _hermes_hub_jarvis_env_int(
        "HERMES_JARVIS_CONVERSATION_WINDOW_SECONDS", 120, 15, 1800
    )
    window["active_until"] = now + duration
    if role == "user":
        window["last_user_at"] = now
        window["awaiting_followup"] = False
        window["turn_count"] = int(window.get("turn_count") or 0) + 1
        if not str(window.get("topic") or "").strip():
            window["topic"] = " ".join(str(text or "").split())[:240]
    elif role == "assistant":
        window["last_assistant_at"] = now
        window["awaiting_followup"] = True


def _hermes_hub_jarvis_set_trigger(
    session: Dict[str, Any], trigger: Dict[str, Any]
) -> Dict[str, Any]:
    normalized = {
        "type": str(trigger.get("type") or "event")[:64],
        "source": str(trigger.get("source") or "system")[:32],
        "target": str(trigger.get("target") or session.get("goal") or "")[:300],
        "reason": str(trigger.get("reason") or "")[:500],
        "text": str(trigger.get("text") or "")[:1000],
        "confidence": max(0.0, min(1.0, float(trigger.get("confidence") or 0.0))),
        "importance": max(0.0, min(1.0, float(trigger.get("importance") or 0.0))),
        "urgency": max(0.0, min(1.0, float(trigger.get("urgency") or 0.0))),
        "timestamp": time.time(),
    }
    description = normalized["reason"] or normalized["text"] or normalized["type"]
    session["last_situation"] = description[:500]
    session["conversation_window"]["last_trigger"] = normalized
    return normalized


def _hermes_hub_jarvis_conversation_payload(session: Dict[str, Any]) -> Dict[str, Any]:
    window = session["conversation_window"]
    return {
        "active": float(window.get("active_until") or 0.0) > time.time(),
        "awaiting_followup": bool(window.get("awaiting_followup", False)),
        "topic": str(window.get("topic") or "")[:240] or None,
        "turn_count": int(window.get("turn_count") or 0),
    }


def _hermes_hub_jarvis_reactor_context(
    session: Dict[str, Any], trigger: Optional[Dict[str, Any]] = None
) -> Dict[str, Any]:
    perceptions = []
    for item in list(session["perceptions"])[-12:]:
        if not isinstance(item, dict) or not item.get("text"):
            continue
        perceptions.append(
            {
                "id": item.get("id"),
                "source": item.get("source"),
                "kind": item.get("kind"),
                "text": str(item.get("text") or "")[:500],
                "importance": item.get("importance"),
                "urgency": item.get("urgency"),
            }
        )
    dialogue = [
        {"role": str(item.get("role") or "")[:16], "content": str(item.get("content") or "")[:700]}
        for item in list(session["transcript"])[-8:]
        if isinstance(item, dict)
    ]
    helpful = sum(1 for item in session["feedback"] if item.get("helpful") is True)
    unhelpful = sum(1 for item in session["feedback"] if item.get("helpful") is False)
    situation = trigger or session["conversation_window"].get("last_trigger") or {
        "type": "observe",
        "source": "system",
        "reason": session.get("last_situation") or "No explicit trigger.",
    }
    return {
        "identity": "Hermes, assistente locale dell'utente",
        "goal": session["goal"],
        "mode": session["mode"],
        "short_term_summary": session.get("short_term_summary") or None,
        "working_facts": list(session.get("working_facts") or []),
        "conversation_window": _hermes_hub_jarvis_conversation_payload(session),
        "recent_dialogue": dialogue,
        "recent_perceptions": perceptions,
        "feedback": {"helpful": helpful, "unhelpful": unhelpful},
        "situation": situation,
    }


def _hermes_hub_jarvis_reactor_system_prompt(purpose: str) -> str:
    base = (
        "You are Hermes, the user's private local assistant. Remain coherent over time, notice relevant changes, "
        "and prefer silence over low-value interruption. Treat session observations as temporary context, not durable "
        "facts. Never invent unseen details. The dynamic SITUATION is the highest-priority current reason for acting."
    )
    if purpose == "observer":
        return base + (
            " Act as Perceptor and Senser. Return one JSON object only; no markdown. Allowed action: ignore, "
            "respond_simple, ask_user, escalate, urgent_candidate. Required keys: action, observation, reason, "
            "confidence, importance, urgency, utility, reply, recommended_frame_ids. All scores are 0..1. For passive "
            "observation respond_simple is forbidden. Stable or merely descriptive scenes must be ignore. For a direct "
            "question, respond_simple only for obvious color, count, position, or clearly readable text. Escalate "
            "ambiguity, safety, money, devices, memory, tools, comparison, diagnosis, or multi-step work."
        )
    if purpose == "summarizer":
        return base + (
            " Act as short-term memory summarizer. Return one JSON object only with summary, topic, open_loop and "
            "notable_facts. Preserve unresolved tasks, corrections, changes and user intent. Drop repetition and visual "
            "noise. open_loop MUST be the JSON boolean true or false, never a string or null. summary and topic MUST "
            "be strings. notable_facts MUST be a JSON array with at most eight short strings."
        )
    return base + (
        " Act as Reactor. Use original images directly. Put important information first; normally one or two spoken "
        "sentences. Use Hermes tools and durable memory only when needed."
    )


def _hermes_hub_jarvis_reactor_prompt(
    session: Dict[str, Any],
    purpose: str,
    *,
    question: Optional[str] = None,
    trigger: Optional[Dict[str, Any]] = None,
) -> str:
    context = _hermes_hub_jarvis_reactor_context(session, trigger)
    situation = context.pop("situation")
    return (
        f"Purpose: {purpose}\nQuestion: {question or ''}\n"
        f"CONTEXT: {json.dumps(context, ensure_ascii=False, separators=(',', ':'))}\n"
        f"SITUATION (highest priority): {json.dumps(situation, ensure_ascii=False, separators=(',', ':'))}"
    )


def _hermes_hub_jarvis_context_text(session: Dict[str, Any]) -> str:
    return json.dumps(
        _hermes_hub_jarvis_reactor_context(session),
        ensure_ascii=False,
        separators=(",", ":"),
    )


def _hermes_hub_jarvis_semantic_similarity(left: str, right: str) -> float:
    import re as _re, unicodedata as _unicodedata

    def _terms(value: str) -> set[str]:
        normalized = _unicodedata.normalize("NFKD", str(value or "")).encode("ascii", "ignore").decode("ascii")
        return set(_re.findall(r"[a-z0-9]+", normalized.lower()))

    left_terms, right_terms = _terms(left), _terms(right)
    if not left_terms or not right_terms:
        return 0.0
    return len(left_terms & right_terms) / len(left_terms | right_terms)


def _hermes_hub_jarvis_validate_summary(value: Any) -> Dict[str, Any]:
    raw = _hermes_hub_jarvis_parse_json_object(value)
    summary = " ".join(str(raw.get("summary") or "").split())[:1600]
    if not summary:
        raise ValueError("missing_summary")
    open_loop = raw.get("open_loop", False)
    if not isinstance(open_loop, bool):
        raise ValueError("invalid_open_loop")
    facts = raw.get("notable_facts") or []
    if not isinstance(facts, list):
        raise ValueError("invalid_notable_facts")
    return {
        "summary": summary,
        "topic": " ".join(str(raw.get("topic") or "").split())[:240],
        "open_loop": open_loop,
        "notable_facts": [" ".join(str(item).split())[:240] for item in facts[:8] if str(item).strip()],
    }


async def _hermes_hub_jarvis_summarizer_call(
    adapter: Any, session: Dict[str, Any], perceptions: List[Dict[str, Any]]
) -> tuple[Dict[str, Any], Dict[str, float]]:
    runtime = _hermes_hub_jarvis_runtime(adapter)
    started = time.perf_counter()
    config = _hermes_hub_jarvis_model_config("fast")
    if not config["base_url"] or not config["model"]:
        raise RuntimeError("summarizer_model_unavailable")
    compact = [
        {"id": item["id"], "source": item["source"], "kind": item["kind"], "text": item["text"]}
        for item in perceptions[-20:]
    ]
    payload = {
        "model": config["model"],
        "messages": [
            {"role": "system", "content": _hermes_hub_jarvis_reactor_system_prompt("summarizer")},
            {
                "role": "user",
                "content": json.dumps(
                    {
                        "previous_summary": session.get("short_term_summary") or None,
                        "current_goal": session["goal"],
                        "new_perceptions": compact,
                    },
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
            },
        ],
        "temperature": 0.0,
        "max_tokens": _hermes_hub_jarvis_env_int(
            "HERMES_JARVIS_SUMMARY_MAX_TOKENS", 256, 96, 768
        ),
        "response_format": {"type": "json_object"},
        "chat_template_kwargs": {"enable_thinking": False},
        "stream": False,
    }
    timeout = _hermes_hub_jarvis_env_float(
        "HERMES_JARVIS_SUMMARY_TIMEOUT_SECONDS", 15.0, 1.0, 120.0
    )
    queued = time.perf_counter()
    async with runtime["fast_sem"]:
        acquired = time.perf_counter()
        response = await _hermes_hub_jarvis_http_json(runtime, config, payload, timeout)
    result = _hermes_hub_jarvis_validate_summary(_hermes_hub_jarvis_fast_content(response))
    return result, {
        "queue_ms": round((acquired - queued) * 1000, 2),
        "model_ms": round((time.perf_counter() - acquired) * 1000, 2),
        "total_ms": round((time.perf_counter() - started) * 1000, 2),
    }


async def _hermes_hub_jarvis_summarize(
    adapter: Any, session: Dict[str, Any], generation: int
) -> None:
    task = asyncio.current_task()
    session["tasks"].add(task)
    try:
        await asyncio.sleep(0.75)
        cursor = int(session.get("summary_cursor") or 0)
        items = [
            item for item in list(session["perceptions"])
            if item.get("summarizable") and int(item.get("id") or 0) > cursor
        ]
        if not items or generation != session.get("summarizer_generation"):
            return
        upto = int(items[-1]["id"])
        result, metrics = await _hermes_hub_jarvis_summarizer_call(adapter, session, items)
        if generation != session.get("summarizer_generation"):
            return
        session["short_term_summary"] = result["summary"]
        session["working_facts"].clear()
        session["working_facts"].extend(result["notable_facts"])
        session["summary_cursor"] = upto
        session["summary_version"] = int(session.get("summary_version") or 0) + 1
        session["summary_failures"] = 0
        session["summary_retry_after"] = 0.0
        window = session["conversation_window"]
        if result["topic"]:
            window["topic"] = result["topic"]
        window["awaiting_followup"] = result["open_loop"]
        _hermes_hub_jarvis_publish(
            session,
            "memory.summary",
            summary=result["summary"],
            topic=result["topic"] or None,
            open_loop=result["open_loop"],
            version=session["summary_version"],
            metrics=metrics,
        )
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        failures = int(session.get("summary_failures") or 0) + 1
        session["summary_failures"] = failures
        session["summary_retry_after"] = time.time() + min(60.0, float(2 ** min(failures, 5)))
        _hermes_hub_jarvis_publish(
            session,
            "memory.summary_failed",
            code="summarizer_failed",
            message=_hermes_hub_jarvis_safe_error(exc),
            retry_after=session["summary_retry_after"],
        )
    finally:
        session["tasks"].discard(task)
        if session.get("summarizer_task") is task:
            session["summarizer_task"] = None
        _hermes_hub_jarvis_schedule_summarizer(adapter, session)


def _hermes_hub_jarvis_schedule_summarizer(adapter: Any, session: Dict[str, Any]) -> None:
    if session.get("status") == "ended" or session.get("summarizer_suspended"):
        return
    if time.time() < float(session.get("summary_retry_after") or 0.0):
        return
    threshold = _hermes_hub_jarvis_env_int(
        "HERMES_JARVIS_SUMMARY_EVERY_EVENTS", 6, 2, 64
    )
    cursor = int(session.get("summary_cursor") or 0)
    pending = sum(
        1 for item in session["perceptions"]
        if item.get("summarizable") and int(item.get("id") or 0) > cursor
    )
    if pending < threshold:
        return
    current = session.get("summarizer_task")
    if current is not None and not current.done():
        return
    session["summarizer_generation"] += 1
    generation = session["summarizer_generation"]
    session["summarizer_task"] = asyncio.create_task(
        _hermes_hub_jarvis_summarize(adapter, session, generation)
    )


async def _hermes_hub_jarvis_fast_call(
    adapter: Any,
    session: Dict[str, Any],
    frame: Dict[str, Any],
    question: Optional[str],
) -> tuple[Dict[str, Any], Dict[str, float]]:
    runtime = _hermes_hub_jarvis_runtime(adapter)
    started = time.perf_counter()
    if _hermes_hub_jarvis_env_bool("HERMES_JARVIS_MOCK_MODE", False):
        result = {
            "action": "respond_simple" if question else "ignore",
            "observation": "Mock frame received.",
            "reason": "Explicit Jarvis mock mode.",
            "confidence": 0.99,
            "importance": 0.1,
            "urgency": 0.0,
            "utility": 0.1,
            "reply": "Risposta Jarvis mock." if question else None,
            "recommended_frame_ids": [frame["id"]],
        }
        return result, {"total_ms": round((time.perf_counter() - started) * 1000, 2)}

    config = _hermes_hub_jarvis_model_config("fast")
    if not config["base_url"] or not config["model"]:
        raise RuntimeError("fast_model_unavailable")
    purpose = "direct_question" if question else "passive_observation"
    input_trigger = {
        "type": purpose,
        "source": "speech" if question else "video",
        "target": session["goal"],
        "reason": "Explicit user question." if question else "A sampled scene change is ready for evaluation.",
        "text": question or "",
    }
    prompt = _hermes_hub_jarvis_reactor_prompt(
        session,
        purpose,
        question=question,
        trigger=input_trigger,
    ) + f"\nFrame id: {frame['id']}. recommended_frame_ids may contain only this id, otherwise []."
    content = [
        {"type": "text", "text": prompt},
        {"type": "image_url", "image_url": {"url": _hermes_hub_jarvis_frame_data_url(frame)}},
    ]
    payload = {
        "model": config["model"],
        "messages": [
            {"role": "system", "content": _hermes_hub_jarvis_reactor_system_prompt("observer")},
            {"role": "user", "content": content},
        ],
        "temperature": 0.0,
        "max_tokens": _hermes_hub_jarvis_env_int("HERMES_JARVIS_FAST_MAX_TOKENS", 256, 64, 512),
        "response_format": {"type": "json_object"},
        "chat_template_kwargs": {"enable_thinking": False},
        "stream": False,
    }
    timeout = _hermes_hub_jarvis_env_float(
        "HERMES_JARVIS_FAST_TIMEOUT_SECONDS", 12.0, 0.5, 120.0
    )
    queued = time.perf_counter()
    async with runtime["fast_sem"]:
        acquired = time.perf_counter()
        response = await _hermes_hub_jarvis_http_json(runtime, config, payload, timeout)
    parsed = _hermes_hub_jarvis_validate_fast_result(
        _hermes_hub_jarvis_fast_content(response)
    )
    if question is None and parsed["action"] == "respond_simple":
        should_verify = parsed["importance"] >= 0.75 and parsed["utility"] >= 0.70
        parsed["action"] = "escalate" if should_verify else "ignore"
        parsed["reply"] = None
    return parsed, {
        "queue_ms": round((acquired - queued) * 1000, 2),
        "model_ms": round((time.perf_counter() - acquired) * 1000, 2),
        "total_ms": round((time.perf_counter() - started) * 1000, 2),
    }


def _hermes_hub_jarvis_reasoning_route() -> Optional[Dict[str, Any]]:
    config = _hermes_hub_jarvis_model_config("reasoning")
    if not config["dedicated"]:
        return None
    route: Dict[str, Any] = {"model": config["model"]}
    if config["base_url"]:
        route["base_url"] = config["base_url"]
    if config["api_key"]:
        route["api_key"] = config["api_key"]
    if config["provider"]:
        route["provider"] = config["provider"]
    route["max_iterations"] = _hermes_hub_jarvis_env_int(
        "HERMES_JARVIS_REASONING_MAX_ITERATIONS", 12, 1, 60
    )
    route["max_tokens"] = _hermes_hub_jarvis_env_int(
        "HERMES_JARVIS_REASONING_MAX_TOKENS", 96, 64, 2048
    )
    return route


def _hermes_hub_jarvis_result_text(result: Any) -> str:
    if isinstance(result, str):
        return result.strip()
    if not isinstance(result, dict):
        return ""
    for key in ("final_response", "response", "content", "text", "message"):
        value = result.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
        if isinstance(value, dict):
            nested = value.get("content") or value.get("text")
            if isinstance(nested, str) and nested.strip():
                return nested.strip()
    return ""


def _hermes_hub_jarvis_selected_frames(
    session: Dict[str, Any], frame_ids: Optional[List[str]] = None
) -> List[Dict[str, Any]]:
    _hermes_hub_jarvis_prune_frames(session)
    selected: List[Dict[str, Any]] = []
    requested = [str(item) for item in (frame_ids or [])[:3]]
    if requested:
        for frame_id in requested:
            frame = session["frames"].get(frame_id)
            if frame is not None:
                selected.append(frame)
    else:
        selected = list(session["frames"].values())[-3:]
    return selected


async def _hermes_hub_jarvis_reasoning_call(
    adapter: Any,
    session: Dict[str, Any],
    prompt: str,
    frames: List[Dict[str, Any]],
) -> tuple[str, Dict[str, float]]:
    runtime = _hermes_hub_jarvis_runtime(adapter)
    started = time.perf_counter()
    if _hermes_hub_jarvis_env_bool("HERMES_JARVIS_MOCK_MODE", False):
        return "Risposta Jarvis reasoning mock.", {
            "total_ms": round((time.perf_counter() - started) * 1000, 2)
        }
    config = _hermes_hub_jarvis_model_config("reasoning")
    if not config["base_url"] or not config["model"]:
        raise RuntimeError("reasoning_model_unavailable")
    content: List[Dict[str, Any]] = [{"type": "text", "text": prompt}]
    for frame in frames[:3]:
        content.append(
            {"type": "image_url", "image_url": {"url": _hermes_hub_jarvis_frame_data_url(frame)}}
        )
    history = [dict(item) for item in list(session["transcript"])[-12:]]
    agent_ref: List[Any] = [None]
    timeout = _hermes_hub_jarvis_env_float(
        "HERMES_JARVIS_REASONING_TIMEOUT_SECONDS", 60.0, 1.0, 600.0
    )
    queued = time.perf_counter()
    async with runtime["reasoning_sem"]:
        acquired = time.perf_counter()
        task = asyncio.create_task(
            adapter._run_agent(
                user_message=content,
                conversation_history=history,
                ephemeral_system_prompt=(
                    _hermes_hub_jarvis_reactor_system_prompt("reasoning")
                    + " Jarvis Mode is temporary. Do not persist images or claim certainty not supported by evidence."
                ),
                session_id=None,
                gateway_session_key=None,
                route=_hermes_hub_jarvis_reasoning_route(),
                agent_ref=agent_ref,
            )
        )
        try:
            result, _usage = await asyncio.wait_for(task, timeout=timeout)
        except (asyncio.TimeoutError, asyncio.CancelledError):
            if agent_ref[0] is not None:
                try:
                    agent_ref[0].interrupt()
                except Exception:
                    pass
            if not task.done():
                task.cancel()
            if isinstance(sys.exc_info()[1], asyncio.CancelledError):
                raise
            raise TimeoutError("reasoning_model_timeout")
    text_value = _hermes_hub_jarvis_result_text(result)
    if not text_value:
        raise ValueError("reasoning_model_empty")
    return text_value[:4000], {
        "queue_ms": round((acquired - queued) * 1000, 2),
        "model_ms": round((time.perf_counter() - acquired) * 1000, 2),
        "total_ms": round((time.perf_counter() - started) * 1000, 2),
    }


def _hermes_hub_jarvis_requires_reasoning(question: str) -> bool:
    lowered = question.lower()
    critical = (
        "sicurezza", "pericol", "corrente", "elettric", "medic", "farmac", "denaro",
        "password", "cancella", "formatta", "multi-step", "passaggi", "analizza",
        "verifica", "confronta", "perché", "diagnosi", "rischio", "safe", "danger",
    )
    return len(question) > 320 or any(token in lowered for token in critical)


def _hermes_hub_jarvis_voice_control(
    session: Dict[str, Any], question: str
) -> Optional[str]:
    lowered = " ".join(question.lower().split())
    now = time.time()
    if "termina modalit" in lowered and "jarvis" in lowered:
        session["status"] = "ended"
        return "Termino modalità Jarvis."
    if "parla solo quando" in lowered or "solo domande" in lowered:
        session["mode"] = "questions_only"
        return "Modalità Solo domande attiva."
    if "più proattivo" in lowered or "piu proattivo" in lowered:
        session["mode"] = "proactive"
        return "Modalità Proattiva attiva."
    if "metti in pausa la vista" in lowered or "pausa la vista" in lowered:
        session["view_paused"] = True
        return "Vista in pausa."
    if "riprendi la vista" in lowered:
        session["view_paused"] = False
        return "Vista ripresa."
    if "non interrompermi" in lowered:
        seconds = 300 if "cinque" in lowered or "5 minut" in lowered else 60
        session["mute_until"] = now + seconds
        return f"Non ti interromperò per {seconds // 60} minuti."
    focus = "concentrati su"
    if focus in lowered:
        index = lowered.index(focus) + len(focus)
        session["goal"] = question[index:].strip()[:2000]
        return "Obiettivo aggiornato."
    return None


def _hermes_hub_jarvis_initiative_decision(
    session: Dict[str, Any], proposal: Dict[str, Any], now: Optional[float] = None
) -> tuple[bool, str, float]:
    now = now or time.time()
    mode = session["mode"]
    if mode == "questions_only":
        return False, "questions_only", 0.0
    if session["status"] != "active" or session["view_paused"]:
        return False, "session_inactive", 0.0
    urgency = float(proposal["urgency"])
    if session["speaking"] and urgency < 0.95:
        return False, "already_speaking", 0.0
    if now < float(session["mute_until"]) and urgency < 0.95:
        return False, "muted", 0.0
    confidence = float(proposal["confidence"])
    minimum_confidence = 0.88 if mode == "assistive" else 0.72
    if confidence < minimum_confidence:
        return False, "low_confidence", 0.0
    cooldown = _hermes_hub_jarvis_env_float(
        "HERMES_JARVIS_ASSISTIVE_COOLDOWN_SECONDS" if mode == "assistive" else "HERMES_JARVIS_PROACTIVE_COOLDOWN_SECONDS",
        30.0 if mode == "assistive" else 12.0,
        0.0,
        600.0,
    )
    elapsed = now - float(session["last_intervention_at"])
    if elapsed < cooldown and urgency < 0.95:
        return False, "cooldown", 0.0
    event_key = str(proposal.get("event_key") or proposal.get("text") or "").strip().lower()[:200]
    dedupe_ttl = _hermes_hub_jarvis_env_float(
        "HERMES_JARVIS_DEDUPE_SECONDS", 300.0, 1.0, 3600.0
    )
    session["dedupe"] = {
        key: timestamp
        for key, timestamp in session["dedupe"].items()
        if now - timestamp <= dedupe_ttl
    }
    if event_key and event_key in session["dedupe"] and urgency < 0.95:
        return False, "duplicate", 0.0
    disturbance = 0.35 if mode == "assistive" else 0.20
    if _hermes_hub_jarvis_conversation_payload(session)["active"] and urgency < 0.95:
        disturbance += 0.15
    score = float(proposal["utility"]) * urgency * confidence - disturbance
    threshold = _hermes_hub_jarvis_env_float(
        "HERMES_JARVIS_ASSISTIVE_THRESHOLD" if mode == "assistive" else "HERMES_JARVIS_PROACTIVE_THRESHOLD",
        0.48,
        -1.0,
        1.0,
    )
    threshold = max(-1.0, min(1.0, threshold + float(session.get("feedback_bias") or 0.0)))
    if score < threshold and urgency < 0.95:
        return False, "below_threshold", score
    semantic_threshold = _hermes_hub_jarvis_env_float(
        "HERMES_JARVIS_SEMANTIC_DEDUPE_THRESHOLD", 0.82, 0.5, 1.0
    )
    semantic_ttl = _hermes_hub_jarvis_env_float(
        "HERMES_JARVIS_SEMANTIC_DEDUPE_SECONDS", 600.0, 10.0, 3600.0
    )
    proposed_text = str(proposal.get("text") or "")
    for previous in session.get("interventions") or []:
        if now - float(previous.get("timestamp") or 0.0) > semantic_ttl:
            continue
        if _hermes_hub_jarvis_semantic_similarity(proposed_text, str(previous.get("text") or "")) >= semantic_threshold:
            return False, "semantic_duplicate", score
    session["last_intervention_at"] = now
    if event_key:
        session["dedupe"][event_key] = now
    return True, "speak", score


def _hermes_hub_jarvis_remember_intervention(
    session: Dict[str, Any], event_id: str, text: str, event_key: str
) -> None:
    session["interventions"].append(
        {
            "event_id": str(event_id)[:64],
            "text": " ".join(str(text or "").split())[:1200],
            "event_key": str(event_key or "")[:200],
            "timestamp": time.time(),
            "helpful": None,
        }
    )


def _hermes_hub_jarvis_apply_feedback(
    session: Dict[str, Any], event_id: str, helpful: bool
) -> float:
    matching_intervention = next(
        (
            item
            for item in session.get("interventions") or []
            if str(item.get("event_id") or "") == event_id
        ),
        None,
    )
    if matching_intervention is None:
        raise ValueError("intervention_not_found")
    if matching_intervention.get("helpful") is not None:
        raise ValueError("feedback_already_recorded")
    session["feedback"].append(
        {"event_id": event_id, "helpful": helpful, "timestamp": time.time()}
    )
    step = _hermes_hub_jarvis_env_float("HERMES_JARVIS_FEEDBACK_STEP", 0.04, 0.0, 0.2)
    current_bias = float(session.get("feedback_bias") or 0.0)
    session["feedback_bias"] = max(
        -0.2, min(0.3, current_bias - step if helpful else current_bias + step)
    )
    matching_intervention["helpful"] = helpful
    _hermes_hub_jarvis_record_perception(
        session,
        "feedback",
        "intervention_rating",
        "The user marked the previous intervention as helpful."
        if helpful
        else "The user marked the previous intervention as unhelpful.",
        importance=0.8,
    )
    return float(session["feedback_bias"])


async def _hermes_hub_jarvis_observe(
    adapter: Any, session: Dict[str, Any], frame_id: str, generation: int
) -> None:
    task = asyncio.current_task()
    session["tasks"].add(task)
    try:
        frame = session["frames"].get(frame_id)
        if frame is None or session["observer_generation"] != generation:
            return
        fast, metrics = await _hermes_hub_jarvis_fast_call(adapter, session, frame, None)
        if session["observer_generation"] != generation:
            return
        session["last_observation"] = fast["observation"] or None
        session["last_route"] = "fast"
        session["last_latency_ms"] = metrics.get("total_ms")
        session["observations"].append(fast)
        trigger = _hermes_hub_jarvis_set_trigger(
            session,
            {
                "type": fast["action"],
                "source": "video",
                "target": session["goal"],
                "reason": fast["reason"],
                "text": fast["observation"],
                "confidence": fast["confidence"],
                "importance": fast["importance"],
                "urgency": fast["urgency"],
            },
        )
        _hermes_hub_jarvis_record_perception(
            session,
            "video",
            "observation",
            fast["observation"] or fast["reason"],
            importance=fast["importance"],
            urgency=fast["urgency"],
            frame_id=frame_id,
        )
        _hermes_hub_jarvis_schedule_summarizer(adapter, session)
        if fast["action"] == "ignore":
            return
        _hermes_hub_jarvis_publish(
            session,
            "observer.result",
            action=fast["action"],
            observation=fast["observation"],
            situation=session["last_situation"],
            confidence=fast["confidence"],
            metrics=metrics,
        )
        if fast["action"] not in {"ask_user", "escalate", "urgent_candidate"}:
            return
        reasoning = _hermes_hub_jarvis_model_config("reasoning")
        if not (_hermes_hub_jarvis_env_bool("HERMES_JARVIS_MOCK_MODE", False) or (reasoning["base_url"] and reasoning["model"])):
            _hermes_hub_jarvis_publish(
                session,
                "session.error",
                code="reasoning_model_unavailable",
                message="Intervento autonomo disabilitato: modello ragionante non disponibile.",
            )
            return
        _hermes_hub_jarvis_publish(session, "assistant.escalating", route="reasoning")
        verification_prompt = _hermes_hub_jarvis_reactor_prompt(
            session,
            "autonomous_verification",
            trigger=trigger,
        ) + (
            "\nReturn one JSON object only with keys speak(bool), text(string|null), confidence, importance, "
            "urgency, utility (0..1), event_key(short stable string). Prefer silence. Never speak for a merely "
            "descriptive observation. Candidate: "
            + json.dumps(fast, ensure_ascii=False, separators=(",", ":"))
        )
        raw, reasoning_metrics = await _hermes_hub_jarvis_reasoning_call(
            adapter, session, verification_prompt, [frame]
        )
        verification = _hermes_hub_jarvis_validate_verification(raw)
        if not verification["speak"]:
            return
        speak, reason, score = _hermes_hub_jarvis_initiative_decision(session, verification)
        if not speak:
            _hermes_hub_jarvis_publish(
                session, "initiative.silent", reason=reason, score=round(score, 4)
            )
            return
        session["last_route"] = "reasoning"
        session["last_latency_ms"] = reasoning_metrics.get("total_ms")
        event = _hermes_hub_jarvis_publish(
            session,
            "assistant.speak",
            text=verification["text"],
            route="reasoning",
            autonomous=True,
            metrics=reasoning_metrics,
        )
        _hermes_hub_jarvis_remember_intervention(
            session, event["id"], verification["text"], verification["event_key"]
        )
        _hermes_hub_jarvis_touch_conversation(session, "assistant", verification["text"])
        _hermes_hub_jarvis_record_perception(
            session,
            "assistant",
            "autonomous_intervention",
            verification["text"],
            importance=verification["importance"],
            urgency=verification["urgency"],
        )
        _hermes_hub_jarvis_schedule_summarizer(adapter, session)
    except asyncio.CancelledError:
        raise
    except asyncio.TimeoutError:
        _hermes_hub_jarvis_publish(
            session, "session.error", code="fast_model_timeout", message="Timeout modello rapido."
        )
    except TimeoutError:
        _hermes_hub_jarvis_publish(
            session,
            "session.error",
            code="reasoning_model_timeout",
            message="Timeout modello ragionante.",
        )
    except Exception as exc:
        _hermes_hub_jarvis_publish(
            session,
            "session.error",
            code="observer_failed",
            message=_hermes_hub_jarvis_safe_error(exc),
        )
    finally:
        session["tasks"].discard(task)
        if session.get("observer_task") is task:
            session["observer_task"] = None


async def _hermes_hub_jarvis_schedule_observer(
    adapter: Any, session: Dict[str, Any], frame_id: str
) -> None:
    previous = session.get("observer_task")
    if previous is not None and not previous.done():
        previous.cancel()
    session["observer_generation"] += 1
    generation = session["observer_generation"]
    task = asyncio.create_task(_hermes_hub_jarvis_observe(adapter, session, frame_id, generation))
    session["observer_task"] = task


async def _hermes_hub_jarvis_direct_turn(
    adapter: Any,
    session: Dict[str, Any],
    question: str,
    frame_ids: Optional[List[str]],
) -> Dict[str, Any]:
    task = asyncio.current_task()
    session["tasks"].add(task)
    session["summarizer_suspended"] = True
    try:
        trigger = _hermes_hub_jarvis_set_trigger(
            session,
            {
                "type": "direct_question",
                "source": "speech",
                "target": session["goal"],
                "reason": "The user is addressing Hermes directly.",
                "text": question,
                "confidence": 1.0,
                "importance": 1.0,
                "urgency": 0.5,
            },
        )
        _hermes_hub_jarvis_touch_conversation(session, "user", question)
        _hermes_hub_jarvis_record_perception(
            session, "speech", "user_utterance", question, importance=1.0, urgency=0.5
        )
        summarizer = session.get("summarizer_task")
        if summarizer is not None and not summarizer.done():
            summarizer.cancel()
            await asyncio.gather(summarizer, return_exceptions=True)
        control_reply = _hermes_hub_jarvis_voice_control(session, question)
        if control_reply is not None:
            session["updated_at"] = time.time()
            session["transcript"].append({"role": "user", "content": question})
            session["transcript"].append({"role": "assistant", "content": control_reply})
            _hermes_hub_jarvis_touch_conversation(session, "assistant", control_reply)
            _hermes_hub_jarvis_record_perception(
                session, "assistant", "control_reply", control_reply, importance=0.6
            )
            event = _hermes_hub_jarvis_publish(
                session, "assistant.speak", text=control_reply, route="control", autonomous=False
            )
            return {"text": control_reply, "route": "control", "event_id": event["id"]}

        observer = session.get("observer_task")
        if observer is not None and not observer.done():
            observer.cancel()
            await asyncio.gather(observer, return_exceptions=True)
        frames = _hermes_hub_jarvis_selected_frames(session, frame_ids)
        if not frames:
            raise ValueError("frame_required")
        session["transcript"].append({"role": "user", "content": question})
        reasoning_config = _hermes_hub_jarvis_model_config("reasoning")
        reasoning_available = _hermes_hub_jarvis_env_bool("HERMES_JARVIS_MOCK_MODE", False) or bool(
            reasoning_config["base_url"] and reasoning_config["model"]
        )
        fast_config = _hermes_hub_jarvis_model_config("fast")
        fast_available = _hermes_hub_jarvis_env_bool("HERMES_JARVIS_MOCK_MODE", False) or bool(
            fast_config["base_url"] and fast_config["model"]
        )
        route = "reasoning" if _hermes_hub_jarvis_requires_reasoning(question) else "fast"
        answer: Optional[str] = None
        metrics: Dict[str, float] = {}
        fast: Optional[Dict[str, Any]] = None
        if route == "fast" and fast_available:
            _hermes_hub_jarvis_publish(session, "assistant.thinking", route="fast")
            try:
                fast, metrics = await _hermes_hub_jarvis_fast_call(
                    adapter, session, frames[-1], question
                )
                minimum = _hermes_hub_jarvis_env_float(
                    "HERMES_JARVIS_SIMPLE_MIN_CONFIDENCE", 0.95, 0.0, 1.0
                )
                if fast["action"] in {"respond_simple", "ask_user"} and fast["confidence"] >= minimum:
                    answer = fast["reply"]
                else:
                    route = "reasoning"
            except asyncio.TimeoutError:
                route = "reasoning"
                _hermes_hub_jarvis_publish(
                    session, "session.error", code="fast_model_timeout", message="Timeout modello rapido."
                )
            except Exception as exc:
                route = "reasoning"
                _hermes_hub_jarvis_publish(
                    session,
                    "session.error",
                    code="fast_model_invalid_or_unavailable",
                    message=_hermes_hub_jarvis_safe_error(exc),
                )
        elif route == "fast":
            route = "reasoning"

        if answer is None and route == "reasoning":
            if not reasoning_available:
                if fast is not None and fast.get("reply") and fast["confidence"] >= 0.9:
                    answer = fast["reply"]
                    route = "fast"
                else:
                    raise RuntimeError("reasoning_model_unavailable")
            else:
                _hermes_hub_jarvis_publish(session, "assistant.escalating", route="reasoning")
                prompt = _hermes_hub_jarvis_reactor_prompt(
                    session,
                    "direct_reasoning",
                    question=question,
                    trigger=trigger,
                ) + f"\nObserver result: {json.dumps(fast, ensure_ascii=False) if fast else 'not run'}"
                answer, metrics = await _hermes_hub_jarvis_reasoning_call(
                    adapter, session, prompt, frames
                )
        if not answer:
            raise ValueError("empty_answer")
        answer = answer.strip()[:1200]
        session["transcript"].append({"role": "assistant", "content": answer})
        _hermes_hub_jarvis_touch_conversation(session, "assistant", answer)
        _hermes_hub_jarvis_record_perception(
            session, "assistant", "direct_answer", answer, importance=1.0, urgency=0.5
        )
        session["last_route"] = route
        session["last_latency_ms"] = metrics.get("total_ms")
        session["updated_at"] = time.time()
        event = _hermes_hub_jarvis_publish(
            session,
            "assistant.speak",
            text=answer,
            route=route,
            autonomous=False,
            metrics=metrics,
        )
        return {"text": answer, "route": route, "metrics": metrics, "event_id": event["id"]}
    finally:
        session["summarizer_suspended"] = False
        _hermes_hub_jarvis_schedule_summarizer(adapter, session)
        session["tasks"].discard(task)


async def _hermes_hub_jarvis_session_or_none(
    adapter: Any, session_id: str
) -> Optional[Dict[str, Any]]:
    runtime = _hermes_hub_jarvis_runtime(adapter)
    session = runtime["sessions"].get(session_id)
    if session is None:
        return None
    if session["expires_at"] <= time.time() or session["status"] == "ended":
        await _hermes_hub_jarvis_drop_session(runtime, session_id, "ttl_expired")
        return None
    session["updated_at"] = time.time()
    return session
# HERMES_HUB_JARVIS_RUNTIME_END'''

    text, changed = _upsert_versioned_block(
        text,
        begin=_JARVIS_RUNTIME_BEGIN,
        end=_JARVIS_RUNTIME_END,
        block=runtime,
        anchor='def _multimodal_validation_error(exc: ValueError, *, param: str) -> "web.Response":',
        label="Jarvis runtime v1",
    )
    if changed:
        changes.append("Jarvis runtime v1")

    handler_anchor = '    async def _handle_models(self, request: "web.Request") -> "web.Response":'
    handler_boundary = '''    async def _handle_jarvis_marker_v1(self) -> None:
        """Stable boundary protecting the generated Jarvis block from legacy handler rewrites."""
        return None

'''
    if "async def _handle_jarvis_marker_v1(" not in text:
        if text.count(handler_anchor) != 1:
            raise PatchError("Patch anchor not found or ambiguous: Jarvis handler boundary v1")
        text = text.replace(handler_anchor, handler_boundary + handler_anchor, 1)
        changes.append("Jarvis handler boundary v1")

    handlers = r'''    # HERMES_HUB_JARVIS_HANDLERS_BEGIN
    # HERMES_HUB_JARVIS_MODE_V1
    async def _handle_jarvis_create_session(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        if not _hermes_hub_jarvis_env_bool("HERMES_JARVIS_ENABLED", False):
            return _hermes_hub_jarvis_error("jarvis_disabled", "Jarvis Mode non abilitato.", 503)
        try:
            body = await _hermes_hub_jarvis_json_body(request)
        except OverflowError:
            return _hermes_hub_jarvis_error("payload_too_large", "Payload sessione troppo grande.", 413)
        except ValueError:
            return _hermes_hub_jarvis_error("invalid_json", "JSON non valido.", 400)
        runtime = _hermes_hub_jarvis_runtime(self)
        max_sessions = _hermes_hub_jarvis_env_int("HERMES_JARVIS_MAX_SESSIONS", 4, 1, 32)
        active = [item for item in runtime["sessions"].values() if item["status"] != "ended"]
        if len(active) >= max_sessions:
            return _hermes_hub_jarvis_error("session_limit", "Limite sessioni Jarvis raggiunto.", 429)
        try:
            session = _hermes_hub_jarvis_new_session(body)
        except ValueError:
            return _hermes_hub_jarvis_error("invalid_mode", "Modalità Jarvis non valida.", 400)
        runtime["sessions"][session["id"]] = session
        _hermes_hub_jarvis_ensure_sweeper(self)
        _hermes_hub_jarvis_publish(session, "session.ready", mode=session["mode"])
        return web.json_response(_hermes_hub_jarvis_public_session(session), status=201)

    async def _handle_jarvis_get_session(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        session = await _hermes_hub_jarvis_session_or_none(self, request.match_info.get("session_id", ""))
        if session is None:
            return _hermes_hub_jarvis_error("session_not_found", "Sessione Jarvis non trovata.", 404)
        return web.json_response(_hermes_hub_jarvis_public_session(session))

    async def _handle_jarvis_patch_session(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        session = await _hermes_hub_jarvis_session_or_none(self, request.match_info.get("session_id", ""))
        if session is None:
            return _hermes_hub_jarvis_error("session_not_found", "Sessione Jarvis non trovata.", 404)
        try:
            body = await _hermes_hub_jarvis_json_body(request)
        except OverflowError:
            return _hermes_hub_jarvis_error("payload_too_large", "Payload sessione troppo grande.", 413)
        except ValueError:
            return _hermes_hub_jarvis_error("invalid_json", "JSON non valido.", 400)
        if "mode" in body:
            mode = str(body["mode"]).strip().lower()
            if mode not in {"questions_only", "assistive", "proactive"}:
                return _hermes_hub_jarvis_error("invalid_mode", "Modalità Jarvis non valida.", 400)
            session["mode"] = mode
        if "goal" in body:
            session["goal"] = str(body.get("goal") or "").strip()[:2000]
        if "view_paused" in body:
            session["view_paused"] = bool(body["view_paused"])
        if "speaking" in body:
            session["speaking"] = bool(body["speaking"])
        if "mute_seconds" in body:
            try:
                mute_seconds = max(0, min(3600, int(body["mute_seconds"])))
            except (TypeError, ValueError):
                return _hermes_hub_jarvis_error("invalid_mute_seconds", "Durata pausa non valida.", 400)
            session["mute_until"] = time.time() + mute_seconds
        status = str(body.get("status") or "").strip().lower()
        if status:
            if status not in {"active", "paused", "ended"}:
                return _hermes_hub_jarvis_error("invalid_status", "Stato Jarvis non valido.", 400)
            session["status"] = status
            session["view_paused"] = status == "paused" or session["view_paused"]
        session["updated_at"] = time.time()
        if session["status"] == "ended":
            runtime = _hermes_hub_jarvis_runtime(self)
            public = _hermes_hub_jarvis_public_session(session)
            await _hermes_hub_jarvis_drop_session(runtime, session["id"], "client_ended")
            return web.json_response(public)
        _hermes_hub_jarvis_publish(session, "session.updated", mode=session["mode"], status=session["status"])
        return web.json_response(_hermes_hub_jarvis_public_session(session))

    async def _handle_jarvis_delete_session(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        runtime = _hermes_hub_jarvis_runtime(self)
        session_id = request.match_info.get("session_id", "")
        if session_id not in runtime["sessions"]:
            return _hermes_hub_jarvis_error("session_not_found", "Sessione Jarvis non trovata.", 404)
        await _hermes_hub_jarvis_drop_session(runtime, session_id, "client_deleted")
        return web.json_response({"id": session_id, "deleted": True})

    async def _handle_jarvis_frame(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        session = await _hermes_hub_jarvis_session_or_none(self, request.match_info.get("session_id", ""))
        if session is None:
            return _hermes_hub_jarvis_error("session_not_found", "Sessione Jarvis non trovata.", 404)
        if session["status"] != "active" or session["view_paused"]:
            return _hermes_hub_jarvis_error("view_paused", "Vista Jarvis in pausa.", 409)
        started = time.perf_counter()
        try:
            data, mime_type = await _hermes_hub_jarvis_frame_body(request)
        except OverflowError:
            return _hermes_hub_jarvis_error("frame_too_large", "Fotogramma oltre limite.", 413)
        except TypeError:
            return _hermes_hub_jarvis_error("unsupported_frame_type", "Formato fotogramma non supportato.", 415)
        except ValueError as exc:
            return _hermes_hub_jarvis_error(str(exc), "Fotogramma non valido.", 400)
        import hashlib as _hashlib

        digest = _hashlib.sha256(data).hexdigest()
        latest = next(reversed(session["frames"].values()), None) if session["frames"] else None
        duplicate = bool(latest and latest["sha256"] == digest)
        if duplicate:
            return web.json_response({"frame_id": latest["id"], "duplicate": True, "accepted": False})
        frame_id = uuid.uuid4().hex
        try:
            captured_at = float(request.query.get("captured_at", time.time()))
        except (TypeError, ValueError):
            captured_at = time.time()
        frame = {
            "id": frame_id,
            "data": data,
            "mime_type": mime_type,
            "sha256": digest,
            "received_at": time.time(),
            "captured_at": captured_at,
        }
        session["frames"][frame_id] = frame
        _hermes_hub_jarvis_prune_frames(session)
        _hermes_hub_jarvis_record_perception(
            session,
            "video",
            "sampled_frame",
            "New sampled camera frame accepted.",
            frame_id=frame_id,
            summarizable=False,
        )
        session["updated_at"] = time.time()
        autonomous = session["mode"] != "questions_only"
        if autonomous:
            await _hermes_hub_jarvis_schedule_observer(self, session, frame_id)
        return web.json_response(
            {
                "frame_id": frame_id,
                "accepted": True,
                "duplicate": False,
                "observer_scheduled": autonomous,
                "upload_ms": round((time.perf_counter() - started) * 1000, 2),
            },
            status=202,
        )

    async def _handle_jarvis_turn(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        session = await _hermes_hub_jarvis_session_or_none(self, request.match_info.get("session_id", ""))
        if session is None:
            return _hermes_hub_jarvis_error("session_not_found", "Sessione Jarvis non trovata.", 404)
        if session["status"] not in {"active", "paused"}:
            return _hermes_hub_jarvis_error("session_inactive", "Sessione Jarvis non attiva.", 409)
        try:
            body = await _hermes_hub_jarvis_json_body(request, 65_536)
        except OverflowError:
            return _hermes_hub_jarvis_error("payload_too_large", "Turno Jarvis troppo grande.", 413)
        except ValueError:
            return _hermes_hub_jarvis_error("invalid_json", "JSON non valido.", 400)
        question = str(body.get("text") or body.get("transcript") or "").strip()
        if not question:
            return _hermes_hub_jarvis_error("missing_transcript", "Trascrizione mancante.", 400)
        if len(question) > 4000:
            return _hermes_hub_jarvis_error("transcript_too_long", "Trascrizione oltre limite.", 413)
        frame_ids = body.get("frame_ids")
        if frame_ids is not None and not isinstance(frame_ids, list):
            return _hermes_hub_jarvis_error("invalid_frame_ids", "frame_ids deve essere un array.", 400)
        try:
            result = await _hermes_hub_jarvis_direct_turn(self, session, question, frame_ids)
        except asyncio.CancelledError:
            raise
        except TimeoutError:
            return _hermes_hub_jarvis_error("reasoning_model_timeout", "Timeout modello ragionante.", 504)
        except ValueError as exc:
            code = str(exc)
            status = 409 if code == "frame_required" else 502
            message = "Serve almeno un fotogramma recente." if code == "frame_required" else "Risposta modello non valida."
            return _hermes_hub_jarvis_error(code, message, status)
        except RuntimeError as exc:
            code = str(exc)
            return _hermes_hub_jarvis_error(code, "Modello Jarvis non disponibile.", 503)
        except Exception as exc:
            return _hermes_hub_jarvis_error("turn_failed", _hermes_hub_jarvis_safe_error(exc), 502)
        if session["status"] == "ended":
            runtime = _hermes_hub_jarvis_runtime(self)
            await _hermes_hub_jarvis_drop_session(runtime, session["id"], "voice_control")
        return web.json_response(result)

    async def _handle_jarvis_events(self, request: "web.Request") -> "web.StreamResponse":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        session = await _hermes_hub_jarvis_session_or_none(self, request.match_info.get("session_id", ""))
        if session is None:
            return _hermes_hub_jarvis_error("session_not_found", "Sessione Jarvis non trovata.", 404)
        queue = asyncio.Queue(
            maxsize=_hermes_hub_jarvis_env_int("HERMES_JARVIS_SSE_QUEUE_SIZE", 64, 4, 512)
        )
        session["subscribers"].add(queue)
        response = web.StreamResponse(
            status=200,
            headers={
                "Content-Type": "text/event-stream",
                "Cache-Control": "no-cache, no-transform",
                "Connection": "keep-alive",
                "X-Accel-Buffering": "no",
            },
        )
        await response.prepare(request)
        last_id = str(request.headers.get("Last-Event-ID", "")).strip()
        try:
            last_number = int(last_id) if last_id else 0
        except ValueError:
            last_number = 0
        try:
            for event in list(session["events"]):
                if int(event["id"]) > last_number:
                    await response.write(
                        f"id: {event['id']}\nevent: {event['type']}\ndata: {json.dumps(event, ensure_ascii=False)}\n\n".encode("utf-8")
                    )
            keepalive = _hermes_hub_jarvis_env_float(
                "HERMES_JARVIS_SSE_KEEPALIVE_SECONDS", 15.0, 2.0, 60.0
            )
            while True:
                try:
                    event = await asyncio.wait_for(queue.get(), timeout=keepalive)
                except asyncio.TimeoutError:
                    await response.write(b": keepalive\n\n")
                    continue
                if event is None:
                    break
                await response.write(
                    f"id: {event['id']}\nevent: {event['type']}\ndata: {json.dumps(event, ensure_ascii=False)}\n\n".encode("utf-8")
                )
        except (ConnectionResetError, BrokenPipeError, asyncio.CancelledError):
            pass
        finally:
            session["subscribers"].discard(queue)
            try:
                await response.write_eof()
            except Exception:
                pass
        return response

    async def _handle_jarvis_feedback(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        session = await _hermes_hub_jarvis_session_or_none(self, request.match_info.get("session_id", ""))
        if session is None:
            return _hermes_hub_jarvis_error("session_not_found", "Sessione Jarvis non trovata.", 404)
        try:
            body = await _hermes_hub_jarvis_json_body(request)
        except OverflowError:
            return _hermes_hub_jarvis_error("payload_too_large", "Feedback troppo grande.", 413)
        except ValueError:
            return _hermes_hub_jarvis_error("invalid_json", "JSON non valido.", 400)
        event_id = str(body.get("event_id") or "").strip()[:64]
        helpful = body.get("helpful")
        if not event_id or not isinstance(helpful, bool):
            return _hermes_hub_jarvis_error("invalid_feedback", "Feedback non valido.", 400)
        try:
            initiative_bias = _hermes_hub_jarvis_apply_feedback(session, event_id, helpful)
        except ValueError as exc:
            code = str(exc)
            if code == "intervention_not_found":
                return _hermes_hub_jarvis_error(
                    code, "Intervento autonomo non trovato.", 404
                )
            if code == "feedback_already_recorded":
                return _hermes_hub_jarvis_error(
                    code, "Feedback gia registrato.", 409
                )
            return _hermes_hub_jarvis_error(
                "invalid_feedback", "Feedback non valido.", 400
            )
        _hermes_hub_jarvis_schedule_summarizer(self, session)
        _hermes_hub_jarvis_publish(
            session,
            "feedback.updated",
            event_id=event_id,
            helpful=helpful,
            initiative_bias=round(initiative_bias, 4),
        )
        return web.json_response(
            {"accepted": True, "initiative_bias": round(initiative_bias, 4)}
        )

    async def _handle_jarvis_cleanup(self, _app: "web.Application") -> None:
        await _hermes_hub_jarvis_shutdown(self)
    # HERMES_HUB_JARVIS_HANDLERS_END'''

    text, changed = _upsert_versioned_block(
        text,
        begin=_JARVIS_HANDLERS_BEGIN,
        end=_JARVIS_HANDLERS_END,
        block=handlers,
        anchor=handler_anchor,
        label="Jarvis handlers v1",
    )
    if changed:
        changes.append("Jarvis endpoint handlers v1")

    capabilities = r'''            # HERMES_HUB_JARVIS_CAPABILITIES_BEGIN
            # HERMES_HUB_JARVIS_MODE_V1
            "jarvis": _hermes_hub_jarvis_capabilities(),
            # HERMES_HUB_JARVIS_CAPABILITIES_END'''
    text, changed = _upsert_versioned_block(
        text,
        begin=_JARVIS_CAPABILITIES_BEGIN,
        end=_JARVIS_CAPABILITIES_END,
        block=capabilities,
        anchor='            "auth": {',
        label="Jarvis capabilities v1",
    )
    if changed:
        changes.append("Jarvis capabilities v1")

    dynamic_routes = "def _http_route_table(self)" in text
    if dynamic_routes:
        route_begin = "        # HERMES_HUB_JARVIS_ROUTES_BEGIN"
        route_end = "        # HERMES_HUB_JARVIS_ROUTES_END"
        routes = r'''        # HERMES_HUB_JARVIS_ROUTES_BEGIN
        # HERMES_HUB_JARVIS_MODE_V1
        routes.extend([
            ("POST", "/v1/jarvis/sessions", self._handle_jarvis_create_session),
            ("GET", "/v1/jarvis/sessions/{session_id}", self._handle_jarvis_get_session),
            ("PATCH", "/v1/jarvis/sessions/{session_id}", self._handle_jarvis_patch_session),
            ("DELETE", "/v1/jarvis/sessions/{session_id}", self._handle_jarvis_delete_session),
            ("POST", "/v1/jarvis/sessions/{session_id}/frames", self._handle_jarvis_frame),
            ("POST", "/v1/jarvis/sessions/{session_id}/turns", self._handle_jarvis_turn),
            ("GET", "/v1/jarvis/sessions/{session_id}/events", self._handle_jarvis_events),
            ("POST", "/v1/jarvis/sessions/{session_id}/feedback", self._handle_jarvis_feedback),
        ])
        # HERMES_HUB_JARVIS_ROUTES_END'''
        route_anchor = "        if _CRON_AVAILABLE:\n"
    else:
        route_begin = _JARVIS_ROUTES_BEGIN
        route_end = _JARVIS_ROUTES_END
        routes = r'''            # HERMES_HUB_JARVIS_ROUTES_BEGIN
            # HERMES_HUB_JARVIS_MODE_V1
            self._app.on_cleanup.append(self._handle_jarvis_cleanup)
            self._app.router.add_post("/v1/jarvis/sessions", self._handle_jarvis_create_session)
            self._app.router.add_get("/v1/jarvis/sessions/{session_id}", self._handle_jarvis_get_session)
            self._app.router.add_patch("/v1/jarvis/sessions/{session_id}", self._handle_jarvis_patch_session)
            self._app.router.add_delete("/v1/jarvis/sessions/{session_id}", self._handle_jarvis_delete_session)
            self._app.router.add_post("/v1/jarvis/sessions/{session_id}/frames", self._handle_jarvis_frame)
            self._app.router.add_post("/v1/jarvis/sessions/{session_id}/turns", self._handle_jarvis_turn)
            self._app.router.add_get("/v1/jarvis/sessions/{session_id}/events", self._handle_jarvis_events)
            self._app.router.add_post("/v1/jarvis/sessions/{session_id}/feedback", self._handle_jarvis_feedback)
            # HERMES_HUB_JARVIS_ROUTES_END'''
        route_anchor = '            self._app.router.add_get("/v1/capabilities", self._handle_capabilities)\n'
    text, changed = _upsert_versioned_block(
        text,
        begin=route_begin,
        end=route_end,
        block=routes,
        anchor=route_anchor,
        label="Jarvis routes v1",
    )
    if changed:
        changes.append("Jarvis routes and cleanup hook v1")

    if dynamic_routes:
        cleanup = r'''            # HERMES_HUB_JARVIS_CLEANUP_BEGIN
            self._app.on_cleanup.append(self._handle_jarvis_cleanup)
            # HERMES_HUB_JARVIS_CLEANUP_END'''
        text, changed = _upsert_versioned_block(
            text,
            begin=_JARVIS_CLEANUP_BEGIN,
            end=_JARVIS_CLEANUP_END,
            block=cleanup,
            anchor='            self._app["api_server_adapter"] = self\n',
            label="Jarvis cleanup hook v1",
        )
        if changed:
            changes.append("Jarvis cleanup hook v1")

    return text, changes


def _route_registered(text: str, method: str, path: str, handler: str) -> bool:
    """Recognize legacy aiohttp calls and the newer declarative route table."""
    add_method = method.lower()
    legacy = f'self._app.router.add_{add_method}("{path}", self.{handler})'
    declarative = f'("{method.upper()}", "{path}", self.{handler})'
    return legacy in text or declarative in text


def _patch_dynamic_http_routes(text: str) -> tuple[str, bool]:
    """Add Hermes Hub routes to upstream's multiplex-aware route table."""
    if "def _http_route_table(self)" not in text:
        return text, False
    block = r'''        # HERMES_HUB_DYNAMIC_ROUTES_BEGIN
        routes.extend([
            ("POST", "/v1/hermes/native", self._handle_responses),
            ("GET", "/v1/hub/hardware", self._handle_hub_hardware),
            ("GET", "/v1/hub/server/control", self._handle_hub_server_control),
            ("POST", "/v1/hub/server/action", self._handle_hub_server_action),
            ("POST", "/v1/hub/server/maintenance", self._handle_hub_server_maintenance),
            ("GET", "/v1/hub/audit", self._handle_get_hub_audit),
            ("POST", "/v1/hub/audit", self._handle_post_hub_audit),
            ("GET", "/v1/video/library", self._handle_video_library),
            ("GET", "/v1/news/library", self._handle_news_library),
            ("POST", "/v1/media/upload", self._handle_hub_media_upload),
            ("GET", "/v1/media/{media_id:.*}", self._handle_hub_media),
            ("GET", "/v1/hub/memory", self._handle_hub_memory),
            ("PATCH", "/v1/hub/memory", self._handle_patch_hub_memory),
            ("GET", "/v1/hub/state", self._handle_get_hub_state),
            ("POST", "/v1/hub/state", self._handle_post_hub_state),
            ("DELETE", "/v1/hub/state/{state_id}", self._handle_delete_hub_state),
            ("GET", "/v1/hub/conversations", self._handle_get_hub_conversations),
            ("GET", "/v1/hub/conversations/events", self._handle_get_hub_conversations_events),
            ("POST", "/v1/hub/conversations/import", self._handle_post_hub_conversations_import),
            ("PUT", "/v1/hub/conversations/{conversation_id}", self._handle_put_hub_conversation),
            ("DELETE", "/v1/hub/conversations/{conversation_id}", self._handle_delete_hub_conversation),
            ("GET", "/v1/hub/notifications", self._handle_get_hub_notifications),
            ("POST", "/v1/hub/notifications", self._handle_post_hub_notification),
            ("PATCH", "/v1/hub/notifications/{notification_id}", self._handle_patch_hub_notification),
            ("POST", "/v1/audio/transcriptions", self._handle_audio_transcriptions),
            ("POST", "/v1/audio/speech", self._handle_audio_speech),
        ])
        # HERMES_HUB_DYNAMIC_ROUTES_END'''
    return _upsert_versioned_block(
        text,
        begin=_DYNAMIC_ROUTES_BEGIN,
        end=_DYNAMIC_ROUTES_END,
        block=block,
        anchor="        if _CRON_AVAILABLE:\n",
        label="dynamic HTTP route table",
    )


def _patch_text(text: str) -> tuple[str, list[str]]:
    changes: list[str] = []

    text, dynamic_routes_changed = _patch_dynamic_http_routes(text)
    if dynamic_routes_changed:
        changes.append("dynamic HTTP route table")

    if _MODEL_ROUTE_TOOLSETS_MARKER not in text:
        old_route_parser = '''        allowed_keys = ("model", "provider", "api_key", "base_url")
        routes: Dict[str, Dict[str, Any]] = {}
        for alias, cfg in raw.items():
            alias_str = str(alias).strip()
            if not alias_str or not isinstance(cfg, dict):
                logger.warning(
                    "api_server model_routes: dropping invalid route entry %r", alias_str or alias
                )
                continue
            route = {
                key: str(cfg[key]).strip()
                for key in allowed_keys
                if cfg.get(key) is not None and str(cfg[key]).strip()
            }
'''
        new_route_parser = f'''        {_MODEL_ROUTE_TOOLSETS_MARKER}
        allowed_keys = ("model", "provider", "api_key", "base_url")
        routes: Dict[str, Dict[str, Any]] = {{}}
        for alias, cfg in raw.items():
            alias_str = str(alias).strip()
            if not alias_str or not isinstance(cfg, dict):
                logger.warning(
                    "api_server model_routes: dropping invalid route entry %r", alias_str or alias
                )
                continue
            route = {{
                key: str(cfg[key]).strip()
                for key in allowed_keys
                if cfg.get(key) is not None and str(cfg[key]).strip()
            }}
            configured_toolsets = cfg.get("toolsets")
            if isinstance(configured_toolsets, (list, tuple)):
                route["toolsets"] = sorted({{str(item).strip() for item in configured_toolsets if str(item).strip()}})
            elif configured_toolsets is not None:
                logger.warning(
                    "api_server model_routes: ignoring non-list toolsets for route %r", alias_str
                )
'''
        text, _ = _replace_once(
            text,
            old_route_parser,
            new_route_parser,
            "model route toolsets parser",
        )
        old_toolset_resolution = '''        user_config = _load_gateway_config()
        enabled_toolsets = sorted(_get_platform_tools(user_config, "api_server"))
'''
        new_toolset_resolution = '''        user_config = _load_gateway_config()
        route_toolsets = route.get("toolsets") if route and not session_override else None
        enabled_toolsets = (
            sorted(route_toolsets)
            if route_toolsets is not None
            else sorted(_get_platform_tools(user_config, "api_server"))
        )
'''
        text, _ = _replace_once(
            text,
            old_toolset_resolution,
            new_toolset_resolution,
            "model route toolsets resolution",
        )
        changes.append("per-model-route toolsets")

    if _MODEL_ROUTE_LIMITS_MARKER not in text:
        old_route_toolsets = '''            configured_toolsets = cfg.get("toolsets")
            if isinstance(configured_toolsets, (list, tuple)):
                route["toolsets"] = sorted({str(item).strip() for item in configured_toolsets if str(item).strip()})
            elif configured_toolsets is not None:
                logger.warning(
                    "api_server model_routes: ignoring non-list toolsets for route %r", alias_str
                )
'''
        new_route_limits = f'''            configured_toolsets = cfg.get("toolsets")
            if isinstance(configured_toolsets, (list, tuple)):
                route["toolsets"] = sorted({{str(item).strip() for item in configured_toolsets if str(item).strip()}})
            elif configured_toolsets is not None:
                logger.warning(
                    "api_server model_routes: ignoring non-list toolsets for route %r", alias_str
                )
            {_MODEL_ROUTE_LIMITS_MARKER}
            configured_max_iterations = cfg.get("max_iterations")
            if configured_max_iterations is not None:
                try:
                    route["max_iterations"] = max(1, min(120, int(configured_max_iterations)))
                except (TypeError, ValueError):
                    logger.warning(
                        "api_server model_routes: ignoring invalid max_iterations for route %r", alias_str
                    )
'''
        text, _ = _replace_once(
            text,
            old_route_toolsets,
            new_route_limits,
            "model route max iterations parser",
        )
        old_iteration_resolution = '''        max_iterations = _current_max_iterations()
'''
        new_iteration_resolution = '''        max_iterations = _current_max_iterations()
        if route and not session_override and route.get("max_iterations") is not None:
            max_iterations = int(route["max_iterations"])
'''
        text, _ = _replace_once(
            text,
            old_iteration_resolution,
            new_iteration_resolution,
            "model route max iterations resolution",
        )
        changes.append("per-model-route iteration limit")

    if _MODEL_ROUTE_MAX_TOKENS_MARKER not in text:
        old_iteration_parser = '''            configured_max_iterations = cfg.get("max_iterations")
            if configured_max_iterations is not None:
                try:
                    route["max_iterations"] = max(1, min(120, int(configured_max_iterations)))
                except (TypeError, ValueError):
                    logger.warning(
                        "api_server model_routes: ignoring invalid max_iterations for route %r", alias_str
                    )
'''
        new_token_parser = f'''            configured_max_iterations = cfg.get("max_iterations")
            if configured_max_iterations is not None:
                try:
                    route["max_iterations"] = max(1, min(120, int(configured_max_iterations)))
                except (TypeError, ValueError):
                    logger.warning(
                        "api_server model_routes: ignoring invalid max_iterations for route %r", alias_str
                    )
            {_MODEL_ROUTE_MAX_TOKENS_MARKER}
            configured_max_tokens = cfg.get("max_tokens")
            if configured_max_tokens is not None:
                try:
                    route["max_tokens"] = max(64, min(4096, int(configured_max_tokens)))
                except (TypeError, ValueError):
                    logger.warning(
                        "api_server model_routes: ignoring invalid max_tokens for route %r", alias_str
                    )
'''
        text, _ = _replace_once(
            text,
            old_iteration_parser,
            new_token_parser,
            "model route max tokens parser",
        )
        old_agent_limits = '''            max_iterations=max_iterations,
            quiet_mode=True,
'''
        new_agent_limits = '''            max_iterations=max_iterations,
            max_tokens=(
                int(route["max_tokens"])
                if route and not session_override and route.get("max_tokens") is not None
                else None
            ),
            quiet_mode=True,
'''
        text, _ = _replace_once(
            text,
            old_agent_limits,
            new_agent_limits,
            "model route max tokens agent",
        )
        changes.append("per-model-route output token limit")

    if _MODEL_ROUTE_MAX_TOKENS_FIX_MARKER not in text:
        old_explicit_max_tokens = '''            max_iterations=max_iterations,
            max_tokens=(
                int(route["max_tokens"])
                if route and not session_override and route.get("max_tokens") is not None
                else None
            ),
            quiet_mode=True,
'''
        new_agent_limits = '''            max_iterations=max_iterations,
            quiet_mode=True,
'''
        text, _ = _replace_once(
            text,
            old_explicit_max_tokens,
            new_agent_limits,
            "remove duplicate model route max tokens argument",
        )
        old_route_base_url = '''            if route.get("base_url"):
                runtime_kwargs["base_url"] = route["base_url"]
            logger.debug(
'''
        new_route_base_url = f'''            if route.get("base_url"):
                runtime_kwargs["base_url"] = route["base_url"]
            {_MODEL_ROUTE_MAX_TOKENS_FIX_MARKER}
            if route.get("max_tokens") is not None:
                runtime_kwargs["max_tokens"] = int(route["max_tokens"])
            logger.debug(
'''
        text, _ = _replace_once(
            text,
            old_route_base_url,
            new_route_base_url,
            "model route max tokens runtime override",
        )
        changes.append("fix per-model-route max tokens runtime override")

    cleanup_patterns = [
        (
            r'\n\n            async def _emit_responses_prompt_progress\(percent: int, label: str\) -> None:\n[\s\S]*?                await _emit_raw_hermes_event\(payload\)\n',
            "remove estimated responses prompt progress helper",
        ),
        (
            r'^\s+await _emit_responses_prompt_progress\([^\n]+\)\n',
            "remove estimated responses prompt progress call",
        ),
        (
            r'^\s+await _write_event\("hermes\.processing\.progress", \{\n(?:^\s{16}.+\n)*?^\s{12}\}\)\n',
            "remove estimated responses prompt progress event",
        ),
        (
            r'\n            async def _emit_chat_prompt_progress\(percent: int, label: str\) -> None:\n[\s\S]*?                await response\.write\(f"event: hermes\.processing\.progress\\ndata: \{json\.dumps\(payload\)\}\\n\\n"\.encode\(\)\)\n',
            "remove estimated chat prompt progress helper",
        ),
        (
            r'^\s+await _emit_chat_prompt_progress\([^\n]+\)\n',
            "remove estimated chat prompt progress call",
        ),
    ]
    for pattern, label in cleanup_patterns:
        text, count = re.subn(pattern, "\n" if pattern.startswith(r"\n") else "", text, flags=re.MULTILINE)
        if count:
            changes.append(label)

    if 'body.setdefault("return_progress", True)' not in text:
        text, count = re.subn(
            r'(^\s+async def _handle_(?:chat_completions|responses)\(self, request:[\s\S]*?^\s+body = await request\.json\(\)\n)',
            r'\1            body.setdefault("return_progress", True)\n            body.setdefault("timings_per_token", True)\n',
            text,
            flags=re.MULTILINE,
        )
        if count == 0:
            raise RuntimeError("Patch anchor not found: force llama prompt progress and timings")
        changes.append("force llama prompt progress and timings")

    if "import asyncio" not in text:
        if "import json\n" in text:
            text = text.replace("import json\n", "import asyncio\nimport json\n", 1)
        elif "import os\n" in text:
            text = text.replace("import os\n", "import asyncio\nimport os\n", 1)
        else:
            text = "import asyncio\n" + text
        changes.append("asyncio import for hub events")

    limit_replacements = {
        'payload["items"] = normalized[:500]': 'payload["items"] = normalized',
        'sorted(active, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)[:200] +\n        sorted(deleted, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)[:300]': 'sorted(active, key=lambda x: float(x.get("updatedAt") or 0), reverse=True) +\n        sorted(deleted, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)',
        'current["items"] = sorted(out, key=lambda x: float(x.get("updatedAt") or 0) if isinstance(x, dict) else 0, reverse=True)[:500]': 'current["items"] = sorted(out, key=lambda x: float(x.get("updatedAt") or 0) if isinstance(x, dict) else 0, reverse=True)',
    }
    for old, new in limit_replacements.items():
        if old in text:
            text = text.replace(old, new)
            changes.append("unlimited hub conversation archive")

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
            + _HARDWARE_DISK_BLOCK_V1
            + "\n"
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

    text, hardware_filter_upgraded = _upgrade_hardware_disk_filter(text)
    if hardware_filter_upgraded:
        changes.append("hardware disk filter v1")

    if "def _hermes_hub_server_control_snapshot" not in text:
        text, _ = _replace_once(
            text,
            "def _multimodal_validation_error(exc: ValueError, *, param: str) -> \"web.Response\":",
            '''def _hermes_hub_run_control_command(args: List[str], timeout: int = 12) -> Dict[str, Any]:
    import subprocess as _subprocess
    try:
        completed = _subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=False)
        return {"ok": completed.returncode == 0, "exit_code": completed.returncode, "stdout": completed.stdout[-20000:], "stderr": completed.stderr[-8000:]}
    except Exception as exc:
        return {"ok": False, "exit_code": -1, "stdout": "", "stderr": str(exc)}


def _hermes_hub_service_targets() -> Dict[str, Dict[str, str]]:
    return {
        "hermes": {"unit": os.environ.get("HERMES_AGENT_SERVICE", "hermes-hub.service"), "scope": "user"},
        "gateway": {"unit": os.environ.get("HERMES_HUB_SERVICE", "hermes-hub.service"), "scope": "user"},
        "llama": {"unit": os.environ.get("HERMES_LLAMA_SERVICE", "hermes-llama.service"), "scope": "system"},
        "tailscale": {"unit": os.environ.get("HERMES_TAILSCALE_SERVICE", "tailscaled.service"), "scope": "system"},
    }


def _hermes_hub_systemctl_args(target: Dict[str, str], action: str, *extra: str) -> List[str]:
    unit = str(target.get("unit") or "").strip()
    scope = str(target.get("scope") or "").strip().lower()
    if not unit:
        raise ValueError("systemd unit missing")
    if scope == "user":
        return ["systemctl", "--user", action, unit, *extra]
    if scope == "system":
        return ["sudo", "-n", "systemctl", action, unit, *extra]
    raise ValueError(f"unsupported systemd scope: {scope}")


def _hermes_hub_server_control_snapshot(log_filter: str = "") -> Dict[str, Any]:
    services = _hermes_hub_service_targets()
    service_rows = []
    for key, target in services.items():
        result = _hermes_hub_run_control_command(
            _hermes_hub_systemctl_args(
                target,
                "show",
                "--property=LoadState,ActiveState,SubState,UnitFileState,Description",
                "--no-pager",
            ),
            5,
        )
        fields = {}
        for line in result.get("stdout", "").splitlines():
            if "=" in line:
                name, value = line.split("=", 1)
                fields[name] = value
        error = result.get("stderr", "")
        if not result.get("ok") and not error:
            error = f"systemctl exit {result.get('exit_code', -1)}"
        service_rows.append({
            "id": key,
            "unit": target["unit"],
            "scope": target["scope"],
            "load": fields.get("LoadState", "unknown"),
            "active": fields.get("ActiveState", "unknown"),
            "sub": fields.get("SubState", "unknown"),
            "enabled": fields.get("UnitFileState", "unknown"),
            "description": fields.get("Description", ""),
            "error": error,
        })
    log_parts = []
    user_units = sorted({target["unit"] for target in services.values() if target["scope"] == "user"})
    system_units = sorted({target["unit"] for target in services.values() if target["scope"] == "system"})
    if user_units:
        command = ["journalctl", "--user"]
        for unit in user_units:
            command.extend(["-u", unit])
        command.extend(["-n", "250", "--no-pager", "--output=short-iso"])
        log_parts.append(_hermes_hub_run_control_command(command, 8).get("stdout", ""))
    if system_units:
        command = ["sudo", "-n", "journalctl"]
        for unit in system_units:
            command.extend(["-u", unit])
        command.extend(["-n", "250", "--no-pager", "--output=short-iso"])
        log_parts.append(_hermes_hub_run_control_command(command, 8).get("stdout", ""))
    logs = "\\n".join(part for part in log_parts if part)
    if log_filter:
        wanted = log_filter.lower()
        logs = "\\n".join(line for line in logs.splitlines() if wanted in line.lower())
    gpu = _hermes_hub_run_control_command(["nvidia-smi", "--query-compute-apps=pid,process_name,used_memory", "--format=csv,noheader,nounits"], 5)
    processes = _hermes_hub_run_control_command(["ps", "-eo", "pid,etimes,pcpu,pmem,comm,args", "--sort=-pcpu"], 5).get("stdout", "")
    active = [line for line in processes.splitlines() if any(name in line.lower() for name in ("hermes", "llama", "python"))][:80]
    compatible = all(row["load"] == "loaded" and not row["error"] for row in service_rows)
    return {"status": "ok", "timestamp": time.time(), "services": service_rows, "logs": logs[-40000:], "active_runs": active, "gpu_processes": gpu.get("stdout", ""), "queue": _hermes_hub_read_json(_hermes_hub_storage_path("HERMES_HUB_RUN_QUEUE_PATH", "run_queue.json"), {"items": []}), "compatibility_warning": "" if compatible else "Una o piu unita systemd non sono installate o non sono leggibili."}


def _hermes_hub_server_control_action(body: Dict[str, Any]) -> Dict[str, Any]:
    service = str(body.get("service") or "").strip().lower()
    action = str(body.get("action") or "").strip().lower()
    services = _hermes_hub_service_targets()
    target = services.get(service)
    if target is None or action not in {"start", "stop", "restart"}:
        return {"status": "denied", "message": "Servizio o azione fuori allowlist."}
    command = _hermes_hub_systemctl_args(target, action)
    scheduled = target["scope"] == "user" and service in {"hermes", "gateway"} and action in {"stop", "restart"}
    if scheduled:
        transient = f"hermes-hub-control-{int(time.time() * 1000)}"
        command = ["systemd-run", "--user", f"--unit={transient}", "--collect", "--on-active=1s", "--no-block", *command]
    result = _hermes_hub_run_control_command(command, 45)
    tool = "systemd-run" if scheduled else "systemctl"
    _hermes_hub_audit_event({"event": "server.service", "summary": f"{action} {service}", "tool": tool, "risk": "high" if action in {"stop", "restart"} else "medium", "status": "ok" if result["ok"] else "error"})
    return {"status": "scheduled" if scheduled and result["ok"] else ("ok" if result["ok"] else "error"), "service": service, "action": action, "unit": target["unit"], "scope": target["scope"], "scheduled": scheduled, **result}


def _hermes_hub_server_maintenance(body: Dict[str, Any]) -> Dict[str, Any]:
    operation = str(body.get("operation") or "").strip().lower()
    commands = {
        "update": os.environ.get("HERMES_HUB_UPDATE_COMMAND", ""),
        "rollback": os.environ.get("HERMES_HUB_ROLLBACK_COMMAND", ""),
        "backup": os.environ.get("HERMES_HUB_BACKUP_COMMAND", ""),
        "restore": os.environ.get("HERMES_HUB_RESTORE_COMMAND", ""),
        "diagnostic": os.environ.get("HERMES_HUB_DIAGNOSTIC_COMMAND", ""),
    }
    command = commands.get(operation, "")
    if not command:
        return {"status": "unavailable", "operation": operation, "message": "Operazione non configurata sul server."}
    import shlex as _shlex
    result = _hermes_hub_run_control_command(_shlex.split(command), 900)
    _hermes_hub_audit_event({"event": "server.maintenance", "summary": operation, "tool": "maintenance", "risk": "high", "status": "ok" if result["ok"] else "error"})
    return {"status": "ok" if result["ok"] else "error", "operation": operation, **result}


def _multimodal_validation_error(exc: ValueError, *, param: str) -> "web.Response":''',
            "server control collectors",
        )
        changes.append("server control collectors")

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
    item["updated_at"] = float(item.get("updated_at") or time.time())
    items[:] = [existing for existing in items if not isinstance(existing, dict) or str(existing.get("id", "")) != str(item.get("id", ""))]
    items.append(item)
    items.sort(key=lambda value: float(value.get("updated_at") or value.get("created_at") or 0) if isinstance(value, dict) else 0, reverse=True)
    del items[1000:]
    current["updated_at"] = time.time()
    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_STATE_PATH", "hub_state.json"), current)
    _hermes_hub_audit_event({"event": str(item.get("type") or "hub.state"), "summary": str(item.get("value") or item.get("status") or "")[:500], "project": item.get("project_id"), "device": item.get("device"), "risk": "low"})
    return item


def _hermes_hub_delete_state(state_id: str) -> Dict[str, Any]:
    current = _hermes_hub_state_payload()
    before = len(current.get("items", []))
    current["items"] = [item for item in current.get("items", []) if str(item.get("id", "")) != str(state_id)]
    current["updated_at"] = time.time()
    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_STATE_PATH", "hub_state.json"), current)
    _hermes_hub_audit_event({"event": "hub.state.delete", "summary": str(state_id), "device": "server", "risk": "low"})
    return {"object": "hermes.hub.state.delete", "deleted": before - len(current["items"]), "id": state_id}


def _hermes_hub_conversations_payload() -> Dict[str, Any]:
    path = _hermes_hub_storage_path("HERMES_HUB_CONVERSATIONS_PATH", "hub_conversations.json")
    payload = _hermes_hub_read_json(path, {"items": []})
    items = payload.get("items")
    if not isinstance(items, list):
        items = []
    normalized = []
    seen = set()
    try:
        retention_days = float(os.environ.get("HERMES_HUB_DELETED_CONVERSATION_RETENTION_DAYS", "30"))
    except Exception:
        retention_days = 30.0
    deleted_cutoff = time.time() * 1000 - max(retention_days, 0.0) * 86400000
    for item in items:
        conv = _hermes_hub_normalize_conversation(item)
        if not conv:
            continue
        if conv.get("deletedAt"):
            try:
                deleted_at = float(conv.get("deletedAt") or 0)
            except Exception:
                deleted_at = 0
            if deleted_at > 0 and deleted_at < deleted_cutoff:
                continue
        cid = str(conv.get("id") or "")
        if cid in seen:
            continue
        seen.add(cid)
        normalized.append(conv)
    normalized.sort(key=lambda x: float(x.get("updatedAt") or x.get("updated_at") or 0), reverse=True)
    payload["items"] = normalized
    payload["object"] = "hermes.hub.conversations"
    payload["status"] = "ok"
    payload["path"] = str(path)
    payload["description"] = "Archivio chat Hermes Hub condiviso tra Windows, Android e reinstallazioni app."
    return payload


_hermes_hub_conversation_event_subscribers = set()


def _hermes_hub_conversation_event_payload(reason: str, result: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    current = _hermes_hub_conversations_payload()
    return {
        "object": "hermes.hub.conversations.event",
        "reason": reason,
        "updated_at": time.time(),
        "count": len(current.get("items", [])) if isinstance(current.get("items"), list) else 0,
        "merged": result.get("merged") if isinstance(result, dict) else None,
        "id": result.get("id") if isinstance(result, dict) else None,
    }


def _hermes_hub_publish_conversation_event(reason: str, result: Optional[Dict[str, Any]] = None) -> None:
    if not _hermes_hub_conversation_event_subscribers:
        return
    payload = _hermes_hub_conversation_event_payload(reason, result)
    dead = []
    for queue in list(_hermes_hub_conversation_event_subscribers):
        try:
            queue.put_nowait(payload)
        except Exception:
            dead.append(queue)
    for queue in dead:
        _hermes_hub_conversation_event_subscribers.discard(queue)


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
        "bookmarked": bool(raw.get("bookmarked", raw.get("isBookmarked", False))),
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
        "projectId": raw.get("projectId") or raw.get("project_id") or None,
        "workspacePath": raw.get("workspacePath") or raw.get("workspace_path") or None,
        "repositoryUrl": raw.get("repositoryUrl") or raw.get("repository_url") or None,
        "projectInstructions": raw.get("projectInstructions") or raw.get("project_instructions") or None,
        "projectMemory": raw.get("projectMemory") or raw.get("project_memory") or None,
        "authorizedTools": raw.get("authorizedTools") if isinstance(raw.get("authorizedTools"), list) else (raw.get("authorized_tools") if isinstance(raw.get("authorized_tools"), list) else []),
        "artifactType": raw.get("artifactType") or raw.get("artifact_type") or None,
        "artifactUrl": raw.get("artifactUrl") or raw.get("artifact_url") or None,
        "artifactFileName": raw.get("artifactFileName") or raw.get("artifact_file_name") or None,
        "artifactMimeType": raw.get("artifactMimeType") or raw.get("artifact_mime_type") or None,
        "sourceConversationId": raw.get("sourceConversationId") or raw.get("source_conversation_id") or None,
        "sourceRunId": raw.get("sourceRunId") or raw.get("source_run_id") or None,
        "version": int(_hermes_hub_number(raw.get("version"), 0)),
        "tags": raw.get("tags") if isinstance(raw.get("tags"), list) else [],
        "folder": raw.get("folder") or None,
        "summary": raw.get("summary") or None,
        "parentConversationId": raw.get("parentConversationId") or raw.get("parent_conversation_id") or None,
        "branchFromMessageId": raw.get("branchFromMessageId") or raw.get("branch_from_message_id") or None,
        "linkedConversationIds": raw.get("linkedConversationIds") if isinstance(raw.get("linkedConversationIds"), list) else (raw.get("linked_conversation_ids") if isinstance(raw.get("linked_conversation_ids"), list) else []),
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
        sorted(active, key=lambda x: float(x.get("updatedAt") or 0), reverse=True) +
        sorted(deleted, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)
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
    current["items"] = sorted(out, key=lambda x: float(x.get("updatedAt") or 0) if isinstance(x, dict) else 0, reverse=True)
    current["updated_at"] = time.time()
    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_CONVERSATIONS_PATH", "hub_conversations.json"), current)
    return {"object": "hermes.hub.conversations.delete", "deleted": 1 if found else 0, "id": conversation_id, "tombstone": True}


def _hermes_hub_audit_payload(filters: Optional[Dict[str, str]] = None) -> Dict[str, Any]:
    path = _hermes_hub_storage_path("HERMES_HUB_AUDIT_PATH", "hub_audit.json")
    payload = _hermes_hub_read_json(path, {"items": []})
    items = [item for item in payload.get("items", []) if isinstance(item, dict)]
    for key, value in (filters or {}).items():
        wanted = str(value or "").strip().lower()
        if wanted:
            items = [item for item in items if wanted in str(item.get(key, "")).lower()]
    items.sort(key=lambda item: float(item.get("timestamp") or 0), reverse=True)
    return {"object": "hermes.hub.audit", "status": "ok", "items": items[:2000], "path": str(path)}


def _hermes_hub_audit_event(payload: Dict[str, Any]) -> Dict[str, Any]:
    current = _hermes_hub_audit_payload()
    path = _hermes_hub_storage_path("HERMES_HUB_AUDIT_PATH", "hub_audit.json")
    item = {
        "id": str((payload or {}).get("id") or f"audit_{int(time.time() * 1000)}"),
        "timestamp": float((payload or {}).get("timestamp") or time.time()),
        "event": str((payload or {}).get("event") or "hub.event")[:120],
        "summary": str((payload or {}).get("summary") or "")[:4000],
        "project": str((payload or {}).get("project") or (payload or {}).get("project_id") or "")[:200],
        "run": str((payload or {}).get("run") or (payload or {}).get("run_id") or "")[:200],
        "tool": str((payload or {}).get("tool") or "")[:200],
        "device": str((payload or {}).get("device") or "server")[:200],
        "risk": str((payload or {}).get("risk") or "low")[:40],
        "status": str((payload or {}).get("status") or "ok")[:80],
        "metadata": (payload or {}).get("metadata") if isinstance((payload or {}).get("metadata"), dict) else {},
    }
    items = current.get("items", [])
    items.insert(0, item)
    del items[5000:]
    _hermes_hub_write_json(path, {"items": items, "updated_at": time.time()})
    return item


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
        item.setdefault("category", item.get("kind") or "Generale")
        item.setdefault("priority", item.get("severity") or "Normale")
        item.setdefault("archived", False)
        item.setdefault("snoozed_until", None)
        if unread_only and (item.get("read_at") or item.get("archived") or float(item.get("snoozed_until") or 0) > time.time()):
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
        "category": str((payload or {}).get("category") or (payload or {}).get("kind") or "Generale"),
        "priority": str((payload or {}).get("priority") or (payload or {}).get("severity") or "Normale"),
        "archived": False,
        "snoozed_until": None,
        "automation_id": str((payload or {}).get("automation_id") or (payload or {}).get("cron_id") or ""),
        "run_id": str((payload or {}).get("run_id") or ""),
        "file_url": str((payload or {}).get("file_url") or (payload or {}).get("url") or ""),
        "project_id": str((payload or {}).get("project_id") or ""),
        "actions": (payload or {}).get("actions") if isinstance((payload or {}).get("actions"), list) else [],
        "payload": (payload or {}).get("payload") if isinstance((payload or {}).get("payload"), dict) else {},
        "created_at": float((payload or {}).get("created_at") or time.time()),
        "read_at": None,
    }
    items.append(item)
    items.sort(key=lambda x: float(x.get("created_at") or 0), reverse=True)
    del items[500:]
    current["updated_at"] = time.time()
    _hermes_hub_write_json(path, current)
    _hermes_hub_audit_event({"event": "notification.created", "summary": title, "project": item.get("project_id"), "run": item.get("run_id"), "device": "server", "risk": item.get("priority") or "low"})
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
        if "snoozed_until" in (payload or {}):
            try:
                item["snoozed_until"] = float(payload.get("snoozed_until") or 0) or None
            except Exception:
                item["snoozed_until"] = None
        if "priority" in (payload or {}):
            item["priority"] = str(payload.get("priority") or "Normale")[:40]
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
        raw = str(_Path.home() / "video")
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
    raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or str(_Path.home() / "video")
    raw_upload = os.environ.get("HERMES_HUB_UPLOAD_PATH") or str(_Path.home() / ".hermes" / "hub_uploads")
    raw_news = os.environ.get("HERMES_NEWS_LIBRARY_PATH") or str(_Path.home() / "news")
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

        if len(basename) >= 4:
            upload_root = _Path(os.environ.get("HERMES_HUB_UPLOAD_PATH", str(_Path.home() / ".hermes" / "hub_uploads"))).expanduser()
            try:
                upload_matches = [candidate.resolve() for candidate in upload_root.rglob(f"{basename}*") if candidate.is_file()]
                upload_unique = {str(candidate): candidate for candidate in upload_matches}
                if len(upload_unique) == 1:
                    return next(iter(upload_unique.values()))
            except Exception:
                pass

            matches: List[_Path] = []
            for root in roots:
                if root == upload_root:
                    continue
                try:
                    matches.extend(candidate.resolve() for candidate in root.rglob(f"{basename}*") if candidate.is_file())
                except Exception:
                    continue
            unique = {str(candidate): candidate for candidate in matches}
            if len(unique) == 1:
                return next(iter(unique.values()))
    return None


def _hermes_hub_is_tailnet_peer(request: "web.Request") -> bool:
    import ipaddress as _ipaddress

    raw = (request.headers.get("X-Forwarded-For", "").split(",", 1)[0].strip() or request.remote or "")
    try:
        ip = _ipaddress.ip_address(raw)
    except Exception:
        return False
    return ip.is_loopback or ip in _ipaddress.ip_network("100.64.0.0/10")


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

    if "def _hermes_hub_audit_payload" not in text:
        text, _ = _replace_once(
            text,
            "def _hermes_hub_notifications_payload(unread_only: bool = False) -> Dict[str, Any]:",
            '''def _hermes_hub_audit_payload(filters: Optional[Dict[str, str]] = None) -> Dict[str, Any]:
    path = _hermes_hub_storage_path("HERMES_HUB_AUDIT_PATH", "hub_audit.json")
    payload = _hermes_hub_read_json(path, {"items": []})
    items = [item for item in payload.get("items", []) if isinstance(item, dict)]
    for key, value in (filters or {}).items():
        wanted = str(value or "").strip().lower()
        if wanted:
            items = [item for item in items if wanted in str(item.get(key, "")).lower()]
    items.sort(key=lambda item: float(item.get("timestamp") or 0), reverse=True)
    return {"object": "hermes.hub.audit", "status": "ok", "items": items[:2000], "path": str(path)}


def _hermes_hub_audit_event(payload: Dict[str, Any]) -> Dict[str, Any]:
    current = _hermes_hub_audit_payload()
    path = _hermes_hub_storage_path("HERMES_HUB_AUDIT_PATH", "hub_audit.json")
    item = {
        "id": str((payload or {}).get("id") or f"audit_{int(time.time() * 1000)}"),
        "timestamp": float((payload or {}).get("timestamp") or time.time()),
        "event": str((payload or {}).get("event") or "hub.event")[:120],
        "summary": str((payload or {}).get("summary") or "")[:4000],
        "project": str((payload or {}).get("project") or (payload or {}).get("project_id") or "")[:200],
        "run": str((payload or {}).get("run") or (payload or {}).get("run_id") or "")[:200],
        "tool": str((payload or {}).get("tool") or "")[:200],
        "device": str((payload or {}).get("device") or "server")[:200],
        "risk": str((payload or {}).get("risk") or "low")[:40],
        "status": str((payload or {}).get("status") or "ok")[:80],
        "metadata": (payload or {}).get("metadata") if isinstance((payload or {}).get("metadata"), dict) else {},
    }
    items = current.get("items", [])
    items.insert(0, item)
    del items[5000:]
    _hermes_hub_write_json(path, {"items": items, "updated_at": time.time()})
    return item


def _hermes_hub_notifications_payload(unread_only: bool = False) -> Dict[str, Any]:''',
            "audit storage helpers upgrade",
        )
        changes.append("audit storage helpers upgrade")

    if '"event": str(item.get("type") or "hub.state")' not in text:
        text, _ = _replace_once(
            text,
            '    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_STATE_PATH", "hub_state.json"), current)\n    return item',
            '    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_STATE_PATH", "hub_state.json"), current)\n    _hermes_hub_audit_event({"event": str(item.get("type") or "hub.state"), "summary": str(item.get("value") or item.get("status") or "")[:500], "project": item.get("project_id"), "device": item.get("device"), "risk": "low"})\n    return item',
            "audit continuity state changes",
        )
        changes.append("audit continuity state changes")

    if "def _hermes_hub_serialized_store_mutation" not in text:
        text, _ = _replace_once(
            text,
            'def _hermes_hub_storage_path(env_name: str, default_name: str) -> "Path":',
            '''def _hermes_hub_serialized_store_mutation(function):
    import functools as _functools
    import threading as _threading

    global _hermes_hub_store_mutex
    if "_hermes_hub_store_mutex" not in globals():
        _hermes_hub_store_mutex = _threading.RLock()

    @_functools.wraps(function)
    def _wrapped(*args, **kwargs):
        with _hermes_hub_store_mutex:
            return function(*args, **kwargs)
    return _wrapped


def _hermes_hub_storage_path(env_name: str, default_name: str) -> "Path":''',
            "serialized hub store mutations",
        )
        for function_name in (
            "_hermes_hub_patch_memory",
            "_hermes_hub_add_state",
            "_hermes_hub_delete_state",
            "_hermes_hub_merge_conversations",
            "_hermes_hub_delete_conversation",
            "_hermes_hub_audit_event",
            "_hermes_hub_add_notification",
            "_hermes_hub_patch_notification",
        ):
            signature = f"def {function_name}("
            text = text.replace(signature, f"@_hermes_hub_serialized_store_mutation\n{signature}")
        changes.append("serialized hub store mutations")

    if "# HERMES_HUB_ATOMIC_STORE_WRITE_V2" not in text:
        old_write = '''    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8") as handle:
        _json.dump(payload, handle, ensure_ascii=False, indent=2)
    tmp.replace(path)'''
        new_write = '''    # HERMES_HUB_ATOMIC_STORE_WRITE_V2
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.{os.getpid()}-{time.time_ns()}.tmp")
    try:
        with tmp.open("w", encoding="utf-8") as handle:
            _json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)'''
        if old_write in text:
            text, _ = _replace_once(text, old_write, new_write, "atomic hub store write v2")
            changes.append("atomic hub store write v2")

    if "def _hermes_hub_resolve_media_path" not in text:
        text, _ = _replace_once(
            text,
            "def _multimodal_validation_error(exc: ValueError, *, param: str) -> \"web.Response\":",
            '''def _hermes_hub_media_roots() -> List["Path"]:
    from pathlib import Path as _Path

    roots: List[_Path] = []
    raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or str(_Path.home() / "video")
    raw_upload = os.environ.get("HERMES_HUB_UPLOAD_PATH") or str(_Path.home() / ".hermes" / "hub_uploads")
    raw_news = os.environ.get("HERMES_NEWS_LIBRARY_PATH") or str(_Path.home() / "news")
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

        if len(basename) >= 4:
            upload_root = _Path(os.environ.get("HERMES_HUB_UPLOAD_PATH", str(_Path.home() / ".hermes" / "hub_uploads"))).expanduser()
            try:
                upload_matches = [candidate.resolve() for candidate in upload_root.rglob(f"{basename}*") if candidate.is_file()]
                upload_unique = {str(candidate): candidate for candidate in upload_matches}
                if len(upload_unique) == 1:
                    return next(iter(upload_unique.values()))
            except Exception:
                pass

            matches: List[_Path] = []
            for root in roots:
                if root == upload_root:
                    continue
                try:
                    matches.extend(candidate.resolve() for candidate in root.rglob(f"{basename}*") if candidate.is_file())
                except Exception:
                    continue
            unique = {str(candidate): candidate for candidate in matches}
            if len(unique) == 1:
                return next(iter(unique.values()))
    return None


def _hermes_hub_is_tailnet_peer(request: "web.Request") -> bool:
    import ipaddress as _ipaddress

    raw = (request.headers.get("X-Forwarded-For", "").split(",", 1)[0].strip() or request.remote or "")
    try:
        ip = _ipaddress.ip_address(raw)
    except Exception:
        return False
    return ip.is_loopback or ip in _ipaddress.ip_network("100.64.0.0/10")


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
    try:
        retention_days = float(os.environ.get("HERMES_HUB_DELETED_CONVERSATION_RETENTION_DAYS", "30"))
    except Exception:
        retention_days = 30.0
    deleted_cutoff = time.time() * 1000 - max(retention_days, 0.0) * 86400000
    for item in items:
        conv = _hermes_hub_normalize_conversation(item)
        if not conv:
            continue
        if conv.get("deletedAt"):
            try:
                deleted_at = float(conv.get("deletedAt") or 0)
            except Exception:
                deleted_at = 0
            if deleted_at > 0 and deleted_at < deleted_cutoff:
                continue
        cid = str(conv.get("id") or "")
        if cid in seen:
            continue
        seen.add(cid)
        normalized.append(conv)
    normalized.sort(key=lambda x: float(x.get("updatedAt") or x.get("updated_at") or 0), reverse=True)
    payload["items"] = normalized
    payload["object"] = "hermes.hub.conversations"
    payload["status"] = "ok"
    payload["path"] = str(path)
    payload["description"] = "Archivio chat Hermes Hub condiviso tra Windows, Android e reinstallazioni app."
    return payload


_hermes_hub_conversation_event_subscribers = set()


def _hermes_hub_conversation_event_payload(reason: str, result: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    current = _hermes_hub_conversations_payload()
    return {
        "object": "hermes.hub.conversations.event",
        "reason": reason,
        "updated_at": time.time(),
        "count": len(current.get("items", [])) if isinstance(current.get("items"), list) else 0,
        "merged": result.get("merged") if isinstance(result, dict) else None,
        "id": result.get("id") if isinstance(result, dict) else None,
    }


def _hermes_hub_publish_conversation_event(reason: str, result: Optional[Dict[str, Any]] = None) -> None:
    if not _hermes_hub_conversation_event_subscribers:
        return
    payload = _hermes_hub_conversation_event_payload(reason, result)
    dead = []
    for queue in list(_hermes_hub_conversation_event_subscribers):
        try:
            queue.put_nowait(payload)
        except Exception:
            dead.append(queue)
    for queue in dead:
        _hermes_hub_conversation_event_subscribers.discard(queue)


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
        sorted(active, key=lambda x: float(x.get("updatedAt") or 0), reverse=True) +
        sorted(deleted, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)
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
    current["items"] = sorted(out, key=lambda x: float(x.get("updatedAt") or 0) if isinstance(x, dict) else 0, reverse=True)
    current["updated_at"] = time.time()
    _hermes_hub_write_json(_hermes_hub_storage_path("HERMES_HUB_CONVERSATIONS_PATH", "hub_conversations.json"), current)
    return {"object": "hermes.hub.conversations.delete", "deleted": 1 if found else 0, "id": conversation_id, "tombstone": True}


def _hermes_hub_notifications_payload(unread_only: bool = False) -> Dict[str, Any]:''',
            "hub conversations storage helpers",
        )
        changes.append("hub conversations storage helpers")

    if "HERMES_HUB_DELETED_CONVERSATION_RETENTION_DAYS" not in text and "def _hermes_hub_conversations_payload" in text:
        text, _ = _replace_once(
            text,
            "    normalized = []\n    seen = set()\n    for item in items:\n",
            "    normalized = []\n"
            "    seen = set()\n"
            "    try:\n"
            "        retention_days = float(os.environ.get(\"HERMES_HUB_DELETED_CONVERSATION_RETENTION_DAYS\", \"30\"))\n"
            "    except Exception:\n"
            "        retention_days = 30.0\n"
            "    deleted_cutoff = time.time() * 1000 - max(retention_days, 0.0) * 86400000\n"
            "    for item in items:\n",
            "hub conversation tombstone retention",
        )
        text, _ = _replace_once(
            text,
            "        if not conv:\n            continue\n        cid = str(conv.get(\"id\") or \"\")\n",
            "        if not conv:\n"
            "            continue\n"
            "        if conv.get(\"deletedAt\"):\n"
            "            try:\n"
            "                deleted_at = float(conv.get(\"deletedAt\") or 0)\n"
            "            except Exception:\n"
            "                deleted_at = 0\n"
            "            if deleted_at > 0 and deleted_at < deleted_cutoff:\n"
            "                continue\n"
            "        cid = str(conv.get(\"id\") or \"\")\n",
            "hub conversation tombstone retention filter",
        )
        changes.append("hub conversation tombstone retention")

    if "_hermes_hub_conversation_event_subscribers" not in text and "def _hermes_hub_number" in text:
        text, _ = _replace_once(
            text,
            "\n\ndef _hermes_hub_number(value: Any, fallback: float = 0.0) -> float:",
            '''\n\n_hermes_hub_conversation_event_subscribers = set()


def _hermes_hub_conversation_event_payload(reason: str, result: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    current = _hermes_hub_conversations_payload()
    return {
        "object": "hermes.hub.conversations.event",
        "reason": reason,
        "updated_at": time.time(),
        "count": len(current.get("items", [])) if isinstance(current.get("items"), list) else 0,
        "merged": result.get("merged") if isinstance(result, dict) else None,
        "id": result.get("id") if isinstance(result, dict) else None,
    }


def _hermes_hub_publish_conversation_event(reason: str, result: Optional[Dict[str, Any]] = None) -> None:
    if not _hermes_hub_conversation_event_subscribers:
        return
    payload = _hermes_hub_conversation_event_payload(reason, result)
    dead = []
    for queue in list(_hermes_hub_conversation_event_subscribers):
        try:
            queue.put_nowait(payload)
        except Exception:
            dead.append(queue)
    for queue in dead:
        _hermes_hub_conversation_event_subscribers.discard(queue)


def _hermes_hub_number(value: Any, fallback: float = 0.0) -> float:''',
            "hub conversation event helpers",
        )
        changes.append("hub conversation event helpers")

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
        sorted(active, key=lambda x: float(x.get("updatedAt") or 0), reverse=True) +
        sorted(deleted, key=lambda x: float(x.get("updatedAt") or 0), reverse=True)
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
    current["items"] = sorted(out, key=lambda x: float(x.get("updatedAt") or 0) if isinstance(x, dict) else 0, reverse=True)
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
    raw = query_path or os.environ.get("HERMES_NEWS_LIBRARY_PATH") or str(_Path.home() / "news")
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
            '    raw = os.environ.get("HERMES_NEWS_LIBRARY_PATH") or str(_Path.home() / "news")\n',
            '    query_path = ""\n'
            '    if request is not None:\n'
            '        query_path = (request.query.get("path") or request.query.get("library_path") or "").strip()\n'
            '    raw = query_path or os.environ.get("HERMES_NEWS_LIBRARY_PATH") or str(_Path.home() / "news")\n'
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
        news_before = text
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
        if text != news_before:
            changes.append("news library folder only")

    if 'raw_news = os.environ.get("HERMES_NEWS_LIBRARY_PATH") or str(_Path.home() / "news")' not in text and 'raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or str(_Path.home() / "video")' in text:
        text = text.replace(
            '    raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or str(_Path.home() / "video")\n'
            '    for part in os.environ.get("HERMES_MEDIA_ROOTS", "").split(os.pathsep):\n',
            '    raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or str(_Path.home() / "video")\n'
            '    raw_news = os.environ.get("HERMES_NEWS_LIBRARY_PATH") or str(_Path.home() / "news")\n'
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
            and 'raw_news = os.environ.get("HERMES_NEWS_LIBRARY_PATH") or str(_Path.home() / "news")' not in media_roots_body
            and 'raw_upload = os.environ.get("HERMES_HUB_UPLOAD_PATH")' in media_roots_body
        ):
            start, end = media_roots_match.span("body")
            fixed_body = media_roots_body.replace(
                '    raw_upload = os.environ.get("HERMES_HUB_UPLOAD_PATH") or str(_Path.home() / ".hermes" / "hub_uploads")\n',
                '    raw_upload = os.environ.get("HERMES_HUB_UPLOAD_PATH") or str(_Path.home() / ".hermes" / "hub_uploads")\n'
                '    raw_news = os.environ.get("HERMES_NEWS_LIBRARY_PATH") or str(_Path.home() / "news")\n',
                1,
            )
            if fixed_body != media_roots_body:
                text = text[:start] + fixed_body + text[end:]
                changes.append("media roots raw_news repair")

    if 'def _hermes_hub_is_tailnet_peer(request: "web.Request") -> bool:' not in text and 'def _hermes_hub_media_cache_path(source: "Path") -> "Path":' in text:
        text = text.replace(
            '\n\ndef _hermes_hub_media_cache_path(source: "Path") -> "Path":\n',
            '\n\n'
            'def _hermes_hub_is_tailnet_peer(request: "web.Request") -> bool:\n'
            '    import ipaddress as _ipaddress\n'
            '\n'
            '    raw = (request.headers.get("X-Forwarded-For", "").split(",", 1)[0].strip() or request.remote or "")\n'
            '    try:\n'
            '        ip = _ipaddress.ip_address(raw)\n'
            '    except Exception:\n'
            '        return False\n'
            '    return ip.is_loopback or ip in _ipaddress.ip_network("100.64.0.0/10")\n'
            '\n\n'
            'def _hermes_hub_media_cache_path(source: "Path") -> "Path":\n',
            1,
        )
        changes.append("media proxy tailnet helper")

    if 'root.rglob(f"{basename}*")' not in text and 'def _hermes_hub_resolve_media_path' in text:
        media_prefix_before = text
        text = text.replace(
            '    if basename and basename == decoded:\n'
            '        for root in roots:\n'
            '            try:\n'
            '                for candidate in root.rglob(basename):\n'
            '                    if candidate.is_file():\n'
            '                        return candidate.resolve()\n'
            '            except Exception:\n'
            '                continue\n'
            '    return None\n',
            '    if basename and basename == decoded:\n'
            '        for root in roots:\n'
            '            try:\n'
            '                for candidate in root.rglob(basename):\n'
            '                    if candidate.is_file():\n'
            '                        return candidate.resolve()\n'
            '            except Exception:\n'
            '                continue\n'
            '\n'
            '        if len(basename) >= 4:\n'
            '            matches: List[_Path] = []\n'
            '            for root in roots:\n'
            '                try:\n'
            '                    matches.extend(candidate.resolve() for candidate in root.rglob(f"{basename}*") if candidate.is_file())\n'
            '                except Exception:\n'
            '                    continue\n'
            '            unique = {str(candidate): candidate for candidate in matches}\n'
            '            if len(unique) == 1:\n'
            '                return next(iter(unique.values()))\n'
            '    return None\n',
            1,
        )
        if text != media_prefix_before:
            changes.append("media proxy basename prefix fallback")

    if 'upload_matches = [candidate.resolve() for candidate in upload_root.rglob(f"{basename}*")' not in text and 'root.rglob(f"{basename}*")' in text:
        text = text.replace(
            '        if len(basename) >= 4:\n'
            '            matches: List[_Path] = []\n'
            '            for root in roots:\n'
            '                try:\n'
            '                    matches.extend(candidate.resolve() for candidate in root.rglob(f"{basename}*") if candidate.is_file())\n'
            '                except Exception:\n'
            '                    continue\n'
            '            unique = {str(candidate): candidate for candidate in matches}\n'
            '            if len(unique) == 1:\n'
            '                return next(iter(unique.values()))\n',
            '        if len(basename) >= 4:\n'
            '            upload_root = _Path(os.environ.get("HERMES_HUB_UPLOAD_PATH", str(_Path.home() / ".hermes" / "hub_uploads"))).expanduser()\n'
            '            try:\n'
            '                upload_matches = [candidate.resolve() for candidate in upload_root.rglob(f"{basename}*") if candidate.is_file()]\n'
            '                upload_unique = {str(candidate): candidate for candidate in upload_matches}\n'
            '                if len(upload_unique) == 1:\n'
            '                    return next(iter(upload_unique.values()))\n'
            '            except Exception:\n'
            '                pass\n'
            '\n'
            '            matches: List[_Path] = []\n'
            '            for root in roots:\n'
            '                if root == upload_root:\n'
            '                    continue\n'
            '                try:\n'
            '                    matches.extend(candidate.resolve() for candidate in root.rglob(f"{basename}*") if candidate.is_file())\n'
            '                except Exception:\n'
            '                    continue\n'
            '            unique = {str(candidate): candidate for candidate in matches}\n'
            '            if len(unique) == 1:\n'
            '                return next(iter(unique.values()))\n',
            1,
        )
        changes.append("media proxy upload prefix priority")

    if 'raw_upload = os.environ.get("HERMES_HUB_UPLOAD_PATH")' not in text and 'raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or str(_Path.home() / "video")' in text:
        text = text.replace(
            '    raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or str(_Path.home() / "video")\n'
            '    for part in os.environ.get("HERMES_MEDIA_ROOTS", "").split(os.pathsep):\n',
            '    raw_video = os.environ.get("HERMES_VIDEO_LIBRARY_PATH") or str(_Path.home() / "video")\n'
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

    if not re.search(r":compatv[23]", text) and "def _hermes_hub_transcode_mp4" in text:
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

    if "def _hermes_hub_project_system_prompt" not in text:
        text, _ = _replace_once(
            text,
            "def _multimodal_validation_error(exc: ValueError, *, param: str) -> \"web.Response\":",
            'def _hermes_hub_project_system_prompt(body: Optional[Dict[str, Any]]) -> Optional[str]:\n'
            '    """Return only the explicit, bounded project prompt from Hermes Hub metadata."""\n'
            '    if not isinstance(body, dict):\n'
            '        return None\n'
            '    metadata = body.get("metadata")\n'
            '    if not isinstance(metadata, dict):\n'
            '        return None\n'
            '    project = metadata.get("project_context")\n'
            '    if not isinstance(project, dict):\n'
            '        return None\n'
            '    prompt = project.get("system_prompt")\n'
            '    if not isinstance(prompt, str):\n'
            '        return None\n'
            '    prompt = prompt.strip()\n'
            '    return prompt[:20000] or None\n'
            '\n'
            '\n'
            "def _multimodal_validation_error(exc: ValueError, *, param: str) -> \"web.Response\":",
            "hermes hub project system prompt helper",
        )
        changes.append("hermes hub project system prompt helper")

    if "accepted_api_keys = _hermes_hub_api_keys(self._api_key)" not in text:
        plain_auth = (
            '        if not self._api_key:\n'
            '            return None\n'
            '\n'
            '        auth_header = request.headers.get("Authorization", "")\n'
            '        if auth_header.startswith("Bearer "):\n'
            '            token = auth_header[7:].strip()\n'
            '            if hmac.compare_digest(token, self._api_key):\n'
            '                return None  # Auth OK\n'
        )
        hardened_auth = (
            '        if not self._api_key:\n'
            '            return None\n'
            '\n'
            '        auth_header = request.headers.get("Authorization", "")\n'
            '        if auth_header.startswith("Bearer "):\n'
            '            token = auth_header[7:].strip()\n'
            '            # Compare as bytes: ``hmac.compare_digest`` raises TypeError on a\n'
            '            # str containing non-ASCII characters, and ``token`` is the raw\n'
            '            # client-supplied header. A stray non-ASCII byte in the key would\n'
            '            # otherwise crash this handler (500) instead of returning a clean\n'
            '            # 401. Encoding both sides keeps the timing-safe comparison and\n'
            "            # matches web_server.py's dashboard-token check.\n"
            '            if hmac.compare_digest(token.encode(), self._api_key.encode()):\n'
            '                return None  # Auth OK\n'
        )
        replacement = (
            '        accepted_api_keys = _hermes_hub_api_keys(self._api_key)\n'
            '        if not accepted_api_keys:\n'
            '            return None\n'
            '\n'
            '        auth_header = request.headers.get("Authorization", "")\n'
            '        if auth_header.startswith("Bearer "):\n'
            '            token = auth_header[7:].strip()\n'
            '            token_bytes = token.encode()\n'
            '            if any(hmac.compare_digest(token_bytes, api_key.encode()) for api_key in accepted_api_keys):\n'
            '                return None  # Auth OK\n'
        )
        if plain_auth in text:
            text = text.replace(plain_auth, replacement, 1)
        elif hardened_auth in text:
            text = text.replace(hardened_auth, replacement, 1)
        else:
            raise PatchError("Patch anchor not found: auth accept hermes hub key aliases")
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

    project_session_prompt = 'system_prompt = _hermes_hub_project_system_prompt(body) if _is_hermes_hub_request(request, body) else (body.get("system_message") or body.get("instructions"))'
    if project_session_prompt not in text:
        text = text.replace(
            'system_prompt = None if _is_hermes_hub_request(request, body) else (body.get("system_message") or body.get("instructions"))',
            project_session_prompt,
        )
        text = text.replace(
            'system_prompt = body.get("system_message") or body.get("instructions")',
            project_session_prompt,
        )
        changes.append("session chat allow bounded hermes hub project prompt")

    if "allow_client_system_prompt = not _is_hermes_hub_request(request, body)" not in text:
        text, _ = _replace_once(
            text,
            '        # Extract system message (becomes ephemeral system prompt layered ON TOP of core)\n'
            '        system_prompt = None\n'
            '        conversation_messages: List[Dict[str, str]] = []',
            '        # Extract system message (becomes ephemeral system prompt layered ON TOP of core)\n'
            '        system_prompt = _hermes_hub_project_system_prompt(body) if _is_hermes_hub_request(request, body) else None\n'
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
    else:
        text = text.replace(
            '        system_prompt = None\n'
            '        allow_client_system_prompt = not _is_hermes_hub_request(request, body)',
            '        system_prompt = _hermes_hub_project_system_prompt(body) if _is_hermes_hub_request(request, body) else None\n'
            '        allow_client_system_prompt = not _is_hermes_hub_request(request, body)',
            1,
        )

    project_response_prompt = 'instructions = _hermes_hub_project_system_prompt(body) if _is_hermes_hub_request(request, body) else body.get("instructions")'
    if project_response_prompt not in text:
        text = text.replace(
            'instructions = None if _is_hermes_hub_request(request, body) else body.get("instructions")',
            project_response_prompt,
            1,
        )
        text = text.replace(
            'instructions = body.get("instructions")',
            project_response_prompt,
            1,
        )
        changes.append("responses allow bounded hermes hub project prompt")

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

    if '"audio_speech": True,' not in text:
        text, _ = _replace_regex_once(
            text,
            r'(^\s+"features": \{\n)',
            r'\1'
            r'                "audio_speech": True,' "\n",
            "capabilities audio speech",
        )
        changes.append("capabilities audio speech")

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
            r'                "audio_speech": {"method": "POST", "path": "/v1/audio/speech"},' "\n"
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

        if '"audio_speech": {"method": "POST", "path": "/v1/audio/speech"}' not in text:
            text, _ = _replace_regex_once(
                text,
                r'(^\s+"hub_notifications": \{"method": "GET/POST/PATCH", "path": "/v1/hub/notifications"\},\n)',
                r'\1'
                r'                "audio_speech": {"method": "POST", "path": "/v1/audio/speech"},' "\n",
                "capabilities audio speech endpoint",
            )
            changes.append("capabilities audio speech endpoint")

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

    if "Invalid multipart audio upload" not in text:
        text, count = re.subn(
            r'        reader = await request\.multipart\(\)\n'
            r'        field = await reader\.next\(\)\n'
            r'        audio_data = None\n'
            r'        while field is not None:\n'
            r'            if field\.name == "file":\n'
            r'                audio_data = await field\.read\(\)\n'
            r'            field = await reader\.next\(\)\n',
            '        try:\n'
            '            reader = await request.multipart()\n'
            '            field = await reader.next()\n'
            '            audio_data = None\n'
            '            while field is not None:\n'
            '                if field.name == "file":\n'
            '                    audio_data = await field.read()\n'
            '                field = await reader.next()\n'
            '        except Exception as e:\n'
            '            return web.json_response({"error": "Invalid multipart audio upload", "detail": str(e)}, status=400)\n',
            text,
            count=1,
        )
        if count:
            changes.append("audio transcriptions multipart guard")

    if "async def _handle_audio_speech" not in text:
        text, _ = _replace_once(
            text,
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            "    async def _handle_audio_speech(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        try:\n"
            "            body = await request.json()\n"
            "        except Exception as e:\n"
            "            return web.json_response({\"error\": \"Invalid JSON body\", \"detail\": str(e)}, status=400)\n"
            "        if not isinstance(body, dict):\n"
            "            return web.json_response({\"error\": \"Invalid JSON body\"}, status=400)\n"
            "        text = str(body.get(\"input\") or body.get(\"text\") or \"\").strip()\n"
            "        if not text:\n"
            "            return web.json_response({\"error\": \"No text provided\"}, status=400)\n"
            "        max_chars = int(os.environ.get(\"HERMES_KOKORO_TTS_MAX_CHARS\", \"6000\"))\n"
            "        if len(text) > max_chars:\n"
            "            return web.json_response({\"error\": f\"Text too long; max {max_chars} characters\"}, status=413)\n"
            "        voice = str(body.get(\"voice\") or os.environ.get(\"HERMES_KOKORO_TTS_VOICE\", \"if_sara\"))\n"
            "        lang = str(body.get(\"lang\") or body.get(\"language\") or \"it\")\n"
            "        try:\n"
            "            speed = float(body.get(\"speed\") or 1.0)\n"
            "        except Exception:\n"
            "            speed = 1.0\n"
            "        try:\n"
            "            loop = asyncio.get_running_loop()\n"
            "            audio = await loop.run_in_executor(None, _hermes_hub_kokoro_speech_bytes, text, voice, lang, speed)\n"
            "            return web.Response(body=audio, content_type=\"audio/wav\")\n"
            "        except Exception as e:\n"
            "            return web.json_response({\"error\": \"Kokoro TTS unavailable\", \"detail\": str(e)}, status=500)\n"
            "\n"
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            "audio speech endpoint handler",
        )
        changes.append("audio speech endpoint handler")

    old_kokoro_executor = "audio = await loop.run_in_executor(None, _hermes_hub_kokoro_speech_bytes, text, voice, lang, speed)"
    new_kokoro_executor = "audio = await loop.run_in_executor(_hermes_hub_kokoro_executor(), _hermes_hub_kokoro_speech_bytes, text, voice, lang, speed)"
    if old_kokoro_executor in text:
        text = text.replace(old_kokoro_executor, new_kokoro_executor, 1)
        changes.append("dedicated kokoro tts executor")

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
            "    async def _handle_get_hub_conversations_events(self, request: \"web.Request\") -> \"web.StreamResponse\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        import json as _json\n"
            "        queue = asyncio.Queue()\n"
            "        _hermes_hub_conversation_event_subscribers.add(queue)\n"
            "        response = web.StreamResponse(status=200, headers={\"Content-Type\": \"text/event-stream\", \"Cache-Control\": \"no-cache\", \"Connection\": \"keep-alive\"})\n"
            "        await response.prepare(request)\n"
            "        try:\n"
            "            await response.write(b\": connected\\n\\n\")\n"
            "            await queue.put(_hermes_hub_conversation_event_payload(\"snapshot\"))\n"
            "            while True:\n"
            "                try:\n"
            "                    payload = await asyncio.wait_for(queue.get(), timeout=25)\n"
            "                except asyncio.TimeoutError:\n"
            "                    await response.write(b\": keepalive\\n\\n\")\n"
            "                    continue\n"
            "                data = _json.dumps(payload, ensure_ascii=False)\n"
            "                await response.write(f\"event: conversations.updated\\ndata: {data}\\n\\n\".encode(\"utf-8\"))\n"
            "        except asyncio.CancelledError:\n"
            "            raise\n"
            "        except Exception:\n"
            "            pass\n"
            "        finally:\n"
            "            _hermes_hub_conversation_event_subscribers.discard(queue)\n"
            "        return response\n"
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
            "        merged = _hermes_hub_merge_conversations([body])\n"
            "        _hermes_hub_publish_conversation_event(\"put\", merged)\n"
            "        return web.json_response(merged)\n"
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
            "        _hermes_hub_publish_conversation_event(\"import\", merged)\n"
            "        return web.json_response(merged)\n"
            "\n"
            "    async def _handle_delete_hub_conversation(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        deleted = _hermes_hub_delete_conversation(request.match_info.get(\"conversation_id\", \"\"))\n"
            "        _hermes_hub_publish_conversation_event(\"delete\", deleted)\n"
            "        return web.json_response(deleted)\n"
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
            "    async def _handle_get_hub_conversations_events(self, request: \"web.Request\") -> \"web.StreamResponse\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        import json as _json\n"
            "        queue = asyncio.Queue()\n"
            "        _hermes_hub_conversation_event_subscribers.add(queue)\n"
            "        response = web.StreamResponse(status=200, headers={\"Content-Type\": \"text/event-stream\", \"Cache-Control\": \"no-cache\", \"Connection\": \"keep-alive\"})\n"
            "        await response.prepare(request)\n"
            "        try:\n"
            "            await response.write(b\": connected\\n\\n\")\n"
            "            await queue.put(_hermes_hub_conversation_event_payload(\"snapshot\"))\n"
            "            while True:\n"
            "                try:\n"
            "                    payload = await asyncio.wait_for(queue.get(), timeout=25)\n"
            "                except asyncio.TimeoutError:\n"
            "                    await response.write(b\": keepalive\\n\\n\")\n"
            "                    continue\n"
            "                data = _json.dumps(payload, ensure_ascii=False)\n"
            "                await response.write(f\"event: conversations.updated\\ndata: {data}\\n\\n\".encode(\"utf-8\"))\n"
            "        except asyncio.CancelledError:\n"
            "            raise\n"
            "        except Exception:\n"
            "            pass\n"
            "        finally:\n"
            "            _hermes_hub_conversation_event_subscribers.discard(queue)\n"
            "        return response\n"
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
            "        merged = _hermes_hub_merge_conversations([body])\n"
            "        _hermes_hub_publish_conversation_event(\"put\", merged)\n"
            "        return web.json_response(merged)\n"
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
            "        _hermes_hub_publish_conversation_event(\"import\", merged)\n"
            "        return web.json_response(merged)\n"
            "\n"
            "    async def _handle_delete_hub_conversation(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        deleted = _hermes_hub_delete_conversation(request.match_info.get(\"conversation_id\", \"\"))\n"
            "        _hermes_hub_publish_conversation_event(\"delete\", deleted)\n"
            "        return web.json_response(deleted)\n"
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

    if "async def _handle_hub_server_control" not in text:
        text, _ = _replace_once(
            text,
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            '''    async def _handle_hub_server_control(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        query = str(request.query.get("filter", ""))[:120]
        return web.json_response(await asyncio.to_thread(_hermes_hub_server_control_snapshot, query))

    async def _handle_hub_server_action(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        try:
            body = await request.json()
        except Exception:
            body = {}
        result = await asyncio.to_thread(_hermes_hub_server_control_action, body if isinstance(body, dict) else {})
        return web.json_response(result, status=403 if result.get("status") == "denied" else 200)

    async def _handle_hub_server_maintenance(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        try:
            body = await request.json()
        except Exception:
            body = {}
        return web.json_response(await asyncio.to_thread(_hermes_hub_server_maintenance, body if isinstance(body, dict) else {}))

    async def _handle_models(self, request: "web.Request") -> "web.Response":''',
            "server control endpoint handlers",
        )
        changes.append("server control endpoint handlers")

    if "async def _handle_get_hub_audit" not in text:
        text, _ = _replace_once(
            text,
            "    async def _handle_models(self, request: \"web.Request\") -> \"web.Response\":",
            '''    async def _handle_get_hub_audit(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        filters = {key: str(request.query.get(key, ""))[:200] for key in ("project", "run", "tool", "device", "risk", "event")}
        return web.json_response(_hermes_hub_audit_payload(filters))

    async def _handle_post_hub_audit(self, request: "web.Request") -> "web.Response":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        try:
            body = await request.json()
        except Exception:
            body = {}
        return web.json_response(_hermes_hub_audit_event(body if isinstance(body, dict) else {}))

    async def _handle_models(self, request: "web.Request") -> "web.Response":''',
            "audit endpoint handlers",
        )
        changes.append("audit endpoint handlers")

    if "async def _handle_get_hub_conversations_events" not in text and "    async def _handle_put_hub_conversation" in text:
        text, _ = _replace_once(
            text,
            "    async def _handle_put_hub_conversation(self, request: \"web.Request\") -> \"web.Response\":",
            '''    async def _handle_get_hub_conversations_events(self, request: "web.Request") -> "web.StreamResponse":
        auth_error = self._check_auth(request)
        if auth_error is not None:
            return auth_error
        import json as _json
        queue = asyncio.Queue()
        _hermes_hub_conversation_event_subscribers.add(queue)
        response = web.StreamResponse(status=200, headers={"Content-Type": "text/event-stream", "Cache-Control": "no-cache", "Connection": "keep-alive"})
        await response.prepare(request)
        try:
            await response.write(b": connected\\n\\n")
            await queue.put(_hermes_hub_conversation_event_payload("snapshot"))
            while True:
                try:
                    payload = await asyncio.wait_for(queue.get(), timeout=25)
                except asyncio.TimeoutError:
                    await response.write(b": keepalive\\n\\n")
                    continue
                data = _json.dumps(payload, ensure_ascii=False)
                await response.write(f"event: conversations.updated\\ndata: {data}\\n\\n".encode("utf-8"))
        except asyncio.CancelledError:
            raise
        except Exception:
            pass
        finally:
            _hermes_hub_conversation_event_subscribers.discard(queue)
        return response

    async def _handle_put_hub_conversation(self, request: "web.Request") -> "web.Response":''',
            "hub conversations events handler",
        )
        changes.append("hub conversations events handler")

    handler_replacements = {
        "        body[\"id\"] = request.match_info.get(\"conversation_id\", body.get(\"id\", \"\"))\n        return web.json_response(_hermes_hub_merge_conversations([body]))\n": "        body[\"id\"] = request.match_info.get(\"conversation_id\", body.get(\"id\", \"\"))\n        merged = _hermes_hub_merge_conversations([body])\n        _hermes_hub_publish_conversation_event(\"put\", merged)\n        return web.json_response(merged)\n",
        "        merged = _hermes_hub_merge_conversations(incoming if isinstance(incoming, list) else [])\n        return web.json_response(merged)\n": "        merged = _hermes_hub_merge_conversations(incoming if isinstance(incoming, list) else [])\n        _hermes_hub_publish_conversation_event(\"import\", merged)\n        return web.json_response(merged)\n",
        "        return web.json_response(_hermes_hub_delete_conversation(request.match_info.get(\"conversation_id\", \"\")))\n": "        deleted = _hermes_hub_delete_conversation(request.match_info.get(\"conversation_id\", \"\"))\n        _hermes_hub_publish_conversation_event(\"delete\", deleted)\n        return web.json_response(deleted)\n",
    }
    for old, new in handler_replacements.items():
        if old in text:
            text = text.replace(old, new)
            changes.append("hub conversations event publish")

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
            "    async def _handle_get_hub_conversations_events(self, request: \"web.Request\") -> \"web.StreamResponse\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        import json as _json\n"
            "        queue = asyncio.Queue()\n"
            "        _hermes_hub_conversation_event_subscribers.add(queue)\n"
            "        response = web.StreamResponse(status=200, headers={\"Content-Type\": \"text/event-stream\", \"Cache-Control\": \"no-cache\", \"Connection\": \"keep-alive\"})\n"
            "        await response.prepare(request)\n"
            "        try:\n"
            "            await response.write(b\": connected\\n\\n\")\n"
            "            await queue.put(_hermes_hub_conversation_event_payload(\"snapshot\"))\n"
            "            while True:\n"
            "                try:\n"
            "                    payload = await asyncio.wait_for(queue.get(), timeout=25)\n"
            "                except asyncio.TimeoutError:\n"
            "                    await response.write(b\": keepalive\\n\\n\")\n"
            "                    continue\n"
            "                data = _json.dumps(payload, ensure_ascii=False)\n"
            "                await response.write(f\"event: conversations.updated\\ndata: {data}\\n\\n\".encode(\"utf-8\"))\n"
            "        except asyncio.CancelledError:\n"
            "            raise\n"
            "        except Exception:\n"
            "            pass\n"
            "        finally:\n"
            "            _hermes_hub_conversation_event_subscribers.discard(queue)\n"
            "        return response\n"
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
            "        merged = _hermes_hub_merge_conversations([body])\n"
            "        _hermes_hub_publish_conversation_event(\"put\", merged)\n"
            "        return web.json_response(merged)\n"
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
            "        _hermes_hub_publish_conversation_event(\"import\", merged)\n"
            "        return web.json_response(merged)\n"
            "\n"
            "    async def _handle_delete_hub_conversation(self, request: \"web.Request\") -> \"web.Response\":\n"
            "        auth_error = self._check_auth(request)\n"
            "        if auth_error is not None:\n"
            "            return auth_error\n"
            "        deleted = _hermes_hub_delete_conversation(request.match_info.get(\"conversation_id\", \"\"))\n"
            "        _hermes_hub_publish_conversation_event(\"delete\", deleted)\n"
            "        return web.json_response(deleted)\n"
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
            "            if _hermes_hub_is_tailnet_peer(request):\n"
            "                auth_error = None\n"
            "            else:\n"
            "                media_token = request.query.get(\"hub_token\") or request.query.get(\"api_key\") or request.query.get(\"token\")\n"
            "                accepted_api_keys = _hermes_hub_api_keys(self._api_key)\n"
            "                if not media_token or not any(hmac.compare_digest(media_token, api_key) for api_key in accepted_api_keys):\n"
            "                    return auth_error\n"
            "        media_id = request.match_info.get(\"media_id\", \"\")\n"
            "        path = _hermes_hub_resolve_media_path(media_id, request.query.get(\"root\"))\n"
            "        if path is None:\n"
            "            from pathlib import Path as _Path\n"
            "            short_id = str(media_id or \"\").strip().strip(\"/\")\n"
            "            if 4 <= len(short_id) <= 64 and \"/\" not in short_id:\n"
            "                upload_root = _Path(os.environ.get(\"HERMES_HUB_UPLOAD_PATH\", str(_Path.home() / \".hermes\" / \"hub_uploads\"))).expanduser()\n"
            "                matches = []\n"
            "                try:\n"
            "                    matches = [candidate.resolve() for candidate in upload_root.rglob(f\"{short_id}*\") if candidate.is_file()]\n"
            "                except Exception:\n"
            "                    matches = []\n"
            "                unique = {str(candidate): candidate for candidate in matches}\n"
            "                if len(unique) == 1:\n"
            "                    path = next(iter(unique.values()))\n"
            "                if path is None:\n"
            "                    matches = []\n"
            "                    for root in _hermes_hub_media_roots():\n"
            "                        if root == upload_root:\n"
            "                            continue\n"
            "                        try:\n"
            "                            matches.extend(candidate.resolve() for candidate in root.rglob(f\"{short_id}*\") if candidate.is_file())\n"
            "                        except Exception:\n"
            "                            continue\n"
            "                    unique = {str(candidate): candidate for candidate in matches}\n"
            "                    if len(unique) == 1:\n"
            "                        path = next(iter(unique.values()))\n"
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
            '            if _hermes_hub_is_tailnet_peer(request):\n'
            '                auth_error = None\n'
            '            else:\n'
            '                media_token = request.query.get("hub_token") or request.query.get("api_key") or request.query.get("token")\n'
            '                accepted_api_keys = _hermes_hub_api_keys(self._api_key)\n'
            '                if not media_token or not any(hmac.compare_digest(media_token, api_key) for api_key in accepted_api_keys):\n'
            '                    return auth_error\n',
            1,
        )
        changes.append("media proxy query token auth")

    if 'if _hermes_hub_is_tailnet_peer(request):' not in text and 'media_token = request.query.get("hub_token")' in text:
        text = text.replace(
            '        if auth_error is not None:\n'
            '            media_token = request.query.get("hub_token") or request.query.get("api_key") or request.query.get("token")\n'
            '            accepted_api_keys = _hermes_hub_api_keys(self._api_key)\n'
            '            if not media_token or not any(hmac.compare_digest(media_token, api_key) for api_key in accepted_api_keys):\n'
            '                return auth_error\n'
            '        media_id = request.match_info.get("media_id", "")\n',
            '        if auth_error is not None:\n'
            '            if _hermes_hub_is_tailnet_peer(request):\n'
            '                auth_error = None\n'
            '            else:\n'
            '                media_token = request.query.get("hub_token") or request.query.get("api_key") or request.query.get("token")\n'
            '                accepted_api_keys = _hermes_hub_api_keys(self._api_key)\n'
            '                if not media_token or not any(hmac.compare_digest(media_token, api_key) for api_key in accepted_api_keys):\n'
            '                    return auth_error\n'
            '        media_id = request.match_info.get("media_id", "")\n',
            1,
        )
        changes.append("media proxy tailnet auth")

    if 'short_id = str(media_id or "").strip().strip("/")' not in text and 'path = _hermes_hub_resolve_media_path(media_id, request.query.get("root"))' in text:
        text = text.replace(
            '        path = _hermes_hub_resolve_media_path(media_id, request.query.get("root"))\n'
            '        if path is None:\n'
            '            return web.json_response({"error": "Media not found"}, status=404)\n',
            '        path = _hermes_hub_resolve_media_path(media_id, request.query.get("root"))\n'
            '        if path is None:\n'
            '            from pathlib import Path as _Path\n'
            '            short_id = str(media_id or "").strip().strip("/")\n'
            '            if 4 <= len(short_id) <= 64 and "/" not in short_id:\n'
            '                upload_root = _Path(os.environ.get("HERMES_HUB_UPLOAD_PATH", str(_Path.home() / ".hermes" / "hub_uploads"))).expanduser()\n'
            '                matches = []\n'
            '                try:\n'
            '                    matches = [candidate.resolve() for candidate in upload_root.rglob(f"{short_id}*") if candidate.is_file()]\n'
            '                except Exception:\n'
            '                    matches = []\n'
            '                unique = {str(candidate): candidate for candidate in matches}\n'
            '                if len(unique) == 1:\n'
            '                    path = next(iter(unique.values()))\n'
            '                if path is None:\n'
            '                    matches = []\n'
            '                    for root in _hermes_hub_media_roots():\n'
            '                        if root == upload_root:\n'
            '                            continue\n'
            '                        try:\n'
            '                            matches.extend(candidate.resolve() for candidate in root.rglob(f"{short_id}*") if candidate.is_file())\n'
            '                        except Exception:\n'
            '                            continue\n'
            '                    unique = {str(candidate): candidate for candidate in matches}\n'
            '                    if len(unique) == 1:\n'
            '                        path = next(iter(unique.values()))\n'
            '        if path is None:\n'
            '            return web.json_response({"error": "Media not found"}, status=404)\n',
            1,
        )
        changes.append("media proxy short id fallback")

    if 'roots = [_Path(os.environ.get("HERMES_HUB_UPLOAD_PATH", str(_Path.home() / ".hermes" / "hub_uploads"))).expanduser()] + _hermes_hub_media_roots()' in text:
        text = text.replace(
            '                roots = [_Path(os.environ.get("HERMES_HUB_UPLOAD_PATH", str(_Path.home() / ".hermes" / "hub_uploads"))).expanduser()] + _hermes_hub_media_roots()\n'
            '                matches = []\n'
            '                for root in roots:\n'
            '                    try:\n'
            '                        matches.extend(candidate.resolve() for candidate in root.rglob(f"{short_id}*") if candidate.is_file())\n'
            '                    except Exception:\n'
            '                        continue\n'
            '                unique = {str(candidate): candidate for candidate in matches}\n'
            '                if len(unique) == 1:\n'
            '                    path = next(iter(unique.values()))\n',
            '                upload_root = _Path(os.environ.get("HERMES_HUB_UPLOAD_PATH", str(_Path.home() / ".hermes" / "hub_uploads"))).expanduser()\n'
            '                matches = []\n'
            '                try:\n'
            '                    matches = [candidate.resolve() for candidate in upload_root.rglob(f"{short_id}*") if candidate.is_file()]\n'
            '                except Exception:\n'
            '                    matches = []\n'
            '                unique = {str(candidate): candidate for candidate in matches}\n'
            '                if len(unique) == 1:\n'
            '                    path = next(iter(unique.values()))\n'
            '                if path is None:\n'
            '                    matches = []\n'
            '                    for root in _hermes_hub_media_roots():\n'
            '                        if root == upload_root:\n'
            '                            continue\n'
            '                        try:\n'
            '                            matches.extend(candidate.resolve() for candidate in root.rglob(f"{short_id}*") if candidate.is_file())\n'
            '                        except Exception:\n'
            '                            continue\n'
            '                    unique = {str(candidate): candidate for candidate in matches}\n'
            '                    if len(unique) == 1:\n'
            '                        path = next(iter(unique.values()))\n',
            1,
        )
        changes.append("media proxy upload short id priority")

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

    if 'item[0] == "__hermes_raw_event__"' not in text:
        text, _ = _replace_once(
            text,
            '                if isinstance(item, tuple) and len(item) == 2 and item[0] == "__tool_progress__":\n'
            '                    event_data = json.dumps(item[1])\n'
            '                    await response.write(\n'
            '                        f"event: hermes.tool.progress\\ndata: {event_data}\\n\\n".encode()\n'
            '                    )\n'
            '                else:',
            '                if isinstance(item, tuple) and len(item) == 2 and item[0] == "__tool_progress__":\n'
            '                    event_data = json.dumps(item[1])\n'
            '                    await response.write(\n'
            '                        f"event: hermes.tool.progress\\ndata: {event_data}\\n\\n".encode()\n'
            '                    )\n'
            '                elif isinstance(item, tuple) and len(item) == 2 and item[0] == "__hermes_raw_event__":\n'
            '                    payload = item[1] if isinstance(item[1], dict) else {"type": "hermes.event", "payload": item[1]}\n'
            '                    event_name = str(payload.get("event") or payload.get("type") or "hermes.event")\n'
            '                    await response.write(\n'
            '                        f"event: {event_name}\\ndata: {json.dumps(payload)}\\n\\n".encode()\n'
            '                    )\n'
            '                else:',
            "chat completions raw hermes dispatch",
        )
        changes.append("chat completions raw hermes dispatch")

    if (
        'def _on_tool_progress(event_type, name, preview, args, **kwargs):\n                """Forward real llama.cpp processing/timing events to Chat Completions SSE."""' not in text
        and '"""Pass through Hermes-native tool/progress metadata' not in text
    ):
        text, _ = _replace_once(
            text,
            '            # Start agent in background.  agent_ref is a mutable container\n',
            '            def _on_tool_progress(event_type, name, preview, args, **kwargs):\n'
            '                """Forward real llama.cpp processing/timing events to Chat Completions SSE."""\n'
            '                if str(event_type or "") != "hermes.processing.progress":\n'
            '                    return\n'
            '                payload = {\n'
            '                    "type": "hermes.processing.progress",\n'
            '                    "event": "hermes.processing.progress",\n'
            '                    "tool": name,\n'
            '                    "label": preview,\n'
            '                    "arguments": args or {},\n'
            '                    "estimated": False,\n'
            '                }\n'
            '                payload.update(kwargs or {})\n'
            '                _stream_q.put(("__hermes_raw_event__", payload))\n'
            '\n'
            '            # Start agent in background.  agent_ref is a mutable container\n',
            "chat completions processing progress callback",
        )
        text, _ = _replace_once(
            text,
            '                tool_start_callback=_on_tool_start,\n'
            '                tool_complete_callback=_on_tool_complete,\n'
            '                agent_ref=agent_ref,\n',
            '                tool_start_callback=_on_tool_start,\n'
            '                tool_complete_callback=_on_tool_complete,\n'
            '                tool_progress_callback=_on_tool_progress,\n'
            '                agent_ref=agent_ref,\n',
            "chat completions wire processing progress callback",
        )
        changes.append("chat completions processing progress callback")

    progress_passthrough = '"""Pass through Hermes-native tool/progress metadata including reasoning."""'
    if progress_passthrough not in text:
        text, count = re.subn(
            r'(?m)^            def _on_tool_progress\(event_type, name, preview, args, \*\*kwargs\):\n'
            r'(?:^ {16,}.*\n|^[ \t]*\n)*',
            '            def _on_tool_progress(event_type, name, preview, args, **kwargs):\n'
            '                """Pass through Hermes-native tool/progress metadata including reasoning."""\n'
            '                event_name = str(event_type or "hermes.tool.progress")\n'
            '                is_reasoning = "reasoning" in event_name.lower()\n'
            '                if str(name).startswith("_") and not is_reasoning:\n'
            "                    return\n"
            "                payload = {\n"
            '                    "type": "hermes.reasoning.available" if is_reasoning else event_name,\n'
            '                    "event": "reasoning.available" if is_reasoning else event_name,\n'
            '                    "tool": name,\n'
            '                    "label": preview,\n'
            '                    "reasoning": (preview or "") if is_reasoning else None,\n'
            '                    "arguments": args or {},\n'
            "                }\n"
            "                payload.update(kwargs or {})\n"
            '                _stream_q.put(("__hermes_raw_event__", payload))\n',
            text,
            count=1,
        )
        if count != 1:
            raise RuntimeError("Patch anchor not found: responses tool_progress callback")
        changes.append("responses tool_progress raw passthrough")

    responses_reasoning_passthrough = '"""Forward Responses progress metadata and reasoning."""'
    if responses_reasoning_passthrough not in text:
        legacy_responses_progress = (
            '            def _on_tool_progress(event_type, name, preview, args, **kwargs):\n'
            '                """Pass through Hermes-native tool/progress metadata."""\n'
            '                if str(name).startswith("_"):\n'
            '                    return\n'
            '                payload = {\n'
            '                    "type": str(event_type or "hermes.tool.progress"),\n'
            '                    "event": str(event_type or "hermes.tool.progress"),\n'
            '                    "tool": name,\n'
            '                    "label": preview,\n'
            '                    "arguments": args or {},\n'
            '                }\n'
            '                payload.update(kwargs or {})\n'
            '                _stream_q.put(("__hermes_raw_event__", payload))\n'
        )
        unpatched_responses_progress = (
            '            def _on_tool_progress(event_type, name, preview, args, **kwargs):\n'
            '                """Queue non-start tool progress events if needed in future.\n'
            '\n'
            '                The structured Responses stream uses ``tool_start_callback``\n'
            '                and ``tool_complete_callback`` for exact call-id correlation,\n'
            '                so progress events are currently ignored here.\n'
            '                """\n'
            '                return\n'
        )
        responses_progress_source = (
            legacy_responses_progress
            if legacy_responses_progress in text
            else unpatched_responses_progress
        )
        text, _ = _replace_once(
            text,
            responses_progress_source,
            '            def _on_tool_progress(event_type, name, preview, args, **kwargs):\n'
            '                """Forward Responses progress metadata and reasoning."""\n'
            '                event_name = str(event_type or "hermes.tool.progress")\n'
            '                is_reasoning = "reasoning" in event_name.lower()\n'
            '                if str(name).startswith("_") and not is_reasoning:\n'
            '                    return\n'
            '                payload = {\n'
            '                    "type": "hermes.reasoning.available" if is_reasoning else event_name,\n'
            '                    "event": "reasoning.available" if is_reasoning else event_name,\n'
            '                    "tool": name,\n'
            '                    "label": preview,\n'
            '                    "reasoning": (preview or "") if is_reasoning else None,\n'
            '                    "arguments": args or {},\n'
            '                }\n'
            '                payload.update(kwargs or {})\n'
            '                _stream_q.put(("__hermes_raw_event__", payload))\n',
            "responses reasoning passthrough callback",
        )
        changes.append("responses reasoning passthrough callback")

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

    if not _route_registered(text, "POST", "/v1/hermes/native", "_handle_responses"):
        text, _ = _replace_regex_once(
            text,
            r'(^\s+self\._app\.router\.add_post\("/v1/responses", self\._handle_responses\)\n)(^\s+self\._app\.router\.add_get\("/v1/responses/\{response_id\}", self\._handle_get_response\))',
            r'\1'
            r'            self._app.router.add_post("/v1/hermes/native", self._handle_responses)' "\n"
            r'\2',
            "router hermes_native alias",
        )
        changes.append("router hermes_native alias")

    if not _route_registered(text, "GET", "/v1/hub/hardware", "_handle_hub_hardware"):
        text, _ = _replace_regex_once(
            text,
            r'(^\s+self\._app\.router\.add_get\("/v1/capabilities", self\._handle_capabilities\)\n)',
            r'\1'
            r'            self._app.router.add_get("/v1/hub/hardware", self._handle_hub_hardware)' "\n",
            "router hardware endpoint",
        )
        changes.append("router hardware endpoint")

    if not _route_registered(text, "GET", "/v1/hub/server/control", "_handle_hub_server_control"):
        text, _ = _replace_regex_once(
            text,
            r'(^\s+self\._app\.router\.add_get\("/v1/hub/hardware", self\._handle_hub_hardware\)\n)',
            r'\1'
            r'            self._app.router.add_get("/v1/hub/server/control", self._handle_hub_server_control)' "\n"
            r'            self._app.router.add_post("/v1/hub/server/action", self._handle_hub_server_action)' "\n"
            r'            self._app.router.add_post("/v1/hub/server/maintenance", self._handle_hub_server_maintenance)' "\n",
            "router server control endpoints",
        )
        changes.append("router server control endpoints")

    if not _route_registered(text, "GET", "/v1/hub/audit", "_handle_get_hub_audit"):
        text, _ = _replace_regex_once(
            text,
            r'(^\s+self\._app\.router\.add_get\("/v1/hub/hardware", self\._handle_hub_hardware\)\n)',
            r'\1'
            r'            self._app.router.add_get("/v1/hub/audit", self._handle_get_hub_audit)' "\n"
            r'            self._app.router.add_post("/v1/hub/audit", self._handle_post_hub_audit)' "\n",
            "router audit endpoints",
        )
        changes.append("router audit endpoints")

    if not _route_registered(text, "GET", "/v1/video/library", "_handle_video_library"):
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
        if not _route_registered(text, "POST", "/v1/media/upload", "_handle_hub_media_upload"):
            text, _ = _replace_regex_once(
                text,
                r'(^\s+self\._app\.router\.add_get\("/v1/video/library", self\._handle_video_library\)\n)',
                r'\1'
                r'            self._app.router.add_post("/v1/media/upload", self._handle_hub_media_upload)' "\n",
                "router media upload endpoint",
            )
            changes.append("router media upload endpoint")

        if not _route_registered(text, "GET", "/v1/media/{media_id:.*}", "_handle_hub_media"):
            text, _ = _replace_regex_once(
                text,
                r'(^\s+self\._app\.router\.add_get\("/v1/video/library", self\._handle_video_library\)\n)',
                r'\1'
                r'            self._app.router.add_get("/v1/media/{media_id:.*}", self._handle_hub_media)' "\n",
                "router media proxy endpoint",
            )
            changes.append("router media proxy endpoint")

        if not _route_registered(text, "GET", "/v1/news/library", "_handle_news_library"):
            text, _ = _replace_regex_once(
                text,
                r'(^\s+self\._app\.router\.add_get\("/v1/video/library", self\._handle_video_library\)\n)',
                r'\1'
                r'            self._app.router.add_get("/v1/news/library", self._handle_news_library)' "\n",
                "router news library endpoint",
            )
            changes.append("router news library endpoint")

    if not _route_registered(text, "GET", "/v1/hub/conversations", "_handle_get_hub_conversations"):
        text, _ = _replace_regex_once(
            text,
            r'(^\s+self\._app\.router\.add_get\("/v1/hub/state", self\._handle_get_hub_state\)\n)',
            r'\1'
            r'            self._app.router.add_get("/v1/hub/conversations", self._handle_get_hub_conversations)' "\n"
            r'            self._app.router.add_get("/v1/hub/conversations/events", self._handle_get_hub_conversations_events)' "\n"
            r'            self._app.router.add_post("/v1/hub/conversations/import", self._handle_post_hub_conversations_import)' "\n"
            r'            self._app.router.add_put("/v1/hub/conversations/{conversation_id}", self._handle_put_hub_conversation)' "\n"
            r'            self._app.router.add_delete("/v1/hub/conversations/{conversation_id}", self._handle_delete_hub_conversation)' "\n",
            "router hub conversations endpoints",
        )
        changes.append("router hub conversations endpoints")
    elif not _route_registered(
        text, "GET", "/v1/hub/conversations/events", "_handle_get_hub_conversations_events"
    ):
        text, _ = _replace_once(
            text,
            '            self._app.router.add_get("/v1/hub/conversations", self._handle_get_hub_conversations)\n',
            '            self._app.router.add_get("/v1/hub/conversations", self._handle_get_hub_conversations)\n'
            '            self._app.router.add_get("/v1/hub/conversations/events", self._handle_get_hub_conversations_events)\n',
            "router hub conversations events endpoint",
        )
        changes.append("router hub conversations events endpoint")

    if not _route_registered(text, "GET", "/v1/hub/notifications", "_handle_get_hub_notifications"):
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

    if not _route_registered(
        text, "POST", "/v1/audio/transcriptions", "_handle_audio_transcriptions"
    ):
        text, _ = _replace_regex_once(
            text,
            r'(^\s+self\._app\.router\.add_get\("/v1/hub/state", self\._handle_get_hub_state\)\n)',
            r'\1'
            r'            self._app.router.add_post("/v1/audio/transcriptions", self._handle_audio_transcriptions)' "\n",
            "router audio transcriptions endpoints",
        )
        changes.append("router audio transcriptions endpoints")

    if not _route_registered(text, "POST", "/v1/audio/speech", "_handle_audio_speech"):
        text, _ = _replace_regex_once(
            text,
            r'(^\s+self\._app\.router\.add_get\("/v1/hub/state", self\._handle_get_hub_state\)\n)',
            r'\1'
            r'            self._app.router.add_post("/v1/audio/speech", self._handle_audio_speech)' "\n",
            "router audio speech endpoint",
        )
        changes.append("router audio speech endpoint")

    kokoro_runtime = (
            "\n\n"
            "# HERMES_HUB_KOKORO_GPU_V6\n"
            "def _hermes_hub_kokoro_executor():\n"
            "    from concurrent.futures import ThreadPoolExecutor\n"
            "\n"
            "    global _hermes_hub_kokoro_thread_pool\n"
            "    if '_hermes_hub_kokoro_thread_pool' not in globals():\n"
            "        _hermes_hub_kokoro_thread_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix='hermes-kokoro')\n"
            "    return _hermes_hub_kokoro_thread_pool\n"
            "\n"
            "\n"
            "def _hermes_hub_build_kokoro_model(force_cpu=False):\n"
            "    import gc\n"
            "    import os\n"
            "    from pathlib import Path\n"
            "\n"
            "    from kokoro_onnx import Kokoro\n"
            "\n"
            "    voices_path = Path(os.environ.get('HERMES_KOKORO_VOICES_PATH', '~/.hermes/kokoro-tts/models/voices-v1.0.bin')).expanduser()\n"
            "    cpu_path = Path(os.environ.get('HERMES_KOKORO_CPU_MODEL_PATH', '~/.hermes/kokoro-tts/models/kokoro-v1.0.int8.onnx')).expanduser()\n"
            "    gpu_path = Path(os.environ.get('HERMES_KOKORO_GPU_MODEL_PATH', '~/.hermes/kokoro-tts/models/kokoro-v1.0.fp16.onnx')).expanduser()\n"
            "    configured_path = str(os.environ.get('HERMES_KOKORO_MODEL_PATH', '')).strip()\n"
            "    provider = 'CPUExecutionProvider' if force_cpu else str(os.environ.get('HERMES_KOKORO_ONNX_PROVIDER', 'CUDAExecutionProvider')).strip()\n"
            "    device_id = int(os.environ.get('HERMES_KOKORO_CUDA_DEVICE', '1'))\n"
            "    use_gpu = provider == 'CUDAExecutionProvider' and gpu_path.is_file()\n"
            "    model_path = Path(configured_path).expanduser() if configured_path else (gpu_path if use_gpu else cpu_path)\n"
            "    if not model_path.is_file():\n"
            "        raise RuntimeError(f'Kokoro model not found: {model_path}')\n"
            "    if not voices_path.is_file():\n"
            "        raise RuntimeError(f'Kokoro voices not found: {voices_path}')\n"
            "\n"
            "    if use_gpu:\n"
            "        try:\n"
            "            import onnxruntime as _ort\n"
            "            if hasattr(_ort, 'preload_dlls'):\n"
            "                _ort.preload_dlls(directory='')\n"
            "            bootstrap_path = cpu_path if cpu_path.is_file() else model_path\n"
            "            model = Kokoro(str(bootstrap_path), str(voices_path))\n"
            "            session = _ort.InferenceSession(\n"
            "                str(model_path),\n"
            "                providers=[\n"
            "                    ('CUDAExecutionProvider', {'device_id': device_id, 'cudnn_conv_algo_search': 'HEURISTIC'}),\n"
            "                    'CPUExecutionProvider',\n"
            "                ],\n"
            "            )\n"
            "            if 'CUDAExecutionProvider' not in session.get_providers():\n"
            "                raise RuntimeError(f'CUDA provider unavailable: {session.get_providers()}')\n"
            "            previous_session = model.sess\n"
            "            model.sess = session\n"
            "            del previous_session\n"
            "            gc.collect()\n"
            "            print(f'Kokoro TTS provider: CUDAExecutionProvider device {device_id}, model {model_path.name}')\n"
            "        except Exception as exc:\n"
            "            print('Kokoro CUDA unavailable, falling back to CPU:', exc)\n"
            "            if not cpu_path.is_file():\n"
            "                raise\n"
            "            model = Kokoro(str(cpu_path), str(voices_path))\n"
            "            provider = 'CPUExecutionProvider'\n"
            "    else:\n"
            "        model = Kokoro(str(model_path), str(voices_path))\n"
            "        provider = 'CPUExecutionProvider'\n"
            "\n"
            "    _hermes_hub_patch_kokoro_speed_dtype(model)\n"
            "    return model, provider\n"
            "\n"
            "\n"
            "def _hermes_hub_tts_segments(text, lang):\n"
            "    import os\n"
            "    import re\n"
            "\n"
            "    normalized_lang = str(lang or '').strip().lower().replace('_', '-')\n"
            "    mixed_language_enabled = str(os.environ.get('HERMES_KOKORO_TTS_MIXED_LANGUAGE', '1')).lower() not in {'0', 'false', 'no'}\n"
            "    if normalized_lang not in {'it', 'it-it'} or not mixed_language_enabled:\n"
            "        return [(text, lang, None)]\n"
            "\n"
            "    terms = (\n"
            "        r'chatgpt|openai|gpt(?:[- ]?\\d+(?:\\.\\d+)*)?|youtube|github|android|windows|google|apple|'\n"
            "        r'iphone|ipad|macos|linux|tts|stt|api|url|http(?:s)?|json|javascript|typescript|python|kotlin|'\n"
            "        r'c\\+\\+|c#|sql|html|css|wi-?fi|bluetooth|download|upload|streaming|browser|player|'\n"
            "        r'full[- ]?screen|gateway|voiceover|prompt|token|release|build|bug|crash|backup|cache|cloud'\n"
            "    )\n"
            "    pattern = re.compile(r'(?<!\\w)(?:' + terms + r')(?!\\w)', re.IGNORECASE)\n"
            "    english_voice = str(os.environ.get('HERMES_KOKORO_TTS_ENGLISH_VOICE', 'af_bella')).strip() or 'af_bella'\n"
            "    segments = []\n"
            "\n"
            "    def append_segment(value, segment_lang, segment_voice):\n"
            "        if not value:\n"
            "            return\n"
            "        if segments and segments[-1][1:] == (segment_lang, segment_voice):\n"
            "            segments[-1] = (segments[-1][0] + value, segment_lang, segment_voice)\n"
            "        else:\n"
            "            segments.append((value, segment_lang, segment_voice))\n"
            "\n"
            "    cursor = 0\n"
            "    for match in pattern.finditer(text):\n"
            "        append_segment(text[cursor:match.start()], lang, None)\n"
            "        append_segment(match.group(0), 'en-us', english_voice)\n"
            "        cursor = match.end()\n"
            "    append_segment(text[cursor:], lang, None)\n"
            "    return segments or [(text, lang, None)]\n"
            "\n"
            "\n"
            "def _hermes_hub_kokoro_speech_bytes(text, voice='if_sara', lang='it', speed=1.0):\n"
            "    import io\n"
            "    import threading\n"
            "\n"
            "    import numpy as _np\n"
            "    import soundfile as _sf\n"
            "\n"
            "    global _hermes_hub_kokoro_model, _hermes_hub_kokoro_provider, _hermes_hub_kokoro_lock\n"
            "    if '_hermes_hub_kokoro_lock' not in globals():\n"
            "        _hermes_hub_kokoro_lock = threading.Lock()\n"
            "    with _hermes_hub_kokoro_lock:\n"
            "        if '_hermes_hub_kokoro_model' not in globals():\n"
            "            _hermes_hub_kokoro_model, _hermes_hub_kokoro_provider = _hermes_hub_build_kokoro_model()\n"
            "\n"
            "        def create_segment(segment_text, segment_voice, segment_lang):\n"
            "            global _hermes_hub_kokoro_model, _hermes_hub_kokoro_provider\n"
            "            try:\n"
            "                return _hermes_hub_kokoro_model.create(segment_text, voice=segment_voice, lang=segment_lang, speed=float(speed))\n"
            "            except Exception as exc:\n"
            "                detail = f'{type(exc).__name__}: {exc}'.lower()\n"
            "                provider_failure = any(token in detail for token in ('cuda', 'cudnn', 'cublas', 'onnxruntime', 'execution provider', 'device error'))\n"
            "                if _hermes_hub_kokoro_provider != 'CUDAExecutionProvider' or not provider_failure:\n"
            "                    raise\n"
            "                print('Kokoro CUDA inference failed, switching to CPU:', exc)\n"
            "                _hermes_hub_kokoro_model, _hermes_hub_kokoro_provider = _hermes_hub_build_kokoro_model(force_cpu=True)\n"
            "                return _hermes_hub_kokoro_model.create(segment_text, voice=segment_voice, lang=segment_lang, speed=float(speed))\n"
            "\n"
            "        audio_parts = []\n"
            "        sample_rate = None\n"
            "        previous_lang = None\n"
            "        for segment_text, segment_lang, segment_voice in _hermes_hub_tts_segments(text, lang):\n"
            "            selected_voice = segment_voice or voice\n"
            "            try:\n"
            "                audio, current_sample_rate = create_segment(segment_text, selected_voice, segment_lang)\n"
            "            except Exception:\n"
            "                if segment_voice is None:\n"
            "                    raise\n"
            "                print(f'Kokoro English voice {selected_voice!r} unavailable; using {voice!r}.')\n"
            "                audio, current_sample_rate = create_segment(segment_text, voice, lang)\n"
            "                segment_lang = lang\n"
            "            current_sample_rate = int(current_sample_rate)\n"
            "            if sample_rate is None:\n"
            "                sample_rate = current_sample_rate\n"
            "            elif sample_rate != current_sample_rate:\n"
            "                raise RuntimeError(f'Kokoro sample-rate mismatch: {sample_rate} != {current_sample_rate}')\n"
            "            if audio_parts and previous_lang != segment_lang:\n"
            "                audio_parts.append(_np.zeros(max(1, round(sample_rate * 0.065)), dtype=_np.float32))\n"
            "            audio_parts.append(_np.asarray(audio, dtype=_np.float32).reshape(-1))\n"
            "            previous_lang = segment_lang\n"
            "    audio = _np.concatenate(audio_parts) if audio_parts else _np.zeros(0, dtype=_np.float32)\n"
            "    out = io.BytesIO()\n"
            "    _sf.write(out, audio, int(sample_rate), format='WAV')\n"
            "    return out.getvalue()\n"
            "\n"
            "\n"
            "def _hermes_hub_patch_kokoro_speed_dtype(model):\n"
            "    try:\n"
            "        import types\n"
            "        import numpy as _np\n"
            "\n"
            "        def _create_audio_compat(self, phonemes, voice, speed):\n"
            "            tokens = self.tokenizer.tokenize(phonemes[:510])\n"
            "            style = _np.asarray(voice[len(tokens)], dtype=_np.float32).reshape(1, 256)\n"
            "            input_ids = _np.array([[0, *tokens, 0]], dtype=_np.int64)\n"
            "            input_names = {item.name for item in self.sess.get_inputs()}\n"
            "            token_name = 'input_ids' if 'input_ids' in input_names else 'tokens'\n"
            "            outputs = self.sess.run(None, {token_name: input_ids, 'style': style, 'speed': _np.array([float(speed)], dtype=_np.float32)})\n"
            "            return _np.asarray(outputs[0], dtype=_np.float32).reshape(-1), 24000\n"
            "\n"
            "        model._create_audio = types.MethodType(_create_audio_compat, model)\n"
            "    except Exception:\n"
            "        pass\n"
    )

    if "def _hermes_hub_kokoro_speech_bytes(" not in text:
        text += kokoro_runtime
        changes.append("kokoro tts runtime helpers")
    elif "# HERMES_HUB_KOKORO_GPU_V6" not in text:
        runtime_start = text.find("# HERMES_HUB_KOKORO_GPU_")
        if runtime_start < 0:
            runtime_start = text.index("def _hermes_hub_kokoro_speech_bytes(")
        runtime_end = text.find("\ndef _hermes_hub_preload_kokoro():", runtime_start)
        if runtime_end < 0:
            raise PatchError("kokoro runtime upgrade anchor not found")
        text = text[:runtime_start] + kokoro_runtime.lstrip("\n") + text[runtime_end:]
        changes.append("upgrade kokoro tts runtime to mixed-language v6")

    if "def _hermes_hub_preload_kokoro():" not in text:
        text += (
            "\n\n"
            "def _hermes_hub_preload_kokoro():\n"
            "    try:\n"
            "        import os\n"
            "        enabled = str(os.environ.get('HERMES_KOKORO_PRELOAD', '1')).lower() not in {'0', 'false', 'no'}\n"
            "        if not enabled:\n"
            "            return\n"
            "        voice = os.environ.get('HERMES_KOKORO_TTS_VOICE', 'if_sara')\n"
            "        _hermes_hub_kokoro_executor().submit(_hermes_hub_kokoro_speech_bytes, 'ok', voice, 'it', 1.08).result()\n"
            "        print('Kokoro TTS preloaded successfully.')\n"
            "    except Exception as e:\n"
            "        print('Failed to pre-load Kokoro TTS:', e)\n"
            "\n"
            "_hermes_hub_preload_kokoro()\n"
        )
        changes.append("pre-load kokoro tts at startup")
    elif "_hermes_hub_kokoro_speech_bytes('ok', voice, 'it', 1.08)" in text:
        text = text.replace(
            "_hermes_hub_kokoro_speech_bytes('ok', voice, 'it', 1.08)",
            "_hermes_hub_kokoro_executor().submit(_hermes_hub_kokoro_speech_bytes, 'ok', voice, 'it', 1.08).result()",
            1,
        )
        changes.append("preload kokoro on dedicated executor")

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

    text, hardening_changes = _harden_runtime(text)
    changes.extend(hardening_changes)

    text, jarvis_changes = _patch_jarvis_v1(text)
    changes.extend(jarvis_changes)
    return text, changes


def _patch_agent_chat_completion_helpers(text: str) -> tuple[str, list[str]]:
    changes: list[str] = []

    if "def _hermes_hub_model_extra_dict(value):" not in text:
        text, _ = _replace_once(
            text,
            "def interruptible_streaming_api_call(agent, api_kwargs: dict, *, on_first_delta=None):\n",
            "def _hermes_hub_model_extra_dict(value):\n"
            "    try:\n"
            "        if value is None:\n"
            "            return {}\n"
            "        if isinstance(value, dict):\n"
            "            return value\n"
            "        if hasattr(value, \"model_dump\"):\n"
            "            dumped = value.model_dump()\n"
            "            return dumped if isinstance(dumped, dict) else {}\n"
            "        if hasattr(value, \"dict\"):\n"
            "            dumped = value.dict()\n"
            "            return dumped if isinstance(dumped, dict) else {}\n"
            "    except Exception:\n"
            "        return {}\n"
            "    return {}\n"
            "\n"
            "\n"
            "def _hermes_hub_chunk_field(chunk, field):\n"
            "    try:\n"
            "        direct = getattr(chunk, field, None)\n"
            "        if direct is not None:\n"
            "            return direct\n"
            "    except Exception:\n"
            "        pass\n"
            "    extra = _hermes_hub_model_extra_dict(getattr(chunk, \"model_extra\", None))\n"
            "    return extra.get(field)\n"
            "\n"
            "\n"
            "def _hermes_hub_plain(value):\n"
            "    if value is None:\n"
            "        return None\n"
            "    if isinstance(value, (str, int, float, bool)):\n"
            "        return value\n"
            "    if isinstance(value, dict):\n"
            "        return {str(k): _hermes_hub_plain(v) for k, v in value.items()}\n"
            "    if isinstance(value, (list, tuple)):\n"
            "        return [_hermes_hub_plain(v) for v in value]\n"
            "    dumped = _hermes_hub_model_extra_dict(value)\n"
            "    if dumped:\n"
            "        return _hermes_hub_plain(dumped)\n"
            "    return str(value)\n"
            "\n"
            "\n"
            "def _hermes_hub_float(value):\n"
            "    try:\n"
            "        return float(value)\n"
            "    except Exception:\n"
            "        return None\n"
            "\n"
            "\n"
            "def _hermes_hub_int(value):\n"
            "    try:\n"
            "        return int(value)\n"
            "    except Exception:\n"
            "        return None\n"
            "\n"
            "\n"
            "def _hermes_hub_reasoning_text(chunk):\n"
            "    direct = _hermes_hub_plain(_hermes_hub_chunk_field(chunk, \"reasoning_content\"))\n"
            "    if isinstance(direct, str) and direct:\n"
            "        return direct\n"
            "    choices = _hermes_hub_plain(_hermes_hub_chunk_field(chunk, \"choices\"))\n"
            "    if not isinstance(choices, list):\n"
            "        return None\n"
            "    for choice in choices:\n"
            "        if not isinstance(choice, dict):\n"
            "            continue\n"
            "        delta = choice.get(\"delta\")\n"
            "        if not isinstance(delta, dict):\n"
            "            continue\n"
            "        reasoning = delta.get(\"reasoning_content\") or delta.get(\"reasoning\")\n"
            "        if isinstance(reasoning, str) and reasoning:\n"
            "            return reasoning\n"
            "    return None\n"
            "\n"
            "\n"
            "def _hermes_hub_emit_llama_stream_metadata(agent, chunk):\n"
            "    cb = getattr(agent, \"tool_progress_callback\", None)\n"
            "    if cb is None:\n"
            "        return\n"
            "    prompt_progress = _hermes_hub_plain(_hermes_hub_chunk_field(chunk, \"prompt_progress\"))\n"
            "    timings = _hermes_hub_plain(_hermes_hub_chunk_field(chunk, \"timings\"))\n"
            "    reasoning = _hermes_hub_reasoning_text(chunk)\n"
            "    if reasoning:\n"
            "        try:\n"
            "            cb(\"reasoning.available\", \"_thinking\", reasoning, {})\n"
            "        except Exception:\n"
            "            pass\n"
            "    if not prompt_progress and not timings:\n"
            "        return\n"
            "    payload = {\n"
            "        \"type\": \"hermes.processing.progress\",\n"
            "        \"event\": \"hermes.processing.progress\",\n"
            "        \"phase\": \"processing\" if prompt_progress else \"generation\",\n"
            "        \"source\": \"llama.cpp\",\n"
            "        \"estimated\": False,\n"
            "    }\n"
            "    if isinstance(prompt_progress, dict):\n"
            "        payload[\"prompt_progress\"] = prompt_progress\n"
            "        total = _hermes_hub_int(prompt_progress.get(\"total\"))\n"
            "        processed = _hermes_hub_int(prompt_progress.get(\"processed\"))\n"
            "        if total and total > 0 and processed is not None:\n"
            "            progress = max(0.0, min(1.0, processed / float(total)))\n"
            "            payload[\"progress\"] = progress\n"
            "            payload[\"percent\"] = round(progress * 100.0)\n"
            "        payload[\"label\"] = \"Elaborazione prompt\"\n"
            "    if isinstance(timings, dict):\n"
            "        payload[\"timings\"] = timings\n"
            "        tps = _hermes_hub_float(timings.get(\"predicted_per_second\") or timings.get(\"tokens_per_second\"))\n"
            "        if tps is not None and tps > 0:\n"
            "            payload[\"tokens_per_second\"] = tps\n"
            "            payload.setdefault(\"phase\", \"generation\")\n"
            "            payload.setdefault(\"label\", \"Generazione risposta\")\n"
            "    try:\n"
            "        cb(\"hermes.processing.progress\", \"llama.cpp\", payload.get(\"label\", \"llama.cpp\"), {}, **payload)\n"
            "    except Exception:\n"
            "        pass\n"
            "\n"
            "\n"
            "def interruptible_streaming_api_call(agent, api_kwargs: dict, *, on_first_delta=None):\n",
            "agent helpers llama stream metadata functions",
        )
        changes.append("agent helpers llama stream metadata functions")

    if 'api_kwargs["extra_body"] = _hermes_hub_extra_body' not in text:
        text, _ = _replace_once(
            text,
            "def interruptible_streaming_api_call(agent, api_kwargs: dict, *, on_first_delta=None):\n",
            "def interruptible_streaming_api_call(agent, api_kwargs: dict, *, on_first_delta=None):\n"
            "    _hermes_hub_extra_body = api_kwargs.get(\"extra_body\")\n"
            "    if not isinstance(_hermes_hub_extra_body, dict):\n"
            "        _hermes_hub_extra_body = {}\n"
            "    _hermes_hub_extra_body.setdefault(\"return_progress\", True)\n"
            "    _hermes_hub_extra_body.setdefault(\"timings_per_token\", True)\n"
            "    api_kwargs[\"extra_body\"] = _hermes_hub_extra_body\n",
            "agent request real llama prompt progress and timings",
        )
        changes.append("agent request real llama prompt progress and timings")

    if 'agent._touch_activity("receiving stream response")\n            _hermes_hub_emit_llama_stream_metadata(agent, chunk)' not in text:
        text, _ = _replace_once(
            text,
            '            agent._touch_activity("receiving stream response")\n'
            '\n'
            '            # Update per-attempt diagnostic counters.',
            '            agent._touch_activity("receiving stream response")\n'
            '            _hermes_hub_emit_llama_stream_metadata(agent, chunk)\n'
            '\n'
            '            # Update per-attempt diagnostic counters.',
            "agent emit real llama prompt progress and timings",
        )
        changes.append("agent emit real llama prompt progress and timings")

    return text, changes


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", help="Path to gateway/platforms/api_server.py")
    parser.add_argument("--check", action="store_true", help="Validate patchability without writing")
    args = parser.parse_args()

    target = _find_target(args.target)
    original = target.read_text(encoding="utf-8")
    patched, changes = _patch_text(original)
    helper_target = _find_agent_chat_completion_helpers(target)
    helper_original = None
    helper_patched = None
    helper_changes: list[str] = []
    if helper_target is not None:
        helper_original = helper_target.read_text(encoding="utf-8")
        helper_patched, helper_changes = _patch_agent_chat_completion_helpers(helper_original)
    else:
        helper_changes = ["agent chat_completion_helpers.py not found; raw llama progress passthrough skipped"]

    if args.check:
        actionable_helper_changes = [
            change for change in helper_changes
            if not change.endswith("not found; raw llama progress passthrough skipped")
        ]
        state = "already patched" if not changes and not actionable_helper_changes else "patchable"
        print(f"Hermes native gateway patch {state}: {target}")
        if changes:
            for change in changes:
                print(f"- {change}")
        if helper_target is not None:
            print(f"Hermes Agent stream helper: {helper_target}")
            for change in helper_changes:
                print(f"- {change}")
        else:
            print("- agent chat_completion_helpers.py not found; raw llama progress passthrough skipped")
        return 0

    actionable_helper_changes = [
        change for change in helper_changes
        if not change.endswith("not found; raw llama progress passthrough skipped")
    ]

    if not changes and not actionable_helper_changes:
        print(f"Hermes native gateway already patched: {target}")
        if helper_target is not None:
            print(f"Hermes Agent stream helper already patched: {helper_target}")
        return 0

    updates: list[tuple[Path, str]] = []
    if changes:
        updates.append((target, patched))
    if helper_target is not None and actionable_helper_changes and helper_patched is not None:
        updates.append((helper_target, helper_patched))

    backups = _write_compiled_transaction(updates)

    if changes:
        print(f"Hermes native gateway patched: {target}")
        print(f"Backup: {backups[target]}")
    else:
        print(f"Hermes native gateway already patched: {target}")
    for change in changes:
        print(f"- {change}")
    if helper_target is not None and actionable_helper_changes:
        print(f"Hermes Agent stream helper patched: {helper_target}")
        print(f"Backup: {backups[helper_target]}")
        for change in helper_changes:
            print(f"- {change}")
    elif helper_target is not None:
        print(f"Hermes Agent stream helper already patched: {helper_target}")
    else:
        print("- agent chat_completion_helpers.py not found; raw llama progress passthrough skipped")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
