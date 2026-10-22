#!/usr/bin/env python3
"""hermes-laya-bridge — canale diretto con laya per forzare il GPU manager.

Perche' esiste: quando il GPU manager si blocca (modo che "torna indietro",
LLM che non ricarica, ERROR incastrato) il canale migliore e' parlare
direttamente con laya. Il manager non aiuta in quel caso — e' proprio lui il
problema — quindi questo servizio e' un processo SEPARATO, sempre attivo, che:

  1. riceve un testo in linguaggio naturale dall'app (POST /laya);
  2. lo interpreta con laya (modello di decisione locale, risposta a scelta),
     con fallback deterministico a parole chiave se laya non risponde;
  3. FORZA il modo richiesto sul manager via POST /force (LLM|MEDIA|AUTO|DIRECT);
  4. se il manager non risponde, lo RIAVVIA via systemd e riprova; per l'LLM,
     come ultima spiaggia, riavvia direttamente il servizio tabby.

Solo stdlib (nessun fastapi/uvicorn): il servizio deve dipendere il meno
possibile. La logica pura (classify/parse/compose/merge) e' separata dall'I/O ed
e' testabile senza rete; `handle_command` prende le funzioni di I/O iniettate.

Config: default nel codice, override da `/etc/hermes/laya-bridge.yaml` (se
presente). La chiave del manager non va duplicata: se `manager_key` e' vuota si
usa `HERMES_GPU_MANAGER_KEY` oppure l'`api_key` di
`/etc/hermes/gpu-manager.yaml`. L'autenticazione in ingresso usa le chiavi del
bridge, o — se non configurate — le stesse chiavi del manager.
"""

from __future__ import annotations

import json
import logging
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

APP_NAME = "hermes-laya-bridge"

CONFIG_PATH = "/etc/hermes/laya-bridge.yaml"
MANAGER_CONFIG_PATHS = ("/etc/hermes/gpu-manager.yaml", "/etc/hermes/gpu-manager.yml")

DEFAULT_CONFIG = {
    "host": "0.0.0.0",
    # 8644 e' occupata dal gateway Hermes Agent (serve 8642+8644): usa 8645.
    "port": 8645,
    # Dove gira il GPU manager e la chiave per parlargli.
    "manager_url": "http://127.0.0.1:8643",
    "manager_key": "",
    "manager_service": "hermes-gpu-manager.service",
    "llm_service": "hermes-tabby.service",
    # laya (modello di decisione locale).
    "laya_url": "http://127.0.0.1:11435",
    "laya_model": "laya:multilingual",
    "laya_min_confidence": 0.7,
    "timeout": 15,
    # Quanto aspettare che il manager riparta prima di riprovare il force.
    "manager_restart_timeout": 60,
    # Chiavi accettate in ingresso (Bearer). Se vuote si usa la chiave manager.
    "api_key": "",
    "trusted_keys": [],
    # Chiave app (letture NL + submit job): l'agente non la conosce mai.
    # Via credential file app.key, env HERMES_GPU_APP_KEY o yaml.
    "app_key": "",
    # Chiavi agente/gateway (solo submit job via /laya/jobs, mai NL/modi).
    # Allowlist non segreta: l'agente conosce gia' queste chiavi.
    "agent_keys": [],
}

# Risposta a scelta per laya: intento -> modo del manager.
LAYA_QID = "bridge_action"
LAYA_QUESTIONS = {
    LAYA_QID: {
        "type": "choice",
        "instructions": (
            "L utente vuole comandare il GPU manager Hermes. "
            "Se vuole l LLM caricato e pronto (chat, tabby, qwen) scegli llm_ready. "
            "Se vuole ComfyUI per generare immagini o video (attiva, passa a, "
            "usa comfy, metti comfy) scegli activate_comfy. "
            "Solo se nomina esplicitamente diretto/direct/diretto scegli "
            "direct_mode. Se vuole la modalita automatica scegli auto_mode. "
            "Se chiede solo lo stato o cosa succede scegli status. Altrimenti none."
        ),
        "criteria": {
            "llm_ready": "caricare o tenere pronto l LLM, la chat, tabby o qwen",
            "activate_comfy": "attivare o usare ComfyUI per generare immagini o video",
            "auto_mode": "tornare alla modalita automatica",
            "direct_mode": "usare ComfyUI diretto, solo se dice diretto o direct",
            "status": "chiedere lo stato del sistema, cosa succede",
            "none": "altro: nessun comando riconosciuto",
        },
    }
}

# laya choice -> (intent, mode).
CHOICE_TO_INTENT = {
    "llm_ready": ("mode", "LLM"),
    "activate_comfy": ("mode", "MEDIA"),
    "auto_mode": ("mode", "AUTO"),
    "direct_mode": ("mode", "DIRECT"),
    "status": ("status", None),
    "none": (None, None),
}

# Fallback deterministico: (modo, parole chiave). Ordine = priorita': DIRECT
# prima di MEDIA ("comfy diretto" contiene "comfy"). Le chiavi sono radici per
# tollerare le flessioni ("genera", "immagini", "immagina").
_MODE_KEYWORDS = (
    ("DIRECT", ("direct", "diretto", "comfy diretto", "comfy-direct")),
    ("MEDIA", ("comfy", "comfyui", "media", "video", "immagin", "genera", "render")),
    ("LLM", ("llm", "ready", "tabby", "qwen", "modello", "carica", "pronto")),
    ("AUTO", ("auto", "automatico", "automatic")),
)
_STATUS_KEYWORDS = ("stato", "status", "come sta", "che succede", "situazione",
                    "riassunto", "come va")

# Riavvio manager (solo NL app, mai agente-diagnostica): verbo + target.
_REBOOT_VERBS = ("riavvia", "restart", "reboot", "ravvia")
_REBOOT_TARGETS = ("manager", "gestore")

log = logging.getLogger(APP_NAME)


# ------------------------------------------------------------ helpers puri ---

def _deep_merge(base: dict, override: dict) -> dict:
    """Merge profondo: override vince, i dict si fondono, il resto rimpiazza."""
    out = dict(base)
    for key, value in (override or {}).items():
        if isinstance(value, dict) and isinstance(out.get(key), dict):
            out[key] = _deep_merge(out[key], value)
        else:
            out[key] = value
    return out


def _yaml_scalar(text: str, key: str) -> str:
    """Fallback minimale: `key: value` alla prima occorrenza (niente yaml).

    Utile per leggere l'`api_key` dal config del manager anche senza PyYAML.
    """
    try:
        for raw in text.splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or ":" not in line:
                continue
            name, _, value = line.partition(":")
            if name.strip() == key:
                value = value.strip()
                if len(value) >= 2 and value[0] in "'\"" and value[-1] == value[0]:
                    value = value[1:-1]
                return value
    except Exception:  # noqa: BLE001
        pass
    return ""


def _load_yaml_file(path: str) -> dict:
    try:
        text = Path(path).read_text(encoding="utf-8")
    except OSError:
        return {}
    try:
        import yaml  # type: ignore

        data = yaml.safe_load(text)
        return data if isinstance(data, dict) else {}
    except Exception:  # noqa: BLE001 - senza yaml, estrai l'api_key
        key = _yaml_scalar(text, "api_key")
        return {"api_key": key} if key else {}


def load_config(env: dict | None = None) -> dict:
    """Config effettiva: default + /etc/hermes/laya-bridge.yaml + env."""
    env = os.environ if env is None else env
    cfg = _deep_merge(DEFAULT_CONFIG, _load_yaml_file(CONFIG_PATH))
    if env.get("HERMES_LAYA_BRIDGE_PORT"):
        try:
            cfg["port"] = int(env["HERMES_LAYA_BRIDGE_PORT"])
        except ValueError:
            pass
    if env.get("HERMES_GPU_MANAGER_KEY") and not cfg.get("manager_key"):
        cfg["manager_key"] = env["HERMES_GPU_MANAGER_KEY"]
    cred_manager = _credential_file("manager.key")
    if cred_manager and not cfg.get("manager_key"):
        cfg["manager_key"] = cred_manager
    cred_app = _credential_file("app.key")
    if cred_app and not cfg.get("app_key"):
        cfg["app_key"] = cred_app
    elif env.get("HERMES_GPU_APP_KEY") and not cfg.get("app_key"):
        cfg["app_key"] = env["HERMES_GPU_APP_KEY"]
    return cfg


def _credential_file(name: str) -> str:
    """Chiave da file root-only via systemd LoadCredential (come il manager)."""
    base = os.environ.get("CREDENTIALS_DIRECTORY", "")
    if not base:
        return ""
    try:
        with open(os.path.join(base, name), "r", encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        return ""


def _manager_config_keys() -> list:
    """Chiavi del manager lette da /etc (api_key + trusted_keys)."""
    for path in MANAGER_CONFIG_PATHS:
        data = _load_yaml_file(path)
        keys = [data.get("api_key") or ""]
        extra = data.get("trusted_keys") or []
        if isinstance(extra, str):
            extra = [extra]
        if isinstance(extra, list):
            keys.extend(k for k in extra if isinstance(k, str))
        keys = [k for k in keys if isinstance(k, str) and k]
        if keys:
            return keys
    return []


def manager_keys(cfg: dict) -> list:
    """Chiavi con cui il bridge parla col manager (per il Bearer in uscita)."""
    keys = [cfg.get("manager_key") or ""]
    keys.extend(_manager_config_keys())
    return [k for k in keys if isinstance(k, str) and k]


def accept_keys(cfg: dict) -> list:
    """Chiavi accettate in ingresso: quelle del bridge, o quelle del manager."""
    keys = [cfg.get("api_key") or ""]
    extra = cfg.get("trusted_keys") or []
    if isinstance(extra, str):
        extra = [extra]
    if isinstance(extra, list):
        keys.extend(k for k in extra if isinstance(k, str))
    keys = [k for k in keys if isinstance(k, str) and k]
    return keys or manager_keys(cfg)


def app_keys(cfg: dict) -> list:
    """Chiave app (utenti): NL completo + submit job. Mai all'agente."""
    keys = [cfg.get("app_key") or ""]
    return [k for k in keys if isinstance(k, str) and k]


def agent_keys(cfg: dict) -> list:
    """Chiavi agente/gateway (solo submit via /laya/jobs): allowlist, non segrete."""
    extra = cfg.get("agent_keys") or []
    if isinstance(extra, str):
        extra = [extra]
    if not isinstance(extra, list):
        return []
    return [k for k in extra if isinstance(k, str) and k]


def caller_role(cfg: dict, auth: str) -> str:
    """Ruolo del chiamante: 'app' | 'agent' | ''. Mai entrambi."""
    import hmac as _hmac
    for key in app_keys(cfg):
        if _hmac.compare_digest(auth, f"Bearer {key}"):
            return "app"
    for key in agent_keys(cfg):
        if _hmac.compare_digest(auth, f"Bearer {key}"):
            return "agent"
    return ""


def classify_text(text: str) -> dict:
    """Fallback deterministico testo -> {"intent","mode"}. Mai solleva."""
    try:
        if not isinstance(text, str):
            return {"intent": "unknown", "mode": None}
        low = text.lower()
        if any(verb in low for verb in _REBOOT_VERBS) and any(
            target in low for target in _REBOOT_TARGETS
        ):
            return {"intent": "reboot", "mode": None}
        for mode, keywords in _MODE_KEYWORDS:
            for keyword in keywords:
                if keyword in low:
                    return {"intent": "mode", "mode": mode}
        for keyword in _STATUS_KEYWORDS:
            if keyword in low:
                return {"intent": "status", "mode": None}
        return {"intent": "unknown", "mode": None}
    except Exception:  # noqa: BLE001
        return {"intent": "unknown", "mode": None}


_SPLIT_PATTERN = re.compile(
    r"\s*(?:;|\n|\+|,|\be poi\b|\bpoi\b|\be\b|\bquindi\b|\bper favore\b)\s*"
)


def split_commands(text: str, limit: int = 4) -> list:
    """Puro/testabile: divide un messaggio in sotto-comandi.

    "carica l'llm e metti in auto" -> ["carica l'llm", "metti in auto"].
    Mai solleva; un solo segmento se non ci sono separatori.
    """
    try:
        if not isinstance(text, str):
            return []
        parts = [p.strip(" ,;\t\n") for p in _SPLIT_PATTERN.split(text)]
        cleaned = [p for p in (q.strip() for q in parts) if p]
        if not cleaned:
            return []
        return cleaned[: max(1, int(limit))]
    except Exception:  # noqa: BLE001
        stripped = str(text or "").strip()
        return [stripped] if stripped else []


def parse_laya_choice(data: dict, qid: str = LAYA_QID) -> tuple[str, float]:
    """Estrae (choice, confidence) da una risposta /api/decide. Mai solleva."""
    try:
        answers = data.get("answers") if isinstance(data, dict) else None
        answer = answers.get(qid) if isinstance(answers, dict) else None
        choice = answer.get("choice") if isinstance(answer, dict) else None
        if not isinstance(choice, str):
            return "", 0.0
        try:
            confidence = float(answer.get("confidence", 0.0))  # type: ignore[union-attr]
        except (TypeError, ValueError):
            confidence = 0.0
        return choice, confidence
    except Exception:  # noqa: BLE001
        return "", 0.0


def intent_from_choice(choice: str) -> tuple[str | None, str | None]:
    """laya choice -> (intent, mode). Sconosciuta -> (None, None)."""
    if not isinstance(choice, str):
        return None, None
    intent, mode = CHOICE_TO_INTENT.get(choice, (None, None))
    return intent, mode


def compose_reply(mode: str, outcome: dict) -> str:
    """Risposta breve in italiano a partire dall'esito del force."""
    stage = (outcome or {}).get("stage")
    ready = (outcome or {}).get("backend_ready")
    if stage == "forced":
        if ready is True:
            if mode == "MEDIA":
                return "Comfy attivo e raggiungibile."
            if mode == "DIRECT":
                return "Comfy diretto attivo e raggiungibile."
            if mode == "LLM":
                return "LLM caricato e raggiungibile."
            return f"Fatto: il manager e' passato in {mode}."
        if ready is False:
            if mode in ("MEDIA", "DIRECT"):
                return (f"Ho impostato {mode}, ma Comfy non risponde ancora: "
                        f"sta caricando, ricontrolla tra un minuto.")
            if mode == "LLM":
                return ("Ho impostato LLM, ma non risponde ancora: "
                        "sta caricando, ricontrolla tra un minuto.")
        return f"Fatto: il manager e' passato in {mode}."
    if stage == "forced_after_restart":
        return f"Il manager non rispondeva: l'ho riavviato e l'ho forzato in {mode}."
    if stage == "forced_after_llm_restart":
        return (f"Il manager non rispondeva: ho riavviato direttamente l'LLM "
                f"e l'ho forzato in {mode}.")
    if stage == "auth":
        return "La chiave del manager non e' valida: non posso forzare il modo."
    if stage == "bad_mode":
        return "Non ho riconosciuto il modo richiesto."
    if stage == "manager_down":
        return "Il manager non risponde e non sono riuscito a riavviarlo."
    return "Non sono riuscita a completare il comando."


def summarize_status(status: dict) -> str:
    """Riassunto breve dello stato del manager. Mai solleva."""
    try:
        if not isinstance(status, dict):
            return "Stato non disponibile."
        desired = status.get("desired_mode", "?")
        state = status.get("current_state", "?")
        queue = status.get("queue_length", 0)
        parts = [f"Manager in {state} (modo {desired})",
                 f"coda {int(queue) if isinstance(queue, (int, float)) else 0}"]
        media_models = status.get("media_loaded_models") or []
        if isinstance(media_models, list) and media_models:
            shown = ", ".join(str(m) for m in media_models[:3])
            more = f" +{len(media_models) - 3}" if len(media_models) > 3 else ""
            parts.append(f"su GPU: video {shown}{more}")
        elif status.get("llm_loaded"):
            llm_model = str(status.get("llm_model") or "").strip()
            parts.append(f"su GPU: LLM {llm_model}".rstrip() if llm_model
                         else "su GPU: LLM")
        else:
            parts.append("GPU senza workload riconosciuto")
        return ", ".join(parts) + "."
    except Exception:  # noqa: BLE001
        return "Stato non disponibile."


# ------------------------------------------------------------------- I/O ---

def http_json(method: str, url: str, payload: dict | None = None,
              headers: dict | None = None, timeout: int = 15) -> tuple[int, object]:
    """Richiesta JSON best-effort. Ritorna (status, dict|str). 0 su errore rete."""
    data = None
    hdrs = {"Content-Type": "application/json"}
    if headers:
        hdrs.update(headers)
    try:
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", "replace")
            status = resp.status
    except urllib.error.HTTPError as exc:
        try:
            body = exc.read().decode("utf-8", "replace")
        except Exception:  # noqa: BLE001
            body = ""
        status = exc.code
    except Exception as exc:  # noqa: BLE001 - rete/manager giu'
        return 0, str(exc)
    try:
        return status, json.loads(body) if body else {}
    except json.JSONDecodeError:
        return status, body


def run_cmd(cmd: list, timeout: int = 60) -> tuple[int, str]:
    """Esegue un comando (sudo -n systemctl ...). Mai solleva."""
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return proc.returncode, (proc.stdout + proc.stderr)[-2000:]
    except subprocess.TimeoutExpired:
        return 124, "timeout"
    except Exception as exc:  # noqa: BLE001
        return 127, str(exc)


def _auth_headers(cfg: dict) -> dict:
    keys = manager_keys(cfg)
    return {"Authorization": f"Bearer {keys[0]}"} if keys else {}


# ------------------------------------------------------------- comandi ---

def ask_laya(cfg: dict, text: str, http=http_json) -> tuple[bool, str, float]:
    """Chiede a laya di classificare il testo. (ok, choice, confidence)."""
    url = str(cfg.get("laya_url", "")).rstrip("/") + "/api/decide"
    payload = {
        "model": cfg.get("laya_model", "laya:multilingual"),
        "state": f"TESTO UTENTE: {str(text)[:2000]}",
        "questions": LAYA_QUESTIONS,
    }
    try:
        code, data = http("POST", url, payload, {}, int(cfg.get("timeout", 15)))
    except Exception:  # noqa: BLE001
        return False, "", 0.0
    if code != 200 or not isinstance(data, dict):
        return False, "", 0.0
    choice, confidence = parse_laya_choice(data)
    return bool(choice), choice, confidence


def get_status(cfg: dict, http=http_json) -> tuple[bool, dict]:
    url = str(cfg.get("manager_url", "")).rstrip("/") + "/status"
    try:
        code, data = http("GET", url, None, _auth_headers(cfg), int(cfg.get("timeout", 15)))
    except Exception:  # noqa: BLE001
        return False, {}
    return (code == 200 and isinstance(data, dict)), (data if isinstance(data, dict) else {})


def restart_manager(cfg: dict, http=http_json, runner=run_cmd, sleeper=time.sleep,
                    clock=time.monotonic) -> bool:
    """Riavvia il servizio manager e attende che /status risponda. Mai solleva."""
    service = str(cfg.get("manager_service", "hermes-gpu-manager.service"))
    log.warning("manager non raggiungibile: riavvio %s", service)
    try:
        runner(["sudo", "-n", "systemctl", "restart", service], 60)
    except Exception:  # noqa: BLE001
        pass
    deadline_s = float(cfg.get("manager_restart_timeout", 60))
    waited = 0.0
    while waited < deadline_s:
        sleeper(2.0)
        waited += 2.0
        ok, _ = get_status(cfg, http)
        if ok:
            return True
    return False


def wait_backend(cfg: dict, mode: str, http=http_json,
                 sleeper=time.sleep) -> tuple[bool, dict | None]:
    """Attende che il backend del modo richiesto risponda. Mai solleva.

    Poll limitati (default 12 x 5s = 60s max): con sleeper finto i test
    restano istantanei. AUTO non attende mai. Ritorna (pronto, status|None).
    """
    try:
        if mode == "AUTO":
            return True, None
        polls = int(cfg.get("backend_wait_polls", 12))
        interval = float(cfg.get("backend_wait_interval_s", 5.0))
        polls = max(1, min(polls, 60))
        interval = max(0.0, min(interval, 30.0))
        last: dict | None = None
        for _ in range(polls):
            ok, status = get_status(cfg, http)
            last = status if isinstance(status, dict) else None
            if not ok or last is None:
                try:
                    sleeper(interval)
                except Exception:  # noqa: BLE001
                    pass
                continue
            if mode in ("MEDIA", "DIRECT"):
                if bool(last.get("media_online")):
                    return True, last
            elif mode == "LLM":
                if bool(last.get("llm_online")):
                    return True, last
            else:
                return True, last
            try:
                sleeper(interval)
            except Exception:  # noqa: BLE001
                pass
        return False, last
    except Exception:  # noqa: BLE001
        return False, None


def force_mode(cfg: dict, mode: str, reason: str, http=http_json, runner=run_cmd,
               sleeper=time.sleep, clock=time.monotonic) -> dict:
    """Forza il modo; se il manager non risponde, riavvia+e riprova. Mai solleva."""
    url = str(cfg.get("manager_url", "")).rstrip("/") + "/force"
    headers = _auth_headers(cfg)
    payload = {"mode": mode, "reason": str(reason)[:200]}
    timeout = int(cfg.get("timeout", 15))

    code, data = http("POST", url, payload, headers, timeout)
    if code == 200:
        state = data.get("state") if isinstance(data, dict) else None
        return {"ok": True, "stage": "forced", "status": code, "manager_state": state}
    if code == 401:
        return {"ok": False, "stage": "auth", "status": code}
    if code == 422:
        return {"ok": False, "stage": "bad_mode", "status": code}
    if code and code < 500:
        # 4xx inatteso: non e' un problema di processo, non riavviare.
        return {"ok": False, "stage": "rejected", "status": code}

    # 0 (manager giu') o 5xx: tenta il riavvio del manager e riprova.
    restarted = restart_manager(cfg, http, runner, sleeper, clock)
    if restarted:
        code2, data2 = http("POST", url, payload, headers, timeout)
        if code2 == 200:
            state = data2.get("state") if isinstance(data2, dict) else None
            return {"ok": True, "stage": "forced_after_restart", "status": code2,
                    "manager_state": state}
        code = code2 or code
    if mode == "LLM":
        # Ultima spiaggia per l'LLM: riavvia direttamente tabby.
        llm_service = str(cfg.get("llm_service", "hermes-tabby.service"))
        log.warning("manager ancora giu': riavvio diretto %s", llm_service)
        try:
            runner(["sudo", "-n", "systemctl", "restart", llm_service], 60)
        except Exception:  # noqa: BLE001
            pass
        return {"ok": True, "stage": "forced_after_llm_restart", "status": code,
                "manager_state": None, "best_effort": True}
    return {"ok": False, "stage": "manager_down", "status": code}


def _classify_segment(cfg: dict, segment: str, http=http_json) -> dict:
    """Puro-ish/testabile: un segmento -> {intent, mode, backend, choice}."""
    backend = "rules"
    intent: str | None = None
    mode: str | None = None
    choice = ""
    try:
        laya_ok, choice, confidence = ask_laya(cfg, segment, http)
    except Exception:  # noqa: BLE001
        laya_ok, choice, confidence = False, "", 0.0
    try:
        min_conf = float(cfg.get("laya_min_confidence", 0.7))
    except (TypeError, ValueError):
        min_conf = 0.7
    if laya_ok and confidence >= min_conf:
        intent, mode = intent_from_choice(choice)
        if intent:
            backend = "laya"
    if not intent:
        verdict = classify_text(segment)
        intent = verdict.get("intent")
        mode = verdict.get("mode")
        backend = "rules"
    return {"intent": intent, "mode": mode, "backend": backend, "choice": choice}


def _handle_single(cfg: dict, text: str, *, execute: bool = True, http=http_json,
                   runner=run_cmd, sleeper=time.sleep, clock=time.monotonic) -> dict:
    """Un singolo comando (stessa semantica di prima del multi-intent)."""
    classified = _classify_segment(cfg, text, http)
    intent = classified["intent"]
    mode = classified["mode"]
    backend = classified["backend"]
    choice = classified["choice"]

    if intent == "status":
        ok, status = get_status(cfg, http)
        reply = summarize_status(status) if ok else "Il manager non risponde."
        return {"ok": ok, "reply": reply, "intent": "status", "mode": None,
                "backend": backend, "manager_reachable": ok}

    if intent == "reboot":
        if not execute:
            return {"ok": True, "intent": "reboot", "mode": None, "backend": backend,
                    "reply": "Ok, riavvierei il manager.", "executed": False}
        restarted = restart_manager(cfg, http, runner, sleeper, clock)
        reply = "Manager riavviato, torna tra poco." if restarted else "Riavvio manager fallito."
        return {"ok": restarted, "reply": reply, "intent": "reboot", "mode": None,
                "backend": backend, "manager_reachable": restarted, "executed": True}

    if intent != "mode" or mode is None:
        return {"ok": False, "intent": "unknown", "mode": None, "backend": backend,
                "reply": compose_reply("", {"stage": "bad_mode"})}

    if not execute:
        return {"ok": True, "intent": "mode", "mode": mode, "backend": backend,
                "reply": f"Ok, forzerei {mode}.", "executed": False}

    outcome = force_mode(cfg, mode, text, http, runner, sleeper, clock)
    if outcome.get("ok"):
        ready, _snapshot = wait_backend(cfg, mode, http, sleeper)
        outcome["backend_ready"] = ready
    return {
        "ok": bool(outcome.get("ok")),
        "reply": compose_reply(mode, outcome),
        "intent": "mode",
        "mode": mode,
        "backend": backend,
        "choice": choice if backend == "laya" else "",
        "executed": True,
        "manager": outcome,
    }


# Preset sottomettibili via /laya/jobs (stessi del manager; raw workflow mai).
LAYA_SUBMIT_PRESETS = {
    "create_image": "image",
    "edit_image": "image",
    "journey_video_preview": "video",
    "journey_video_quality": "video",
    "journey_video_first_last": "video",
    "journey_video_reference": "video",
    "journey_video_reference_fast": "video",
    "journey_video_reframe916": "video",
}

LAYA_SUBMIT_MAX_PARAMS_BYTES = 32 * 1024
LAYA_SUBMIT_MAX_PROMPT_CHARS = 4000


def submit_preset_job(cfg: dict, preset: str, params: dict | None, http=http_json) -> dict:
    """Sottomette un preset al manager. Solo preset allowlist, mai workflow raw."""
    name = str(preset or "").strip()
    kind = LAYA_SUBMIT_PRESETS.get(name)
    if not kind:
        return {"ok": False, "error": f"preset non consentito: {name or '?'}", "status": 400}
    clean = dict(params) if isinstance(params, dict) else {}
    try:
        if len(json.dumps(clean, ensure_ascii=False)) > LAYA_SUBMIT_MAX_PARAMS_BYTES:
            return {"ok": False, "error": "params troppo grandi", "status": 413}
    except Exception:  # noqa: BLE001
        return {"ok": False, "error": "params illeggibili", "status": 400}
    prompt = clean.get("prompt")
    if isinstance(prompt, str) and len(prompt) > LAYA_SUBMIT_MAX_PROMPT_CHARS:
        return {"ok": False, "error": "prompt troppo lungo", "status": 413}
    url = str(cfg.get("manager_url", "")).rstrip("/") + f"/jobs/{kind}"
    code, data = http("POST", url, {"preset": name, "params": clean}, _auth_headers(cfg), 30)
    if code in (200, 202) and isinstance(data, dict) and data.get("job_id"):
        return {"ok": True, "job_id": data["job_id"], "status": data.get("status", "queued"),
                "preset": name, "code": code}
    detail = ""
    if isinstance(data, dict):
        detail = str(data.get("error") or data.get("detail") or "")[:200]
    return {"ok": False, "error": detail or "submit fallito", "status": code}


def handle_command(cfg: dict, text: str, *, execute: bool = True, http=http_json,
                   runner=run_cmd, sleeper=time.sleep, clock=time.monotonic) -> dict:
    """Interpreta il testo e (se `execute`) forza i modi. Mai solleva.

    Un messaggio puo' contenere piu' comandi ("carica l'llm e metti in
    auto"): vengono classificati tutti ed eseguiti in ordine. Con un solo
    segmento il risultato e' identico a prima (`actions` con un elemento).
    Ritorna {ok, reply, intent, mode, modes, actions, backend, ...}:
    `backend` e' "laya", "rules" o combinati con "+".
    """
    text = str(text or "").strip()
    if not text:
        return {"ok": False, "reply": "Dimmi cosa fare (es. 'metti l'LLM ready', "
                                      "'attiva comfy', 'metti in auto').",
                "intent": "unknown", "mode": None, "backend": "rules"}

    segments = split_commands(text)
    if len(segments) <= 1:
        single = _handle_single(cfg, text, execute=execute, http=http,
                                runner=runner, sleeper=sleeper, clock=clock)
        single["modes"] = [single["mode"]] if single.get("mode") else []
        single["actions"] = [dict(single, segment=text)]
        return single

    classified = [_classify_segment(cfg, seg, http) for seg in segments]
    backends = "+".join(dict.fromkeys(c["backend"] for c in classified))
    modes = [c["mode"] for c in classified
             if c.get("intent") == "mode" and c.get("mode")]

    if not execute:
        previews = []
        for seg, found in zip(segments, classified):
            if found["intent"] == "mode" and found["mode"]:
                previews.append(f"forzerei {found['mode']}")
            elif found["intent"] == "status":
                previews.append("mostrerei lo stato")
            elif found["intent"] == "reboot":
                previews.append("riavvierei il manager")
            else:
                previews.append(f"non ho capito {seg!r}")
        ok = all(f["intent"] in ("mode", "status", "reboot") for f in classified)
        actions = [dict(segment=seg, intent=f["intent"], mode=f["mode"],
                        backend=f["backend"], executed=False)
                   for seg, f in zip(segments, classified)]
        return {"ok": ok, "reply": "Ok, " + "; ".join(previews) + ".",
                "intent": "multi", "mode": modes[-1] if modes else None,
                "modes": modes, "actions": actions, "backend": backends,
                "executed": False}

    actions = []
    for seg, found in zip(segments, classified):
        if found["intent"] == "status":
            ok, status = get_status(cfg, http)
            actions.append({
                "segment": seg, "intent": "status", "mode": None,
                "backend": found["backend"],
                "ok": ok,
                "reply": summarize_status(status) if ok else "Il manager non risponde.",
                "executed": True,
            })
        elif found["intent"] == "reboot":
            restarted = restart_manager(cfg, http, runner, sleeper, clock)
            actions.append({
                "segment": seg, "intent": "reboot", "mode": None,
                "backend": found["backend"],
                "ok": restarted,
                "reply": "Manager riavviato, torna tra poco." if restarted else "Riavvio manager fallito.",
                "executed": True,
            })
        elif found["intent"] == "mode" and found["mode"]:
            outcome = force_mode(cfg, found["mode"], seg, http, runner, sleeper, clock)
            if outcome.get("ok"):
                ready, _snapshot = wait_backend(cfg, found["mode"], http, sleeper)
                outcome["backend_ready"] = ready
            actions.append({
                "segment": seg, "intent": "mode", "mode": found["mode"],
                "backend": found["backend"],
                "choice": found["choice"] if found["backend"] == "laya" else "",
                "ok": bool(outcome.get("ok")),
                "reply": compose_reply(found["mode"], outcome),
                "executed": True,
                "manager": outcome,
            })
        else:
            actions.append({
                "segment": seg, "intent": "unknown", "mode": None,
                "backend": found["backend"], "ok": False,
                "reply": f"Non ho capito {seg!r}.",
                "executed": False,
            })
    mode_actions = [a for a in actions if a["intent"] == "mode"]
    status_actions = [a for a in actions if a["intent"] == "status"]
    reboot_actions = [a for a in actions if a["intent"] == "reboot"]
    ok = all(a["ok"] for a in mode_actions) and all(a["ok"] for a in status_actions) \
        and all(a["ok"] for a in reboot_actions)
    if not mode_actions and not status_actions and not reboot_actions:
        ok = False
    reply = " ".join(a["reply"] for a in actions)
    return {"ok": ok, "reply": reply, "intent": "multi",
            "mode": modes[-1] if modes else None, "modes": modes,
            "actions": actions, "backend": backends, "executed": True}


# --------------------------------------------------------------- HTTP ---

class _Handler(BaseHTTPRequestHandler):
    server_version = "hermes-laya-bridge/1.0"
    cfg: dict = dict(DEFAULT_CONFIG)

    def log_message(self, fmt: str, *args) -> None:  # noqa: A003 - firma stdlib
        log.info("%s - %s", self.address_string(), fmt % args)

    def _send(self, code: int, obj: dict) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:  # noqa: BLE001 - client sparito
            pass

    def _authorized(self) -> bool:
        keys = accept_keys(self.cfg)
        if not keys:
            return False
        auth = self.headers.get("authorization", "")
        import hmac as _hmac

        return any(_hmac.compare_digest(auth, f"Bearer {k}") for k in keys)

    def _read_json(self) -> dict:
        try:
            length = int(self.headers.get("content-length") or 0)
            raw = self.rfile.read(length) if length > 0 else b""
            data = json.loads(raw.decode("utf-8")) if raw else {}
            return data if isinstance(data, dict) else {}
        except Exception:  # noqa: BLE001
            return {}

    def do_GET(self) -> None:  # noqa: N802 - nome stdlib
        if self.path.split("?")[0] == "/health":
            self._send(200, {"service": APP_NAME, "ok": True})
            return
        if self.path.split("?")[0] == "/":
            self._send(200, {"service": APP_NAME, "chat": "POST /laya"})
            return
        if self.path.split("?")[0] == "/laya/jobs":
            return self._get_job()
        self._send(404, {"ok": False, "error": "not found"})

    def do_POST(self) -> None:  # noqa: N802 - nome stdlib
        path = self.path.split("?")[0]
        if path == "/laya/jobs":
            return self._post_jobs()
        if path != "/laya":
            self._send(404, {"ok": False, "error": "not found"})
            return
        if not app_keys(self.cfg):
            self._send(500, {"ok": False, "error": "bridge api_key non configurata"})
            return
        if caller_role(self.cfg, self.headers.get("authorization", "")) != "app":
            self._send(401, {"ok": False, "error": "invalid bridge api key"})
            return
        payload = self._read_json()
        text = payload.get("text", "")
        execute = payload.get("execute", True) is not False
        try:
            result = handle_command(self.cfg, text, execute=execute)
        except Exception as exc:  # noqa: BLE001 - il canale non risponde mai 500
            log.warning("handle_command fallito: %r", exc)
            result = {"ok": False, "reply": "Errore interno.", "intent": "unknown"}
        self._send(200, result)

    def _get_job(self) -> None:
        """Stato job (solo lettura) per app e agente. Mai workflow interi."""
        import re as _re
        import urllib.parse as _urlparse
        role = caller_role(self.cfg, self.headers.get("authorization", ""))
        if role not in ("app", "agent"):
            if not app_keys(self.cfg) and not agent_keys(self.cfg):
                self._send(500, {"ok": False, "error": "bridge api_key non configurata"})
            else:
                self._send(401, {"ok": False, "error": "invalid bridge api key"})
            return
        query = _urlparse.urlparse(self.path).query
        params = _urlparse.parse_qs(query)
        job_id = (params.get("job_id") or [""])[0].strip()
        if not _re.fullmatch(r"[A-Za-z0-9_-]{1,64}", job_id):
            self._send(400, {"ok": False, "error": "bad job_id"})
            return
        url = str(self.cfg.get("manager_url", "")).rstrip("/") + "/jobs/" + job_id
        code, data = http_json("GET", url, None, _auth_headers(self.cfg), 15)
        if code != 200 or not isinstance(data, dict):
            self._send(502, {"ok": False, "error": "manager non raggiungibile", "status": code})
            return
        safe = {k: data.get(k) for k in (
            "job_id", "status", "progress", "phase", "preset", "kind",
            "error", "created_at", "updated_at", "result_paths")}
        safe["result_paths"] = safe.get("result_paths") if isinstance(safe.get("result_paths"), list) else []
        self._send(200, {"ok": True, **{k: v for k, v in safe.items() if v is not None}})

    def _post_jobs(self) -> None:
        """Submit preset per app e agente. Mai workflow raw, mai NL/modi."""
        role = caller_role(self.cfg, self.headers.get("authorization", ""))
        if role not in ("app", "agent"):
            if not app_keys(self.cfg) and not agent_keys(self.cfg):
                self._send(500, {"ok": False, "error": "bridge api_key non configurata"})
            else:
                self._send(401, {"ok": False, "error": "invalid bridge api key"})
            return
        try:
            length = int(self.headers.get("content-length") or 0)
        except (TypeError, ValueError):
            length = 0
        if length > LAYA_SUBMIT_MAX_PARAMS_BYTES + 1024:
            self._send(413, {"ok": False, "error": "body troppo grande"})
            return
        payload = self._read_json()
        result = submit_preset_job(self.cfg, payload.get("preset", ""),
                                   payload.get("params", {}))
        self._send(200 if result.get("ok") else result.get("status", 400), result)


def serve(cfg: dict | None = None) -> None:
    logging.basicConfig(level=logging.INFO, stream=sys.stdout,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")
    cfg = load_config() if cfg is None else cfg
    handler = type("Handler", (_Handler,), {"cfg": cfg})
    host = str(cfg.get("host", "0.0.0.0"))
    port = int(cfg.get("port", 8645))
    httpd = ThreadingHTTPServer((host, port), handler)
    log.info("%s in ascolto su %s:%d (manager=%s laya=%s)", APP_NAME, host, port,
             cfg.get("manager_url"), cfg.get("laya_url"))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    serve()
