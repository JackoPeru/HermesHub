"""Forzatura di modo richiesta dall'esterno (bridge laya / app). Logica PURA.

Il canale diretto (bridge `hermes-laya-bridge`, app) puo' chiedere al manager di
forzare un modo ("metti l'LLM ready", "attiva comfy", "metti in auto") anche
quando lo stato e' bloccato. La decisione NON e' un pin persistente ("solo
transizione"): si applica ORA scavalcando le guardie che possono incastrare il
manager, poi il manager riprende la sua logica normale.

Qui vive SOLO la parte deterministica e testabile, senza fastapi e senza import
di `manager`:

  - `normalize_mode`: testo/alias -> uno di `FORCE_MODES`, oppure None;
  - `blocker_defaults`/`apply_force_state`: azzera i campi di stato che tengono
    il manager bloccato (cooldown media, throttle di error-retry, gate "tabby
    sta generando", watchdog a 4 fallimenti, miss della direct, cooldown del
    supervisore). `apply_force_state` muta il dict di stato passato (il `_state`
    del manager) e ritorna i nomi dei campi che ha davvero cambiato.

Nessuna delle funzioni solleva mai: input ostile degrada a un risultato valido e
conservativo (None / lista vuota).
"""

from __future__ import annotations

from typing import Any

# Modi che il manager accetta (stesso vocabolario di manager.MODES).
FORCE_MODES = ("LLM", "MEDIA", "AUTO", "DIRECT")

# Alias comodi in minuscolo -> modo canonico. Le chiavi sono normalizzate con
# strip/lower; la mode canonica (es. "llm") vince comunque via .upper().
MODE_ALIASES = {
    "llm": "LLM",
    "ready": "LLM",
    "tabby": "LLM",
    "qwen": "LLM",
    "llm_ready": "LLM",
    "media": "MEDIA",
    "comfy": "MEDIA",
    "comfyui": "MEDIA",
    "video": "MEDIA",
    "immagini": "MEDIA",
    "auto": "AUTO",
    "automatico": "AUTO",
    "automatic": "AUTO",
    "direct": "DIRECT",
    "diretto": "DIRECT",
    "comfy-direct": "DIRECT",
}

# Stati transitori: se il manager vi resta con il lock libero, la transizione
# non e' piu' in corso ed e' considerato incastrato.
TRANSITIONAL_STATES = (
    "LLM_UNLOADING",
    "GPU_FREE",
    "MEDIA_STARTING",
    "MEDIA_STOPPING",
    "LLM_LOADING",
)

# Finestra (secondi) in cui un force "LLM"/"AUTO" scavalca il gate
# "_tabby_recent_progress" di `restore_llm_with_retries` e la soglia a 4
# fallimenti del watchdog: l'utente ha chiesto l'LLM, si ricarica davvero.
LLM_WINDOW_S = 180.0

# Campi azzerati dal force: ognuno da' un motivo per cui il manager puo' non
# applicare la transizione richiesta. `supervisor_cooldown` e' un dict fresco.
_BLOCKER_FIELDS = (
    "last_error",
    "media_consec_failures",
    "media_cooldown_until",
    "cooldown_log_ts",
    "error_retry_ts",
    "llm_notloaded_fails",
    "llm_watch_ts",
    "direct_miss",
)

# Campi transitori da RIMUOVERE (presenza = timer attivo).
_BLOCKER_POP = ("direct_silent_since",)


def blocker_defaults() -> dict:
    """Valori a cui riportare i campi bloccanti (dict fresco a ogni chiamata)."""
    return {
        "last_error": "",
        "media_consec_failures": 0,
        "media_cooldown_until": 0.0,
        "cooldown_log_ts": 0.0,
        "error_retry_ts": 0.0,
        "llm_notloaded_fails": 0,
        "llm_watch_ts": 0.0,
        "direct_miss": 0,
        "supervisor_cooldown": {},
    }


def normalize_mode(raw: Any) -> str | None:
    """Normalizza una mode richiesta a LLM|MEDIA|AUTO|DIRECT, o None. Mai solleva."""
    try:
        if not isinstance(raw, str):
            return None
        token = raw.strip().lower()
        if not token:
            return None
        upper = token.upper()
        if upper in FORCE_MODES:
            return upper
        return MODE_ALIASES.get(token)
    except Exception:  # noqa: BLE001 - input ostile: nessuna mode
        return None


def apply_force_state(
    state: dict,
    mode: str,
    now: float,
    llm_window_s: float = LLM_WINDOW_S,
) -> list:
    """Azzera i blocker su `state` (il `_state` del manager). Ritorna i nomi toccati.

    Imposta anche `force_llm_until` per la sola finestra LLM/AUTO (scavalca i
    gate di restore per `llm_window_s` secondi) e lo rimuove per MEDIA/DIRECT.
    Difensiva: `state` non-dict degrada a lista vuota; campi mancanti sono
    creati; nessuna eccezione.
    """
    cleared: list = []
    try:
        if not isinstance(state, dict):
            return cleared
        for key, value in blocker_defaults().items():
            if state.get(key) != value:
                cleared.append(key)
            state[key] = value
        for key in _BLOCKER_POP:
            if key in state:
                state.pop(key, None)
                cleared.append(key)
        if isinstance(mode, str) and mode.upper() in ("LLM", "AUTO"):
            state["force_llm_until"] = float(now) + float(llm_window_s)
            cleared.append("force_llm_until")
        elif "force_llm_until" in state:
            state.pop("force_llm_until", None)
            cleared.append("force_llm_until")
        return cleared
    except Exception:  # noqa: BLE001 - stato ostile: ritorna cio' che ha toccato
        return cleared
