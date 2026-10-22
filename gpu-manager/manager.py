"""Hermes GPU Manager — CPU-only daemon to share 2x GPU between TabbyAPI/Qwen and ComfyUI.

Modes: LLM (Qwen online) | MEDIA (ComfyUI online) | AUTO (switch on demand).
LLM mode is the safe default: AUTO + idle == LLM_READY.
"""

from __future__ import annotations

import asyncio
import hmac
import io
import ipaddress
import json
import logging
import os
import re
import secrets
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
import wave

try:
    # Canonical source: scripts/hermes_hub_gateway/triage.py (kept byte-identical).
    import triage as _triage_mod
except ImportError:
    _triage_mod = None  # type: ignore[assignment]
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

try:
    # Sottosistema Character ID (pacchetto separato, rotte /characters/*).
    from character_id.api import register_character_routes as _register_character_routes
except ImportError:
    _register_character_routes = None  # type: ignore[assignment] - mai rompere il boot

try:
    # Supervisore Fase 0 (event store + vocabolario): solo stdlib, mai fastapi.
    import recovery
except Exception as _exc:  # noqa: BLE001 - un import rotto non deve fermare il boot
    recovery = None  # type: ignore[assignment]
    logging.warning("recovery package non disponibile: %r", _exc)

APP_NAME = "hermes-gpu-manager"

# ---------------------------------------------------------------- config ---

DEFAULT_CONFIG = {
    "host": "0.0.0.0",
    "port": 8643,
    "api_key": "",
    # Chiave app (letture + coda job + display + characters): l'agente non la
    # conosce mai. Laya usa api_key (controllo pieno). Mai chiavi condivise.
    "app_key": "",
    # False = /mode/media e /mode/direct rifiutati con 409 (i video vanno in
    # coda in AUTO, il manuale non serve). /mode/llm e /mode/auto restano liberi.
    "allow_manual_modes": False,
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
    # Supervisore di recupero (Fase 1). Le soglie sono replicate da
    # recovery.self_heal.DEFAULT_LIMITS in modo letterale (non via Name) cosi'
    # i test che estraggono DEFAULT_CONFIG col solo AST restano validi; un test
    # di test_recovery_self_heal verifica che le due copie restino identiche.
    "supervisor": {
        # Cooldown per-azione (secondi): la stessa azione non si ripete prima,
        # anche se la regola resta vera. Il manager esegue max 1 azione/tick.
        "cooldown_s": 300,
        "self_heal": {
            "enabled": True,
            "stale_job_s": 600.0,
            "media_down_s": 180.0,
            "error_burst_n": 3,
            "error_burst_window_s": 600.0,
            "max_job_retries": 2,
            "llm_transition_s": 180.0,
            "cooldown_media_s": 1200.0,
            "enable_media_rules": False,
            "enable_current_job_rule": True,
        },
        # Advisor laya in ombra (Fase 2): PROPONE un'azione, non la esegue mai.
        # Throttle separato dal self-heal perche' laya e' un modello locale
        # (ms/decine di ms per chiamata) e non va interrogato a ogni tick da 2s.
        "advisor": {
            "enabled": True,
            "url": "http://127.0.0.1:11435",
            "model": "laya:multilingual",
            "min_confidence": 0.7,
            "timeout": 15,
            "interval_s": 120,
        },
        # Operator laya (Fase 3): i suggerimenti dell'advisor diventano
        # ESEGUIBILI, ma solo se il nome e' in allowlist e i parametri si
        # risolvono dallo snapshot (laya non inventa job_id). Default OFF
        # (fail-closed): abilitare l'esecuzione guidata dal modello e' una
        # scelta ESPLICITA dell'installazione, da fare solo dopo aver osservato
        # i suggerimenti in ombra (Fase 2).
        "operator": {
            "enabled": False,
            "min_confidence": 0.8,
            "interval_s": 120,
        },
        # Canale NL (Fase 4): risponde in linguaggio naturale e PROPONE
        # un'azione. `allow_execute` default False (fail-closed): di default il
        # canale spiega e propone, non esegue; l'esecuzione richiede SIA
        # `confirm=true` nel body SIA questo flag esplicito. Il testo della
        # risposta lo compone il manager in modo deterministico, mai un modello.
        "channel": {
            "enabled": True,
            "allow_execute": False,
            "min_confidence": 0.7,
            "timeout": 15,
        },
    },
    "character_id": {
        # Root dati Character ID (baseline §11: /var/lib/hermes assente sul
        # server, si riusa la convenzione /opt/hermes esistente).
        "root": "/opt/hermes/character-id",
        # Interprete del tools-venv (numpy/opencv/insightface, CPU-only).
        "tools_python": "/opt/hermes/character-id/tools-venv/bin/python",
        # Interprete del trainer venv (torch cu128 + musubi, GPU).
        "trainer_python": "/opt/hermes/character-id/trainer/venv/bin/python",
        "workflows_dir": "/opt/hermes/media-workflows",
        # Wall-clock massimo di un training (ore): oltre, il worker cancella
        # il job e libera le GPU. Default generoso: non rompe i run esistenti.
        "max_wallclock_hours": 12,
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
    cred_manager = _credential_file("manager.key")
    if cred_manager:
        cfg["api_key"] = cred_manager
    cred_app = _credential_file("app.key")
    if cred_app:
        cfg["app_key"] = cred_app
    elif os.environ.get("HERMES_GPU_APP_KEY"):
        cfg["app_key"] = os.environ["HERMES_GPU_APP_KEY"]
    return cfg


def _credential_file(name: str) -> str:
    """Chiave da file root-only via systemd LoadCredential.

    Il manager gira come servizio di sistema: i file in $CREDENTIALS_DIRECTORY
    non sono leggibili dagli altri utenti (l'agente gira come utente diverso
    dai servizi o comunque senza accesso ai credential store). Ordine:
    credential file -> env (test/dev) -> configurato altrove.
    """
    base = os.environ.get("CREDENTIALS_DIRECTORY", "")
    if not base:
        return ""
    try:
        candidate = os.path.join(base, name)
        with open(candidate, "r", encoding="utf-8") as handle:
            value = handle.read().strip()
        return value
    except OSError:
        return ""


def _deep_merge(base: dict, override: dict) -> None:
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(base.get(key), dict):
            _deep_merge(base[key], value)
        else:
            base[key] = value


CONFIG = load_config()

# Event store del supervisore (Fase 0). Best-effort: se l'import o l'apertura
# falliscono il manager prosegue senza (le registrazioni diventano no-op).
EVENTS = None
if recovery is not None:
    try:
        EVENTS = recovery.EventStore(str(CONFIG["db_path"]))
    except Exception as _exc:  # noqa: BLE001 - mai fatale al boot
        EVENTS = None
        logging.warning("event store non disponibile: %r", _exc)


def _events():
    """Accessore all'event store del supervisore (None se non disponibile)."""
    return EVENTS


def _emit(kind: str, message: str, severity: str = "info", data: dict | None = None) -> None:
    """Registra un evento del supervisore. Effetto collaterale best-effort."""
    store = _events()
    if store is None:
        return
    try:
        store.record(kind, message, severity=severity, data=data)
    except Exception:  # noqa: BLE001 - mai fatale
        pass


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
    "state_since": 0.0,
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
    # Supervisore Fase 1: {nome_azione: monotonic dell'ultima esecuzione}.
    "supervisor_cooldown": {},
}
if _state["desired_mode"] not in MODES:
    _state["desired_mode"] = "AUTO"

_lock = asyncio.Lock()
# Lock dedicato al submit path: check+create backlog atomici. _lock non e
# adatto (tenuto a lungo da _drive durante le transizioni con sleep/IO).
_submit_lock = asyncio.Lock()
_db: sqlite3.Connection | None = None
# Lock globale per il db condiviso: la connessione e creata con
# check_same_thread=False ed e usata da route e worker su thread diversi
# (anche via asyncio.to_thread). Ogni uso del db condiviso avviene sotto
# `with _db_lock:` con blocchi brevi, mai oltre await (fail-closed: niente
# accessi concorrenti non protetti, niente deadlock da annidamento).
_db_lock = threading.Lock()


WORKFLOWS_DIR = "/opt/hermes/media-workflows"
MEDIA_OUTPUT_DIR = "/opt/hermes/media-output"

PRESETS = {
    "create_image": {"kind": "image", "backend": "qwen-image-2.1", "model": "qwen_image_2.1-Q4_K", "file": "qwen/qwen-image-2.1-8gb-t2i.json"},
    "edit_image": {"kind": "image", "backend": "qwen-image-2.1", "model": "qwen_image_2.1-Q4_K", "file": "qwen/qwen-image-2.1-8gb-edit.json", "needs_input": True},
    "journey_video_preview": {"kind": "video", "backend": "minimax-h3", "model": "minimax_h3_fl2va_pruned_int8+turbo4", "file": "h3/i2v-turbo.json"},
    "journey_video_quality": {"kind": "video", "backend": "minimax-h3", "model": "minimax_h3_fl2va_pruned_int8", "file": "h3/i2v.json", "disabled": "DISABLED_FULLSTEPS_OOM"},
    "journey_video_first_last": {"kind": "video", "backend": "minimax-h3", "model": "minimax_h3_fl2va_pruned_int8", "file": "h3/first_last.json", "disabled": "DISABLED_FULLSTEPS_OOM"},
    "journey_video_reference": {"kind": "video", "backend": "minimax-h3", "model": "minimax_h3_ref2va_pruned_int8", "file": "h3/reference.json", "disabled": "DISABLED_FULLSTEPS_OOM"},
    "journey_video_reference_fast": {"kind": "video", "backend": "minimax-h3", "model": "minimax_h3_ref2va_pruned_int8+turbo4+vsa", "file": "h3/ref2va-vsa.json", "needs_input": True, "area_cap_px": 1032192, "default_width": 1344, "default_height": 768},
    "journey_video_reframe916": {"kind": "video", "backend": "minimax-h3", "model": "minimax_h3_ref2va_pruned_int8", "file": "h3/ref2va-reframe-916.json", "needs_video": True, "area_cap_px": 1032192, "default_width": 768, "default_height": 1344},
}

IMAGE_EXTS = (".png", ".jpg", ".jpeg", ".webp")
VIDEO_EXTS = (".mp4", ".webm", ".mkv", ".mov", ".m4v")
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
    "LoadImage", "LoraLoader", "LoraLoaderModelOnly", "SaveAnimatedPNG",
    "LoadVideo", "GetVideoComponents", "SaveVideo",
    "VAEDecodeAudio", "CreateVideo",
    "KSamplerSelect", "BasicScheduler", "SamplerCustomAdvanced",
    "BasicGuider", "RandomNoise",
    "MiniMaxH3ImageToVideo", "MiniMaxH3ReferenceToVideo", "MiniMaxH3BandAVLatent",
    "H3MultiStream",
    "MiniMaxH3SigmaShift", "Ref2VAVSAGatePatch",
})

# Cap anti-OOM per i workflow raw/custom: stessa soglia dei preset video per
# length (max 3600). Per l'area raw: 768x1344 (reframe916 ref2va, multi-GPU
# H3 MultiStream) ammessa oltre 480p (480x854); oltre si rifiuta con errore
# onesto, mai riscalato in silenzio.
_WORKFLOW_MAX_AREA = max(480 * 854, 1032192)
_WORKFLOW_MAX_LENGTH = 3600

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
    # Anti-OOM: cap d'area su TUTTI i video (qualsiasi durata) a 480p max.
    # Anche i clip <=8s ad alta risoluzione (fino a 2048px) esauriscono i
    # 30GB di RAM e uccidono il server. I template sono già fissi a fps
    # 24.0; qui si scala la risoluzione mantenendo l'aspect. Vale per tutti
    # i video, anche se il chiamante chiede di più (fail-safe, non negoziabile).
    # Base default: 480p landscape 854x480 (area = cap, mai riscalato).
    if (PRESETS.get(preset) or {}).get("kind") == "video":
        try:
            _clamp_dur = float(params.get("duration", 0) or 0)
        except (TypeError, ValueError):
            _clamp_dur = 0
        try:
            _w0 = float(params.get("width", 854) or 854)
            _h0 = float(params.get("height", 480) or 480)
        except (TypeError, ValueError):
            _w0, _h0 = 854.0, 480.0
        # Area cap per-preset (anti-OOM fail-safe): 480x854 (~410k px)
        # per default; i preset con area_cap_px esplicito (es. journey_video_reframe916
        # @768x1344) lo alzano. Scala mantenendo l'aspect, min 64px per lato.
        _area_cap = float(spec.get("area_cap_px", 410400))
        if _w0 * _h0 > _area_cap:
            _scale = (_area_cap / (_w0 * _h0)) ** 0.5
            params["width"] = max(64, int(_w0 * _scale))
            params["height"] = max(64, int(_h0 * _scale))
            log.info("preset %s: clip %.0fs, risoluzione ridotta a %sx%s (anti-OOM area cap %d)",
                     preset, _clamp_dur, params["width"], params["height"], int(_area_cap))
        # MiniMax H3: il latente del VAE (16x) deve essere pari su H e W,
        # quindi i pixel devono essere multipli di 32 (non basta 16: 528 ->
        # latente 33, dispari, crash). Un valore non valido fa crashare il
        # run con RuntimeError shape e il job va 'lost by ComfyUI'.
        if (PRESETS.get(preset) or {}).get("kind") == "video":
            try:
                _w1 = int(float(params.get("width", 854) or 854))
                _h1 = int(float(params.get("height", 480) or 480))
            except (TypeError, ValueError):
                _w1, _h1 = 854, 480
            _w32 = max(64, _w1 // 32 * 32)
            _h32 = max(64, _h1 // 32 * 32)
            if _w32 != _w1 or _h32 != _h1:
                log.info("preset %s: risoluzione snappata a multipli di 32: %sx%s",
                         preset, _w32, _h32)
            params["width"], params["height"] = _w32, _h32
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
        "WIDTH": _num(params.get("width", 1024 if (PRESETS.get(preset) or {}).get("kind") == "image" else 854), 1024, 64, 2048),
        "HEIGHT": _num(params.get("height", 1024 if (PRESETS.get(preset) or {}).get("kind") == "image" else 480), 1024, 64, 2048),
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
        if upper == "PROMPT" and isinstance(value, str):
            # Il prompt e testo libero (utente o LLM): sequenze {{...}} al suo
            # interno non sono placeholder (verrebbero riscritte dai replace
            # successivi o causerebbero 400 spuri).
            value = value.replace("{{", "{ {").replace("}}", "} }")
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
        # Incoming files must live under trusted roots (hub uploads, runtimes,
        # outputs, tmp). No symlinks, no outside reads.
        try:
            resolved = src_path.resolve()
        except OSError:
            return None, f"input image unreadable: {src}"
        if src_path.is_symlink() or not any(
            str(resolved) == str(root) or str(resolved).startswith(str(root) + os.sep)
            for root in (
                Path("/home/matteo/.hermes"), Path("/opt/hermes"), Path("/tmp"))
        ):
            return None, f"input image outside trusted roots: {src}"
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
    # Video in input (ref2va ref): path assoluto, roots fidati, stage nello
    # stesso input dir, EXTS video.
    input_video = params.get("input_video") or (
        params.get("input_videos") or [None])[0]
    if input_video:
        src_path = Path(str(input_video))
        if not src_path.is_file():
            return None, f"input video missing: {input_video}"
        try:
            resolved = src_path.resolve()
        except OSError:
            return None, f"input video unreadable: {input_video}"
        if src_path.is_symlink() or not any(
            str(resolved) == str(root) or str(resolved).startswith(str(root) + os.sep)
            for root in (
                Path("/home/matteo/.hermes"), Path("/opt/hermes"), Path("/tmp"))
        ):
            return None, f"input video outside trusted roots: {input_video}"
        if src_path.suffix.lower() not in VIDEO_EXTS:
            return None, f"unsupported input video type: {input_video}"
        if src_path.stat().st_size > MAX_INPUT_MB * 1024 * 1024:
            return None, f"input video too large: {input_video}"
        dest_vname = f"{job_id}_refvideo{src_path.suffix.lower()}"
        dest_v = Path(str(CONFIG["media"].get("input_dir",
                           "/opt/hermes/runtimes/comfyui/app/input"))) / dest_vname
        dest_v.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src_path, dest_v)
        mapping["INPUT_VIDEO"] = dest_vname
    elif spec.get("needs_video"):
        return None, f"preset {preset} needs one input video in input_video"
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


# Ancora fail-closed per la cleanup: solo sotto /opt/hermes si cancella.
_CLEANUP_ANCHOR = Path("/opt/hermes")


def _is_under_anchor(candidate: Path, anchor: Path) -> bool:
    """True se candidate risolto e sotto anchor (puro, fail-closed)."""
    try:
        cand = Path(candidate).resolve()
        anch = Path(anchor).resolve()
        try:
            return cand.is_relative_to(anch)  # type: ignore[attr-defined]
        except AttributeError:
            try:
                cand.relative_to(anch)
                return True
            except ValueError:
                return False
    except Exception:  # noqa: BLE001 - dubbio = fuori anchor
        return False


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
    # Ancora fail-closed: canonicalizza e abortisci prima di cancellare.
    try:
        resolved = [Path(r).resolve() for r in roots]
    except Exception as exc:  # noqa: BLE001
        log.error("artifact cleanup rifiutato: resolve fallito: %r", exc)
        return 0
    for r in resolved:
        if not _is_under_anchor(r, _CLEANUP_ANCHOR):
            log.error("artifact cleanup rifiutato: root fuori anchor: %s", str(r))
            return 0
    for root in resolved:
        input_root = root == resolved[0]
        try:
            entries = list(root.iterdir())
        except OSError:
            continue
        for entry in entries:
            try:
                # Mai seguire symlink: skip immediato via lstat.
                if entry.is_symlink():
                    continue
                try:
                    mtime = entry.lstat().st_mtime
                except OSError as exc:
                    log.warning("artifact cleanup: skip %s: %s", entry, exc)
                    continue
                if mtime > cutoff:
                    continue
                if input_root and not (
                    entry.is_file()
                    and entry.suffix.lower() in IMAGE_EXTS
                    and "_in" in entry.stem
                ):
                    # Only our staged job inputs; never touch anything else.
                    continue
                if entry.is_dir():
                    # Ricontrolla symlink prima di rmtree (TOCTOU).
                    if entry.is_symlink():
                        continue
                    try:
                        # Mai ignore_errors=True: fallisci rumoroso per-entry.
                        shutil.rmtree(entry)
                    except OSError as exc:
                        log.warning("artifact cleanup: rmtree fallita %s: %s", entry, exc)
                        continue
                else:
                    if entry.is_symlink():
                        continue
                    try:
                        entry.unlink(missing_ok=True)
                    except OSError as exc:
                        log.warning("artifact cleanup: unlink fallita %s: %s", entry, exc)
                        continue
                removed += 1
            except OSError as exc:
                log.warning("artifact cleanup: skip %s: %s", entry, exc)
                continue
    if removed:
        log.info("artifact cleanup removed %d stale entries", removed)
    return removed


def _db_conn() -> sqlite3.Connection:
    global _db
    # Init sotto lock: la connessione e condivisa tra route e worker.
    # I chiamanti acquisiscono `with _db_lock:` SOLO dopo aver ottenuto la
    # connessione (mai annidato qui dentro: Lock non rientrante).
    with _db_lock:
        if _db is None:
            Path(str(CONFIG["db_path"])).parent.mkdir(parents=True, exist_ok=True)
            _db = sqlite3.connect(str(CONFIG["db_path"]), check_same_thread=False)
            _db.execute("PRAGMA journal_mode=WAL")
            _db.execute("PRAGMA busy_timeout=5000")
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
    assert _db is not None
    return _db


def _persist_desired() -> None:
    db = _db_conn()
    with _db_lock:
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
    # Istante monotono d'ingresso nello state: le regole del supervisore (R5/R6)
    # misurano da qui quanto una transizione e' bloccata.
    _state["state_since"] = time.monotonic()
    _state["last_transition"] = f"{old}->{new_state} {note}".strip()
    log.info("STATE %s", _state["last_transition"])
    # Ogni transizione e un evento; l'ingresso in ERROR genera anche un evento
    # dedicato di severita error (vocabolario del supervisore).
    _emit("state", f"{old}->{new_state}",
          severity=("error" if new_state == "ERROR" else "info"),
          data={"from": old, "to": new_state, "note": note})
    if new_state == "ERROR":
        # Fallback sempre-operativo (richiesta utente 0.6.278): qualunque
        # errore riporta la modalita' desiderata su AUTO, cosi' il recovery
        # comincia da LLM_READY (agente/chat sempre operativa) invece di
        # insistere su media/direct rotte: mai piantato per colpa del desired.
        if str(_state.get("desired_mode", "")).upper() != "AUTO":
            old_desired = _state.get("desired_mode")
            _state["desired_mode"] = "AUTO"
            _state["media_cooldown_until"] = 0.0
            try:
                _persist_desired()
            except Exception:  # noqa: BLE001 - persist opzionale, non blocca
                pass
            log.warning("ERROR fallback: desired %s -> AUTO (note=%s)", old_desired, note)
            _emit("error", "fallback: desired reset to AUTO",
                  severity="error", data={"old_desired": str(old_desired), "note": note})
        _emit("error", f"{old}->ERROR", severity="error",
              data={"from": old, "note": note})


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


# Units whose CUDA processes are service-owned backends: NEVER strays, even
# if their cmdline matches a runtime pattern. Checked via cgroup, so it also
# covers forked workers (ExLlamaV3 tensor-parallel ranks) and --user units.
_MANAGED_CUDA_UNITS = (
    "hermes-tabby.service",
    "hermes-comfyui.service",
    "hermes-comfyui-direct.service",
    "hermes-kokoro-tts.service",
    "uninote-stt.service",
    "hermes-laya.service",
    "hermes-hub.service",
)


def _cgroup_service_owned(cgroup: str) -> bool:
    """Pure matcher (unit-testable): True if the cgroup belongs to a managed unit."""
    low = cgroup.lower()
    if not low:
        return False
    return any(unit in low for unit in _MANAGED_CUDA_UNITS)


def _pid_service_owned(pid: int) -> bool:
    try:
        cgroup = Path(f"/proc/{pid}/cgroup").read_text(errors="replace")[:500]
    except Exception:  # noqa: BLE001 - racy /proc, fail-closed: skip kill
        return True
    return _cgroup_service_owned(cgroup)


# Unita' systemd proprietarie dei backend CUDA, per attribuire la VRAM al
# backend vero invece che al totale (un modello video in Comfy non e' "LLM").
_LLM_CUDA_UNITS = ("hermes-tabby.service",)
_MEDIA_CUDA_UNITS = ("hermes-comfyui.service", "hermes-comfyui-direct.service")
# Pattern cmdline (fallback quando il cgroup non risolve, es. processi figli
# fuori dalle unit). Ordine: prima LLM, poi media; lowercase entrambi i lati.
_LLM_CMD_PATTERNS = ("exllamav3", "tabby", "llama-server", "llama_cpp", "llamacpp")
_MEDIA_CMD_PATTERNS = ("comfyui", "comfyui-direct", "comfy_main", "cuda_malloc_async")
_MODEL_FILE_SUFFIXES = (".gguf", ".safetensors", ".onnx", ".pt", ".bin", ".pth")


def proc_backend_label(cmdline: str, cgroup: str) -> str:
    """Puro/testabile: backend proprietario di un processo CUDA.

    Ritorna "llm", "media", "other" o "unknown". Il cgroup (unit systemd)
    vince; in mancanza, i pattern sulla cmdline.
    """
    low_cg = str(cgroup or "").lower()
    if low_cg:
        for unit in _LLM_CUDA_UNITS:
            if unit in low_cg:
                return "llm"
        for unit in _MEDIA_CUDA_UNITS:
            if unit in low_cg:
                return "media"
    low_cmd = str(cmdline or "").lower()
    if low_cmd:
        for pattern in _LLM_CMD_PATTERNS:
            if pattern in low_cmd:
                return "llm"
        for pattern in _MEDIA_CMD_PATTERNS:
            if pattern in low_cmd:
                return "media"
        return "other"
    return "unknown"


def attribute_vram_mb(entries: list) -> dict:
    """Puro/testabile: somma la VRAM per backend da [(used_mb, backend)]."""
    totals = {"llm": 0.0, "media": 0.0, "other": 0.0, "unknown": 0.0}
    for entry in entries or []:
        try:
            used = float(entry[0])
            backend = str(entry[1])
        except (TypeError, ValueError, IndexError):
            continue
        if backend not in totals:
            backend = "unknown"
        totals[backend] += used
    return totals


def model_files_from_targets(targets: list) -> list:
    """Puro/testabile: basename dei file modello (pesi) da target /proc.

    Serve a dire COSA e' caricato (anche con load manuale in ComfyUI),
    non solo quanti GB. Deduplica preservando l'ordine, cap a 8.
    """
    seen: set = set()
    result: list = []
    for target in targets or []:
        name = str(target or "").split("/")[-1].strip()
        if not name or name in seen:
            continue
        lowered = name.lower()
        if not any(lowered.endswith(suffix) for suffix in _MODEL_FILE_SUFFIXES):
            continue
        seen.add(name)
        result.append(name)
        if len(result) >= 8:
            break
    return result


def llm_model_from_models(data: object) -> str:
    """Puro/testabile: id del modello da una risposta /v1/models OpenAI."""
    try:
        items = data.get("data") if isinstance(data, dict) else None  # type: ignore[union-attr]
        first = items[0] if isinstance(items, list) and items else None
        model_id = first.get("id") if isinstance(first, dict) else ""
        return str(model_id or "").strip()[:160]
    except (AttributeError, TypeError, IndexError):
        return ""


def proc_cgroup(pid: int) -> str:
    try:
        return Path(f"/proc/{pid}/cgroup").read_text(errors="replace")[:500]
    except Exception:  # noqa: BLE001 - racy /proc
        return ""


def cuda_backend_usage() -> dict:
    """VRAM per backend dai processi CUDA reali. Mai solleva."""
    totals = {"llm": 0.0, "media": 0.0, "other": 0.0, "unknown": 0.0}
    try:
        procs = gpu_compute_procs()
    except Exception:  # noqa: BLE001
        return totals
    for proc in procs[:32]:
        try:
            pid = int(proc.get("pid", -1))
            used = float(proc.get("used_mb", 0) or 0)
        except (TypeError, ValueError, AttributeError):
            continue
        if pid < 0:
            continue
        label = proc_backend_label(_proc_cmd(pid), proc_cgroup(pid))
        if label not in totals:
            label = "unknown"
        totals[label] += used
    return totals


def media_pids(limit: int = 4) -> list:
    """PID dei backend media (ComfyUI). Mai solleva."""
    found: list = []
    try:
        procs = gpu_compute_procs()
    except Exception:  # noqa: BLE001
        return found
    for proc in procs[:32]:
        try:
            pid = int(proc.get("pid", -1))
        except (TypeError, ValueError, AttributeError):
            continue
        if pid < 0 or len(found) >= max(1, limit):
            continue
        try:
            if proc_backend_label(_proc_cmd(pid), proc_cgroup(pid)) == "media":
                found.append(pid)
        except Exception:  # noqa: BLE001 - racy /proc
            continue
    return found


def _fd_targets(pid: int, cap: int = 200) -> list:
    targets: list = []
    try:
        names = sorted(os.listdir(f"/proc/{pid}/fd"))[: max(0, cap)]
    except Exception:  # noqa: BLE001 - processo morto / permessi
        return targets
    for name in names:
        try:
            targets.append(os.readlink(f"/proc/{pid}/fd/{name}"))
        except Exception:  # noqa: BLE001 - fd chiuso nel frattempo
            continue
    try:
        for name in sorted(os.listdir(f"/proc/{pid}/map_files"))[: max(0, cap)]:
            try:
                targets.append(os.readlink(f"/proc/{pid}/map_files/{name}"))
            except Exception:  # noqa: BLE001
                continue
    except Exception:  # noqa: BLE001 - map_files puo' mancare
        pass
    return targets


# --------------------------------------------------------------- backends ---

def llm_base() -> str:
    return str(CONFIG["llm"]["base_url"]).rstrip("/")


def media_base() -> str:
    return str(CONFIG["media"]["base_url"]).rstrip("/")


async def llm_online() -> bool:
    code, _ = await _http_async("GET", llm_base() + "/v1/models", timeout=10)
    return code == 200


def vram_total_meets(gpus: list, threshold_mb: float) -> bool:
    """Somma VRAM usata >= soglia (puro e testabile)."""
    try:
        return sum(float(g.get("memory_used_mb", 0) or 0) for g in gpus) >= float(threshold_mb)
    except (TypeError, ValueError, AttributeError):
        return False


async def llm_loaded() -> bool:
    """True only when Qwen weights are really resident (VRAM), not just listed.

    La VRAM e' attribuita ai processi del backend LLM (tabby/exllama per
    PID, non il totale GPU): un modello video caricato in Comfy non deve
    mai risultare "LLM su GPU". Lo split tensoriale varia (visto live
    6.6+10.8GB con modello attivo e servente), quindi la somma e' globale
    ma solo sui processi LLM.
    """
    if not await llm_online():
        return False
    threshold = float(CONFIG["switching"].get("llm_vram_min_mb", 8000))
    try:
        llm_mb = await asyncio.to_thread(
            lambda: float(cuda_backend_usage().get("llm", 0.0)))
    except Exception:  # noqa: BLE001
        return False
    return llm_mb >= threshold


async def llm_vram_mb() -> float:
    """VRAM attribuita ai processi del backend LLM. Mai solleva."""
    try:
        return float((await asyncio.to_thread(cuda_backend_usage)).get("llm", 0.0))
    except Exception:  # noqa: BLE001
        return 0.0


async def media_vram_mb() -> float:
    """VRAM attribuita ai processi ComfyUI (anche con load manuale). Mai solleva."""
    try:
        return float((await asyncio.to_thread(cuda_backend_usage)).get("media", 0.0))
    except Exception:  # noqa: BLE001
        return 0.0


async def llm_model() -> str:
    """Id del modello servito dal backend LLM (/v1/models). Mai solleva."""
    try:
        code, body = await _http_async("GET", llm_base() + "/v1/models", timeout=10)
    except Exception:  # noqa: BLE001
        return ""
    if code != 200 or not body:
        return ""
    try:
        data = json.loads(body)
    except (json.JSONDecodeError, TypeError, ValueError):
        return ""
    return llm_model_from_models(data)


async def media_loaded_models() -> list:
    """File modello mappati dai processi ComfyUI (fd + map_files).

    Funziona anche con load manuale da UI (il manager non ha sottomesso
    il job): dice COSA e' caricato davvero. Mai solleva.
    """
    try:
        pids = await asyncio.to_thread(media_pids)
    except Exception:  # noqa: BLE001
        return []
    targets: list = []
    for pid in pids[:4]:
        try:
            targets.extend(await asyncio.to_thread(_fd_targets, pid))
        except Exception:  # noqa: BLE001
            continue
        if len(targets) >= 400:
            break
    try:
        return model_files_from_targets(targets)
    except Exception:  # noqa: BLE001
        return []


# Cache della prova di risposta: una completion vera per ogni poll di stato
# sarebbe spreco e carico; 30s bastano a watchdog (tick 30s) e TopBar (15s).
_serving_probe = {"ok": False, "at": 0.0}
_serving_lock = asyncio.Lock()
SERVING_CACHE_TTL = 30.0
SERVING_PROBE_TIMEOUT = 45


async def _llm_serving_probe(timeout: int) -> bool:
    """Una vera completion da 1 token: 200 + choices[0].message.content non-blank."""
    try:
        code, body = await _http_async(
            "POST",
            llm_base() + "/v1/chat/completions",
            {
                "model": str(CONFIG["llm"].get("health_model") or "hermes-agent"),
                "messages": [{"role": "user", "content": "Reply with OK"}],
                "max_tokens": 2,
                "stream": False,
            },
            timeout=timeout,
        )
    except Exception:  # noqa: BLE001 - qualunque errore trasporto = non serve
        return False
    if code != 200:
        return False
    body_s = body or ""
    try:
        data = json.loads(body_s)
        choice0 = (data.get("choices") or [None])[0] or {}
        msg = choice0.get("message") or {} if isinstance(choice0, dict) else {}
        content = msg.get("content") if isinstance(msg, dict) else ""
        if isinstance(content, str) and content.strip():
            return True
        reasoning = msg.get("reasoning_content") if isinstance(msg, dict) else ""
        if isinstance(reasoning, str) and reasoning.strip():
            return True
        if isinstance(choice0, dict):
            rc2 = choice0.get("reasoning_content") or ""
            if isinstance(rc2, str) and rc2.strip():
                return True
        return False
    except (json.JSONDecodeError, ValueError, AttributeError, IndexError, TypeError):
        return len(body_s) > 20


async def llm_serving(timeout: int = SERVING_PROBE_TIMEOUT) -> bool:
    """True solo se tabby completa davvero (non basta VRAM piena + API su).

    Caso reale: tabby con pesi residenti ma modello sganciato rispondeva 503
    alle completion mentre llm_loaded() era True. Senza questo, watchdog e
    /status mentivano verde.
    """
    async with _serving_lock:
        now = time.monotonic()
        if now - _serving_probe["at"] < SERVING_CACHE_TTL:
            return _serving_probe["ok"]
    ok = await _llm_serving_probe(timeout)
    async with _serving_lock:
        _serving_probe["ok"] = ok
        _serving_probe["at"] = time.monotonic()
    return ok


async def media_online() -> bool:
    code, _ = await _http_async("GET", media_base() + "/system_stats", timeout=10)
    if code == 200:
        return True
    code, _ = await _http_async("GET", media_base() + "/prompt", timeout=10)
    return code in (200, 405)


def direct_queue_running(body: str) -> bool:
    """True se la coda Comfy ha un prompt in esecuzione (puro e testabile)."""
    try:
        data = json.loads(body)
    except (json.JSONDecodeError, TypeError, AttributeError):
        return False
    if not isinstance(data, dict):
        return False
    running = data.get("queue_running")
    return isinstance(running, list) and len(running) > 0


async def direct_queue_busy() -> bool:
    """Backend DIRECT occupato in generazione? Non solleva mai."""
    try:
        code, body = await _http_async("GET", media_base() + "/queue", timeout=10)
    except Exception:  # noqa: BLE001 - probe must never raise
        return False
    if code != 200:
        return False
    return direct_queue_running(body)


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
        _emit("llm", "unload accepted", data={"ok": True, "rc": code})
        return True
    # Idempotente: se non c'e' alcun modello caricato lo scopo e' gia'
    # raggiunto. Un 503 "No models are currently loaded." qui NON e' un
    # errore fatale: trattarlo come tale lasciava il manager in ERROR a
    # ogni transizione verso MEDIA (con l'LLM offline), perche' il
    # modello era gia' stato scaricato. Il gate VRAM subito dopo copre
    # comunque il caso in cui i pesi fossero ancora residenti.
    low = (body or "").lower()
    if code in (400, 404, 409, 503) and ("no model" in low or "not loaded" in low):
        log.info("TabbyAPI unload: nessun modello caricato (rc=%s), gia' a posto", code)
        _emit("llm", "unload noop (nessun modello)", data={"ok": True, "rc": code})
        return True
    log.warning("TabbyAPI unload rc=%s body=%s", code, body[-300:])
    _emit("llm", "unload failed", severity="warning", data={"ok": False, "rc": code})
    return False


async def vram_free(threshold_mb: float) -> bool:
    gpus = await asyncio.to_thread(gpu_snapshot)
    if not gpus:
        return False
    return all(g["memory_used_mb"] < threshold_mb for g in gpus)


# Nomi backend noti come avviati nel file/services: confronto esatto sul
# basename, mai substring (fail-closed: sconosciuto = mai kill).
_STRAY_BACKEND_BINS = frozenset({
    "tabbyapi", "tabbyapi.exe",
    "llama-mainline", "llama-mainline.exe",
    "exllamav3", "exllamav3.exe",
    "comfy", "comfy.exe",
    "ffmpeg", "ffmpeg.exe",
})
# Interpreti che lanciano i backend (ComfyUI: `python main.py`).
_STRAY_INTERPRETERS = frozenset({
    "python", "python.exe",
    "python3", "python3.exe",
    "python3.11", "python3.12",
    "uvicorn", "uvicorn.exe",
})
# Script backend noti quando l'eseguibile e un interprete.
_STRAY_BACKEND_SCRIPTS = frozenset({
    "main.py",
    "server.py",
    "tabbyapi",
    "comfy",
    "exllamav3",
})


def _is_stray_runtime_cmd(cmd: str) -> bool:
    """Pure matcher (unit-testable): True se la cmdline e di un runtime nostro.

    Solo basename esatto contro allowlist (mai substring): l'eseguibile
    diretto deve essere un bin backend noto, oppure un interprete
    python/uvicorn con script backend noto tra gli argomenti. Mai il
    manager stesso. Stringa vuota o sconosciuta = False (mai kill).
    """
    if not cmd:
        return False
    low = cmd.lower().strip()
    if not low:
        return False
    parts = low.split()
    if not parts:
        return False
    # Mai il manager stesso (es. `python manager.py` o `gpu-manager`).
    bases = [os.path.basename(p) for p in parts]
    if "gpu-manager" in bases or "manager.py" in bases:
        return False
    b0 = os.path.basename(parts[0])
    # Eseguibile backend diretto: confronto esatto.
    if b0 in _STRAY_BACKEND_BINS:
        return True
    # Interprete: stray solo con script backend noto tra gli argomenti.
    if b0 in _STRAY_INTERPRETERS:
        for tok in parts[1:]:
            if not tok or tok.startswith("-"):
                continue
            b = os.path.basename(tok).split(":")[0]
            if b in _STRAY_BACKEND_SCRIPTS:
                return True
        return False
    # Script backend lanciato diretto (es. `./main.py`).
    if b0 in _STRAY_BACKEND_SCRIPTS:
        return True
    return False


async def cleanup_stray_cuda() -> int:
    """Kill leftover CUDA processes from our runtimes only. Never touch others.

    Ownership guard (2026-10-05 incident): a previous version killed ANY
    process whose cmdline matched exllamav3/comfy, INCLUDING the live
    tabby backend mid-generation. During 100K+ token prefills the engine
    is busy for minutes, llm_loaded() goes False, and the watchdog fired
    restore -> kill -9 live tabby -> restart -> re-prefill from zero ->
    watchdog again: zero forward progress forever. Service-owned PIDs
    (unit cgroup, covers forked TP workers and --user units) are NEVER
    strays; only truly orphaned runtime processes are killed.
    """
    killed = 0
    skipped_owned = 0
    me = os.getpid()
    for proc in await asyncio.to_thread(gpu_compute_procs):
        pid = int(proc.get("pid", 0) or 0)
        # Mai PID 1 / se stesso (fail-closed).
        if not pid or pid == 1 or pid == me:
            continue
        cmd = _proc_cmd(pid)
        if not _is_stray_runtime_cmd(cmd):
            continue
        if _pid_service_owned(pid):
            skipped_owned += 1
            continue
        # TOCTOU: il pid puo essere riciclato tra check e kill. Rileggi la
        # cmdline subito prima del kill e procedi solo se matcha ancora.
        fresh = _proc_cmd(pid)
        # Ricontrolla PID protetti dopo il re-read (fail-closed).
        if pid == 1 or pid == os.getpid():
            continue
        if not _is_stray_runtime_cmd(fresh):
            log.info("stray cleanup pid=%d skipped: cmdline changed/gone (was %.60s)", pid, cmd[:60])
            continue
        code, _ = await _run_async(["sudo", "-n", "kill", "-9", str(pid)], timeout=15)
        if code == 0:
            killed += 1
            log.warning("killed stray CUDA pid=%d cmd=%s", pid, cmd[:120])
    if skipped_owned:
        log.info("stray cleanup skipped %d service-owned CUDA procs", skipped_owned)
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
    with _db_lock:
        db.execute(
            "INSERT INTO jobs(id,kind,status,workflow,progress,result_paths,error,retries,"
            "created,updated,preset,backend,model,params,vram_peak_mb)"
            " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (jid, kind, "queued", json.dumps(workflow), 0.0, "[]", "", 0, now, now,
             preset, backend, model, json.dumps(params or {}), 0),
        )
        db.commit()
    log.info("job %s %s preset=%s queued", jid, kind, preset or "-")
    _emit("job", f"job {jid} queued",
          data={"job_id": jid, "kind": kind, "preset": preset or ""})
    return get_job(jid)


def get_job(jid: str) -> dict | None:
    db = _db_conn()
    with _db_lock:
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
    with _db_lock:
        db.execute(f"UPDATE jobs SET {sets} WHERE id=?", (*fields.values(), jid))
        db.commit()
    # Transizioni terminali (fuori dal lock: get_job lo riacquisisce):
    # avviso best-effort al gateway per il ventaglio push. Mai fatale.
    try:
        if fields.get("status") in ("done", "failed"):
            key = (jid, fields["status"])
            if key not in _JOB_NOTIFY_SENT:
                _JOB_NOTIFY_SENT.add(key)
                if len(_JOB_NOTIFY_SENT) > 5000:
                    _JOB_NOTIFY_SENT.clear()
                notify_gateway_job(get_job(jid), str(fields["status"]), str(fields.get("error") or ""))
    except Exception:  # noqa: BLE001 - notifica opzionale, mai blocca il job
        pass


_JOB_NOTIFY_SENT: set = set()


def notify_gateway_job(job: dict | None, status: str, detail: str = "") -> None:
    """Avvisa il gateway a fine job (push FCM ai device). Best-effort."""
    try:
        if not isinstance(job, dict) or not job.get("job_id"):
            return
        base = (os.environ.get("HERMES_HUB_GATEWAY_URL") or "http://127.0.0.1:8642").rstrip("/")
        key = str((CONFIG.get("api_key") or "")).strip()
        if not key:
            return
        payload = {
            "job_id": job["job_id"],
            "status": status,
            "kind": job.get("kind", ""),
            "preset": job.get("preset", ""),
            "detail": detail[:200],
        }
        req = urllib.request.Request(
            base + "/v1/hub/jobs/notify",
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            resp.read(4096)
    except Exception:  # noqa: BLE001 - gateway giu' o rotta assente: il job resta valido
        pass


def queued_jobs() -> list[dict]:
    db = _db_conn()
    with _db_lock:
        rows = db.execute("SELECT * FROM jobs WHERE status='queued' ORDER BY created").fetchall()
    return [job_row(r) for r in rows]


# ------------------------------------------------- supervisore (Fase 1/2) ---
# Motore pure in recovery.self_heal (snapshot -> Action), eseguito fail-closed,
# piu' l'advisor laya di recovery.advisor (Fase 2) in sola lettura/ombra. Qui
# solo lo strato di I/O: costruzione snapshot, esecuzione delle Action e wiring
# nel worker. NON duplica _drive/watchdog: li complementa.

def _all_jobs(limit: int = 200) -> list[dict]:
    """Righe job piu' recenti (job_row-like), stesso lock del db condiviso."""
    try:
        limit = min(1000, max(1, int(limit)))
    except (TypeError, ValueError):
        limit = 200
    db = _db_conn()
    with _db_lock:
        rows = db.execute("SELECT * FROM jobs ORDER BY created DESC LIMIT ?", (limit,)).fetchall()
    return [job_row(r) for r in rows]


def _supervisor_limits() -> dict:
    """Override delle soglie da CONFIG["supervisor"]["self_heal"] (senza `enabled`)."""
    cfg = CONFIG.get("supervisor") if isinstance(CONFIG, dict) else None
    self_heal = cfg.get("self_heal") if isinstance(cfg, dict) else None
    if not isinstance(self_heal, dict):
        return {}
    return {k: v for k, v in self_heal.items() if k != "enabled"}


def _recent_events_for_supervisor(limit: int = 200) -> list[dict]:
    """Eventi recenti come dict piatti per lo snapshot. Best-effort: [] se assenti."""
    store = _events()
    if store is None:
        return []
    try:
        rows = store.recent(limit=limit)
    except Exception:  # noqa: BLE001 - lettura best-effort
        return []
    out: list[dict] = []
    for ev in rows:
        out.append({
            "ts": float(getattr(ev, "ts", 0.0) or 0.0),
            "kind": str(getattr(ev, "kind", "") or ""),
            "severity": str(getattr(ev, "severity", "") or ""),
            "message": str(getattr(ev, "message", "") or ""),
        })
    return out


async def _supervisor_probes(limits: dict, state: str, current_job) -> dict:
    """Esegue SOLO le probe costose richieste dallo state corrente.

    llm_loaded()/media_online() fanno I/O (HTTP/GPU): chiamarle a ogni tick da
    2s sarebbe spreco. Le regole le usano in pochi state, quindi si eseguono
    solo li'. Fail-closed: una probe non eseguita resta False; per R4/R6 False
    significa "backend giu'", la direzione conservativa.
    """
    probes = {"llm_loaded": False, "llm_serving": False, "media_online": False}
    media_rules = bool(limits.get("enable_media_rules", False))
    current_rule = bool(limits.get("enable_current_job_rule", True))
    need_llm_loaded = media_rules and state == "LLM_UNLOADING"
    need_media_online = (media_rules and state in ("MEDIA_STARTING", "MEDIA_READY", "MEDIA_BUSY")) or (
        current_rule and bool(current_job)
    )
    if need_llm_loaded:
        try:
            probes["llm_loaded"] = await llm_loaded()
        except Exception:  # noqa: BLE001 - probe best-effort
            probes["llm_loaded"] = False
    if need_media_online:
        try:
            probes["media_online"] = await media_online()
        except Exception:  # noqa: BLE001 - probe best-effort
            probes["media_online"] = False
    return probes


async def _supervisor_snapshot() -> dict:
    """Costruisce lo snapshot del contratto leggendo lo stato reale del manager."""
    state = str(_state.get("current_state", ""))
    limits = _supervisor_limits()
    probes = await _supervisor_probes(limits, state, _state.get("current_job"))
    return {
        "now": time.monotonic(),
        "wall_now": time.time(),
        "state": state,
        "state_since": float(_state.get("state_since", 0.0) or 0.0),
        "desired": str(_state.get("desired_mode", "")),
        "last_error": str(_state.get("last_error", "") or ""),
        "current_job": _state.get("current_job"),
        "current_prompt_id": _state.get("current_prompt_id"),
        "queue_len": len(queued_jobs()),
        "llm_loaded": probes["llm_loaded"],
        "llm_serving": probes["llm_serving"],
        "media_online": probes["media_online"],
        "media_consec_failures": int(_state.get("media_consec_failures", 0) or 0),
        "media_cooldown_until": float(_state.get("media_cooldown_until", 0.0) or 0.0),
        "jobs": _all_jobs(),
        "recent_events": _recent_events_for_supervisor(),
        "limits": limits,
    }


async def _supervisor_execute(action) -> str:
    """Mappa una `Action` sulle funzioni esistenti del manager. Ritorna lo stato."""
    name = getattr(action, "name", "")
    params = getattr(action, "params", {}) or {}
    if not isinstance(params, dict):
        params = {}
    if name == "restore_llm":
        ok = await restore_llm_with_retries("supervisor")
        return "restore_llm ok" if ok else "restore_llm failed"
    if name == "cooldown_media":
        try:
            seconds = max(60.0, float(params.get("seconds", 1200) or 1200))
        except (TypeError, ValueError):
            seconds = 1200.0
        until = time.monotonic() + seconds
        _state["media_cooldown_until"] = max(
            float(_state.get("media_cooldown_until", 0.0) or 0.0), until
        )
        return f"cooldown_media {seconds:.0f}s"
    if name == "cancel_stale_jobs":
        job_ids = params.get("job_ids") or []
        cause = str(params.get("cause") or "stale")
        if not isinstance(job_ids, list):
            job_ids = []
        done = 0
        for raw_jid in job_ids:
            jid = str(raw_jid)
            job = get_job(jid)
            if not job or job.get("status") != "running":
                continue
            if jid == _state.get("current_job"):
                prompt_id = _state.get("current_prompt_id")
                _state["current_job"] = None
                _state["current_prompt_id"] = None
                if prompt_id:
                    await comfy_cancel(str(prompt_id))
            update_job(jid, status="failed", error=f"supervisor: {cause}")
            done += 1
        return f"cancel_stale_jobs {done}"
    if name == "retry_job":
        jid = str(params.get("job_id") or "")
        job = get_job(jid) if jid else None
        if not job or job.get("status") != "failed" or jid == _state.get("current_job"):
            return "retry_job skipped"
        try:
            retries = int(job.get("retries") or 0)
        except (TypeError, ValueError):
            retries = 0
        update_job(jid, status="queued", retries=retries + 1, error="")
        return f"retry_job {jid} (retry {retries + 1})"
    if name == "notify_user":
        message = str(params.get("message") or "supervisore: attenzione richiesta")
        _emit("supervisor", message, severity="warning")
        log.warning("supervisor notify_user: %s", message)
        return "notify_user"
    return f"azione {name} sconosciuta"


# Cache del client advisor laya: {chiave config, istanza}. Ricostruito solo se i
# parametri rilevanti cambiano; None (mai eccezione) se `recovery` e' assente.
_ADVISOR_CACHE: dict = {"key": None, "client": None}


def _advisor_client():
    """Crea/cachea `recovery.LayaAdvisor` dai valori di config.

    Best-effort: ritorna None (mai solleva) se `recovery` e' assente, se la
    config e' incompleta o se la costruzione fallisce. Cosi il boot del manager
    non dipende MAI dall'advisor laya.
    """
    if recovery is None:
        return None
    advisor_cls = getattr(recovery, "LayaAdvisor", None)
    if advisor_cls is None:
        return None
    cfg = CONFIG.get("supervisor") if isinstance(CONFIG, dict) else None
    advisor_cfg = cfg.get("advisor") if isinstance(cfg, dict) else None
    if not isinstance(advisor_cfg, dict):
        return None
    url = str(advisor_cfg.get("url", "http://127.0.0.1:11435"))
    model = str(advisor_cfg.get("model", "laya:multilingual"))
    min_confidence = advisor_cfg.get("min_confidence", 0.7)
    timeout = advisor_cfg.get("timeout", 15)
    key = (url, model, min_confidence, timeout)
    if _ADVISOR_CACHE["key"] == key and _ADVISOR_CACHE["client"] is not None:
        return _ADVISOR_CACHE["client"]
    try:
        client = advisor_cls(
            url=url, model=model,
            min_confidence=float(min_confidence), timeout=int(timeout),
        )
    except Exception as exc:  # noqa: BLE001 - config rotta: nessun advisor
        log.warning("advisor client non costruibile: %r", exc)
        return None
    _ADVISOR_CACHE["key"] = key
    _ADVISOR_CACHE["client"] = client
    return client


async def _supervisor_advisor_shadow(snapshot: dict, rule_actions) -> None:
    """Advisor laya in ombra (Fase 2): propone e registra, NON esegue MAI.

    Gated da `supervisor.advisor.enabled` e throttled da `interval_s` (timestamp
    `advisor_last_ts`). Chiama `advise` (sola lettura) e registra un evento con
    l'azione scelta da laya, quelle proposte dalle regole e se concordano. NON
    esegue nessuna azione: nessuna GPU toccata, nessuna mutazione di stato oltre
    al timestamp di throttle. Mai solleva.
    """
    try:
        cfg = CONFIG.get("supervisor") if isinstance(CONFIG, dict) else None
        advisor_cfg = cfg.get("advisor") if isinstance(cfg, dict) else None
        if not isinstance(advisor_cfg, dict) or not bool(advisor_cfg.get("enabled", False)):
            return
        client = _advisor_client()
        if client is None:
            return
        try:
            interval = float(advisor_cfg.get("interval_s", 120) or 120)
        except (TypeError, ValueError):
            interval = 120.0
        now = time.monotonic()
        try:
            last = float(_state.get("advisor_last_ts", 0.0) or 0.0)
        except (TypeError, ValueError):
            last = 0.0
        if interval > 0 and now - last < interval:
            return
        _state["advisor_last_ts"] = now
        advice = await asyncio.to_thread(client.advise, snapshot)
        actions = getattr(advice, "actions", None) or []
        chosen = actions[0] if actions else None
        chosen_name = str(getattr(chosen, "name", "none") or "none") if chosen else "none"
        try:
            confidence = float(getattr(advice, "confidence", 0.0) or 0.0)
        except (TypeError, ValueError):
            confidence = 0.0
        rule_names = [str(getattr(a, "name", "")) for a in (rule_actions or [])]
        agrees = bool(chosen) and chosen_name in rule_names
        _emit(
            "supervisor",
            f"advisor laya: {chosen_name}@{confidence:.2f}",
            data={
                "advisor_action": chosen_name,
                "confidence": confidence,
                "backend": str(getattr(advice, "backend", "laya") or "laya"),
                "reasoning": str(getattr(advice, "reasoning", "") or ""),
                "rule_actions": rule_names,
                "agrees": agrees,
            },
        )
    except Exception as exc:  # noqa: BLE001 - shadow mai fatale
        log.warning("supervisor advisor shadow failed: %r", exc)


async def _supervisor_operator_step(snapshot: dict, rule_actions, acted_this_tick: bool) -> bool:
    """Operator laya (Fase 3): esegue UN'azione risolta dall'allowlist. OPT-IN.

    Gated da `supervisor.operator.enabled` (default False: fail-closed) e
    throttled da `interval_s` (timestamp `operator_last_ts`). Riusa il client
    dell'advisor e `_supervisor_execute`, con lo STESSO cooldown per-azione del
    self-heal: max 1 azione/tick GLOBALE. Se il self-heal ha gia' agito
    (`acted_this_tick` True) non fa nulla. Mai solleva: ogni errore degrada a
    False (nessuna azione eseguita).

    Ritorna True se ha eseguito un'azione, altrimenti False.
    """
    try:
        if recovery is None or acted_this_tick:
            return False
        cfg = CONFIG.get("supervisor") if isinstance(CONFIG, dict) else None
        operator_cfg = cfg.get("operator") if isinstance(cfg, dict) else None
        if not isinstance(operator_cfg, dict) or not bool(operator_cfg.get("enabled", False)):
            return False
        client = _advisor_client()
        if client is None:
            return False
        try:
            interval = float(operator_cfg.get("interval_s", 120) or 120)
        except (TypeError, ValueError):
            interval = 120.0
        now = time.monotonic()
        try:
            last = float(_state.get("operator_last_ts", 0.0) or 0.0)
        except (TypeError, ValueError):
            last = 0.0
        if interval > 0 and now - last < interval:
            return False
        _state["operator_last_ts"] = now

        decide_actions = getattr(recovery, "decide_actions", None)
        if decide_actions is None:
            return False
        advice = await asyncio.to_thread(client.advise, snapshot)
        try:
            min_confidence = float(operator_cfg.get("min_confidence", 0.8) or 0.8)
        except (TypeError, ValueError):
            min_confidence = 0.8
        actions = decide_actions(advice, snapshot, _supervisor_limits(),
                                 min_confidence=min_confidence)
        if not actions:
            # Bassa rumore: nessun bersaglio risolvibile in questo snapshot.
            _emit("supervisor", "operator laya: nessuna azione risolta",
                  severity="debug",
                  data={"rule_actions": [str(getattr(a, "name", "")) for a in (rule_actions or [])]})
            return False

        # Stesso cooldown per-azione del self-heal (max 1 azione/tick globale).
        try:
            cooldown_s = float(cfg.get("cooldown_s", 300) or 300)
        except (TypeError, ValueError):
            cooldown_s = 300.0
        cooldowns = _state.get("supervisor_cooldown")
        if not isinstance(cooldowns, dict):
            cooldowns = {}
            _state["supervisor_cooldown"] = cooldowns
        for action in actions:
            try:
                last_act = float(cooldowns.get(action.name, 0.0) or 0.0)
            except (TypeError, ValueError):
                last_act = 0.0
            if time.monotonic() - last_act < cooldown_s:
                continue
            data = {
                "name": action.name,
                "params": action.params,
                "reason": action.reason,
                "confidence": action.confidence,
                "source": action.source,
            }
            status = await _supervisor_execute(action)
            cooldowns[action.name] = time.monotonic()
            _emit("supervisor", f"operator laya: {action.name} eseguita: {status}",
                  severity="warning", data={**data, "status": status})
            return True
        return False
    except Exception as exc:  # noqa: BLE001 - l'operator non ferma mai il worker
        log.warning("supervisor operator step failed: %r", exc)
        return False


async def _supervisor_step() -> None:
    """Un tick del supervisore: snapshot UNA volta, self-heal + shadow + operator.

    Il self-heal (gated da `self_heal.enabled`) esegue al massimo 1 azione/tick
    in modo fail-closed; l'advisor laya (gated da `advisor.enabled`) resta in
    ombra e non esegue nulla; l'operator laya (gated da `operator.enabled`,
    OPT-IN) esegue UNA azione risolta dall'allowlist solo se il self-heal non ha
    gia' agito. Mai solleva, a prova anche di `recovery is None`.
    """
    try:
        if recovery is None:
            return
        cfg = CONFIG.get("supervisor") if isinstance(CONFIG, dict) else None
        self_heal = cfg.get("self_heal") if isinstance(cfg, dict) else None
        advisor_cfg = cfg.get("advisor") if isinstance(cfg, dict) else None
        operator_cfg = cfg.get("operator") if isinstance(cfg, dict) else None
        self_heal_enabled = (
            isinstance(self_heal, dict) and bool(self_heal.get("enabled", False))
        )
        advisor_enabled = (
            isinstance(advisor_cfg, dict) and bool(advisor_cfg.get("enabled", False))
        )
        operator_enabled = (
            isinstance(operator_cfg, dict) and bool(operator_cfg.get("enabled", False))
        )
        if not self_heal_enabled and not advisor_enabled and not operator_enabled:
            return
        evaluate = getattr(recovery, "evaluate", None)
        snapshot = await _supervisor_snapshot()
        rule_actions: list = []
        acted_this_tick = False
        if self_heal_enabled and evaluate is not None:
            rule_actions = evaluate(snapshot)
            if rule_actions:
                try:
                    cooldown_s = float(cfg.get("cooldown_s", 300) or 300)
                except (TypeError, ValueError):
                    cooldown_s = 300.0
                cooldowns = _state.get("supervisor_cooldown")
                if not isinstance(cooldowns, dict):
                    cooldowns = {}
                    _state["supervisor_cooldown"] = cooldowns
                now = time.monotonic()
                for action in rule_actions:
                    try:
                        last = float(cooldowns.get(action.name, 0.0) or 0.0)
                    except (TypeError, ValueError):
                        last = 0.0
                    if now - last < cooldown_s:
                        continue
                    data = {
                        "name": action.name,
                        "params": action.params,
                        "reason": action.reason,
                        "confidence": action.confidence,
                        "source": action.source,
                    }
                    _emit("supervisor", f"azione {action.name} proposta", data=data)
                    status = await _supervisor_execute(action)
                    cooldowns[action.name] = time.monotonic()
                    _emit("supervisor", f"azione {action.name} eseguita: {status}",
                          severity="warning", data={**data, "status": status})
                    acted_this_tick = True
                    break  # al massimo 1 azione per tick
        await _supervisor_advisor_shadow(snapshot, rule_actions)
        await _supervisor_operator_step(snapshot, rule_actions, acted_this_tick)
    except Exception as exc:  # noqa: BLE001 - il supervisore non ferma mai il worker
        log.warning("supervisor step failed: %r", exc)


def validate_workflow(workflow: dict) -> str:
    if not isinstance(workflow, dict) or not workflow:
        return "workflow must be a non-empty node dict"
    for key, node in workflow.items():
        if not isinstance(node, dict) or "class_type" not in node or "inputs" not in node:
            return f"node {key!r} lacks class_type/inputs"
        if node.get("class_type") not in ALLOWED_NODE_CLASSES:
            return f"node {key!r} class {node.get('class_type')!r} is not allowed"
    # Caps dei preset estese ai workflow raw: area oltre 480p e length oltre
    # il max dei job sono rifiutate con errore onesto (mai riscalate in
    # silenzio). Logica inline e pura: nessun helper esterno, resta testabile
    # via AST-exec come le altre funzioni pure.
    for key, node in workflow.items():
        inputs = node.get("inputs")
        if not isinstance(inputs, dict):
            continue
        width = inputs.get("width")
        height = inputs.get("height")
        try:
            area = float(width) * float(height)  # type: ignore[arg-type]
        except (TypeError, ValueError):
            area = 0.0
        if area != area:  # NaN
            area = 0.0
        if width is not None and height is not None and area > _WORKFLOW_MAX_AREA:
            return (f"node {key!r} {width}x{height} oltre area max 854x480: "
                    "riduci la risoluzione al preset")
        length = inputs.get("length")
        if length is not None:
            try:
                length_num = float(length)  # type: ignore[arg-type]
            except (TypeError, ValueError):
                length_num = 0.0
            if length_num == length_num and length_num > _WORKFLOW_MAX_LENGTH:
                return f"node {key!r} length {length} oltre max {_WORKFLOW_MAX_LENGTH}"
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
    _emit("media", "media ready", data={"backend": "comfyui"})
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


# Finestra (secondi) in cui un force "LLM"/"AUTO" scavalca i gate che possono
# impedire il restore (gate "tabby sta generando" e soglia watchdog): l'utente
# ha chiesto l'LLM, quindi lo si ricarica davvero. Coordinator col bridge/app.
_FORCE_LLM_WINDOW_S = 180.0


def _force_llm_active() -> bool:
    """True se e' in corso una finestra di force LLM (scavalca i gate di restore)."""
    try:
        return time.monotonic() < float(_state.get("force_llm_until") or 0.0)
    except (TypeError, ValueError):
        return False


async def restore_llm_with_retries(context: str) -> bool:
    _emit("llm", "restore attempt", data={"context": context})
    try:
        if not _force_llm_active() and await _tabby_recent_progress():
            log.info("restore %s skipped: tabby is generating (recent progress)", context)
            _state["llm_notloaded_fails"] = 0
            _emit("llm", "restore skipped (generating)", data={"context": context})
            return True
    except Exception:  # noqa: BLE001 - gate best effort, never block restore on error
        pass
    max_retries = int(CONFIG["recovery"].get("max_retries", 3))
    backoffs: list = CONFIG["recovery"].get("retry_backoff", [10, 30, 60])
    for attempt in range(max_retries + 1):
        if await transition_to_llm():
            _emit("llm", "restore ok", data={"context": context, "attempts": attempt + 1})
            return True
        _state["retries"] = attempt + 1
        if attempt >= max_retries:
            break
        wait = backoffs[attempt] if attempt < len(backoffs) else backoffs[-1]
        log.warning("restore %s failed (%s), retry %d/%d in %ss", context, _state["last_error"], attempt + 1, max_retries, wait)
        await asyncio.sleep(wait)
    set_state("ERROR", f"Qwen restore failed after {max_retries} retries ({context})")
    _emit("llm", "restore failed", severity="error",
          data={"context": context, "retries": max_retries})
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
    backend means Comfy is (re)starting or busy generating, so wait — never
    pull the rug during a restart or a long generation. The user closing
    it lands on inactive -> fast path. Probe errors ("unknown") never
    count as misses: only explicit inactive/failed/dead do."""
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
            _state.pop("direct_silent_since", None)
            return
        # Backend occupato (generazione in corso)? Vivo ma lento: mai
        # staccare. Solo un backend davvero silente fa partire il timer.
        if await direct_queue_busy():
            _state["direct_miss"] = 0
            _state.pop("direct_silent_since", None)
            log.info("direct Comfy busy (generation running): staying in DIRECT")
            return
        # Unit alive but backend silent: restart in progress (wait forever) or
        # hung Comfy (deadline below). Never pull the rug on a live unit fast.
        since = float(_state.get("direct_silent_since") or time.monotonic())
        if "direct_silent_since" not in _state:
            _state["direct_silent_since"] = since
        if time.monotonic() - since > 900:
            log.error("direct Comfy silent for 15min with live unit; forcing AUTO")
            _state.pop("direct_silent_since", None)
            _state["direct_miss"] = 0
            _set_desired("AUTO")
        else:
            log.warning("direct Comfy unit %s but backend silent; waiting (restart?)", unit)
        return
    if unit in ("inactive", "failed", "dead"):
        misses = int(_state.get("direct_miss") or 0) + 1
        _state["direct_miss"] = misses
        if misses >= 3:
            log.info("direct ComfyUI gone; returning to AUTO")
            _state["direct_miss"] = 0
            _set_desired("AUTO")
        return
    # Probe inconcludente (sudo/systemd hiccup): tieni la posizione,
    # mai contare un miss su "unknown".
    log.warning("direct Comfy unit state unknown; holding DIRECT (no miss counted)")


# MiniMax H3 patchify_video (patch 2x2) richiede che il latente del VAE (16x
# spaziale) sia pari su H e W: quindi width e height in PIXEL devono essere
# multipli di 32. Un valore multiplo di 16 ma non di 32 (es. 528) fa comunque
# crashare il run con RuntimeError in _embed_and_pack e il job finisce "lost
# by ComfyUI". Snap fail-safe verso il basso, come i cap anti-OOM dei preset.
_VIDEO_DIM_MULTIPLE = 32


def snap_video_dims(workflow: dict) -> list:
    """Snap width/height a multipli di 32 sui workflow MiniMax H3 (in place).

    Pure e testabile (nessun I/O, nessun log): ritorna la lista delle chiavi
    dei nodi modificati, cosi' il chiamante puo' loggare con contesto job.
    Tocca solo i workflow che contengono nodi H3 (i video): le immagini
    restano invariate. Valori non numerici (es. "{{WIDTH}}") sono ignorati.
    """
    changed: list = []
    if not isinstance(workflow, dict):
        return changed
    has_h3 = any(
        isinstance(n, dict) and str(n.get("class_type", "")).startswith("MiniMaxH3")
        for n in workflow.values()
    )
    if not has_h3:
        return changed
    for key, node in workflow.items():
        if not isinstance(node, dict):
            continue
        inputs = node.get("inputs")
        if not isinstance(inputs, dict):
            continue
        touched = False
        for axis in ("width", "height"):
            try:
                num = float(inputs.get(axis))  # type: ignore[arg-type]
            except (TypeError, ValueError):
                continue
            if num != num or num < _VIDEO_DIM_MULTIPLE:  # NaN o sotto soglia
                continue
            snapped = int(num) // _VIDEO_DIM_MULTIPLE * _VIDEO_DIM_MULTIPLE
            if snapped != int(num):
                inputs[axis] = snapped
                touched = True
        if touched:
            changed.append(key)
    return changed


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
        db = _db_conn()
        with _db_lock:
            row = db.execute("SELECT workflow FROM jobs WHERE id=?", (jid,)).fetchone()
        workflow = json.loads(row[0]) if row else None
        if not isinstance(workflow, dict):
            raise ValueError("unreadable workflow")
    except Exception:  # noqa: BLE001
        update_job(jid, status="failed", error="unreadable workflow")
        return False
    changed_dims = snap_video_dims(workflow)
    if changed_dims:
        log.warning("job %s: dimensioni H3 snappate a multipli di 32 (nodi %s)",
                    jid, ",".join(changed_dims))
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
        # node_id comes from workflow keys (caller-controlled): strict allowlist,
        # never a path.
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", str(node_id)):
            log.warning("job %s skipping output with suspicious node id %r", jid, node_id)
            continue
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
        _emit("job", f"job {jid} failed", severity="warning",
              data={"job_id": jid, "status": "failed", "reason": "no output files"})
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
    _emit("job", f"job {jid} done",
          data={"job_id": jid, "status": "done", "files": len(saved)})
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
            with _db_lock:
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
    # Wall-clock prima dell'hold: i training scaduti vanno cancellati ora,
    # cosi l'hold sotto vede lo stato post-cancel e libera le GPU.
    _enforce_training_wallclock()
    if _character_training_hold():
        # Il trainer possiede le GPU (lock+job attivi): il worker resta
        # parcheggiato, niente restore LLM/media che ruberebbero VRAM.
        await _reap_dead_character_jobs()
        if now - float(_state.get("chartrain_log_ts", 0.0)) > 600.0:
            _state["chartrain_log_ts"] = now
            log.info("character training owns the GPUs; worker parked")
        return
    if now - float(_state.get("last_cleanup_ts") or 0.0) > 3600:
        _state["last_cleanup_ts"] = now
        await asyncio.to_thread(cleanup_old_artifacts)
    # Supervisore di recupero (Fase 1): complementa _drive, mai lo sostituisce.
    # Prima di _drive cosi una sua azione viene riconciliata nello stesso tick;
    # dopo i gate training/hold sopra, cosi non tocca le GPU del trainer.
    try:
        await _supervisor_step()
    except Exception as exc:  # noqa: BLE001 - il supervisor non uccide il worker
        log.warning("supervisor step failed: %r", exc, exc_info=True)
    try:
        await _drive()
    except Exception as exc:  # noqa: BLE001 - worker must survive everything
        # Qualunque errore non gestito entra ERROR (fallback AUTO + LLM ready
        # dal recovery): il worker non muore mai e resta riconciliabile.
        log.warning("worker step failed: %r", exc, exc_info=True)
        try:
            set_state("ERROR", f"worker step failed: {exc!r}"[:160])
        except Exception:  # noqa: BLE001 - mai bloccare il loop del worker
            pass
        await asyncio.sleep(5)


_WATCHDOG_RESTORE_FAILS = 4
_WATCHDOG_TRANSITIONAL = ("LLM_UNLOADING", "LLM_LOADING", "GPU_FREE", "MEDIA_STARTING", "MEDIA_STOPPING")


async def llm_watchdog() -> None:
    """Throttled health check while the LLM should stay resident.

    Restore gate (2026-10-05 incident): a 100K+ token prefill blocks the
    engine for minutes, so /v1/models times out and llm_loaded() goes False
    on a HEALTHY backend. Restoring on the first failure killed the live
    tabby mid-generation in a loop. Now: skip while transitional, skip while
    tabby is actually generating, and     require 4 consecutive failures
    (2 min, longer than any legit restart) before restoring.
    """
    if _character_training_hold():
        return  # trainer attivo: mai restore LLM (guerra VRAM)
    now = time.monotonic()
    if now - float(_state.get("llm_watch_ts") or 0.0) >= 30:
        _state["llm_watch_ts"] = now
        if _state.get("current_state") in _WATCHDOG_TRANSITIONAL:
            _state["llm_notloaded_fails"] = 0
            return
        loaded = await llm_loaded()
        # VRAM piena non basta: tabby sganciato risponde 503 ma risulta loaded.
        # La prova di risposta decide; generazioni vere restano protette dal
        # gate progress qui sotto (mai restore mentre genera).
        serving = await llm_serving(timeout=20) if loaded else False
        if not (loaded and serving):
            if _force_llm_active():
                # Force esplicito in corso: ricarica ORA, senza attendere la
                # soglia a 4 fallimenti ne' il gate "tabby sta generando".
                _state["llm_notloaded_fails"] = 0
                log.warning("watchdog: force window, restoring LLM now")
                await restore_llm_with_retries("force")
                return
            fails = int(_state.get("llm_notloaded_fails") or 0) + 1
            _state["llm_notloaded_fails"] = fails
            if await _tabby_recent_progress():
                _state["llm_notloaded_fails"] = 0
                log.info("watchdog: backend check failed but tabby is generating (fail %d); skipping restore", fails)
                return
            if fails < _WATCHDOG_RESTORE_FAILS:
                log.warning("watchdog: backend check failed %d/%d, waiting before restore", fails, _WATCHDOG_RESTORE_FAILS)
                return
            _state["llm_notloaded_fails"] = 0
            log.warning("watchdog: LLM_READY but backend not loaded; restoring")
            await restore_llm_with_retries("watchdog")
        else:
            _state["llm_notloaded_fails"] = 0
            await ensure_voice_up()


async def _tabby_recent_progress(seconds: int = 180) -> bool:
    """True if tabby logged generation progress recently (busy-healthy).

    Cheap journalctl grep; needs journal read rights, else False (fail-closed
    toward the old restore behavior, still gated by consecutive failures).
    Also True when VRAM is actively used (>8000MB and util>0): a long
    prefill holds VRAM without fresh journal lines.
    """
    since = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(time.time() - seconds))
    code, out = await _run_async(
        ["journalctl", "-u", "hermes-tabby.service", "--since", since,
         "--no-pager", "-g", "tokens generated"],
        timeout=20,
    )
    if code == 0 and bool(out.strip()):
        return True
    try:
        for g in await asyncio.to_thread(gpu_snapshot):
            if float(g.get("memory_used_mb", 0)) > 8000 and float(g.get("utilization_gpu", 0)) > 0:
                return True
    except Exception:  # noqa: BLE001 - best effort, never break watchdog
        pass
    return False


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
            # Recovery da qualunque modalita': il fallback entra AUTO e cerca
            # SEMPRE prima LLM_READY (fallback always-operational): la chat
            # ritorna operativa prima di ogni retry media/direct, che poi
            # l'AUTO flow rianima da solo con le code.
            now = time.monotonic()
            if now - float(_state.get("error_retry_ts") or 0.0) < 120:
                return
            _state["error_retry_ts"] = now
            if desired != "AUTO":
                _state["desired_mode"] = "AUTO"
                _persist_desired()
                desired = "AUTO"
            _emit("error", "error recovery tick: restoring LLM first",
                  severity="error",
                  data={"last_error": _state.get("last_error", "")[:160]})
            await restore_llm_with_retries("error-recovery")
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
            _emit("job", f"job {job['job_id']} failed", severity="warning",
                  data={"job_id": job["job_id"], "status": "failed"})
            _emit("media", "media job failed", severity="warning",
                  data={"job_id": job["job_id"]})
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
        # L'agente che aspetta il risultato ha bisogno dell'LLM per finalizzare:
        # se non c'e altro in coda, ricaricalo SUBITO invece di aspettare
        # l'idle-timeout (ogni secondo con Tabby giu rischia 503 al finalize).
        # Manual MEDIA resta su (l'utente gestisce le GPU da se).
        if not queued_jobs() and _state["desired_mode"] in ("AUTO", "LLM"):
            await restore_llm_with_retries("media-job-done")
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
    prev_boot = None
    with _db_lock:
        prev_boot = db.execute("SELECT value FROM kv WHERE key='boot_id'").fetchone()
    # Solo un boot_id STORICO diverso prova un reboot vero. Senza riga
    # storica (primo avvio col nuovo codice) si preserva: a installazione
    # fresca il desired viene comunque dal default AUTO di config.
    fresh_boot = bool(boot_now) and bool(prev_boot) and prev_boot[0] != boot_now
    if boot_now:
        with _db_lock:
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
        with _db_lock:
            stale_ids = db.execute(
                "SELECT id FROM jobs WHERE status IN ('running','queued')"
            ).fetchall()
        for (jid,) in stale_ids:
            update_job(jid, status="cancelled",
                       error="cancelled by reboot, manager reset to AUTO")
            log.info("job %s cancelled after reboot", jid)
        try:
            await comfy_clear_all()
        except Exception as exc:
            log.warning("boot: ComfyUI queue clear failed (backend down?): %s", exc)
    else:
        log.info("boot: same boot_id, preserving desired=%s", _state["desired_mode"])
        # A process restart orphans in-flight tracking (prompt ids live in
        # memory): requeue running rows as failed so users resubmit instead
        # of waiting forever. Queued rows are untouched (no duplicates).
        with _db_lock:
            running_ids = db.execute(
                "SELECT id FROM jobs WHERE status='running'"
            ).fetchall()
        for (jid,) in running_ids:
            update_job(jid, status="failed",
                       error="manager restarted, tracking lost, resubmit")
            log.info("job %s failed after manager restart", jid)
    # Character ID recovery PRIMA di qualsiasi restore: a reboot vero i worker
    # sono morti e vanno marcati (mai `ready` presunto); a restart manager con
    # training vivo restano attivi e il gate sotto protegge le GPU.
    try:
        if _character_store is not None:
            from character_id.recovery import scan_interruptions

            for action in scan_interruptions(_character_store):
                log.info("character-id recovery: %r", action)
    except Exception as exc:  # noqa: BLE001 - recovery non deve rompere il boot
        log.warning("character-id recovery fallita: %s", exc)
    if _character_training_hold():
        # Training sopravvissuto al restart: backend intoccati, il trainer
        # li gestisce (al termine ripristina lui; il worker riparte dopo).
        log.info("boot: character training attivo, backend intoccati")
        return
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
    with _db_lock:
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
            if dest.exists():
                # Idempotent republish (retry paths): reuse, never overwrite.
                urls.append(f"/v1/media/{dest.name}")
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


def _manager_keys() -> list:
    """Chiavi controllo pieno (laya/operatore): MAI chiavi condivise.

    La vecchia master key hub condivisa con gateway/app e' rimossa: chi la
    conosceva (incluso l'agente via yaml leggibile) non controlla piu' nulla.
    """
    keys = [CONFIG.get("api_key") or ""]
    return [k for k in keys if isinstance(k, str) and k]


def _app_keys() -> list:
    """Chiavi ruolo app: letture + coda job + display + characters + laya NL.

    Accetta anche le chiavi controllo (superset): laya puo' tutto.
    """
    keys = [CONFIG.get("app_key") or ""]
    keys.extend(_manager_keys())
    seen: list = []
    for key in keys:
        if isinstance(key, str) and key and key not in seen:
            seen.append(key)
    return seen


def _bearer_matches(request: Request, keys: list) -> bool:
    auth = request.headers.get("authorization", "")
    return any(hmac.compare_digest(auth, f"Bearer {key}") for key in keys)


def require_laya(request: Request) -> None:
    """Solo controllo pieno (laya/operatore): modi, force, reboot, supervisor."""
    expected = _manager_keys()
    if not expected:
        # Fail-closed: senza chiave configurata NESSUNA rotta protetta
        # risponde (prima: passava tutto, anche /system/reboot).
        raise HTTPException(500, "manager api_key non configurata")
    if not _bearer_matches(request, expected):
        raise HTTPException(401, "invalid manager api key")


def require_key(request: Request) -> None:
    """Ruolo app (o controllo): letture, submit, coda, display, characters."""
    expected = _app_keys()
    if not expected:
        # Fail-closed: senza chiave configurata NESSUNA rotta protetta
        # risponde (prima: passava tutto, anche /system/reboot).
        raise HTTPException(500, "manager api_key non configurata")
    if not _bearer_matches(request, expected):
        raise HTTPException(401, "invalid manager api key")


def _is_local_request(request) -> bool:
    try:
        host = request.client.host if request.client else ""
    except Exception:  # noqa: BLE001 - ignoto = limitato (fail-closed)
        return True
    # Legacy loopbacks "127.0.0.1", "::1", "::ffff:127.0.0.1", "localhost"
    # ora via ipaddress.is_loopback (+ "localhost" esplicito).
    if host.strip().lower() in ("localhost",):
        return True
    try:
        ip = ipaddress.ip_address(host.strip())
        if ip.is_loopback:
            return True
        mapped = getattr(ip, "ipv4_mapped", None)
        if mapped is not None and mapped.is_loopback:
            return True
        return False
    except Exception:  # noqa: BLE001 - ignoto = limitato (fail-closed)
        return True


def _require_user_control(request) -> None:
    if _is_local_request(request):
        raise HTTPException(403, "solo l'utente gestisce il manager (locale: solo coda media e letture)")


def _ensure_log_handler() -> None:
    logger = logging.getLogger(APP_NAME)
    if logger.handlers:
        return
    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(logging.INFO)
    logger.addHandler(handler)
    logger.propagate = False


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
        "llm_serving": await llm_serving(),
        "llm_model": await llm_model(),
        "llm_vram_mb": round(await llm_vram_mb(), 1),
        "media_vram_mb": round(await media_vram_mb(), 1),
        "media_loaded_models": await media_loaded_models(),
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
        "h3_license_state": str(CONFIG.get("h3", {}).get("license_state", "DISABLED_LICENSE_GATE")),
        "character_training": (
            _character_store.active_training_jobs() if _character_store is not None else []
        ),
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


def _manual_modes_allowed() -> bool:
    """False quando l'operatore vuole restare in AUTO (video in coda)."""
    return bool(CONFIG.get("allow_manual_modes", False))


def _log_mode_request(request: Request, mode: str) -> None:
    try:
        peer = request.client.host if request.client else "?"
    except Exception:  # noqa: BLE001 - logging mai bloccante
        peer = "?"
    ua = str(request.headers.get("user-agent", ""))[:80]
    log.info("mode %s requested from %s ua=%s", mode, peer, ua)


def _require_manual_allowed(mode: str) -> None:
    if _manual_modes_allowed():
        return
    raise HTTPException(
        409,
        f"Modalita manuale {mode} disabilitata (allow_manual_modes=false): "
        "i video vanno in coda in AUTO.",
    )


@app.post("/mode/llm")
async def mode_llm(request: Request, _: None = Depends(require_laya)) -> dict:
    _require_user_control(request)
    _log_mode_request(request, "LLM")
    return _set_desired("LLM")


@app.post("/mode/media")
async def mode_media(request: Request, _: None = Depends(require_laya)) -> dict:
    _require_user_control(request)
    _log_mode_request(request, "MEDIA")
    _require_manual_allowed("MEDIA")
    return _set_desired("MEDIA")


@app.post("/mode/auto")
async def mode_auto(request: Request, _: None = Depends(require_laya)) -> dict:
    _require_user_control(request)
    _log_mode_request(request, "AUTO")
    return _set_desired("AUTO")


@app.post("/mode/direct")
async def mode_direct(request: Request, _: None = Depends(require_laya)) -> dict:
    _require_user_control(request)
    _log_mode_request(request, "DIRECT")
    _require_manual_allowed("DIRECT")
    return _set_desired("DIRECT")


# ---------------------------------------------------------------- force ---
# Canale diretto di emergenza (bridge laya / app): fora il modo richiesto
# scavalando le guardie che possono lasciare il manager bloccato (cooldown
# media, throttle di error-retry, gate "tabby sta generando", watchdog a 4
# fallimenti, miss della direct). NON e' un pin persistente ("solo
# transizione"): applica ORA la transizione, poi il manager riprende la sua
# logica normale. Protetto SOLO da require_key (MAI _require_user_control): il
# bridge gira in locale e DEVE poterlo chiamare; l'autorizzazione resta la
# manager api_key.
_FORCE_LLM_WINDOW_SEC = 180.0
# Oltre questa eta' uno stato transitorio con `_lock` libero significa che
# nessuna transizione e' piu' in corso (incastro): il force lo riporta a BOOT
# cosi' reconcile_boot lo ri-deriva dallo stato reale dei backend.
_FORCE_WEDGE_S = 30.0
# Riferimenti forti ai task di kick: senza, il GC puo' cancellarli a meta'.
_force_kick_tasks: set = set()


def _force_normalize_mode(raw: str) -> str | None:
    """Alias/testo -> LLM|MEDIA|AUTO|DIRECT via `recovery.force`, o None."""
    if recovery is not None and getattr(recovery, "normalize_mode", None) is not None:
        return recovery.normalize_mode(raw)
    if isinstance(raw, str):
        token = raw.strip().upper()
        if token in MODES:
            return token
    return None


def _force_reset_blockers(mode: str) -> list:
    """Azzera i campi che bloccano il manager (logica pura in recovery.force)."""
    if recovery is None or getattr(recovery, "apply_force_state", None) is None:
        return []
    cleared = recovery.apply_force_state(
        _state, mode, time.monotonic(), llm_window_s=_FORCE_LLM_WINDOW_SEC
    )
    return [str(name) for name in cleared] if isinstance(cleared, list) else []


def _force_transitional() -> tuple:
    if recovery is not None and getattr(recovery, "TRANSITIONAL_STATES", None):
        return tuple(recovery.TRANSITIONAL_STATES)
    return ("LLM_UNLOADING", "GPU_FREE", "MEDIA_STARTING", "MEDIA_STOPPING", "LLM_LOADING")


async def _force_kick() -> None:
    """Sveglia il driver subito dopo un force (il worker ticka ogni 2s)."""
    try:
        await asyncio.sleep(0.2)
        await _drive()
    except Exception as exc:  # noqa: BLE001 - il kick non deve mai rompere
        log.warning("force kick failed: %r", exc)


class ForceRequest(BaseModel):
    """Corpo di POST /force: modo target deciso fuori dal manager.

    `mode` accetta LLM|MEDIA|AUTO|DIRECT (case-insensitive) piu' alias comodi
    ("llm", "ready", "comfy", "auto", "direct"). `reason` finisce solo nell'evento.
    """

    mode: str = ""
    reason: str = ""


@app.post("/force")
async def force_mode(req: ForceRequest, _: None = Depends(require_laya)) -> dict:
    """Forza il modo richiesto ORA, scavalcando i blocchi. Mai pin persistente.

    Flusso: normalizza la mode -> se lo stato e' transitorio e il lock e'
    libero (incastro) lo riporta a BOOT -> imposta il desired -> azzera i
    blocker (finestra LLM inclusa) -> sveglia il driver in un task di sfondo.
    Ritorna sempre `{"ok": ...}`; mode non valida -> 422; recovery assente -> 503.
    """
    if recovery is None or getattr(recovery, "normalize_mode", None) is None:
        raise HTTPException(503, "recovery non disponibile: force non possibile")
    mode = _force_normalize_mode(req.mode)
    if mode is None:
        raise HTTPException(
            422, f"mode non valida: {req.mode!r} (attese LLM|MEDIA|AUTO|DIRECT)"
        )
    reason = req.reason if isinstance(req.reason, str) else ""
    state_before = _state["current_state"]
    wedged = False
    if (state_before in _force_transitional() and not _lock.locked()
            and time.monotonic() - float(_state.get("state_since") or 0.0) > _FORCE_WEDGE_S):
        set_state("BOOT", "force-wedge")
        wedged = True
    _set_desired(mode)
    cleared = _force_reset_blockers(mode)
    _emit("supervisor", f"force -> {mode}", data={
        "mode": mode,
        "reason": reason[:160],
        "state_before": state_before,
        "wedged": wedged,
        "cleared": cleared,
    })
    log.warning("FORCE mode -> %s (from %s, wedged=%s, reason=%s)",
                mode, state_before, wedged, reason[:120])
    try:
        task = asyncio.create_task(_force_kick())
        _force_kick_tasks.add(task)
        task.add_done_callback(_force_kick_tasks.discard)
    except Exception:  # noqa: BLE001 - il kick e' best effort
        log.warning("force kick non avviato")
    return {
        "ok": True,
        "mode": mode,
        "desired_mode": _state["desired_mode"],
        "state_before": state_before,
        "state": _state["current_state"],
        "wedged_reset": wedged,
        "cleared": cleared,
        "kicked": True,
    }


# Ticket single-use per /system/reboot: in memoria, validi 120s, consumati
# all'uso, mai nei log (fail-closed: senza ticket valido niente reboot).
_REBOOT_TICKET_TTL_S = 120.0
_reboot_tickets: dict[str, float] = {}


def _reboot_mint_ticket(now: float | None = None) -> str:
    """Crea ticket 32hex per reboot (puro/testabile, mai loggato)."""
    at = time.time() if now is None else float(now)
    # Purga scaduti per non crescere senza limite.
    for stale in [k for k, exp in _reboot_tickets.items() if exp <= at]:
        _reboot_tickets.pop(stale, None)
    ticket = secrets.token_hex(16)
    _reboot_tickets[ticket] = at + float(_REBOOT_TICKET_TTL_S)
    return ticket


def _reboot_consume_ticket(ticket: str, now: float | None = None) -> bool:
    """Consuma ticket reboot: True solo se valido e non scaduto (single-use)."""
    at = time.time() if now is None else float(now)
    try:
        exp = _reboot_tickets.pop(str(ticket), None)
    except Exception:  # noqa: BLE001 - dubbio = rifiuto
        return False
    if exp is None:
        return False
    try:
        return float(exp) > float(at)
    except (TypeError, ValueError):
        return False

@app.post("/system/reboot", response_model=None)
async def system_reboot(request: Request, _: None = Depends(require_laya)) -> dict:
    """Reboot con ticket single-use: senza ticket valido risponde 409 con
    ticket fresco (120s); con ticket valido esegue. Fire-and-forget."""
    _require_user_control(request)
    try:
        body = await request.json()
    except Exception:  # noqa: BLE001 - body assente = primo passo
        body = {}
    ticket = str((body or {}).get("ticket") or "")
    # Secondo passo: ticket valido e non scaduto -> consuma ed esegui.
    if ticket and _reboot_consume_ticket(ticket):
        # Mai loggare il ticket (segreto single-use).
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
    # Primo passo (o ticket scaduto/riusato): emetti ticket fresco, 409.
    fresh = _reboot_mint_ticket()
    return JSONResponse(status_code=409, content={"ticket": fresh, "expires_in": int(_REBOOT_TICKET_TTL_S)})


@app.post("/jobs/image")
@app.post("/jobs/video")
async def submit_job(request: Request, _: None = Depends(require_key)) -> JSONResponse:
    try:
        body = await request.json()
    except Exception:  # noqa: BLE001
        raise HTTPException(400, "invalid JSON")
    kind = "video" if request.url.path.endswith("/video") else "image"
    # Check+create atomici sotto _submit_lock: senza, N submit concorrenti
    # superano max_queued di N (overshoot).
    async with _submit_lock:
        max_queued = int(CONFIG["media"].get("max_queued", 10))
        backlog = len(queued_jobs()) + (1 if _state.get("current_job") else 0)
        if backlog >= max_queued:
            raise HTTPException(429, f"media queue full ({backlog}/{max_queued})")
        if _character_store is not None and _character_store.active_training_jobs():
            raise HTTPException(409, "GPU occupata: training Character ID in corso")
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
    task or the LLM is unavailable (caller falls back to normal chat).
    409 only for disabled/misconfigured/overloaded backend."""
    try:
        body = await request.json()
    except Exception:  # noqa: BLE001
        raise HTTPException(400, "invalid JSON")
    cfg = _smart_cfg()
    if not cfg.get("enabled", True):
        raise HTTPException(409, "smart fast path disabled")
    if _triage_mod is None:
        raise HTTPException(500, "triage module unavailable")
    # Fast-path informativo (non decisivo): evita triage/LLM costosi a coda
    # piena. Il gate decisivo e atomico e prima della create sotto _submit_lock.
    max_queued = int(CONFIG["media"].get("max_queued", 10))
    backlog = len(queued_jobs()) + (1 if _state.get("current_job") else 0)
    if backlog >= max_queued:
        raise HTTPException(429, f"media queue full ({backlog}/{max_queued})")
    if _character_store is not None and _character_store.active_training_jobs():
        raise HTTPException(409, "GPU occupata: training Character ID in corso")
    text = str(body.get("text", "") or "")
    images = body.get("input_images", []) or []
    if isinstance(images, str):
        images = [images]
    images = [str(p) for p in images[:4]]
    has_image = bool(images) or bool(body.get("has_image"))
    backend = _smart_backend()
    # Triage is sync (laya ~65ms): keep it off the event loop.
    forced = str(body.get("preset", "") or "")
    if forced:
        spec = PRESETS.get(forced)
        if not spec or spec.get("disabled"):
            return JSONResponse({"media": False, "reason": "forced-preset-invalid"})
        # Stesso off-loop del ramo normale: il triage sync bloccherebbe tutto.
        decision = await asyncio.to_thread(_triage_mod.triage, text, has_image,
                                           (), backend)
        decision = type(decision)(media=True, preset=forced,
                                  prompt_hint=decision.prompt_hint or text,
                                  reason=f"forced:{forced}",
                                  confidence=1.0)
    else:
        decision = await asyncio.to_thread(_triage_mod.triage, text, has_image,
                                           (), backend)
    if not decision.media or not decision.preset:
        return JSONResponse({"media": False, "reason": decision.reason})
    if _state["desired_mode"] in ("LLM", "DIRECT"):
        # Nessuno drenerebbe la coda: vale anche per triage_only (niente
        # upload sprecati prima del fallback chat).
        return JSONResponse({"media": False, "reason": "manual-mode"})
    if body.get("triage_only"):
        # Verdetto secco senza submit: il chiamante carica i file solo se serve.
        spec = PRESETS.get(decision.preset) or {}
        if spec.get("disabled"):
            return JSONResponse({"media": False, "reason": "preset-disabled"})
        return JSONResponse({"media": True, "preset": decision.preset,
                             "reason": decision.reason})
    given = str(body.get("prompt", "") or "").strip()
    if given:
        # Prompt incollato dal chiamante (impostazioni app): zero chiamate LLM,
        # funziona anche a LLM scarico.
        prompt = given[:2000]
        log.info("SMART: canned prompt (%d chars)", len(prompt))
    else:
        if not await llm_loaded():
            return JSONResponse({"media": False, "reason": "llm-unloaded",
                                 "fallback": True})
        prompt = await prompt_only_llm(decision.prompt_hint or text)
        if not prompt:
            raise HTTPException(502, "prompt-only LLM call failed")
    spec = PRESETS.get(decision.preset) or {}
    # Gate atomico check+create sotto _submit_lock: ricontrolla backlog e
    # training dopo triage/LLM (finestra di secondi) per non fare overshoot.
    async with _submit_lock:
        max_queued = int(CONFIG["media"].get("max_queued", 10))
        backlog = len(queued_jobs()) + (1 if _state.get("current_job") else 0)
        if backlog >= max_queued:
            raise HTTPException(429, f"media queue full ({backlog}/{max_queued})")
        if _character_store is not None and _character_store.active_training_jobs():
            raise HTTPException(409, "GPU occupata: training Character ID in corso")
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
    with _db_lock:
        total = db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0]
        rows = db.execute("SELECT * FROM jobs ORDER BY created DESC LIMIT ?", (limit,)).fetchall()
    return {"jobs": [job_row(r) for r in rows], "total": total, "limit": limit}


@app.get("/events")
async def list_events(request: Request, _: None = Depends(require_key)) -> dict:
    """Eventi del supervisore (Fase 0): piu recenti prima, filtrabili per kind."""
    try:
        limit = min(1000, max(1, int(request.query_params.get("limit", "100"))))
    except (TypeError, ValueError):
        limit = 100
    kind = request.query_params.get("kind") or None
    store = _events()
    if store is None:
        return {"events": []}
    try:
        rows = store.recent(limit=limit, kind=kind)
    except Exception:  # noqa: BLE001 - lettura best-effort, mai fatale
        return {"events": []}
    return {
        "events": [
            {"ts": ev.ts, "kind": ev.kind, "severity": ev.severity,
             "message": ev.message, "data": ev.data}
            for ev in rows
        ]
    }


class AskRequest(BaseModel):
    """Corpo di POST /supervisor/ask (canale NL del supervisore, Fase 4).

    `question` e' la domanda in linguaggio naturale (vuota = "che succede?").
    `confirm` autorizza l'esecuzione della sola azione PROPOSTA, ma da' il via
    libera solo insieme a `supervisor.channel.allow_execute` (default False):
    senza il flag esplicito il canale risponde e propone, non esegue.
    """

    question: str = ""
    confirm: bool = False


@app.post("/supervisor/ask")
async def supervisor_ask(req: AskRequest, _: None = Depends(require_laya)) -> dict:
    """Canale NL del supervisore (Fase 4): spiega e PROPONE, mai 500.

    Flusso: snapshot -> `recovery.answer` (testo deterministico grounded) ->
    `recovery.propose` (azione dall'allowlist con parametri risolti). L'azione
    viene ESEGUITA solo se `req.confirm` E `channel.allow_execute` sono
    entrambi veri; riusa `_supervisor_execute` e lo stesso cooldown per-azione
    del self-heal/operator. Ogni errore degrada a `{"ok": False, ...}`.
    """
    try:
        cfg = CONFIG.get("supervisor") if isinstance(CONFIG, dict) else None
        channel_cfg = cfg.get("channel") if isinstance(cfg, dict) else None
        if not isinstance(channel_cfg, dict) or not bool(channel_cfg.get("enabled", False)):
            return {"ok": False, "error": "channel disabled"}
        if recovery is None or getattr(recovery, "answer", None) is None:
            return {"ok": False, "error": "recovery unavailable"}

        question = req.question if isinstance(req.question, str) else ""
        confirm = bool(req.confirm)

        snapshot = await _supervisor_snapshot()
        ans = recovery.answer(snapshot, question)
        if not isinstance(ans, dict):
            ans = {}
        text = ans.get("text") if isinstance(ans.get("text"), str) else ""
        facts = ans.get("facts") if isinstance(ans.get("facts"), dict) else {}

        prop = recovery.propose(snapshot, question, _supervisor_limits())
        action_out = None
        if prop is not None:
            params = getattr(prop, "params", {})
            action_out = {
                "name": str(getattr(prop, "name", "")),
                "reason": str(getattr(prop, "reason", "")),
                "confidence": float(getattr(prop, "confidence", 0.0) or 0.0),
                "params": params if isinstance(params, dict) else {},
            }

        executed = None
        # Esecuzione SOLO con proposta valida, conferma esplicita e flag di
        # config: entrambe richieste (fail-closed di default).
        if prop is not None and confirm and channel_cfg.get("allow_execute") is True:
            name = str(getattr(prop, "name", ""))
            try:
                cooldown_s = float(cfg.get("cooldown_s", 300) or 300)
            except (TypeError, ValueError):
                cooldown_s = 300.0
            cooldowns = _state.get("supervisor_cooldown")
            if not isinstance(cooldowns, dict):
                cooldowns = {}
                _state["supervisor_cooldown"] = cooldowns
            try:
                last = float(cooldowns.get(name, 0.0) or 0.0)
            except (TypeError, ValueError):
                last = 0.0
            if time.monotonic() - last >= cooldown_s:
                status = await _supervisor_execute(prop)
                cooldowns[name] = time.monotonic()
                executed = {"name": name, "status": status}
            else:
                executed = {"name": name, "status": "cooldown"}

        _emit(
            "supervisor",
            f"channel ask: {question[:120]}",
            data={
                "question": question[:200],
                "answer": text[:400],
                "action": action_out,
                "executed": executed,
            },
        )
        return {
            "ok": True,
            "answer": text,
            "action": action_out,
            "executed": executed,
            "facts": facts,
            "backend": "rules",
        }
    except Exception as exc:  # noqa: BLE001 - il canale non risponde mai 500
        log.warning("supervisor ask failed: %r", exc)
        return {"ok": False, "error": "internal error"}


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
    _emit("job", f"job {job_id} cancelled",
          data={"job_id": job_id, "status": "cancelled"})
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


# ------------------------------------------------------------------
# "Workflow live" (client Windows, pagina Comfy): l'utente vede e tocca il
# workflow esattamente come nella pagina web di Comfy, ma GLI INTERVENTI
# NON CAMBIANO MAI LA MODALITA' DEL MANAGER (niente set_state, niente
# desired_mode, niente /mode/*): si tocca solo la riga del job.
# - POST /jobs/{id}/workflow = riscrive il workflow di un job non ancora
#   inviato a ComfyUI (QUEUED): sicuro per costruzione, il worker rilegge
#   la riga solo al momento del submit.
# - POST /jobs/{id}/resubmit = interrompe il job in esecuzione e ne mette
#   in coda una COPIA su nuovo job_id (il vecchio resta come record
#   "superseded"): nessuna corsa col tracker morto del vecchio job, che
#   l'opostro vede "cancelled" e si ferma da solo al poll successivo.
# ------------------------------------------------------------------


def _read_job_workflow(jid: str) -> dict | None:
    db = _db_conn()
    with _db_lock:
        row = db.execute("SELECT workflow FROM jobs WHERE id=?", (jid,)).fetchone()
    try:
        workflow = json.loads(row[0]) if row else None
    except (json.JSONDecodeError, TypeError):
        return None
    return workflow if isinstance(workflow, dict) else None


@app.get("/jobs/{job_id}/workflow")
async def get_job_workflow_api(job_id: str, _: None = Depends(require_key)) -> dict:
    job = get_job(job_id)
    if not job:
        raise HTTPException(404, "unknown job")
    workflow = _read_job_workflow(job_id)
    if workflow is None:
        raise HTTPException(409, "workflow unreadable")
    return {"job_id": job_id, "status": job["status"], "workflow": workflow}


@app.post("/jobs/{job_id}/workflow")
async def set_job_workflow_api(job_id: str, body: dict, _: None = Depends(require_key)) -> dict:
    job = get_job(job_id)
    if not job:
        raise HTTPException(404, "unknown job")
    if not isinstance(body, dict):
        raise HTTPException(400, "body must be {\"workflow\": {...}}")
    workflow = body.get("workflow")
    if not isinstance(workflow, dict) or not workflow:
        raise HTTPException(400, "body must be {\"workflow\": {...}}")
    running = (job["status"] == "running" or
               (job_id == _state.get("current_job") and _state.get("current_prompt_id")))
    if running:
        raise HTTPException(409, "job in esecuzione: usa /jobs/{id}/resubmit")
    if job["status"] not in ("queued", "cancelled", "failed"):
        raise HTTPException(409, f"job in stato {job['status']}: non modificabile")
    problem = validate_workflow(workflow)
    if problem:
        raise HTTPException(400, problem)
    update_job(job_id, workflow=json.dumps(workflow), status="queued", error="")
    _emit("job", f"job {job_id} workflow updated by user",
          data={"job_id": job_id, "status": "queued"})
    log.info("job %s workflow updated by user (was %s)", job_id, job["status"])
    return get_job(job_id)


@app.post("/jobs/{job_id}/resubmit")
async def resubmit_job_api(job_id: str, body: dict | None = None, _: None = Depends(require_key)) -> dict:
    job = get_job(job_id)
    if not job:
        raise HTTPException(404, "unknown job")
    if not isinstance(body, dict):
        body = {}
    workflow = body.get("workflow")
    if workflow is None:
        workflow = _read_job_workflow(job_id)
    if not isinstance(workflow, dict) or not workflow:
        raise HTTPException(400, "workflow unreadable (pass {\"workflow\": {...}} or fix the row)")
    problem = validate_workflow(workflow)
    if problem:
        raise HTTPException(400, problem)
    status = job["status"]
    if status == "queued" and job_id != _state.get("current_job"):
        raise HTTPException(409, "job gia' in coda: usa /jobs/{id}/workflow per modificarlo")
    if status == "running" or job_id == _state.get("current_job"):
        _state["current_job"] = None
        prompt_id = _state.get("current_prompt_id")
        _state["current_prompt_id"] = None
        if prompt_id:
            await comfy_cancel(str(prompt_id))
        update_job(job_id, status="cancelled", error="superseded by resubmit")
        _emit("job", f"job {job_id} interrupted for resubmit",
              data={"job_id": job_id, "status": "cancelled"})
        log.info("job %s interrupted by user resubmit", job_id)
    new_job = create_job(job["kind"], workflow, preset=job.get("preset", ""),
                         backend=job.get("backend", ""), model=job.get("model", ""),
                         params=job.get("parameters") or None)
    log.info("job %s resubmitted as new job %s", job_id, new_job["job_id"])
    return {"cancelled_job": job_id, "queued_job": new_job}


# ------------------------------------------------------- browse (read-only) ---
# Sfoglia cartelle immagini per la sezione Immagini (1 livello, solo
# lettura): l'agent gateway e manifest-pinned, il manager e ridistribuibile.
# Jail su root consentiti + auth manager key; i byte passano dal proxy
# /v1/media live del gateway (che ri-applica il suo jail a serve-time).

_BROWSE_IMAGE_EXTENSIONS = frozenset({".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp"})
_BROWSE_MAX_FOLDERS = 500
_BROWSE_MAX_ITEMS = 1500


def _browse_home() -> "Path | None":
    """Home senza expanduser (solleva RuntimeError senza HOME in env)."""
    home = os.environ.get("HOME", "").strip()
    if home:
        return Path(home)
    try:
        import pwd
        return Path(pwd.getpwuid(os.getuid()).pw_dir)
    except Exception:
        return None


def _browse_expand(path: Path, home: "Path | None") -> "Path | None":
    """Espande solo '~' iniziale contro home nota; ~utente delegato, fail-closed."""
    text = str(path).replace("\\", "/")
    if text == "~" or text.startswith("~/"):
        if home is None:
            return None
        return Path(str(home) + text[1:])
    if text.startswith("~"):
        try:
            return Path(text).expanduser()
        except Exception:
            return None
    return path


def _browse_roots() -> list:
    """Radici sfogliabili (mirror HERMES_MEDIA_ROOTS + librerie utente)."""
    try:
        home: "Path | None" = Path.home()
    except Exception:
        home = _browse_home()
    candidates = []
    if home is not None:
        candidates += [
            home / ".hermes" / "hub_uploads",
            home / "video",
            home / "news",
            home / "images",
            home / ".hermes" / "cache",
            home / ".hermes" / "media",
        ]
    for part in os.environ.get("HERMES_MEDIA_ROOTS", "").split(os.pathsep):
        if part.strip():
            candidates.append(Path(part.strip()))
    unique: list = []
    seen = set()
    for root in candidates:
        try:
            resolved = root.expanduser().resolve()
        except Exception:
            continue
        key = str(resolved)
        if key not in seen:
            seen.add(key)
            unique.append(resolved)
    return unique


def _browse_jail(path: Path, roots: list, home: "Path | None" = None) -> "Path | None":
    """Resolve + jail: path risolto se dentro un root consentito, else None."""
    if home is None:
        home = _browse_home()
    expanded = _browse_expand(path, home)
    if expanded is None:
        return None
    try:
        resolved = expanded.resolve()
    except Exception:
        return None
    for root in roots:
        try:
            if resolved == root or root in resolved.parents:
                return resolved
        except Exception:
            continue
    return None


def _browse_scan(current: "Path", roots: list) -> tuple:
    """Un livello: (folders, items). Symlink solo se restano nel jail
    (il proxy gateway ri-verifica a serve-time: doppia cintura)."""
    folders: list = []
    items: list = []
    home = _browse_home()
    try:
        resolved_current = current.resolve()
        entries = sorted(current.iterdir(), key=lambda p: p.name.lower())
    except Exception:
        return folders, items
    for entry in entries:
        if len(folders) >= _BROWSE_MAX_FOLDERS and len(items) >= _BROWSE_MAX_ITEMS:
            break
        try:
            name = entry.name
            if not name or name.startswith("."):
                continue
            if entry.is_symlink():
                try:
                    target = entry.resolve()
                except Exception:
                    continue
                if not target.exists():
                    continue
                if _browse_jail(target, roots, home) is None:
                    continue
                if target.is_dir():
                    if len(folders) >= _BROWSE_MAX_FOLDERS:
                        continue
                    folders.append({"name": name, "path": str(target)})
                    continue
                if not target.is_file() or target.suffix.lower() not in _BROWSE_IMAGE_EXTENSIONS:
                    continue
                try:
                    rel = target.relative_to(resolved_current).as_posix()
                except ValueError:
                    continue
                if len(items) >= _BROWSE_MAX_ITEMS:
                    continue
                stat = target.stat()
                items.append((entry, target, stat, rel))
                continue
            if entry.is_dir():
                if len(folders) >= _BROWSE_MAX_FOLDERS:
                    continue
                folders.append({"name": name, "path": str(entry.resolve())})
                continue
            if not entry.is_file() or entry.suffix.lower() not in _BROWSE_IMAGE_EXTENSIONS:
                continue
            if len(items) >= _BROWSE_MAX_ITEMS:
                continue
            items.append((entry, entry, entry.stat(), entry.name))
        except Exception:
            continue
    items.sort(key=lambda quad: quad[2].st_mtime, reverse=True)
    out = []
    for entry, target, stat, rel in items[:_BROWSE_MAX_ITEMS]:
        out.append({
            "id": rel,
            "title": entry.stem.replace("_", " ").replace("-", " ").strip() or entry.name,
            "filename": entry.name,
            "path": str(target),
            "root": str(current),
            "mime_type": _browse_mime(entry.name),
            "size_bytes": int(stat.st_size),
            "modified_at": float(stat.st_mtime),
        })
    return folders, out


def _browse_mime(filename: str) -> str:
    lower = filename.lower()
    if lower.endswith(".png"):
        return "image/png"
    if lower.endswith(".gif"):
        return "image/gif"
    if lower.endswith(".bmp"):
        return "image/bmp"
    if lower.endswith(".webp"):
        return "image/webp"
    return "image/jpeg"


@app.get("/browse")
async def browse_library(request: Request, _: None = Depends(require_key)) -> dict:
    raw = (request.query_params.get("path") or "").strip()
    roots = await asyncio.to_thread(_browse_roots)

    def _resolve_current() -> "Path | None":
        if not raw:
            for root in roots:
                try:
                    if root.is_dir():
                        return root
                except OSError:
                    continue
            return roots[0] if roots else None
        return _browse_jail(Path(raw), roots)

    current = await asyncio.to_thread(_resolve_current)
    if current is None:
        raise HTTPException(403, "browse path non consentito")
    try:
        is_dir = current.is_dir()
    except OSError:
        is_dir = False
    if not is_dir:
        raise HTTPException(404, "cartella non trovata")
    folders, items = await asyncio.to_thread(_browse_scan, current, roots)
    parent: "str | None" = None
    try:
        candidate_parent = current.parent
        if _browse_jail(candidate_parent, roots) is not None and candidate_parent != current:
            parent = str(candidate_parent)
    except OSError:
        parent = None
    return {
        "object": "hermes.media.browse",
        "status": "ok",
        "path": str(current),
        "parent": parent,
        "folders": folders,
        "items": items,
        "count": len(items),
        "folder_count": len(folders),
    }


# Strong reference to the worker task. asyncio holds only weak refs to# bare create_task() handles: without this the GC silently collects the
# worker mid-run (observed live: queue stalls, no logs, restart revives).
_worker_task: "asyncio.Task[None] | None" = None


@app.on_event("startup")
async def on_startup() -> None:
    global _worker_task
    _ensure_log_handler()
    if not str(CONFIG.get("api_key") or ""):
        # Fail-fast visibile: senza chiave le rotte protette rispondono
        # 500 (vedi require_key). Impostare api_key o HERMES_GPU_MANAGER_KEY.
        log.error("api_key non configurata: rotte protette disabilitate (500). "
                  "Impostare api_key in gpu-manager.yaml o HERMES_GPU_MANAGER_KEY.")
    _db_conn()
    cleanup_old_artifacts()
    _worker_task = asyncio.create_task(worker_loop())
    log.info("hermes-gpu-manager starting, desired=%s", _state["desired_mode"])


_character_store = None
if _register_character_routes is not None:
    try:
        _cid_cfg = CONFIG.get("character_id", {}) or {}

        def _hcid_idle() -> tuple[bool, str]:
            if _state.get("desired_mode") != "AUTO":
                return False, f"modalita manuale {_state.get('desired_mode')} (training solo in AUTO)"
            if _state.get("current_job"):
                return False, f"media job {_state.get('current_job')} in corso"
            if queued_jobs():
                return False, f"coda media non vuota ({len(queued_jobs())} job)"
            state = _state.get("current_state", "")
            if state in ("ERROR",):
                return False, f"manager in {state}"
            return True, ""

        def _hcid_submit(workflow: dict, params: dict) -> dict:
            # Punto di creazione job: prima libera i training scaduti
            # (wall-clock), poi accoda. Mai superare il limite in silenzio.
            _enforce_training_wallclock()
            return create_job("video", workflow, preset="character",
                              backend="minimax-h3", params=params)

        _character_store = _register_character_routes(
            app,
            require_key=require_key,
            require_user=_require_user_control,
            root=str(_cid_cfg.get("root") or "/opt/hermes/character-id"),
            tools_python=str(
                _cid_cfg.get("tools_python")
                or "/opt/hermes/character-id/tools-venv/bin/python"
            ),
            trainer_python=str(
                _cid_cfg.get("trainer_python")
                or "/opt/hermes/character-id/trainer/venv/bin/python"
            ),
            workflows_dir=str(_cid_cfg.get("workflows_dir") or WORKFLOWS_DIR),
            comfy_input_dir=str(
                _cid_cfg.get("comfy_input_dir")
                or CONFIG.get("media", {}).get("input_dir")
                or "/opt/hermes/runtimes/comfyui/app/input"
            ),
            loras_dir=str(
                _cid_cfg.get("loras_dir")
                or "/opt/hermes/runtimes/comfyui/app/models/loras"
            ),
            submit_cb=_hcid_submit,
            validate_cb=validate_workflow,
            idle_cb=_hcid_idle,
        )
    except Exception as exc:  # noqa: BLE001 - character-id non deve rompere il manager
        log.warning("character-id non registrato: %s", exc)
else:  # pragma: no cover - pacchetto sempre presente nel deploy normale
    log.warning("character-id assente: rotte /characters/* disabilitate")


async def _reap_dead_character_jobs() -> None:
    """Orfani GPU: worker morto (SIGKILL/OOM) + silenzio oltre 15 min.

    L'heartbeat dei worker pinga ogni 60s: 15 min senza ping E pid morto =
    morte certa (mai falsi positivi su fasi lente ma vive). Il job va failed,
    il personaggio interrupted, il lock si libera e la chat riparte.
    Mai eccezioni verso il worker loop.
    """
    if _character_store is None:
        return
    try:
        jobs = await asyncio.to_thread(_character_store.active_training_jobs)
    except Exception:
        return
    now = time.time()
    for job in jobs:
        try:
            pid = int(job.get("pid", 0) or 0)
            updated = float(job.get("updated_at", 0) or 0)
            if pid > 0:
                try:
                    os.kill(pid, 0)
                    continue  # vivo: non toccare
                except (OSError, ValueError):
                    pass
            if now - updated < 900:
                continue  # giovane o appena pingato: non toccare
            jid = str(job.get("id", ""))
            cid = str(job.get("character_id", ""))
            log.error("character job orfano %s (%s): worker morto da oltre 15 min, marco failed",
                      jid, job.get("kind"))
            await asyncio.to_thread(
                _character_store.update_job, jid, status="failed", progress=1.0,
                detail="worker morto (reaper)", error="nessun heartbeat da 15 min")
            if cid:
                await asyncio.to_thread(_character_store.set_status, cid, "interrupted")
            try:
                from character_id.training import clear_lock, read_lock

                lock = read_lock(str((CONFIG.get("character_id", {}) or {}).get("root")
                                     or "/opt/hermes/character-id"))
                if lock and str(lock.get("job_id", "")) == jid:
                    clear_lock(str((CONFIG.get("character_id", {}) or {}).get("root")
                                   or "/opt/hermes/character-id"))
            except Exception:
                pass
            await systemctl("start", "hermes-tabby.service")
        except Exception as exc:  # noqa: BLE001 - un job non ferma il reaper
            log.warning("reaper character fallito su un job: %s", exc)


def _character_wallclock_limit_s() -> float:
    """Limite wall-clock training in secondi (default generoso 12h, puro)."""
    try:
        hours = float((CONFIG.get("character_id", {}) or {}).get("max_wallclock_hours", 12))
    except (TypeError, ValueError, AttributeError):
        hours = 12.0
    if hours <= 0:
        hours = 12.0
    return hours * 3600.0


def _training_over_wallclock(created_at: object, now_s: float, max_hours: float = 12) -> bool:
    """True se il training supera il wall-clock (puro, testabile via AST-exec)."""
    try:
        age = float(now_s) - float(created_at)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return False
    try:
        limit = float(max_hours) * 3600.0
    except (TypeError, ValueError):
        limit = 12.0 * 3600.0
    return age > limit


def _enforce_training_wallclock() -> None:
    """Cancella i training oltre il wall-clock e libera le GPU (mai solleva).

    Chiamata dal worker e prima di creare job: un training oltre il limite
    viene cancellato con log onesto, mai lasciato occupare le GPU in eterno.
    """
    if _character_store is None:
        return
    try:
        jobs = _character_store.active_training_jobs()
    except Exception:  # noqa: BLE001 - stato ignoto: non contendere le GPU
        return
    try:
        limit = _character_wallclock_limit_s()
    except Exception:  # noqa: BLE001 - config illeggibile: default generoso
        limit = 12.0 * 3600.0
    now = time.time()
    for job in jobs or []:
        try:
            age = now - float((job or {}).get("created_at", 0) or 0)
        except (TypeError, ValueError, AttributeError):
            continue
        if age <= limit:
            continue
        jid = str((job or {}).get("id", ""))
        if not jid:
            continue
        log.warning("character job %s superato wall-clock (%.1fh), cancel", jid, age / 3600.0)
        try:
            _character_store.update_job(jid, status="cancelled", progress=1.0,
                                        detail="superato wall-clock, cancel",
                                        error="wall-clock superato")
        except Exception as exc:  # noqa: BLE001 - un job non ferma l'enforcement
            log.warning("wall-clock cancel fallito su %s: %s", jid, exc)


def _character_training_hold() -> bool:
    """Training character attivo? Il worker manager deve parcheggiarsi.

    True = job train/evaluate/benchmark non terminali: il trainer possiede le
    GPU (ha scaricato lui i backend e li ripristina lui). Mai eccezioni.
    Se lo store e illeggibile, fallback sul lockfile (niente DB): lock
    presente + pid vivo = hold. Mai permissivo verso la GPU, mai cieco.
    """
    try:
        return bool(_character_store is not None and _character_store.active_training_jobs())
    except Exception:  # noqa: BLE001 - store rotto: prova il lockfile
        pass
    try:
        import json as _json
        import os as _os
        from character_id.training import lock_path

        cfg = globals().get("CONFIG") or {}
        hcid_root = str((cfg.get("character_id", {}) or {}).get("root") or "")
        if not hcid_root:
            # Hook di test (e override operativo): mai hardcodare solo il default.
            hcid_root = _os.environ.get("HCID_ROOT", "") or "/opt/hermes/character-id"
        try:
            lock = _json.loads(lock_path(hcid_root).read_text(encoding="utf-8"))
        except FileNotFoundError:
            return False
        if not isinstance(lock, dict) or type(lock.get("pid")) is not int:
            return True
        pid = lock["pid"]
        if pid <= 0:
            return False
        try:
            _os.kill(pid, 0)
        except ProcessLookupError:
            return False
        return True
    except Exception:  # noqa: BLE001 - stato ignoto: non contendere le GPU
        return True


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "manager:app",
        host=str(CONFIG.get("host", "0.0.0.0")),
        port=int(CONFIG.get("port", 8643)),
        log_level="info",
    )
