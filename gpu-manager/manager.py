"""Hermes GPU Manager — CPU-only daemon to share 2x GPU between TabbyAPI/Qwen and ComfyUI.

Modes: LLM (Qwen online) | MEDIA (ComfyUI online) | AUTO (switch on demand).
LLM mode is the safe default: AUTO + idle == LLM_READY.
"""

from __future__ import annotations

import asyncio
import json
import logging
import shutil
import sqlite3
import subprocess
import time
import urllib.request
import uuid
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
    "ERROR",
)

MODES = ("LLM", "MEDIA", "AUTO")

_state = {
    "current_state": "BOOT",
    "desired_mode": str(CONFIG.get("default_mode", "auto")).upper(),
    "current_job": None,
    "last_error": "",
    "last_transition": "",
    "media_idle_since": 0.0,
    "retries": 0,
    "active_media_model": "",
    "active_preset": "",
    "media_progress": 0.0,
}
if _state["desired_mode"] not in MODES:
    _state["desired_mode"] = "AUTO"

_lock = asyncio.Lock()
_db: sqlite3.Connection | None = None


WORKFLOWS_DIR = "/opt/hermes/media-workflows"
MEDIA_OUTPUT_DIR = "/opt/hermes/media-output"

PRESETS = {
    "journey_image": {"kind": "image", "backend": "qwen-image-2.1", "model": "qwen_image_2.1_bf16", "file": "qwen/t2i.json"},
    "journey_edit": {"kind": "image", "backend": "qwen-image-2.1", "model": "qwen_image_2.1_bf16", "file": "qwen/edit.json"},
    "journey_rgba": {"kind": "image", "backend": "qwen-image-2.1", "model": "qwen_image_2.1_bf16", "file": "qwen/rgba.json"},
    "journey_product": {"kind": "image", "backend": "qwen-image-2.1", "model": "qwen_image_2.1_bf16", "file": "qwen/edit.json"},
    "journey_video_preview": {"kind": "video", "backend": "minimax-h3", "model": "minimax_h3_fl2va_pruned_int8+turbo4", "file": "h3/i2v-turbo.json"},
    "journey_video_quality": {"kind": "video", "backend": "minimax-h3", "model": "minimax_h3_fl2va_pruned_int8", "file": "h3/i2v.json"},
    "journey_video_first_last": {"kind": "video", "backend": "minimax-h3", "model": "minimax_h3_fl2va_pruned_int8", "file": "h3/first_last.json"},
    "journey_video_reference": {"kind": "video", "backend": "minimax-h3", "model": "h3", "file": "h3/reference.json", "disabled": "DISABLED_REFERENCE_WEIGHTS"},
}

IMAGE_EXTS = (".png", ".jpg", ".jpeg", ".webp")
MAX_INPUT_MB = 50


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
    mapping = {
        "JOB_ID": job_id,
        "PROMPT": str(params.get("prompt", "")),
        "NEGATIVE_PROMPT": str(params.get("negative_prompt", " ")),
        "SEED": str(params.get("seed", 7)),
        "STEPS": str(params.get("steps", 20)),
        "CFG": str(params.get("cfg", default_cfg)),
        "RESOLUTION": str(params.get("resolution", 1024)),
        "WIDTH": str(params.get("width", 1344)),
        "HEIGHT": str(params.get("height", 768)),
    }
    try:
        duration = float(params.get("duration", 0) or 0)
    except (TypeError, ValueError):
        duration = 0
    if duration > 0:
        grid_len = ((int(duration * 24) - 5 + 16) // 17) * 17 + 5
        mapping["LENGTH"] = str(max(22, min(3600, grid_len)))
    else:
        mapping["LENGTH"] = str(params.get("length", 124))
    for key, value in params.items():
        mapping[str(key).upper()] = str(value)
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
    for key, value in mapping.items():
        workflow_text = workflow_text.replace("{{" + key + "}}", value)
    if "{{" in workflow_text:
        return None, "unresolved template placeholders remain"
    try:
        workflow = json.loads(workflow_text)
    except (json.JSONDecodeError, ValueError) as exc:
        return None, f"rendered workflow is not valid JSON: {exc}"
    problem = validate_workflow(workflow)
    if problem:
        return None, problem
    return workflow, ""


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
        results = []
    try:
        parameters = json.loads(params) if params else {}
    except (json.JSONDecodeError, TypeError):
        parameters = {}
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


def update_job(jid: str, **fields) -> None:
    fields["updated"] = time.time()
    sets = ", ".join(f"{k}=?" for k in fields)
    db = _db_conn()
    db.execute(f"UPDATE jobs SET {sets} WHERE id=?", (*fields.values(), jid))
    db.commit()


def queued_jobs() -> list[dict]:
    db = _db_conn()
    rows = db.execute("SELECT * FROM jobs WHERE status='queued' ORDER BY created").fetchall()
    jobs = [job_row(r) for r in rows]
    # Model affinity: group compatible backends so one media model serves
    # the whole batch (single load/unload per backend switch).
    jobs.sort(key=lambda j: (j.get("backend") or "", j.get("created_at") or 0))
    return jobs


def validate_workflow(workflow: dict) -> str:
    if not isinstance(workflow, dict) or not workflow:
        return "workflow must be a non-empty node dict"
    for key, node in workflow.items():
        if not isinstance(node, dict) or "class_type" not in node or "inputs" not in node:
            return f"node {key!r} lacks class_type/inputs"
    return ""


# ------------------------------------------------------------- transitions ---

async def transition_to_media() -> bool:
    """LLM_READY -> MEDIA_READY. Returns True when ComfyUI serves."""
    cfg = CONFIG["switching"]
    set_state("LLM_UNLOADING")
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
    await asyncio.sleep(3)
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
    try:
        workflow = json.loads(get_job(jid) and _db_conn().execute("SELECT workflow FROM jobs WHERE id=?", (jid,)).fetchone()[0])
    except Exception:  # noqa: BLE001
        update_job(jid, status="failed", error="unreadable workflow")
        return False
    timeout = int(CONFIG["media"]["job_timeout_video" if kind == "video" else "job_timeout_image"])
    prompt_id = await comfy_submit(workflow)
    if not prompt_id:
        update_job(jid, status="failed", error="ComfyUI submit refused")
        return False
    log.info("job %s prompt %s started", jid, prompt_id)
    update_job(jid, status="running", progress=0.05, phase="submitted")
    deadline = time.monotonic() + timeout
    poll = float(CONFIG["media"].get("poll_interval", 2.0))
    vram_peak = 0.0
    seen_activity = False
    while time.monotonic() < deadline:
        await asyncio.sleep(poll)
        for g in await asyncio.to_thread(gpu_snapshot):
            vram_peak = max(vram_peak, float(g.get("memory_used_mb", 0)))
        update_job(jid, progress=0.5, vram_peak_mb=vram_peak)
        entry = await comfy_history(prompt_id)
        if not entry:
            phase = await comfy_queue_state(prompt_id)
            if phase in ("executing", "queued"):
                update_job(jid, phase=phase)
            continue
        if not seen_activity:
            seen_activity = True
            update_job(jid, phase="executing")
        status = (entry.get("status") or {})
        if status.get("completed") or entry.get("outputs"):
            return await finish_media_job(jid, prompt_id, entry, vram_peak)
        if status.get("status_str") == "error" or "error" in entry:
            update_job(jid, status="failed", error=str(entry.get("error") or status)[:500],
                       vram_peak_mb=vram_peak)
            return False
    update_job(jid, status="failed", error="job timeout", vram_peak_mb=vram_peak)
    await comfy_cancel(prompt_id)
    return False


async def finish_media_job(jid: str, prompt_id: str, entry: dict, vram_peak: float = 0) -> bool:
    job = get_job(jid) or {}
    kind = job.get("kind", "image")
    update_job(jid, phase="saving")
    out_root = Path(str(CONFIG["media"]["output_dir"]))
    dest_root = Path(MEDIA_OUTPUT_DIR) / ("videos" if kind == "video" else "images") / jid
    dest_root.mkdir(parents=True, exist_ok=True)
    saved: list[str] = []
    for node_id, node_out in (entry.get("outputs") or {}).items():
        for item in node_out.get("images", []) + node_out.get("gifs", []):
            name = item.get("filename")
            sub = item.get("subfolder", "")
            src = out_root / sub / name if name else None
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
    return True


def _valid_output(path: Path) -> bool:
    try:
        return path.is_file() and path.stat().st_size > 1024
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
    if prompt_id in blob:
        return "executing" if prompt_id in json.dumps(data.get("queue_running", [])) else "queued"
    return "gone"


# ----------------------------------------------------------------- worker ---

async def drive_auto_once() -> None:
    """One reconciliation step. Called in a loop; never raises."""
    try:
        await _drive()
    except Exception as exc:  # noqa: BLE001 - worker must survive everything
        log.warning("worker step failed: %r", exc)
        await asyncio.sleep(5)


async def _drive() -> None:
    async with _lock:
        state = _state["current_state"]
        desired = _state["desired_mode"]
        if state in ("LLM_UNLOADING", "GPU_FREE", "MEDIA_STARTING", "MEDIA_STOPPING", "LLM_LOADING"):
            return  # transition already running elsewhere
        if state == "BOOT":
            await reconcile_boot()
            return
        if state == "ERROR":
            if desired == "LLM" or (desired == "AUTO" and not queued_jobs()):
                await restore_llm_with_retries("error-recovery")
            return
        if desired == "LLM":
            if state != "LLM_READY":
                await restore_llm_with_retries("mode-llm")
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
        # AUTO
        queue = queued_jobs()
        if queue and state == "LLM_READY":
            if not await transition_to_media():
                for job in queue:
                    update_job(job["job_id"], status="failed", error="media entry failed")
                await restore_llm_with_retries("auto-entry-failed")
                return
            state = _state["current_state"]
        if state in ("MEDIA_READY", "MEDIA_BUSY"):
            await drain_media_queue()
        elif state == "GPU_FREE":
            await restore_llm_with_retries("stray-gpu-free")


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
        ok = await run_media_job(job)
        _state["current_job"] = None
        if not ok and _state["current_state"] == "ERROR":
            await restore_llm_with_retries("media-job-failed")
            return
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
    for (jid,) in db.execute("SELECT id FROM jobs WHERE status='running'").fetchall():
        update_job(jid, status="queued", error="interrupted by reboot, requeued")
        log.info("job %s requeued after reboot", jid)
    llm_up = await llm_loaded()
    media_up = await media_online()
    threshold = float(CONFIG["switching"].get("vram_free_mb", 2500))
    free = await vram_free(threshold)
    desired = _state["desired_mode"]
    if desired == "MEDIA" and media_up:
        set_state("MEDIA_READY", "boot")
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


# -------------------------------------------------------------------- api ---

app = FastAPI(title="Hermes GPU Manager", version="1.0.0")


def require_key(request: Request) -> None:
    expected = str(CONFIG.get("api_key") or "")
    if not expected:
        return
    auth = request.headers.get("authorization", "")
    if auth != f"Bearer {expected}":
        raise HTTPException(401, "invalid manager api key")


@app.get("/")
async def root() -> dict:
    return {"service": APP_NAME, "docs": "/docs", "status": "/status"}


@app.get("/status")
async def status(_: None = Depends(require_key)) -> dict:
    gpus = await asyncio.to_thread(gpu_snapshot)
    current = get_job(_state["current_job"]) if _state["current_job"] else None
    qwen_files = [
        Path(WORKFLOWS_DIR) / "qwen" / "t2i.json",
        Path("/opt/hermes/models/qwen-image-2.1/qwen_image_2.1_bf16.safetensors"),
        Path("/opt/hermes/models/qwen-image-2.1/qwen3vl_8b_int8_convrot.safetensors"),
        Path("/opt/hermes/models/qwen-image-2.1/qwen_image_2.1_vae_bf16.safetensors"),
    ]
    h3_files = [
        Path(WORKFLOWS_DIR) / "h3" / "i2v-turbo.json",
        Path(WORKFLOWS_DIR) / "h3" / "i2v.json",
        Path(WORKFLOWS_DIR) / "h3" / "first_last.json",
        Path("/opt/hermes/models/minimax-h3/minimax_h3_fl2va_pruned_int8_convrot.safetensors"),
        Path("/opt/hermes/models/minimax-h3/qwen3vl_32b_minimax_h3_int8_convrot.safetensors"),
        Path("/opt/hermes/models/minimax-h3/minimax_h3_video_vae_fp16.safetensors"),
    ]
    h3_ready = all(p.is_file() for p in h3_files)
    return {
        "desired_mode": _state["desired_mode"],
        "current_state": _state["current_state"],
        "llm_online": await llm_online(),
        "llm_loaded": await llm_loaded(),
        "media_online": await media_online(),
        "queue_length": len(queued_jobs()),
        "current_job": _state["current_job"],
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


@app.post("/mode/llm")
async def mode_llm(_: None = Depends(require_key)) -> dict:
    _state["desired_mode"] = "LLM"
    _state["last_error"] = ""
    _persist_desired()
    log.info("desired mode -> LLM (manual)")
    return {"desired_mode": "LLM"}


@app.post("/mode/media")
async def mode_media(_: None = Depends(require_key)) -> dict:
    _state["desired_mode"] = "MEDIA"
    _state["last_error"] = ""
    _persist_desired()
    log.info("desired mode -> MEDIA (manual)")
    return {"desired_mode": "MEDIA"}


@app.post("/mode/auto")
async def mode_auto(_: None = Depends(require_key)) -> dict:
    _state["desired_mode"] = "AUTO"
    _state["last_error"] = ""
    _persist_desired()
    log.info("desired mode -> AUTO (manual)")
    return {"desired_mode": "AUTO"}


@app.post("/jobs/image")
@app.post("/jobs/video")
async def submit_job(request: Request, _: None = Depends(require_key)) -> JSONResponse:
    try:
        body = await request.json()
    except Exception:  # noqa: BLE001
        raise HTTPException(400, "invalid JSON")
    kind = "video" if request.url.path.endswith("/video") else "image"
    preset = str(body.get("preset", "") or "")
    params = body.get("parameters", {}) or {}
    for alias in ("prompt", "input_images", "negative_prompt", "seed", "steps",
                  "resolution", "duration", "aspect_ratio", "quality"):
        if alias in body and alias not in params:
            params[alias] = body[alias]
    if preset:
        spec = PRESETS.get(preset)
        if not spec:
            raise HTTPException(400, f"unknown preset {preset!r}")
        if spec.get("disabled"):
            raise HTTPException(409, f"preset {preset} disabled: {spec['disabled']}")
        kind = spec.get("kind", kind)
        # placeholder job id is replaced after creation: render with temp id then fix
        tmp_id = "tmp"
        workflow, problem = render_preset(preset, params, tmp_id)
        if problem:
            raise HTTPException(400, problem)
        job = create_job(kind, workflow, preset=preset,
                         backend=str(spec.get("backend", "")),
                         model=str(spec.get("model", "")), params=params)
        workflow, problem = render_preset(preset, params, job["job_id"])
        if problem:  # pragma: no cover - render already succeeded once
            update_job(job["job_id"], status="failed", error=problem)
            raise HTTPException(400, problem)
        db = _db_conn()
        db.execute("UPDATE jobs SET workflow=? WHERE id=?", (json.dumps(workflow), job["job_id"]))
        db.commit()
        job = get_job(job["job_id"])
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


@app.get("/jobs")
async def list_jobs(_: None = Depends(require_key)) -> dict:
    db = _db_conn()
    rows = db.execute("SELECT * FROM jobs ORDER BY created DESC LIMIT 100").fetchall()
    return {"jobs": [job_row(r) for r in rows]}


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
    log.info("job %s cancelled", job_id)
    return get_job(job_id)


@app.on_event("startup")
async def on_startup() -> None:
    _db_conn()
    asyncio.create_task(worker_loop())
    log.info("hermes-gpu-manager starting, desired=%s", _state["desired_mode"])


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "manager:app",
        host=str(CONFIG.get("host", "0.0.0.0")),
        port=int(CONFIG.get("port", 8643)),
        log_level="info",
    )
