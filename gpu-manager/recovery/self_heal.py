"""Motore di self-heal deterministico del supervisore (Fase 1).

Regole PURE snapshot -> `Action`: nessun fastapi, nessun import di `manager`,
solo stdlib (`json` + il vocabolario di `recovery.types`). Una regola non tocca
MAI la GPU: DESCRIVE un'azione; e' il manager, a valle, a eseguirla in modo
fail-closed. Cosi ogni decisione e rivedibile a mano e testabile senza
infrastruttura (nessuna GPU, nessun server, nessun fastapi).

Confine col manager (evita duplicazioni): il manager gia' recupera da ERROR
(throttle 120s), mette in cooldown il media dopo N fallimenti, fallisce il
current job stale quando il backend e' idle (comfy_busy False) e ripristina
l'LLM via watchdog. Queste regole coprono lacune reali diverse:
  - job `running` orfani (tracking perso, current job azzerato o mai adottato);
  - retry di job persi da ComfyUI (il manager li fallisce e basta);
  - burst di errori che merita un avviso all'utente;
  - transizioni bloccate / media giu' (opt-in: puo' sovrapporsi a _drive/watchdog).

Contratto snapshot (congelato per le fasi 1-4). Chiavi:
  now, wall_now, state, state_since, desired, last_error, current_job,
  current_prompt_id, queue_len, llm_loaded, llm_serving, media_online,
  media_consec_failures, media_cooldown_until, jobs (job_row-like),
  recent_events ([{ts,kind,severity,message}]), limits.
Uno snapshot malformato (chiavi mancanti o tipi sbagliati) non fa scattare
nessuna regola e non solleva mai: `evaluate` e' difensiva per costruzione.
"""

from __future__ import annotations

import json
from typing import Any

from .types import Action

# Soglie di default. Il manager le ri-esporta nella config
# (`supervisor.self_heal`) cosi un'installazione puo' sovrascriverle; `evaluate`
# fonde sempre `DEFAULT_LIMITS` con lo snapshot, quindi una chiave assente non
# cambia il comportamento. Valori in secondi salvo dove indicato.
DEFAULT_LIMITS = {
    "stale_job_s": 600.0,           # R1/R4: job fermo oltre 10 min
    "media_down_s": 180.0,          # R6: media giu' oltre 3 min
    "error_burst_n": 3,             # R3: N errori...
    "error_burst_window_s": 600.0,  # ...in 10 min = burst
    "max_job_retries": 2,           # R2: retry massimi di un job perso
    "llm_transition_s": 180.0,      # R5: LLM_UNLOADING bloccato oltre 3 min
    "cooldown_media_s": 1200.0,     # R6: cooldown applicato al media
    # R5/R6 sono OPT-IN (default False): la loro azione (restore_llm /
    # cooldown_media) puo' sovrapporsi a watchdog e _drive, quindi si attivano
    # solo dopo averle osservate in shadow. Fail-closed: spente = nessuna azione.
    "enable_media_rules": False,
    # R4 (current job) e' ATTIVA di default: quando il backend media e'
    # definitivamente giu' (media_online False) `_drive` fail-close a "busy" e
    # non recupera il job appeso. Disattivabile per singola installazione.
    "enable_current_job_rule": True,
}

# Messaggio stabile (non varia da tick a tick): niente spam di testi diversi.
_ERROR_BURST_MESSAGE = "GPU manager in ERROR: burst di errori ripetuti"


# ------------------------------------------------------------- accessori ---
# Piccoli helper difensivi: qualunque chiave mancante o tipo inatteso degrada a
# un default, mai a un'eccezione. Le regole restano pure e deterministiche.

def _limits(snapshot: Any) -> dict:
    """DEFAULT_LIMITS fuso con `snapshot["limits"]` (override per installazione)."""
    merged = dict(DEFAULT_LIMITS)
    raw = snapshot.get("limits") if isinstance(snapshot, dict) else None
    if isinstance(raw, dict):
        merged.update(raw)
    return merged


def _num(mapping: Any, key: str, default: float) -> float:
    if not isinstance(mapping, dict):
        return float(default)
    raw = mapping.get(key)
    if raw is None:
        return float(default)
    try:
        return float(raw)
    except (TypeError, ValueError):
        return float(default)


def _int(mapping: Any, key: str, default: int) -> int:
    raw = mapping.get(key) if isinstance(mapping, dict) else None
    if isinstance(raw, bool):
        return int(raw)
    try:
        return int(raw)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return int(default)


def _str(mapping: Any, key: str, default: str = "") -> str:
    raw = mapping.get(key) if isinstance(mapping, dict) else None
    return raw if isinstance(raw, str) else default


def _list(snapshot: Any, key: str) -> list:
    raw = snapshot.get(key) if isinstance(snapshot, dict) else None
    return raw if isinstance(raw, list) else []


# ---------------------------------------------------- helper condivisi ---
# Nozioni di "job stale" e "job perso" estratte in helper PURI, cosi' le regole
# (R1/R2) e l'operator laya (Fase 3, `recovery.operator`) condividono la stessa
# definizione invece di duplicarla (e di divergere). Restano difensive: mai
# sollevano, un bersaglio assente ritorna vuoto/None.

def stale_job_ids(snapshot: Any) -> list[str]:
    """Id dei job `running` orfani: non current, non in fase "saving", fermi.

    STESSA nozione di job stale di R1: `status == "running"`, diverso dal
    current job, `wall_now - updated_at > stale_job_s` e NON in fase "saving"
    (derivative ffmpeg: nessun update by design). Ordine = ordine in `jobs`.
    """
    if not isinstance(snapshot, dict):
        return []
    limits = _limits(snapshot)
    stale = _num(limits, "stale_job_s", DEFAULT_LIMITS["stale_job_s"])
    wall_now = _num(snapshot, "wall_now", 0.0)
    if wall_now <= 0 or stale <= 0:
        return []
    current = snapshot.get("current_job")
    ids: list[str] = []
    for job in _list(snapshot, "jobs"):
        if not isinstance(job, dict):
            continue
        if _str(job, "status") != "running":
            continue
        jid = job.get("job_id")
        if not isinstance(jid, str) or not jid or jid == current:
            continue
        if _str(job, "phase") == "saving":
            continue
        updated = _num(job, "updated_at", 0.0)
        if updated <= 0:
            continue
        if wall_now - updated > stale:
            ids.append(jid)
    return ids


def lost_job_id(snapshot: Any) -> str | None:
    """Il piu' vecchio job `failed` perso da ComfyUI sotto il cap di retry.

    STESSA nozione di R2: `status == "failed"`, error contiene "lost by
    comfyui", `retries < max_job_retries` e diverso dal current job. A parita'
    di `created_at` vince il job_id minore (ordinamento stabile).
    """
    if not isinstance(snapshot, dict):
        return None
    limits = _limits(snapshot)
    max_retries = _int(limits, "max_job_retries", DEFAULT_LIMITS["max_job_retries"])
    current = snapshot.get("current_job")
    candidates: list[tuple[float, str]] = []
    for job in _list(snapshot, "jobs"):
        if not isinstance(job, dict):
            continue
        if _str(job, "status") != "failed":
            continue
        if "lost by comfyui" not in _str(job, "error").lower():
            continue
        retries = _int(job, "retries", 0)
        if retries >= max_retries:
            continue
        jid = job.get("job_id")
        if not isinstance(jid, str) or not jid or jid == current:
            continue
        candidates.append((_num(job, "created_at", 0.0), jid))
    if not candidates:
        return None
    candidates.sort()  # il piu' vecchio (created_at), tie-break per job_id
    return candidates[0][1]


# ----------------------------------------------------------------- regole ---
# Ogni regola e' pura `_rule_*(snapshot) -> Action | None`: None = non scatta.

def _rule_orphan_jobs(snapshot: Any) -> Action | None:
    """R1: job `running` diverso dal current, senza aggiornamenti da troppo.

    Copre il tracking orfano: una riga resta `running` ma nessun worker la
    aggiorna (crash, restart con boot_id non rilevato, current job azzerato).
    """
    if not isinstance(snapshot, dict):
        return None
    limits = _limits(snapshot)
    stale = _num(limits, "stale_job_s", DEFAULT_LIMITS["stale_job_s"])
    ids = stale_job_ids(snapshot)
    if not ids:
        return None
    return Action(
        name="cancel_stale_jobs",
        params={"job_ids": ids, "cause": "orphan"},
        reason=f"{len(ids)} job orfani senza aggiornamenti da oltre {stale:.0f}s",
        confidence=0.9,
        source="rules",
    )


def _rule_retry_lost_job(snapshot: Any) -> Action | None:
    """R2: ri-accoda UN job perso da ComfyUI sotto il limite di retry.

    `run_media_job` marca i job persi come failed e si ferma: senza questo, un
    guasto transitorio del backend costa all'utente un re-submit manuale.
    """
    jid = lost_job_id(snapshot)
    if jid is None:
        return None
    return Action(
        name="retry_job",
        params={"job_id": jid},
        reason="job perso da ComfyUI sotto il limite di retry",
        confidence=0.8,
        source="rules",
    )


def _rule_error_burst(snapshot: Any) -> Action | None:
    """R3: troppi errori recenti mentre lo state e' ERROR -> avvisa l'utente."""
    if not isinstance(snapshot, dict):
        return None
    if _str(snapshot, "state") != "ERROR":
        return None
    limits = _limits(snapshot)
    threshold = _int(limits, "error_burst_n", DEFAULT_LIMITS["error_burst_n"])
    window = _num(limits, "error_burst_window_s", DEFAULT_LIMITS["error_burst_window_s"])
    wall_now = _num(snapshot, "wall_now", 0.0)
    if wall_now <= 0 or threshold <= 0 or window <= 0:
        return None
    count = 0
    for event in _list(snapshot, "recent_events"):
        if not isinstance(event, dict):
            continue
        if _str(event, "kind") != "error":
            continue
        ts = _num(event, "ts", 0.0)
        if ts > 0 and 0 <= wall_now - ts <= window:
            count += 1
    if count < threshold:
        return None
    return Action(
        name="notify_user",
        params={"message": _ERROR_BURST_MESSAGE},
        reason=f"{count} errori in {window:.0f}s mentre lo state e' ERROR",
        confidence=0.7,
        source="rules",
    )


def _rule_stuck_current_job(snapshot: Any) -> Action | None:
    """R4: current job `running` fermo con il backend media definitivamente giu'.

    `_drive` fallisce il current stale solo se `comfy_busy()` e' False; con il
    backend irraggiungibile la probe ritorna "busy" (fail-closed) e il job
    resterebbe appeso per sempre. Qui media_online==False e' la prova che il
    backend e' davvero morto, quindi si libera il tracking.
    """
    if not isinstance(snapshot, dict):
        return None
    limits = _limits(snapshot)
    if not bool(limits.get("enable_current_job_rule", True)):
        return None
    if bool(snapshot.get("media_online", False)):
        return None  # backend raggiungibile: decide _drive/comfy, non il supervisore
    current = snapshot.get("current_job")
    if not isinstance(current, str) or not current:
        return None
    stale = _num(limits, "stale_job_s", DEFAULT_LIMITS["stale_job_s"])
    wall_now = _num(snapshot, "wall_now", 0.0)
    if wall_now <= 0 or stale <= 0:
        return None
    job = None
    for candidate in _list(snapshot, "jobs"):
        if isinstance(candidate, dict) and candidate.get("job_id") == current:
            job = candidate
            break
    if job is None or _str(job, "status") != "running":
        return None
    if _str(job, "phase") == "saving":
        return None  # fase derivative (ffmpeg): nessun update by design
    updated = _num(job, "updated_at", 0.0)
    if updated <= 0 or wall_now - updated <= stale:
        return None
    return Action(
        name="cancel_stale_jobs",
        params={"job_ids": [current], "cause": "current-lost"},
        reason=f"current job {current} fermo da oltre {stale:.0f}s con media giu'",
        confidence=0.8,
        source="rules",
    )


def _rule_stuck_unload(snapshot: Any) -> Action | None:
    """R5 (opt-in): LLM_UNLOADING incastrato con l'LLM non caricato -> restore."""
    if not isinstance(snapshot, dict):
        return None
    limits = _limits(snapshot)
    if not bool(limits.get("enable_media_rules", False)):
        return None
    if _str(snapshot, "state") != "LLM_UNLOADING":
        return None
    if bool(snapshot.get("llm_loaded", False)):
        return None
    now = _num(snapshot, "now", 0.0)
    since = _num(snapshot, "state_since", 0.0)
    limit = _num(limits, "llm_transition_s", DEFAULT_LIMITS["llm_transition_s"])
    if now <= 0 or since <= 0 or limit <= 0 or now - since <= limit:
        return None
    return Action(
        name="restore_llm",
        params={},
        reason=f"LLM_UNLOADING bloccato da oltre {limit:.0f}s con LLM non caricato",
        confidence=0.6,
        source="rules",
    )


def _rule_media_down_cooldown(snapshot: Any) -> Action | None:
    """R6 (opt-in): media giu' per troppo tempo in uno state media -> cooldown."""
    if not isinstance(snapshot, dict):
        return None
    limits = _limits(snapshot)
    if not bool(limits.get("enable_media_rules", False)):
        return None
    state = _str(snapshot, "state")
    if state not in ("MEDIA_STARTING", "MEDIA_READY", "MEDIA_BUSY"):
        return None
    if bool(snapshot.get("media_online", False)):
        return None
    now = _num(snapshot, "now", 0.0)
    since = _num(snapshot, "state_since", 0.0)
    down = _num(limits, "media_down_s", DEFAULT_LIMITS["media_down_s"])
    if now <= 0 or since <= 0 or down <= 0 or now - since <= down:
        return None
    seconds = _num(limits, "cooldown_media_s", DEFAULT_LIMITS["cooldown_media_s"])
    return Action(
        name="cooldown_media",
        params={"seconds": seconds},
        reason=f"media giu' da oltre {down:.0f}s in {state}",
        confidence=0.6,
        source="rules",
    )


# Ordine di valutazione (importa per "al massimo 1 azione per tick" nel manager).
_RULES = (
    _rule_orphan_jobs,
    _rule_retry_lost_job,
    _rule_error_burst,
    _rule_stuck_current_job,
    _rule_stuck_unload,
    _rule_media_down_cooldown,
)


def _params_key(params: Any) -> str:
    try:
        return json.dumps(params, sort_keys=True, default=str)
    except (TypeError, ValueError):
        return repr(params)


def evaluate(snapshot: dict) -> list[Action]:
    """Esegue le regole in ordine e ritorna le `Action` (source="rules").

    PURA e deterministica, non solleva MAI: uno snapshot malformato non fa
    scattare regole (chiavi mancanti/tipi inattesi -> default) e le regole
    difettose sono isolate da un try/except. Nessun duplicato per
    (name, params) salvo parametri diversi.
    """
    if not isinstance(snapshot, dict):
        return []
    actions: list[Action] = []
    seen: set[tuple[str, str]] = set()
    for rule in _RULES:
        try:
            action = rule(snapshot)
        except Exception:  # noqa: BLE001 - snapshot ostile: la regola non scatta
            action = None
        if action is None:
            continue
        key = (action.name, _params_key(action.params))
        if key in seen:
            continue
        seen.add(key)
        actions.append(action)
    return actions
