"""Hermes GPU Manager — CPU-only daemon to share 2x GPU between TabbyAPI/Qwen and ComfyUI.

Modes: LLM (Qwen online) | MEDIA (ComfyUI online) | AUTO (switch on demand).
LLM mode is the safe default: AUTO + idle == LLM_READY.
"""

from __future__ import annotations

import asyncio
import hmac
import io
import json
import logging
import os
import shutil
import sqlite3
import subprocess
import time
import urllib.request
import uuid
import wave

try:
    import triage as _triage_mod
except ImportError:
    _triage_mod = None  # type: ignore[assignment]
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

APP_NAME = "hermes-gpu-manager"

# ---------------------------------------------------------------- config ---

DEFAULT_CONFIG = {
    "host": "0.0.0.0",
    "port": 8643,
    "api_key": "",
    "db_path": "/var/lib/hermes-gpu-manager/state.db",
    "output_dir": "/opt/hermes/gpu-manager/outputs",
    "default_mode": "auto",
    "llm": {
        "backend": "tabbyapi",
        "base_url": "http://127.0.0.1:8000",
        "service": "hermes-tabby.service",
        "startup_timeout": 420,
        "health_model": "Qwen3.8-27B-Uncensored-EXL3-3.07bpw",
    },
    "media": {
        "backend": "comfyui",
        "service": "hermes-comfyui.service",
        "base_url": "http://127.0.0.1:8188",
        "output_dir": "/opt/hermes/runtimes/comfyui/app/output",
        "startup_timeout": 300,
        "job_timeout_image": 1200,
        "job_timeout_video": 3600,
        "poll_interval": 2.0,
    },
    "switching": {
        "media_idle_timeout": 30,
        "llm_drain_timeout": 10,
        "llm_shutdown_timeout": 60,
        "vram_free_mb": 2500,
        "post_unload_settle": 5,
        "media_failure_threshold": 2,
        "media_cooldown_seconds": 1200,
        "media_gone_timeout": 120,
    },
    "recovery": {
        "max_retries": 3,
        "retry_backoff": [10, 30, 60],
    },
}


def load_config() -> dict:
    import os

    cfg = json.loads(json.dumps(DEFAULT_CONFIG))
    for path in ("/etc/hermes/gpu-manager.yaml", "/etc/hermes/gpu-manager.yml"):
        try:
            import yaml  # type: ignore

            raw = Path(path).read_text(encoding="utf-8")
            data = yaml.safe_load(raw) or {}
            _deep_merge(cfg, data)
            logging.info("loaded config %s", path)
            break
        except FileNotFoundError:
            continue
        except Exception as exc:  # noqa: BLE001 - config must never crash boot
            logging.warning("ignoring unreadable config %s: %s", path, exc)
    for key in ("HERMES_GPU_MANAGER_PORT",):
        if os.environ.get(key):
            try:
                cfg["port"] = int(os.environ[key])
            except ValueError:
                pass
    if os.environ.get("HERMES_GPU_MANAGER_KEY"):
        cfg["api_key"] = os.environ["HERMES_GPU_MANAGER_KEY"]
    return cfg


def _deep_merge(base: dict, override: dict) -> None:
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(base.get(key), dict):
            _deep_merge(base[key], value)
        else:
            base[key] = value


CONFIG = load_config()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger(APP_NAME)

# ------------------------------------------------------------------ state ---

STATES = (
    "BOOT",
    "LLM_READY",
    "LLM_UNLOADING",
    "GPU_FREE",
    "MEDIA_STARTING",
    "MEDIA_READY",
    "MEDIA_BUSY",
    "MEDIA_STOPPING",
    "LLM_LOADING",
    "DIRECT",
    "ERROR",
)

MODES = ("LLM", "MEDIA", "AUTO", "DIRECT")

_state = {
    "current_state": "BOOT",
    "desired_mode": str(CONFIG.get("default_mode", "auto")).upper(),
    "current_job": None,
    "current_prompt_id": None,
    "last_error": "",
    "last_transition": "",
    "media_idle_since": 0.0,
    "media_consec_failures": 0,
    "media_cooldown_until": 0.0,
    "cooldown_log_ts": 0.0,
    "retries": 0,
    "active_media_model": "",
    "active_preset": "",
    "media_progress": 0.0,
    "llm_watch_ts": 0.0,
    "error_retry_ts": 0.0,
}
if _state["desired_mode"] not in MODES:
    _state["desired_mode"] = "AUTO"

_lock = asyncio.Lock()
_db: sqlite3.Connection | None = None


WORKFLOWS_DIR = "/opt/hermes/media-workflows"
MEDIA_OUTPUT_DIR = "/opt/hermes/media-output"

PRESETS = {
    "create_image": {"kind": "image", "backend": "qwen-image-2.1", "model": "qwen_image_2.1-Q4_K", "file": "qwen/qwen-image-2.1-8gb-t2i.json"},
    "edit_image": {"kind": "image", "backend": "qwen-image-2.1", "model": "qwen_image_2.1-Q4_K", "file": "qwen/qwen-image-2.1-8gb-edit.json", "needs_input": True},
    "journey_video_preview": {"kind": "video", "backend": "minimax-h3", "model": "minimax_h3_fl2va_pruned_int8+turbo4", "file": "h3/i2v-turbo.json"},
    "journey_video_quality": {"kind": "video", "backend": "minimax-h3", "model": "minimax_h3_fl2va_pruned_int8", "file": "h3/i2v.json", "disabled": "DISABLED_FULLSTEPS_OOM"},
    "journey_video_first_last": {"kind": "video", "backend": "minimax-h3", "model": "minimax_h3_fl2va_pruned_int8", "file": "h3/first_last.json", "disabled": "DISABLED_FULLSTEPS_OOM"},
    "journey_video_reference": {"kind": "video", "backend": "minimax-h3", "model": "minimax_h3_ref2va_pruned_int8", "file": "h3/reference.json", "disabled": "DISABLED_FULLSTEPS_OOM"},
}

IMAGE_EXTS = (".png", ".jpg", ".jpeg", ".webp")
MAX_INPUT_MB = 50


# Node classes the manager will ever submit to ComfyUI. Custom/raw
# workflows are restricted to this set: no checkpoint loaders, no
# arbitrary custom nodes, no shell-adjacent utilities.
ALLOWED_NODE_CLASSES = frozenset({
    "UNETLoader", "UnetLoaderGGUF", "UnetLoaderGGUFAdvanced",
    "CLIPLoader", "DualCLIPLoaderGGUF", "TripleCLIPLoaderGGUF",
    "VAELoader", "TextEncodeQwenImage21",
    "QwenImage21Cache", "ComfySwitchNode",
    "EmptyLatentImage", "KSampler", "VAEDecode", "SaveImage",
    "LoadImage", "LoraLoader", "SaveAnimatedPNG",
    "MiniMaxH3ImageToVideo", "MiniMaxH3ReferenceToVideo",
    "H3MultiStream",
})

# Placeholders filled by trusted server-side values only; caller params
# must never override these.
RESERVED_PLACEHOLDERS = frozenset({"JOB_ID", "LENGTH"})

MAX_PROMPT_CHARS = 4000


def _num(value: object, default: float, lo: float, hi: float) -> str:
    """Coerce to a clamped number; anything non-numeric becomes default."""
    try:
        num = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        num = float(default)
    if num != num:  # NaN
        num = float(default)
    num = min(hi, max(lo, num))
    return str(int(num)) if num.is_integer() else str(num)


def _text(value: object, default: str = "", limit: int = MAX_PROMPT_CHARS) -> str:
    """JSON-escape a string for textual {{PLACEHOLDER}} substitution,
    truncated to a sane length.

    Templates quote string slots (e.g. "prompt": "{{PROMPT}}"), so the value
    must be escaped without the surrounding quotes: json.dumps()[1:-1].
    Numeric-looking strings stay bare, keeping unquoted numeric slots valid.
    """
    raw = str(value) if value else default
    return json.dumps(raw[:limit])[1:-1]


# Default negative prompt shipped by the reference qwen-image-2.1-8gb
# workflows. Used for Qwen presets when the caller passes none.
REPO_DEFAULT_NEGATIVE = (
    "low quality, worst quality, blurry, jpeg artifacts, noise, distorted, "
    "deformed, bad anatomy, extra fingers, fused fingers, extra limbs, "
    "disfigured face, watermark, text, signature, oversaturated, "
    "plastic skin, cropped"
)


def _prune_unresolved_loads(workflow: dict) -> dict:
    """Drop LoadImage nodes whose file was never staged (optional
    INPUT_IMAGE_N slots) plus any references to them, so one template
    serves single- and multi-reference calls."""
    dead = {
        nid
        for nid, node in workflow.items()
        if isinstance(node, dict)
        and node.get("class_type") == "LoadImage"
        and isinstance((node.get("inputs") or {}).get("image"), str)
        and "{{" in str(node["inputs"]["image"])
    }
    if not dead:
        return workflow

    def refers_dead(value: object) -> bool:
        return isinstance(value, list) and len(value) == 2 and value[0] in dead

    def scrub(value: object) -> object:
        if refers_dead(value):
            return None
        if isinstance(value, dict):
            return {k: scrub(v) for k, v in value.items() if scrub(v) is not None}
        if isinstance(value, list):
            return [scrub(v) for v in value]
        return value

    for nid in dead:
        del workflow[nid]
    for node in workflow.values():
        if not isinstance(node, dict):
            continue
        inputs = node.get("inputs")
        if isinstance(inputs, dict):
            node["inputs"] = scrub(inputs)
    return workflow


def render_preset(preset: str, params: dict, job_id: str) -> tuple[dict | None, str]:
    """Render a versioned workflow template. Returns (workflow, error)."""
    spec = PRESETS.get(preset)
    if not spec:
        return None, f"unknown preset {preset!r}"
    if spec.get("disabled"):
        return None, f"preset {preset} disabled: {spec['disabled']}"
    path = Path(WORKFLOWS_DIR) / spec["file"]
    try:
        template = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return None, f"workflow template missing: {spec['file']}"
    params = dict(params or {})
    default_cfg = "6.0" if (PRESETS.get(preset) or {}).get("kind") == "video" else "1.0"
    default_negative = (
        REPO_DEFAULT_NEGATIVE
        if (PRESETS.get(preset) or {}).get("backend") == "qwen-image-2.1"
        else " "
    )
    mapping = {
        "JOB_ID": job_id,
        "PROMPT": _text(params.get("prompt", "")),
        "NEGATIVE_PROMPT": _text(params.get("negative_prompt") or default_negative),
        "SEED": _num(params.get("seed", 7), 7, 0, 2 ** 31 - 1),
        "STEPS": _num(params.get("steps", 25), 25, 1, 100),
        "CFG": _num(params.get("cfg", default_cfg), float(default_cfg), 0, 30),
        "RESOLUTION": _num(params.get("resolution", 1024), 1024, 0, 2048),
        "DENOISE": _num(params.get("denoise", 0.8), 0.8, 0, 1),
        "WIDTH": _num(params.get("width", 1024 if (PRESETS.get(preset) or {}).get("kind") == "image" else 1344), 1024, 64, 2048),
        "HEIGHT": _num(params.get("height", 1024 if (PRESETS.get(preset) or {}).get("kind") == "image" else 768), 1024, 64, 2048),
    }
    try:
        duration = float(params.get("duration", 0) or 0)
    except (TypeError, ValueError):
        duration = 0
    if duration > 0:
        grid_len = ((int(duration * 24) - 5 + 16) // 17) * 17 + 5
        mapping["LENGTH"] = str(max(22, min(3600, grid_len)))
    else:
        mapping["LENGTH"] = _num(params.get("length", 124), 124, 22, 3600)
    for key, value in params.items():
        upper = str(key).upper()
        if upper in RESERVED_PLACEHOLDERS or upper in mapping:
            continue
        mapping[upper] = _text(value) if isinstance(value, str) else _num(value, 0, -10**12, 10**12)
    workflow_text = template
    staged: list[str] = []
    input_images = params.get("input_images") or []
    if isinstance(input_images, str):
        input_images = [input_images]
    for idx, src in enumerate(input_images[:4], start=1):
        src_path = Path(str(src))
        if not src_path.is_file():
            return None, f"input image missing: {src}"
        if src_path.suffix.lower() not in IMAGE_EXTS:
            return None, f"unsupported input type: {src_path.suffix}"
        if src_path.stat().st_size > MAX_INPUT_MB * 1024 * 1024:
            return None, f"input too large: {src}"
        dest_name = f"{job_id}_in{idx}{src_path.suffix.lower()}"
        dest = Path(str(CONFIG["media"].get("input_dir", "/opt/hermes/runtimes/comfyui/app/input"))) / dest_name
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src_path, dest)
        staged.append(dest_name)
        mapping[f"INPUT_IMAGE_{idx}"] = dest_name
    if staged:
        mapping["INPUT_IMAGE"] = staged[0]
    elif spec.get("needs_input"):
        return None, f"preset {preset} needs one input photo in input_images"
    for key, value in mapping.items():
        workflow_text = workflow_text.replace("{{" + key + "}}", value)
    try:
        workflow = json.loads(workflow_text)
    except (json.JSONDecodeError, ValueError) as exc:
        return None, f"rendered workflow is not valid JSON: {exc}"
    workflow = _prune_unresolved_loads(workflow)
    if "{{" in json.dumps(workflow):
        return None, "unresolved template placeholders remain"
    problem = validate_workflow(workflow)
    if problem:
        return None, problem
    return workflow, ""


def cleanup_old_artifacts(max_age_days: int = 7) -> int:
    """Delete staged inputs and job output dirs older than the retention.
    Prevents slow disk exhaustion from accumulated media artifacts."""
    removed = 0
    now = time.time()
    cutoff = now - max_age_days * 86400
    roots = [
        Path(str(CONFIG["media"].get("input_dir", "/opt/hermes/runtimes/comfyui/app/input"))),
        Path(MEDIA_OUTPUT_DIR),
    ]
    for root in roots:
        input_root = root == roots[0]
        try:
            entries = list(root.iterdir())
        except OSError:
            continue
        for entry in entries:
            try:
                if entry.stat().st_mtime > cutoff:
                    continue
                if input_root and not (
                    entry.is_file()
                    and entry.suffix.lower() in IMAGE_EXTS
                    and "_in" in entry.stem
                ):
                    # Only our staged job inputs; never touch anything else.
                    continue
                if entry.is_dir() and not entry.is_symlink():
                    shutil.rmtree(entry, ignore_errors=True)
                else:
                    entry.unlink(missing_ok=True)
                removed += 1
            except OSError:
                continue
    if removed:
        log.info("artifact cleanup removed %d stale entries", removed)
    return removed


def _db_conn() -> sqlite3.Connection:
    global _db
    if _db is None:
        Path(str(CONFIG["db_path"])).parent.mkdir(parents=True, exist_ok=True)
        _db = sqlite3.connect(str(CONFIG["db_path"]), check_same_thread=False)
        _db.execute("PRAGMA journal_mode=WAL")
        _db.execute(
            "CREATE TABLE IF NOT EXISTS jobs ("
            "id TEXT PRIMARY KEY, kind TEXT, status TEXT, workflow TEXT, "
            "progress REAL, result_paths TEXT, error TEXT, retries INTEGER, "
            "created REAL, updated REAL)"
        )
        for col in ("preset TEXT DEFAULT ''", "backend TEXT DEFAULT ''",
                    "model TEXT DEFAULT ''", "params TEXT DEFAULT '{}'",
                    "vram_peak_mb REAL DEFAULT 0", "phase TEXT DEFAULT ''"):
            try:
                _db.execute(f"ALTER TABLE jobs ADD COLUMN {col}")
            except sqlite3.OperationalError:
                pass
        _db.execute(
            "CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT)"
        )
        _db.commit()
        row = _db.execute("SELECT value FROM kv WHERE key='desired_mode'").fetchone()
        if row and row[0] in MODES:
            _state["desired_mode"] = row[0]
    return _db


def _persist_desired() -> None:
    db = _db_conn()
    db.execute(
        "INSERT INTO kv(key,value) VALUES('desired_mode',?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (_state["desired_mode"],),
    )
    db.commit()


def set_state(new_state: str, note: str = "") -> None:
    assert new_state in STATES, new_state
    old = _state["current_state"]
    _state["current_state"] = new_state
    _state["last_transition"] = f"{old}->{new_state} {note}".strip()
    log.info("STATE %s", _state["last_transition"])


# ---------------------------------------------------------------- helpers ---

def _run(cmd: list[str], timeout: int = 60) -> tuple[int, str]:
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return proc.returncode, (proc.stdout + proc.stderr)[-4000:]
    except subprocess.TimeoutExpired:
        return 124, "timeout"
    except Exception as exc:  # noqa: BLE001
        return 127, str(exc)


async def _run_async(cmd: list[str], timeout: int = 60) -> tuple[int, str]:
    return await asyncio.to_thread(_run, cmd, timeout)


def _http(method: str, url: str, payload: dict | None = None, timeout: int = 15) -> tuple[int, str]:
    try:
        data = None
        headers = {"Content-Type": "application/json"}
        if payload is not None:
            data = json.dumps(payload).encode()
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")[:20000]
    except Exception as exc:  # noqa: BLE001 - probe must never raise
        msg = str(exc)
        code = 0
        if "HTTP Error 4" in msg or "HTTP Error 5" in msg:
            try:
                code = int(msg.split("HTTP Error ")[1].split(":")[0])
            except (IndexError, ValueError):
                code = -1
        return code, msg[-2000:]


async def _http_async(method: str, url: str, payload: dict | None = None, timeout: int = 15):
    return await asyncio.to_thread(_http, method, url, payload, timeout)


def gpu_snapshot() -> list[dict]:
    code, out = _run(
        [
            "nvidia-smi",
            "--query-gpu=index,name,memory.total,memory.used,utilization.gpu",
            "--format=csv,noheader,nounits",
        ],
        timeout=20,
    )
    gpus: list[dict] = []
    if code != 0:
        return gpus
    for line in out.strip().splitlines():
        parts = [p.strip() for p in line.split(",")]
        if len(parts) < 5:
            continue
        try:
            gpus.append(
                {
                    "index": int(parts[0]),
                    "name": parts[1],
                    "memory_total_mb": float(parts[2]),
                    "memory_used_mb": float(parts[3]),
                    "utilization_gpu": float(parts[4]),
                }
            )
        except ValueError:
            continue
    return gpus


def gpu_compute_procs() -> list[dict]:
    code, out = _run(
        ["nvidia-smi", "--query-compute-apps=pid,used_memory", "--format=csv,noheader,nounits"],
        timeout=20,
    )
    procs: list[dict] = []
    if code != 0:
        return procs
    for line in out.strip().splitlines():
        parts = [p.strip() for p in line.split(",")]
        if len(parts) < 2:
            continue
        try:
            procs.append({"pid": int(parts[0]), "used_mb": float(parts[1])})
        except ValueError:
            continue
    return procs


def _proc_cmd(pid: int) -> str:
    try:
        return Path(f"/proc/{pid}/cmdline").read_bytes().decode("utf-8", "replace").replace("\x00", " ")[:300]
    except Exception:  # noqa: BLE001
        return ""


# --------------------------------------------------------------- backends ---

def llm_base() -> str:
    return str(CONFIG["llm"]["base_url"]).rstrip("/")


def media_base() -> str:
    return str(CONFIG["media"]["base_url"]).rstrip("/")


async def llm_online() -> bool:
    code, _ = await _http_async("GET", llm_base() + "/v1/models", timeout=10)
    return code == 200


async def llm_loaded() -> bool:
    """True only when Qwen weights are really resident (VRAM), not just listed."""
    if not await llm_online():
        return False
    threshold = float(CONFIG["switching"].get("llm_vram_min_mb", 8000))
    gpus = await asyncio.to_thread(gpu_snapshot)
    if not gpus:
        return False
    return all(g["memory_used_mb"] >= threshold for g in gpus)


async def media_online() -> bool:
    code, _ = await _http_async("GET", media_base() + "/system_stats", timeout=10)
    if code == 200:
        return True
    code, _ = await _http_async("GET", media_base() + "/prompt", timeout=10)
    return code in (200, 405)


async def systemctl(action: str, unit: str, timeout: int = 180) -> bool:
    code, out = await _run_async(
        ["sudo", "-n", "systemctl", action, unit], timeout=timeout
    )
    if code != 0:
        log.warning("systemctl %s %s rc=%d: %s", action, unit, code, out[-300:])
        return False
    return True


async def service_active(unit: str) -> bool:
    code, out = await _run_async(
        ["systemctl", "is-active", "--quiet", unit], timeout=15
    )
    return code == 0


def _user_systemctl_cmd(action: str, unit: str, user: str = "matteo") -> list[str]:
    return ["sudo", "-n", "-u", user, "env", "XDG_RUNTIME_DIR=/run/user/1000",
            "systemctl", "--user", action, unit]


async def systemctl_user(action: str, unit: str, timeout: int = 180) -> bool:
    code, out = await _run_async(_user_systemctl_cmd(action, unit), timeout=timeout)
    if code != 0:
        log.warning("systemctl --user %s %s rc=%d: %s", action, unit, code, out[-300:])
        return False
    return True


async def service_active_user(unit: str) -> bool:
    code, _ = await _run_async(
        _user_systemctl_cmd("is-active", unit) + ["--quiet"], timeout=15
    )
    return code == 0


async def unload_llm() -> bool:
    code, body = await _http_async("POST", llm_base() + "/v1/model/unload", {}, timeout=120)
    if code == 200:
        log.info("TabbyAPI unload accepted")
        return True
    log.warning("TabbyAPI unload rc=%s body=%s", code, body[-300:])
    return False


async def vram_free(threshold_mb: float) -> bool:
    gpus = await asyncio.to_thread(gpu_snapshot)
    if not gpus:
        return False
    return all(g["memory_used_mb"] < threshold_mb for g in gpus)


async def cleanup_stray_cuda() -> int:
    """Kill leftover CUDA processes from our runtimes only. Never touch others."""
    killed = 0
    for proc in await asyncio.to_thread(gpu_compute_procs):
        cmd = _proc_cmd(proc["pid"])
        low = cmd.lower()
        if not cmd:
            continue
        if "comfy" in low or "exllamav3" in low or "tabbyapi" in low or "llama-mainline" in low:
            if "gpu-manager" in low:
                continue
            code, _ = await _run_async(["sudo", "-n", "kill", "-9", str(proc["pid"])], timeout=15)
            if code == 0:
                killed += 1
                log.warning("killed stray CUDA pid=%d cmd=%s", proc["pid"], cmd[:120])
    return killed


def cleanup_stale_shm() -> int:
    """Remove torch shared-memory segments of dead processes.

    ExLlamaV3 leaks /dev/shm/torch_<pid>_* on crashes; a full shm makes the
    next TabbyAPI load fail with 'No space left on device'. Only segments
    whose PID is gone are removed; live ones are never touched.
    """
    removed = 0
    try:
        names = os.listdir("/dev/shm")
    except OSError as exc:
        log.warning("shm cleanup scan failed: %r", exc)
        return 0
    for name in names:
        if not name.startswith("torch_"):
            continue
        pid: int | None = None
        parts = name.split("_")
        if len(parts) >= 2 and parts[1].isdigit():
            pid = int(parts[1])
        if pid is not None and os.path.exists(f"/proc/{pid}"):
            continue
        try:
            os.remove(os.path.join("/dev/shm", name))
            removed += 1
        except OSError:
            pass
    if removed:
        log.info("removed %d stale shm segments", removed)
    return removed


async def wait_llm_online(timeout: int) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if await llm_loaded():
            return True
        await asyncio.sleep(10)
    return False


async def wait_media_online(timeout: int) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if await media_online():
            return True
        await asyncio.sleep(5)
    return False


async def llm_test_request() -> bool:
    code, body = await _http_async(
        "POST",
        llm_base() + "/v1/chat/completions",
        {
            "model": str(CONFIG["llm"].get("health_model") or "hermes-agent"),
            "messages": [{"role": "user", "content": "Reply with OK"}],
            "max_tokens": 4,
            "stream": False,
        },
        timeout=180,
    )
    ok = code == 200 and len(body) > 20
    log.info("LLM health check %s", "successful" if ok else f"FAILED rc={code}")
    return ok


# ------------------------------------------------------------------ jobs ---

def job_row(row: sqlite3.Row | tuple) -> dict:
    vals = list(row) + [""] * 16
    (jid, kind, status, workflow, progress, result_paths, error, retries,
     created, updated, preset, backend, model, params, vram_peak, phase) = vals[:16]
    try:
        results = json.loads(result_paths) if result_paths else []
    except (json.JSONDecodeError, TypeError):
        results = ["!corrupt-result-paths"]
    try:
        parameters = json.loads(params) if params else {}
    except (json.JSONDecodeError, TypeError):
        parameters = {"!corrupt-params": True}
    return {
        "job_id": jid,
        "kind": kind,
        "status": status,
        "preset": preset or "",
        "backend": backend or "",
        "model": model or "",
        "parameters": parameters,
        "progress": progress,
        "result_paths": results,
        "error": error or "",
        "retries": retries,
        "vram_peak_mb": vram_peak or 0,
        "phase": phase or "",
        "created_at": created,
        "updated_at": updated,
    }


def create_job(kind: str, workflow: dict, preset: str = "", backend: str = "",
               model: str = "", params: dict | None = None) -> dict:
    jid = uuid.uuid4().hex[:12]
    now = time.time()
    db = _db_conn()
    db.execute(
        "INSERT INTO jobs(id,kind,status,workflow,progress,result_paths,error,retries,"
        "created,updated,preset,backend,model,params,vram_peak_mb)"
        " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (jid, kind, "queued", json.dumps(workflow), 0.0, "[]", "", 0, now, now,
         preset, backend, model, json.dumps(params or {}), 0),
    )
    db.commit()
    log.info("job %s %s preset=%s queued", jid, kind, preset or "-")
    return get_job(jid)


def get_job(jid: str) -> dict | None:
    db = _db_conn()
    row = db.execute("SELECT * FROM jobs WHERE id=?", (jid,)).fetchone()
    return job_row(row) if row else None


# Columns update_job is allowed to write. Anything else is a bug, never SQL.
JOB_WRITABLE_FIELDS = frozenset({
    "status", "workflow", "progress", "result_paths", "error", "retries",
    "updated", "preset", "backend", "model", "params", "vram_peak_mb", "phase",
})


def update_job(jid: str, **fields) -> None:
    fields = {k: v for k, v in fields.items() if k in JOB_WRITABLE_FIELDS}
    fields["updated"] = time.time()
    if not fields:
        return
    sets = ", ".join(f"{k}=?" for k in fields)
    db = _db_conn()
    db.execute(f"UPDATE jobs SET {sets} WHERE id=?", (*fields.values(), jid))
    db.commit()


def queued_jobs() -> list[dict]:
    db = _db_conn()
    rows = db.execute("SELECT * FROM jobs WHERE status='queued' ORDER BY created").fetchall()
    return [job_row(r) for r in rows]


def validate_workflow(workflow: dict) -> str:
    if not isinstance(workflow, dict) or not workflow:
        return "workflow must be a non-empty node dict"
    for key, node in workflow.items():
        if not isinstance(node, dict) or "class_type" not in node or "inputs" not in node:
            return f"node {key!r} lacks class_type/inputs"
        if node.get("class_type") not in ALLOWED_NODE_CLASSES:
            return f"node {key!r} class {node.get('class_type')!r} is not allowed"
    return ""


# ------------------------------------------------------------- transitions ---

async def transition_to_media() -> bool:
    """LLM_READY -> MEDIA_READY. Returns True when ComfyUI serves."""
    cfg = CONFIG["switching"]
    set_state("LLM_UNLOADING")
    # Entering agent media from anywhere: the direct backend must go first,
    # then voice (every GB counts for Comfy).
    await systemctl("stop", direct_service_name())
    await stop_voice()
    drain = float(cfg.get("llm_drain_timeout", 10))
    log.info("waiting %.0fs for in-flight LLM work to finish", drain)
    await asyncio.sleep(min(drain, 30))
    if not await unload_llm():
        set_state("ERROR", "Tabby unload failed")
        return False
    await asyncio.sleep(float(cfg.get("post_unload_settle", 5)))
    threshold = float(cfg.get("vram_free_mb", 2500))
    for _ in range(12):
        if await vram_free(threshold):
            break
        await asyncio.sleep(5)
    if not await vram_free(threshold):
        await cleanup_stray_cuda()
        await asyncio.sleep(5)
    if not await vram_free(threshold):
        set_state("ERROR", "GPU not freed after unload")
        return False
    for g in await asyncio.to_thread(gpu_snapshot):
        log.info("GPU%d free VRAM %.0f MB", g["index"], g["memory_total_mb"] - g["memory_used_mb"])
    set_state("GPU_FREE")
    set_state("MEDIA_STARTING")
    if not await systemctl("start", str(CONFIG["media"]["service"])):
        set_state("ERROR", "ComfyUI start failed")
        return False
    if not await wait_media_online(int(CONFIG["media"]["startup_timeout"])):
        set_state("ERROR", "ComfyUI health timeout")
        await systemctl("stop", str(CONFIG["media"]["service"]))
        return False
    log.info("ComfyUI ready")
    set_state("MEDIA_READY")
    return True


async def transition_to_llm() -> bool:
    """MEDIA_* -> LLM_READY with health check. Returns True on success."""
    set_state("MEDIA_STOPPING")
    await systemctl("stop", str(CONFIG["media"]["service"]))
    await systemctl("stop", direct_service_name())
    await asyncio.sleep(3)
    await asyncio.to_thread(cleanup_stale_shm)
    if not await vram_free(float(CONFIG["switching"].get("vram_free_mb", 2500))):
        await cleanup_stray_cuda()
    set_state("LLM_LOADING")
    if not await systemctl("restart", str(CONFIG["llm"]["service"])):
        set_state("ERROR", "Tabby restart failed")
        return False
    if not await wait_llm_online(int(CONFIG["llm"]["startup_timeout"])):
        set_state("ERROR", "Tabby reload timeout")
        return False
    if not await llm_test_request():
        set_state("ERROR", "LLM health check failed")
        return False
    set_state("LLM_READY")
    _state["retries"] = 0
    await ensure_voice_up(force_warm=True)
    return True


async def restore_llm_with_retries(context: str) -> bool:
    max_retries = int(CONFIG["recovery"].get("max_retries", 3))
    backoffs: list = CONFIG["recovery"].get("retry_backoff", [10, 30, 60])
    for attempt in range(max_retries + 1):
        if await transition_to_llm():
            return True
        _state["retries"] = attempt + 1
        if attempt >= max_retries:
            break
        wait = backoffs[attempt] if attempt < len(backoffs) else backoffs[-1]
        log.warning("restore %s failed (%s), retry %d/%d in %ss", context, _state["last_error"], attempt + 1, max_retries, wait)
        await asyncio.sleep(wait)
    set_state("ERROR", f"Qwen restore failed after {max_retries} retries ({context})")
    return False


# ------------------------------------------------------- voice (TTS/STT) ---
# Kokoro TTS + UniNote STT vivono con l'LLM: partono e scaldano a ogni
# restore LLM, si fermano quando i media prendono le GPU (ogni GB conta
# per Comfy). Mai fatali: un problema voce non ferma mai le transizioni.

def voice_services() -> list[str]:
    v = CONFIG.get("voice", {})
    svcs = v.get("services", ["hermes-kokoro-tts.service"])
    return [str(s) for s in svcs] if isinstance(svcs, list) else []


def voice_user_services() -> list[str]:
    v = CONFIG.get("voice", {})
    svcs = v.get("user_services", ["uninote-stt.service"])
    return [str(s) for s in svcs] if isinstance(svcs, list) else []


def _voice_cfg() -> tuple[str, str, str]:
    v = CONFIG.get("voice", {})
    return (
        str(v.get("kokoro_url", "http://127.0.0.1:8020")),
        str(v.get("stt_url", "http://100.94.223.14:8010")),
        str(v.get("kokoro_key", "")),
    )


def _voice_warm_sync() -> str:
    """Warmup bloccante (gira in thread): "" se TTS+STT rispondono davvero."""
    kokoro_url, stt_url, kokoro_key = _voice_cfg()
    problems: list[str] = []
    try:
        body = json.dumps({"input": "ok", "voice": "if_sara", "lang": "it"}).encode()
        req = urllib.request.Request(
            kokoro_url + "/v1/audio/speech", data=body, method="POST",
            headers={"Content-Type": "application/json",
                     "Authorization": f"Bearer {kokoro_key}"},
        )
        with urllib.request.urlopen(req, timeout=120) as r:
            if r.status not in (200, 201) or not r.read(1):
                problems.append(f"kokoro http {r.status}")
    except Exception as exc:  # noqa: BLE001 - best effort, mai fatale
        problems.append(f"kokoro: {exc!r}"[:160])
    try:
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(16000)
            w.writeframes(b"\x00" * 32000)
        blob = buf.getvalue()
        boundary = "hermesvoicewarm"
        body = (
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
            f'filename="warm.wav"\r\nContent-Type: audio/wav\r\n\r\n'
        ).encode() + blob + f"\r\n--{boundary}--\r\n".encode()
        req = urllib.request.Request(
            stt_url + "/v1/audio/transcriptions", data=body, method="POST",
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        )
        with urllib.request.urlopen(req, timeout=180) as r:
            if r.status not in (200, 201):
                problems.append(f"stt http {r.status}")
    except Exception as exc:  # noqa: BLE001 - best effort, mai fatale
        problems.append(f"stt: {exc!r}"[:160])
    return "; ".join(problems)


async def ensure_voice_up(force_warm: bool = False) -> None:
    """Idempotente, mai solleva: servizi su, modelli scaldati se freddi."""
    for svc in voice_services():
        if not await service_active(svc):
            log.info("voice: starting %s", svc)
            await systemctl("start", svc)
    for svc in voice_user_services():
        if not await service_active_user(svc):
            log.info("voice: starting user %s", svc)
            await systemctl_user("start", svc)
    if not force_warm and time.monotonic() - float(_state.get("voice_warm_ts") or 0.0) < 600:
        return
    problems = await asyncio.to_thread(_voice_warm_sync)
    if problems:
        log.warning("voice warmup issues: %s", problems)
    else:
        _state["voice_warm_ts"] = time.monotonic()
        log.info("voice warm (tts+stt)")


async def stop_voice() -> None:
    for svc in voice_services():
        await systemctl("stop", svc)
    for svc in voice_user_services():
        await systemctl_user("stop", svc)


# ------------------------------------------------- direct comfyui ---
def direct_service_name() -> str:
    return str(CONFIG.get("direct", {}).get("service", "hermes-comfyui-direct.service"))


def direct_public_url() -> str:
    return str(CONFIG.get("direct", {}).get("public_url", ""))


async def transition_to_direct() -> bool:
    """Enter direct-Comfy mode: LLM unloaded, ComfyUI exposed for the user,
    worker parked. Returns True when the direct backend serves."""
    direct = CONFIG.get("direct", {})
    service = direct_service_name()
    set_state("LLM_UNLOADING")
    # Never run both backends: the agent headless Comfy goes first, then
    # voice (every GB counts for the direct session too).
    await systemctl("stop", str(CONFIG["media"]["service"]))
    await stop_voice()
    current = _state.get("current_job")
    if current:
        update_job(str(current), status="failed",
                   error="superseded by direct Comfy mode")
        _state["current_job"] = None
        _state["current_prompt_id"] = None
        log.info("job %s failed: superseded by direct Comfy mode", current)
    if not await unload_llm():
        log.warning("direct: Tabby unload refused, continuing if VRAM is free")
    await asyncio.sleep(float(CONFIG["switching"].get("post_unload_settle", 5)))
    threshold = float(CONFIG["switching"].get("vram_free_mb", 2500))
    for _ in range(12):
        if await vram_free(threshold):
            break
        await asyncio.sleep(5)
    if not await vram_free(threshold):
        await cleanup_stray_cuda()
        await asyncio.sleep(5)
    if not await vram_free(threshold):
        set_state("ERROR", "GPU not freed for direct Comfy")
        return False
    set_state("GPU_FREE")
    set_state("MEDIA_STARTING")
    if not await systemctl("start", service):
        set_state("ERROR", "direct ComfyUI start failed")
        return False
    if not await wait_media_online(int(direct.get("startup_timeout", 600))):
        set_state("ERROR", "direct ComfyUI health timeout")
        await systemctl("stop", service)
        return False
    log.info("direct ComfyUI ready at %s", direct_public_url())
    set_state("DIRECT")
    return True


async def watch_direct() -> None:
    """Parked while the user drives Comfy directly: the manager touches
    nothing. Auto-return to AUTO only when the unit is really gone
    (closed/crashed, 3 consecutive misses): a live unit with a silent
    backend means Comfy is (re)starting, so wait — never pull the rug
    during a restart. The user closing it lands on inactive -> fast path."""
    try:
        code, out = await _run_async(
            ["systemctl", "show", "-p", "ActiveState", "--value",
             direct_service_name()], timeout=15)
        unit = out.strip() if code == 0 else "unknown"
    except Exception:  # noqa: BLE001 - never break the worker
        unit = "unknown"
    if unit in ("active", "activating", "reloading"):
        if await media_online():
            _state["direct_miss"] = 0
            return
        log.warning("direct Comfy unit %s but backend silent; waiting (restart?)", unit)
        return
    misses = int(_state.get("direct_miss") or 0) + 1
    _state["direct_miss"] = misses
    if misses >= 3:
        log.info("direct ComfyUI gone; returning to AUTO")
        _state["direct_miss"] = 0
        _set_desired("AUTO")


# --------------------------------------------------------------- comfyui ---

async def comfy_submit(workflow: dict) -> str | None:
    code, body = await _http_async(
        "POST", media_base() + "/prompt", {"prompt": workflow}, timeout=60
    )
    if code != 200:
        log.warning("ComfyUI submit rc=%s body=%s", code, body[-300:])
        return None
    try:
        return json.loads(body).get("prompt_id")
    except (json.JSONDecodeError, AttributeError):
        return None


async def comfy_history(prompt_id: str) -> dict | None:
    code, body = await _http_async("GET", media_base() + f"/history/{prompt_id}", timeout=30)
    if code != 200:
        return None
    try:
        data = json.loads(body)
        return data.get(prompt_id)
    except (json.JSONDecodeError, AttributeError):
        return None


async def run_media_job(job: dict) -> bool:
    """Execute one job on ComfyUI. Returns True on success."""
    jid = job["job_id"]
    kind = job["kind"]
    if (get_job(jid) or {}).get("status") not in ("queued", "running"):
        return False
    try:
        row = _db_conn().execute("SELECT workflow FROM jobs WHERE id=?", (jid,)).fetchone()
        workflow = json.loads(row[0]) if row else None
        if not isinstance(workflow, dict):
            raise ValueError("unreadable workflow")
    except Exception:  # noqa: BLE001
        update_job(jid, status="failed", error="unreadable workflow")
        return False
    timeout = int(CONFIG["media"]["job_timeout_video" if kind == "video" else "job_timeout_image"])
    prompt_id = await comfy_submit(workflow)
    if not prompt_id:
        update_job(jid, status="failed", error="ComfyUI submit refused")
        return False
    log.info("job %s prompt %s started", jid, prompt_id)
    _state["current_prompt_id"] = prompt_id
    update_job(jid, status="running", progress=0.05, phase="submitted")
    deadline = time.monotonic() + timeout
    poll = float(CONFIG["media"].get("poll_interval", 2.0))
    gone_timeout = float(CONFIG["switching"].get("media_gone_timeout", 120))
    vram_peak = 0.0
    seen_activity = False
    last_seen = time.monotonic()
    polls = 0
    while time.monotonic() < deadline:
        await asyncio.sleep(poll)
        polls += 1
        for g in await asyncio.to_thread(gpu_snapshot):
            vram_peak = max(vram_peak, float(g.get("memory_used_mb", 0)))
        if polls % 5 == 1:
            update_job(jid, progress=0.5, vram_peak_mb=vram_peak)
        if (get_job(jid) or {}).get("status") == "cancelled":
            await comfy_cancel(prompt_id)
            return False
        entry = await comfy_history(prompt_id)
        if not entry:
            phase = await comfy_queue_state(prompt_id)
            if phase in ("executing", "queued"):
                last_seen = time.monotonic()
                update_job(jid, phase=phase)
                continue
            # No history entry and prompt not live in ComfyUI (gone, evicted,
            # or unreachable backend): fail fast instead of sitting until the
            # hour timeout while the GPUs idle.
            if time.monotonic() - last_seen >= gone_timeout:
                log.warning("job %s lost by ComfyUI (phase=%s); failing", jid, phase or "unknown")
                update_job(jid, status="failed",
                           error="lost by ComfyUI: no history and prompt not queued",
                           vram_peak_mb=vram_peak)
                await comfy_cancel(prompt_id)
                return False
            continue
        last_seen = time.monotonic()
        if not seen_activity:
            seen_activity = True
            update_job(jid, phase="executing")
        status = (entry.get("status") or {})
        if status.get("completed") or entry.get("outputs"):
            return await finish_media_job(jid, prompt_id, entry, vram_peak)
        if status.get("status_str") == "error" or "error" in entry:
            if (get_job(jid) or {}).get("status") == "cancelled":
                return False
            update_job(jid, status="failed", error=str(entry.get("error") or status)[:500],
                       vram_peak_mb=vram_peak)
            return False
    if (get_job(jid) or {}).get("status") == "cancelled":
        await comfy_cancel(prompt_id)
        return False
    update_job(jid, status="failed", error="job timeout", vram_peak_mb=vram_peak)
    await comfy_cancel(prompt_id)
    return False


async def finish_media_job(jid: str, prompt_id: str, entry: dict, vram_peak: float = 0) -> bool:
    job = get_job(jid) or {}
    if job.get("status") == "cancelled":
        return False
    kind = job.get("kind", "image")
    update_job(jid, phase="saving")
    out_root = Path(str(CONFIG["media"]["output_dir"]))
    dest_root = Path(MEDIA_OUTPUT_DIR) / ("videos" if kind == "video" else "images") / jid
    dest_root.mkdir(parents=True, exist_ok=True)
    saved: list[str] = []
    for node_id, node_out in (entry.get("outputs") or {}).items():
        for item in node_out.get("images", []) + node_out.get("gifs", []):
            name = Path(str(item.get("filename") or "")).name
            sub = str(item.get("subfolder", "") or "")
            if not name or ".." in sub or sub.startswith(("/", "\\")):
                continue
            src = out_root / sub / name
            try:
                # Never follow symlinks or leave the ComfyUI output tree.
                if not src.is_file() or src.is_symlink():
                    continue
                src.resolve().relative_to(out_root.resolve())
            except (OSError, ValueError):
                continue
            if src and src.is_file():
                dest = dest_root / f"{node_id}_{name}"
                shutil.copy2(src, dest)
                saved.append(str(dest))
    if not saved:
        update_job(jid, status="failed", error="completed but no output files",
                   vram_peak_mb=vram_peak)
        return False
    derivatives = await make_web_derivatives(kind, saved, dest_root)
    meta = {
        "job_id": jid,
        "created_at": job.get("created_at"),
        "preset": job.get("preset", ""),
        "backend": job.get("backend", ""),
        "model": job.get("model", ""),
        "parameters": job.get("parameters", {}),
        "runtime_seconds": round(time.time() - (job.get("created_at") or time.time()), 1),
        "vram_peak_mb": vram_peak,
        "status": "done",
        "error": "",
        "output_paths": saved,
        "derivatives": derivatives,
    }
    (dest_root / "metadata.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    update_job(
        jid, status="done", progress=1.0, phase="done",
        result_paths=json.dumps(saved + derivatives), error="",
        vram_peak_mb=vram_peak,
    )
    log.info("job %s complete (%d files)", jid, len(saved))
    try:
        params = (get_job(jid) or {}).get("parameters") or {}
    except Exception:  # noqa: BLE001 - publish is best effort
        params = {}
    if isinstance(params, dict) and params.get("smart"):
        urls = await publish_smart_results(jid, saved + derivatives)
        if urls:
            params = dict(params)
            params["media_urls"] = urls
            db = _db_conn()
            db.execute("UPDATE jobs SET params=? WHERE id=?",
                       (json.dumps(params), jid))
            db.commit()
    return True


def _valid_output(path: Path) -> bool:
    try:
        return path.is_file() and path.stat().st_size > 256
    except OSError:
        return False


async def make_web_derivatives(kind: str, saved: list[str], dest_root: Path) -> list[str]:
    """Web-ready derivatives. Images: WebP + thumbnail (alpha preserved).
    Videos: H.264 MP4 + WebM + poster. Failures never fail the job."""
    out: list[str] = []
    for src in saved:
        suffix = Path(src).suffix.lower()
        stem = Path(src).stem
        try:
            if kind == "video":
                mp4 = dest_root / f"{stem}.h264.mp4"
                code, _ = await _run_async([
                    "ffmpeg", "-y", "-v", "error", "-i", src,
                    "-c:v", "libx264", "-preset", "medium", "-crf", "20",
                    "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                    "-an", str(mp4),
                ], timeout=600)
                if code == 0 and _valid_output(mp4):
                    out.append(str(mp4))
                else:
                    log.warning("mp4 derivative failed/empty for %s", src)
                webm = dest_root / f"{stem}.webm"
                code, _ = await _run_async([
                    "ffmpeg", "-y", "-v", "error", "-i", src,
                    "-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "30",
                    "-an", str(webm),
                ], timeout=600)
                if code == 0 and _valid_output(webm):
                    out.append(str(webm))
                else:
                    log.warning("webm derivative failed/empty for %s", src)
                poster = dest_root / f"{stem}_poster.jpg"
                code, _ = await _run_async([
                    "ffmpeg", "-y", "-v", "error", "-i", src,
                    "-vframes", "1", "-q:v", "4", str(poster),
                ], timeout=120)
                if code == 0 and _valid_output(poster):
                    out.append(str(poster))
            elif suffix in (".png", ".jpg", ".jpeg"):
                webp = dest_root / f"{stem}.webp"
                code, _ = await _run_async([
                    "ffmpeg", "-y", "-v", "error", "-i", src,
                    "-quality", "80", str(webp),
                ], timeout=300)
                if code == 0 and _valid_output(webp):
                    out.append(str(webp))
                thumb = dest_root / f"{stem}_thumb.jpg"
                code, _ = await _run_async([
                    "ffmpeg", "-y", "-v", "error", "-i", src,
                    "-vf", "scale=320:-1", "-q:v", "5", str(thumb),
                ], timeout=120)
                if code == 0 and _valid_output(thumb):
                    out.append(str(thumb))
        except Exception as exc:  # noqa: BLE001 - derivatives are best effort
            log.warning("derivative failed for %s: %s", src, exc)
    return out


async def comfy_cancel(prompt_id: str) -> None:
    await _http_async("POST", media_base() + "/interrupt", {}, timeout=15)
    await _http_async("POST", media_base() + "/queue", {"delete": [prompt_id]}, timeout=15)


async def comfy_clear_all() -> None:
    """Best-effort: ferma il prompt in esecuzione e svuota tutta la coda ComfyUI."""
    await _http_async("POST", media_base() + "/interrupt", {}, timeout=15)
    await _http_async("POST", media_base() + "/queue", {"clear": True}, timeout=15)


async def comfy_queue_state(prompt_id: str) -> str:
    """running|queued|gone based on the live ComfyUI queue."""
    code, body = await _http_async("GET", media_base() + "/queue", timeout=15)
    if code != 200:
        return ""
    try:
        data = json.loads(body)
    except (json.JSONDecodeError, ValueError):
        return ""
    blob = json.dumps(data.get("queue_running", [])) + json.dumps(data.get("queue_pending", []))
    if not prompt_id or f'"{prompt_id}"' not in blob:
        return "gone"
    return "executing" if f'"{prompt_id}"' in json.dumps(data.get("queue_running", [])) else "queued"


# ----------------------------------------------------------------- worker ---

async def comfy_busy() -> bool:
    """True when ComfyUI is executing or holding queued prompts.

    Unknown (probe failure) counts as busy: never kill job tracking on a
    failed probe.
    """
    code, body = await _http_async("GET", media_base() + "/queue", timeout=15)
    if code != 200:
        return True
    try:
        data = json.loads(body)
    except (json.JSONDecodeError, AttributeError):
        return True
    return bool(data.get("queue_running") or data.get("queue_pending"))


async def drive_auto_once() -> None:
    """One reconciliation step. Called in a loop; never raises."""
    now = time.monotonic()
    _state["last_drive_ts"] = now
    if now - float(_state.get("last_cleanup_ts") or 0.0) > 3600:
        _state["last_cleanup_ts"] = now
        await asyncio.to_thread(cleanup_old_artifacts)
    try:
        await _drive()
    except Exception as exc:  # noqa: BLE001 - worker must survive everything
        log.warning("worker step failed: %r", exc)
        await asyncio.sleep(5)


async def llm_watchdog() -> None:
    """Throttled health check while the LLM should stay resident."""
    now = time.monotonic()
    if now - float(_state.get("llm_watch_ts") or 0.0) >= 30:
        _state["llm_watch_ts"] = now
        if not await llm_loaded():
            log.warning("watchdog: LLM_READY but backend not loaded; restoring")
            await restore_llm_with_retries("watchdog")
        else:
            await ensure_voice_up()


async def _drive() -> None:
    async with _lock:
        state = _state["current_state"]
        desired = _state["desired_mode"]
        if state in ("LLM_UNLOADING", "GPU_FREE", "MEDIA_STARTING", "MEDIA_STOPPING", "LLM_LOADING"):
            # GPU_FREE is transient mid-transition, except after boot where it
            # persists: with manual MEDIA (or AUTO with queued work) move to
            # media instead of stalling here forever; otherwise head back
            # to LLM so AUTO-idle never wedges in GPU_FREE.
            if state == "GPU_FREE":
                if desired == "MEDIA" or (desired == "AUTO" and queued_jobs()):
                    if await transition_to_media():
                        state = _state["current_state"]
                        if state in ("MEDIA_READY", "MEDIA_BUSY"):
                            await drain_media_queue()
                elif desired == "DIRECT":
                    await transition_to_direct()
                elif desired == "LLM" or desired == "AUTO":
                    await restore_llm_with_retries("stray-gpu-free")
                return
            return  # transition already running elsewhere
        if state == "MEDIA_BUSY":
            # The tracking loop updates progress every poll; a job untouched
            # for 10+ min while ComfyUI sits idle means the tracker died
            # silently (seen live). Fail it instead of stalling forever.
            # The "saving" phase (ffmpeg derivatives) is exempt: no updates
            # happen there by design.
            job = get_job(_state["current_job"]) if _state["current_job"] else None
            if job is None:
                _state["current_job"] = None
                set_state("MEDIA_READY")
                return
            stale_s = time.time() - float(job.get("updated_at") or 0)
            if stale_s > 600 and job.get("phase") != "saving" and not await comfy_busy():
                log.warning("stale media job %s (%.0fs, backend idle); failing",
                            job["job_id"], stale_s)
                update_job(job["job_id"], status="failed",
                           error="worker tracking lost (stale, backend idle)")
                _state["current_job"] = None
                set_state("MEDIA_READY")
            return
        if state == "BOOT":
            await reconcile_boot()
            return
        if state == "ERROR":
            # Recover from any mode, throttled: a stuck ERROR used to need a
            # human restart. Prefer the desired path, fall back to LLM so the
            # chat survives a broken media stack.
            now = time.monotonic()
            if now - float(_state.get("error_retry_ts") or 0.0) < 120:
                return
            _state["error_retry_ts"] = now
            if desired == "LLM" or (desired == "AUTO" and not queued_jobs()):
                await restore_llm_with_retries("error-recovery")
            elif desired == "DIRECT":
                log.warning("error-recovery: retrying direct Comfy for desired=DIRECT")
                if not await transition_to_direct():
                    log.warning("error-recovery: direct failed, falling back to LLM")
                    await restore_llm_with_retries("error-direct-failed")
            else:
                log.warning("error-recovery: attempting media path for desired=%s", desired)
                if not await transition_to_media():
                    log.warning("error-recovery: media failed, falling back to LLM")
                    await restore_llm_with_retries("error-media-failed")
            return
        if desired == "DIRECT":
            if state != "DIRECT":
                await transition_to_direct()
            else:
                await watch_direct()
            return
        if desired == "LLM":
            if state != "LLM_READY":
                await restore_llm_with_retries("mode-llm")
            else:
                await llm_watchdog()
            return
        if desired == "MEDIA":
            if state == "LLM_READY":
                if not await transition_to_media():
                    await restore_llm_with_retries("media-entry-failed")
                    return
                state = _state["current_state"]
            if state in ("MEDIA_READY", "MEDIA_BUSY"):
                await drain_media_queue()
            return
        # AUTO: queued work pulls toward media, idleness back toward LLM.
        queue = queued_jobs()
        if queue:
            if state == "LLM_READY":
                if time.monotonic() < float(_state.get("media_cooldown_until", 0.0)):
                    # Media path cooling after repeated failures: keep chat up,
                    # jobs wait instead of flapping GPUs on every drive tick.
                    if time.monotonic() - float(_state.get("cooldown_log_ts", 0.0)) > 60.0:
                        _state["cooldown_log_ts"] = time.monotonic()
                        log.info("media cooldown active; %d job(s) waiting, LLM stays up", len(queue))
                    return
                if not await transition_to_media():
                    for job in queue:
                        update_job(job["job_id"], status="failed", error="media entry failed")
                    await restore_llm_with_retries("auto-entry-failed")
                    return
                state = _state["current_state"]
            if state in ("MEDIA_READY", "MEDIA_BUSY"):
                await drain_media_queue()
            return
        if state == "LLM_READY":
            await llm_watchdog()
        elif state in ("MEDIA_READY", "MEDIA_BUSY"):
            await drain_media_queue()
        elif state == "GPU_FREE":
            await restore_llm_with_retries("stray-gpu-free")
        else:
            # e.g. DIRECT after auto-return: stop any media backend, reload LLM.
            await restore_llm_with_retries("auto-return")


async def drain_media_queue() -> None:
    """Run queued media jobs, then idle handling. Caller holds _lock, state MEDIA_*."""
    queue = queued_jobs()
    if queue:
        job = queue[0]
        _state["current_job"] = job["job_id"]
        _state["active_preset"] = job.get("preset", "")
        _state["active_media_model"] = job.get("model", "") or job.get("backend", "")
        _state["media_idle_since"] = 0.0
        set_state("MEDIA_BUSY", job["job_id"])
        try:
            ok = await run_media_job(job)
        except Exception as exc:  # noqa: BLE001 - never leave a job stuck running
            log.warning("media job %s tracking died: %r", job["job_id"], exc)
            update_job(job["job_id"], status="failed", error=f"worker tracking lost: {exc!r}"[:300])
            ok = False
        _state["current_job"] = None
        _state["current_prompt_id"] = None
        if not ok and _state["current_state"] == "ERROR":
            await restore_llm_with_retries("media-job-failed")
            return
        if not ok:
            # Chat-first: repeated media failures mean the backend is broken
            # (OOM, crash loop). Failing jobs one by one while the LLM stays
            # down kills chat/voice for nothing: restore the LLM now and cool
            # the media path down instead of flapping on every job.
            _state["media_consec_failures"] = int(_state.get("media_consec_failures", 0)) + 1
            threshold = int(CONFIG["switching"].get("media_failure_threshold", 2))
            if _state["media_consec_failures"] >= max(1, threshold) and _state["desired_mode"] == "AUTO":
                cooldown = float(CONFIG["switching"].get("media_cooldown_seconds", 1200))
                _state["media_cooldown_until"] = time.monotonic() + max(60.0, cooldown)
                log.warning("media failed %sx in a row; restoring LLM and cooling media path for %.0fs",
                            _state["media_consec_failures"], max(60.0, cooldown))
                await restore_llm_with_retries("media-job-failed")
                return
        else:
            _state["media_consec_failures"] = 0
            _state["media_cooldown_until"] = 0.0
        set_state("MEDIA_READY")
        _state["media_idle_since"] = time.monotonic()
        return
    if _state["desired_mode"] != "AUTO":
        return  # manual MEDIA stays put until the user switches
    idle_timeout = float(CONFIG["switching"].get("media_idle_timeout", 30))
    if not _state["media_idle_since"]:
        _state["media_idle_since"] = time.monotonic()
    if time.monotonic() - _state["media_idle_since"] >= idle_timeout:
        log.info("media queue idle")
        _state["media_idle_since"] = 0.0
        await restore_llm_with_retries("media-idle")


async def reconcile_boot() -> None:
    log.info("boot reconcile: probing real hardware state")
    db = _db_conn()
    try:
        boot_now = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    except OSError:
        boot_now = ""
    prev_boot = db.execute("SELECT value FROM kv WHERE key='boot_id'").fetchone()
    # Solo un boot_id STORICO diverso prova un reboot vero. Senza riga
    # storica (primo avvio col nuovo codice) si preserva: a installazione
    # fresca il desired viene comunque dal default AUTO di config.
    fresh_boot = bool(boot_now) and bool(prev_boot) and prev_boot[0] != boot_now
    if boot_now:
        db.execute(
            "INSERT INTO kv(key,value) VALUES('boot_id',?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (boot_now,),
        )
        db.commit()
    if fresh_boot:
        # Real (re)boot: policy is AUTO + empty media queue, always.
        # A mere manager process restart keeps the desired mode instead,
        # so deploys during DIRECT don't nuke the user's session.
        log.info("boot: fresh boot_id, forcing AUTO")
        _state["desired_mode"] = "AUTO"
        _persist_desired()
        log.info("boot: desired forced to AUTO, cancelling stale jobs + ComfyUI queue")
        for (jid,) in db.execute(
            "SELECT id FROM jobs WHERE status IN ('running','queued')"
        ).fetchall():
            update_job(jid, status="cancelled",
                       error="cancelled by reboot, manager reset to AUTO")
            log.info("job %s cancelled after reboot", jid)
        try:
            await comfy_clear_all()
        except Exception as exc:
            log.warning("boot: ComfyUI queue clear failed (backend down?): %s", exc)
    else:
        log.info("boot: same boot_id, preserving desired=%s", _state["desired_mode"])
    llm_up = await llm_loaded()
    media_up = await media_online()
    threshold = float(CONFIG["switching"].get("vram_free_mb", 2500))
    free = await vram_free(threshold)
    desired = _state["desired_mode"]
    if desired == "DIRECT":
        # Process restart with a live direct backend: adopt it, no churn.
        if await service_active(direct_service_name()) and await media_online():
            set_state("DIRECT", "boot-adopt")
            return
        log.info("boot: DIRECT desired but backend gone, falling back to AUTO")
        desired = "AUTO"
        _state["desired_mode"] = desired
        _persist_desired()
    if desired == "MEDIA" and media_up:
        set_state("MEDIA_READY", "boot")
    elif desired == "MEDIA" and not media_up:
        # Manual MEDIA with ComfyUI down: park in GPU_FREE so the worker
        # boots the media path instead of pointlessly restoring the LLM.
        set_state("GPU_FREE", "boot")
    elif llm_up and free is False:
        set_state("LLM_READY", "boot")
    elif desired == "LLM" or not queued_jobs():
        if not llm_up:
            await restore_llm_with_retries("boot")
        else:
            set_state("LLM_READY", "boot")
    else:
        set_state("LLM_READY" if llm_up else "GPU_FREE", "boot")


async def worker_loop() -> None:
    await asyncio.sleep(2)
    while True:
        await drive_auto_once()
        await asyncio.sleep(2)


# ------------------------------------------------------- smart fast path ---
# POST /jobs/smart {text, input_images?}: triage (laya, fallback regole) ->
# se media, prompt generato con UNA sola chiamata LLM (niente turn agentico),
# submit interno e job_id. Altrimenti {"media": false} e il chiamante usa
# il flusso chat normale. Mai solleva oltre HTTPException intenzionali.

SMART_PROMPT_SYSTEM = (
    "You translate the user's image/video request into ONE English diffusion "
    "prompt. Output only the prompt, no quotes, no commentary, max 60 words. "
    "Preserve subject, style, composition, lighting and mood from the request."
)


def _smart_cfg() -> dict:
    cfg = CONFIG.get("smart", {})
    return cfg if isinstance(cfg, dict) else {}


def _smart_backend():
    if _triage_mod is None:
        return None
    cfg = _smart_cfg()
    if str(cfg.get("backend", "laya")).lower() == "rules":
        return _triage_mod.RulesBackend()
    return _triage_mod.LayaBackend(
        url=str(cfg.get("laya_url", "http://127.0.0.1:11435")),
        model=str(cfg.get("laya_model", "laya:multilingual")),
        min_confidence=float(cfg.get("min_confidence", 0.75)),
    )


async def prompt_only_llm(user_text: str) -> str | None:
    """One short LLM call, no tools. Returns the prompt or None."""
    cfg = _smart_cfg()
    code, body = await _http_async(
        "POST",
        llm_base() + "/v1/chat/completions",
        {
            "model": str(CONFIG["llm"].get("health_model") or "hermes-agent"),
            "messages": [
                {"role": "system", "content": SMART_PROMPT_SYSTEM},
                {"role": "user", "content": user_text[:2000]},
            ],
            "max_tokens": int(cfg.get("prompt_max_tokens", 250)),
            "temperature": 0.3,
            "stream": False,
        },
        timeout=180,
    )
    if code != 200:
        log.warning("smart: prompt-only LLM rc=%s", code)
        return None
    try:
        content = json.loads(body)["choices"][0]["message"]["content"]
        text = str(content or "").strip().strip('"')
        return text or None
    except (json.JSONDecodeError, KeyError, IndexError, TypeError, AttributeError):
        return None


def _submit_preset_job(kind_hint: str, preset: str, params: dict) -> tuple[dict | None, str, int]:
    """Shared preset submit used by /jobs/image, /jobs/video and /jobs/smart."""
    spec = PRESETS.get(preset)
    if not spec:
        return None, f"unknown preset {preset!r}", 400
    if spec.get("disabled"):
        return None, f"preset {preset} disabled: {spec['disabled']}", 409
    kind = spec.get("kind", kind_hint)
    tmp_id = "tmp"
    workflow, problem = render_preset(preset, params, tmp_id)
    if problem:
        return None, problem, 400
    job = create_job(kind, workflow, preset=preset,
                     backend=str(spec.get("backend", "")),
                     model=str(spec.get("model", "")), params=params)
    workflow, problem = render_preset(preset, params, job["job_id"])
    if problem:  # pragma: no cover - render already succeeded once
        update_job(job["job_id"], status="failed", error=problem)
        return None, problem, 400
    db = _db_conn()
    db.execute("UPDATE jobs SET workflow=? WHERE id=?", (json.dumps(workflow), job["job_id"]))
    db.commit()
    return get_job(job["job_id"]), "", 202


async def publish_smart_results(jid: str, paths: list[str]) -> list[str]:
    """Copy smart-job outputs where the hub serves /v1/media/* names."""
    cfg = _smart_cfg()
    root = Path(str(cfg.get("hub_uploads", "/home/matteo/.hermes/hub_uploads")))
    urls: list[str] = []
    try:
        root.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        log.warning("smart publish: hub_uploads not writable: %s", exc)
        return urls
    for src in paths:
        name = Path(str(src)).name
        if not name or ".." in name:
            continue
        dest = root / f"{jid}_{name}"
        try:
            if not Path(str(src)).is_file():
                continue
            shutil.copy2(src, dest)
            urls.append(f"/v1/media/{dest.name}")
        except OSError as exc:
            log.warning("smart publish: copy failed %s: %s", src, exc)
    return urls


# -------------------------------------------------------------------- api ---

app = FastAPI(title="Hermes GPU Manager", version="1.0.0",
              docs_url=None, redoc_url=None, openapi_url=None)

try:
    from display_bridge import router as _display_router
    app.include_router(_display_router)
    log.info("display bridge routes mounted (/display/*)")
except Exception as exc:
    log.warning("display bridge unavailable: %r", exc)


def require_key(request: Request) -> None:
    expected = str(CONFIG.get("api_key") or "")
    if not expected:
        return
    auth = request.headers.get("authorization", "")
    if not hmac.compare_digest(auth, f"Bearer {expected}"):
        raise HTTPException(401, "invalid manager api key")


@app.get("/")
async def root() -> dict:
    return {"service": APP_NAME, "status": "/status"}


@app.get("/status")
async def status(_: None = Depends(require_key)) -> dict:
    gpus = await asyncio.to_thread(gpu_snapshot)
    current = get_job(_state["current_job"]) if _state["current_job"] else None
    qwen_files = [
        Path(WORKFLOWS_DIR) / "qwen" / "qwen-image-2.1-8gb-t2i.json",
        Path(WORKFLOWS_DIR) / "qwen" / "qwen-image-2.1-8gb-edit.json",
        Path("/opt/hermes/runtimes/comfyui/app/models/diffusion_models/qwen_image_2.1-Q4_K.gguf"),
        Path("/opt/hermes/runtimes/comfyui/app/models/text_encoders/qwen3vl_8b_w4a8.safetensors"),
        Path("/opt/hermes/runtimes/comfyui/app/models/vae/qwen_image_2.1_vae_bf16.safetensors"),
    ]
    h3_files = [
        Path(WORKFLOWS_DIR) / "h3" / "i2v-turbo.json",
        Path(WORKFLOWS_DIR) / "h3" / "i2v.json",
        Path(WORKFLOWS_DIR) / "h3" / "first_last.json",
        Path("/opt/hermes/models/minimax-h3/minimax_h3_fl2va_pruned_int8_convrot.safetensors"),
        Path("/opt/hermes/models/minimax-h3/minimax_h3_ref2va_pruned_int8_convrot.safetensors"),
        Path(WORKFLOWS_DIR) / "h3" / "reference.json",
        Path("/opt/hermes/models/minimax-h3/qwen3vl_32b_minimax_h3_int8_convrot.safetensors"),
        Path("/opt/hermes/models/minimax-h3/minimax_h3_video_vae_fp16.safetensors"),
    ]
    h3_ready = all(p.is_file() for p in h3_files)
    desired = _state["desired_mode"]
    state = _state["current_state"]
    if state == "ERROR":
        hint = f"worker error: {(_state['last_error'] or 'unknown')[:160]}"
    elif desired == "AUTO" and state == "LLM_READY":
        hint = "idle with LLM resident: submit a job, the worker starts media automatically"
    elif desired == "MEDIA" and state == "LLM_READY":
        hint = "switching to media: submit a job or wait for the transition"
    elif desired == "DIRECT" and state == "DIRECT":
        hint = f"direct ComfyUI for you at {direct_public_url()} (manager parked, chat idle)"
    elif desired == "DIRECT":
        hint = f"switching to direct ComfyUI ({state}): wait, do not resubmit"
    elif state in ("MEDIA_READY", "MEDIA_BUSY"):
        hint = "media up: submit jobs directly"
    elif state in ("LLM_UNLOADING", "GPU_FREE", "MEDIA_STARTING", "MEDIA_STOPPING", "LLM_LOADING", "BOOT"):
        hint = f"transition in progress ({state}): wait, do not resubmit"
    else:
        hint = "submit a job; the worker drives the GPUs"
    cooldown_left = max(0.0, float(_state.get("media_cooldown_until", 0.0)) - time.monotonic())
    if cooldown_left > 0 and desired == "AUTO":
        hint = f"media cooling down ({cooldown_left:.0f}s left, chat stays up): jobs wait"
    return {
        "desired_mode": desired,
        "current_state": state,
        "hint": hint,
        "media_consec_failures": int(_state.get("media_consec_failures", 0)),
        "media_cooldown_s": round(cooldown_left, 1),
        "worker_alive_s": round(time.monotonic() - float(_state.get("last_drive_ts") or 0.0), 1),
        "llm_online": await llm_online(),
        "llm_loaded": await llm_loaded(),
        "media_online": await media_online(),
        "queue_length": len(queued_jobs()),
        "current_job": _state["current_job"],
        "voice_services": (
            {svc: await service_active(svc) for svc in voice_services()} |
            {svc: await service_active_user(svc) for svc in voice_user_services()}
        ),
        "direct_url": direct_public_url(),
        "direct_active": desired == "DIRECT" and state == "DIRECT",
        "media_progress": (current or {}).get("progress", 0.0),
        "active_preset": _state["active_preset"],
        "active_media_model": _state["active_media_model"],
        "qwen_image_installed": all(p.is_file() for p in qwen_files),
        "qwen_image_ready": all(p.is_file() for p in qwen_files),
        "h3_installed": h3_ready,
        "h3_ready": h3_ready,
        "h3_license_state": "OVERRIDDEN_LOCAL_TEST",
        "presets": sorted(PRESETS),
        "last_error": _state["last_error"] if _state["current_state"] == "ERROR" else "",
        "last_transition": _state["last_transition"],
        "gpu": gpus,
    }


def _set_desired(mode: str) -> dict:
    if _state["desired_mode"] == mode:
        return {"desired_mode": mode, "unchanged": True}
    _state["desired_mode"] = mode
    _state["last_error"] = ""
    _state["media_consec_failures"] = 0
    _state["media_cooldown_until"] = 0.0
    _persist_desired()
    log.info("desired mode -> %s (manual)", mode)
    return {"desired_mode": mode}


@app.post("/mode/llm")
async def mode_llm(_: None = Depends(require_key)) -> dict:
    return _set_desired("LLM")


@app.post("/mode/media")
async def mode_media(_: None = Depends(require_key)) -> dict:
    return _set_desired("MEDIA")


@app.post("/mode/auto")
async def mode_auto(_: None = Depends(require_key)) -> dict:
    return _set_desired("AUTO")


@app.post("/mode/direct")
async def mode_direct(_: None = Depends(require_key)) -> dict:
    return _set_desired("DIRECT")


@app.post("/system/reboot")
async def system_reboot(_: None = Depends(require_key)) -> dict:
    """Reboot the whole server. Fire-and-forget: the response is returned
    before the reboot is issued so the caller sees the acknowledgement."""
    log.warning("remote full-server reboot requested via API")
    try:
        subprocess.Popen(
            ["sh", "-c", "sleep 2; exec sudo -n /usr/sbin/shutdown -r now 'Hermes Hub remote reboot'"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
    except Exception as exc:  # noqa: BLE001
        log.warning("reboot spawn failed: %r", exc)
        raise HTTPException(500, "reboot spawn failed")
    return {"rebooting": True}


@app.post("/jobs/image")
@app.post("/jobs/video")
async def submit_job(request: Request, _: None = Depends(require_key)) -> JSONResponse:
    try:
        body = await request.json()
    except Exception:  # noqa: BLE001
        raise HTTPException(400, "invalid JSON")
    kind = "video" if request.url.path.endswith("/video") else "image"
    max_queued = int(CONFIG["media"].get("max_queued", 10))
    backlog = len(queued_jobs()) + (1 if _state.get("current_job") else 0)
    if backlog >= max_queued:
        raise HTTPException(429, f"media queue full ({backlog}/{max_queued})")
    preset = str(body.get("preset", "") or "")
    params = body.get("parameters", {}) or {}
    for alias in ("prompt", "input_images", "negative_prompt", "seed", "steps",
                  "resolution", "duration", "aspect_ratio", "quality", "denoise"):
        if alias in body and alias not in params:
            params[alias] = body[alias]
    if preset:
        job, problem, code = _submit_preset_job(kind, preset, params)
        if problem:
            raise HTTPException(code, problem)
        assert job is not None
    else:
        workflow = body.get("workflow", body.get("prompt", body))
        if isinstance(workflow, dict) and "prompt" in workflow and isinstance(workflow["prompt"], dict):
            workflow = workflow["prompt"]
        problem = validate_workflow(workflow if isinstance(workflow, dict) else {})
        if problem:
            raise HTTPException(400, problem)
        job = create_job(kind, workflow, params=params)
    log.info("AUTO: media job received %s", job["job_id"])
    return JSONResponse({"job_id": job["job_id"], "status": "queued",
                         "preset": job.get("preset", "")}, status_code=202)


@app.post("/jobs/smart")
async def submit_smart(request: Request, _: None = Depends(require_key)) -> JSONResponse:
    """Triage fast path: {text, input_images?} -> triage -> prompt-only LLM
    -> internal submit. Returns {"media": false} when this is not a media
    task (caller uses the normal chat flow), 409 when the LLM is unavailable
    (caller falls back to chat), 202 with job_id on accept."""
    try:
        body = await request.json()
    except Exception:  # noqa: BLE001
        raise HTTPException(400, "invalid JSON")
    cfg = _smart_cfg()
    if not cfg.get("enabled", True):
        raise HTTPException(409, "smart fast path disabled")
    if _triage_mod is None:
        raise HTTPException(500, "triage module unavailable")
    text = str(body.get("text", "") or "")
    images = body.get("input_images", []) or []
    if isinstance(images, str):
        images = [images]
    images = [str(p) for p in images[:4]]
    backend = _smart_backend()
    decision = _triage_mod.triage(text, bool(images), backend=backend)
    if not decision.media or not decision.preset:
        return JSONResponse({"media": False, "reason": decision.reason})
    if not await llm_loaded():
        return JSONResponse({"media": False, "reason": "llm-unloaded",
                             "fallback": True})
    prompt = await prompt_only_llm(decision.prompt_hint or text)
    if not prompt:
        raise HTTPException(502, "prompt-only LLM call failed")
    spec = PRESETS.get(decision.preset) or {}
    job, problem, code = _submit_preset_job(
        spec.get("kind", "image"), decision.preset,
        {"prompt": prompt, "input_images": images,
         "smart": True, "smart_text": text[:500],
         "smart_reason": decision.reason})
    if problem or job is None:
        raise HTTPException(code, problem or "submit failed")
    log.info("SMART: %s preset=%s prompt=%.60s",
             job["job_id"], decision.preset, prompt)
    return JSONResponse({"media": True, "job_id": job["job_id"],
                         "status": "queued", "preset": decision.preset,
                         "prompt": prompt}, status_code=202)


@app.get("/jobs")
async def list_jobs(request: Request, _: None = Depends(require_key)) -> dict:
    try:
        limit = min(500, max(1, int(request.query_params.get("limit", "100"))))
    except (TypeError, ValueError):
        limit = 100
    db = _db_conn()
    total = db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0]
    rows = db.execute("SELECT * FROM jobs ORDER BY created DESC LIMIT ?", (limit,)).fetchall()
    return {"jobs": [job_row(r) for r in rows], "total": total, "limit": limit}


@app.get("/jobs/{job_id}")
async def get_job_api(job_id: str, _: None = Depends(require_key)) -> dict:
    job = get_job(job_id)
    if not job:
        raise HTTPException(404, "unknown job")
    return job


@app.post("/jobs/{job_id}/cancel")
async def cancel_job(job_id: str, _: None = Depends(require_key)) -> dict:
    job = get_job(job_id)
    if not job:
        raise HTTPException(404, "unknown job")
    if job["status"] in ("done", "failed", "cancelled"):
        return job
    update_job(job_id, status="cancelled", error="cancelled by user")
    # If this is the running job, stop it in ComfyUI too and release tracking
    # so the worker does not keep polling a dead prompt.
    if job_id == _state.get("current_job"):
        _state["current_job"] = None
        prompt_id = _state.get("current_prompt_id")
        _state["current_prompt_id"] = None
        if prompt_id:
            await comfy_cancel(str(prompt_id))
    log.info("job %s cancelled", job_id)
    return get_job(job_id)


# Strong reference to the worker task. asyncio holds only weak refs to
# bare create_task() handles: without this the GC silently collects the
# worker mid-run (observed live: queue stalls, no logs, restart revives).
_worker_task: "asyncio.Task[None] | None" = None


@app.on_event("startup")
async def on_startup() -> None:
    global _worker_task
    _db_conn()
    cleanup_old_artifacts()
    _worker_task = asyncio.create_task(worker_loop())
    log.info("hermes-gpu-manager starting, desired=%s", _state["desired_mode"])


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "manager:app",
        host=str(CONFIG.get("host", "0.0.0.0")),
        port=int(CONFIG.get("port", 8643)),
        log_level="info",
    )
