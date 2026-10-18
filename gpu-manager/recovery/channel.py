"""Canale in linguaggio naturale del supervisore (Fase 4).

Quarto e ultimo stadio del supervisore: un'interfaccia in linguaggio naturale
verso lo STESSO vocabolario delle fasi precedenti. Due garanzie restano
invariate:

  1. il TESTO della risposta e' composto DETERMINISTICAMENTE dal manager,
     grounded sullo snapshot e sull'event store: mai inventato, mai delegato a
     un modello. `answer` riusa `recovery.advisor.build_state` per il riassunto
     di stato e aggiunge narrazione derivata SOLO dai fatti presenti;
  2. l'AZIONE e' filtrata dall'allowlist chiusa e i suoi parametri concreti
     (es. job_id) li risolve `recovery.operator.resolve_params` a partire dallo
     snapshot: l'utente propone un intento, il manager decide se e' eseguibile.

`answer`/`classify`/`propose` sono LOGICA PURA: nessun fastapi, nessun import
di `manager` (niente cicli), nessuna dipendenza di rete, solo stdlib piu' il
vocabolario/ gli helper delle fasi precedenti. Nessuna delle tre solleva MAI:
input ostile (snapshot malformato, domanda non-stringa) degrada a un risultato
valido e conservativo (testo leggibile oppure None).
"""

from __future__ import annotations

from typing import Any

from .advisor import build_state
from .operator import resolve_params
from .self_heal import evaluate
from .types import ALLOWED_ACTIONS, Action

# Lunghezza massima dei campi liberi (messaggi/domanda) per non gonfiare il testo.
_MAX_FIELD_CHARS = 120
# Confidence fissa di una azione proposta dall'utente (sopra la soglia 0.7 del
# canale): la scelta e' deterministica, non un modello probabilistico.
_USER_CONFIDENCE = 0.8

# Euristica domanda -> azione. Ordine = priorita': la prima parola chiave
# trovata vince, le azioni concrete hanno la precedenza sulla spiegazione.
# Le chiavi sono radici (senza accento finale) per tollerare le flessioni.
_ACTION_KEYWORDS = (
    ("restore_llm", ("riavvia", "restore", "ripristina", "ricarica",
                     "llm giu", "llm down", "unload")),
    ("cooldown_media", ("cooldown", "media", "video", "comfy")),
    ("cancel_stale_jobs", ("bloccat", "stallo", "stale", "orfan",
                           "appes", "incastrat")),
    ("retry_job", ("riprova", "retry", "perso", "riaccoda")),
    ("notify_user", ("avvisa", "umano", "notifica", "notify", "attenzione")),
)

# Parole che segnalano una richiesta di SPIEGAZIONE (intento "explain", nessuna
# azione). Se non compare alcuna parola d'azione, il default e' comunque
# "explain": l'elenco rende l'intento esplicito e testabile.
_EXPLAIN_KEYWORDS = ("stato", "statu", "spiega", "perche", "perché", "coda",
                     "che succede", "riassunto", "situazione", "cosa", "?")


# ------------------------------------------------------------- accessori ---
# Helper difensivi gemelli di quelli in advisor/self_heal: chiave mancante o
# tipo inatteso degrada a un default, mai a un'eccezione.

def _num(mapping: Any, key: str, default: float = 0.0) -> float:
    raw = mapping.get(key) if isinstance(mapping, dict) else None
    try:
        return float(raw)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return float(default)


def _int(mapping: Any, key: str, default: int = 0) -> int:
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


def _list(mapping: Any, key: str) -> list:
    raw = mapping.get(key) if isinstance(mapping, dict) else None
    return raw if isinstance(raw, list) else []


def _clip(text: str, limit: int) -> str:
    if len(text) <= limit:
        return text
    return text[: max(0, limit - 3)] + "..."


def _find_job(snapshot: Any, job_id: str) -> dict | None:
    for job in _list(snapshot, "jobs"):
        if isinstance(job, dict) and job.get("job_id") == job_id:
            return job
    return None


# ------------------------------------------------------------------ facts ---

def _facts(snapshot: dict) -> dict:
    """Estrae SOLO i fatti presenti nello snapshot (mai inventati).

    Difensiva: uno snapshot malformato produce fatti con default coerenti
    (state/desired "?", nessun job, coda 0, cooldown 0, 0 errori), mai
    un'eccezione. Il risultato e' serializzabile in JSON.
    """
    now = _num(snapshot, "now", 0.0)
    state = _str(snapshot, "state", "?") or "?"
    desired = _str(snapshot, "desired", "?") or "?"

    current = snapshot.get("current_job")
    current_job = current if isinstance(current, str) and current else None
    current_status = ""
    if current_job is not None:
        job = _find_job(snapshot, current_job)
        current_status = _str(job, "status", "") if job is not None else ""

    cooldown_until = _num(snapshot, "media_cooldown_until", 0.0)
    cooldown_s = 0.0
    if cooldown_until > 0 and now > 0 and cooldown_until > now:
        cooldown_s = round(cooldown_until - now, 1)

    errors = 0
    for event in _list(snapshot, "recent_events"):
        if isinstance(event, dict) and _str(event, "kind") == "error":
            errors += 1

    return {
        "state": state,
        "desired": desired,
        "current_job": current_job,
        "current_job_status": current_status,
        "queue_len": _int(snapshot, "queue_len", 0),
        "media_consec_failures": _int(snapshot, "media_consec_failures", 0),
        "media_cooldown_s": cooldown_s,
        "recent_errors": errors,
        "last_error": _clip(_str(snapshot, "last_error", ""), _MAX_FIELD_CHARS),
    }


# ------------------------------------------------------------- narrazione ---

def _narrate(facts: dict) -> str:
    """Narrazione in linguaggio naturale derivata SOLO dai fatti estratti."""
    lines = [f"Stato attuale: {facts['state']} (desiderato: {facts['desired']})."]
    if facts["current_job"]:
        if facts["current_job_status"]:
            lines.append(
                f"Job in corso: {facts['current_job']} "
                f"(status {facts['current_job_status']})."
            )
        else:
            lines.append(f"Job in corso: {facts['current_job']}.")
    else:
        lines.append("Nessun job in corso.")
    if facts["queue_len"] > 0:
        lines.append(f"Job in coda: {facts['queue_len']}.")
    else:
        lines.append("Coda vuota.")
    if facts["media_cooldown_s"] > 0:
        lines.append(
            f"Media in cooldown per altri {facts['media_cooldown_s']:.0f}s: "
            "i job aspettano."
        )
    if facts["media_consec_failures"] > 0:
        lines.append(
            f"Fallimenti media consecutivi: {facts['media_consec_failures']}."
        )
    if facts["last_error"]:
        lines.append(f"Ultimo errore: {facts['last_error']}.")
    if facts["recent_errors"] > 0:
        lines.append(f"Errori recenti registrati: {facts['recent_errors']}.")
    return "\n".join(lines)


def _plan(snapshot: dict) -> str:
    """Cosa propone il supervisore: riusa le regole pure di self-heal."""
    try:
        actions = evaluate(snapshot)
    except Exception:  # noqa: BLE001 - snapshot ostile: nessun piano
        actions = []
    names = [
        str(getattr(a, "name", ""))
        for a in actions
        if isinstance(getattr(a, "name", None), str) and getattr(a, "name", "")
    ]
    if names:
        return "Azioni proposte dalle regole: " + ", ".join(names) + "."
    return "Azioni proposte dalle regole: nessuna."


def answer(snapshot: dict, question: str = "") -> dict:
    """Compono una risposta NL DETERMINISTICA e fedele ai dati. MAI solleva.

    Usa `advisor.build_state` per il riassunto di stato e vi aggiunge una
    narrazione derivata soltanto dai fatti presenti nello snapshot. Ritorna
    `{"text": str, "facts": {...}}`: `facts` contiene i valori usati (state,
    desired, current_job, queue_len, cooldown media residuo, errori recenti,
    ultimo errore). Uno snapshot ostile o una domanda non-stringa producono
    comunque un testo valido: il canale non puo' rompere il manager.
    """
    try:
        snap = snapshot if isinstance(snapshot, dict) else {}
        facts = _facts(snap)
        summary = build_state(snap)
        narration = _narrate(facts)
        plan = _plan(snap)
        parts = [summary, narration, plan]
        q = question if isinstance(question, str) else ""
        q = q.strip()
        if q:
            parts.append("Domanda: " + _clip(q, _MAX_FIELD_CHARS))
        text = "\n\n".join(p for p in parts if p)
        return {"text": text, "facts": facts}
    except Exception:  # noqa: BLE001 - mai sollevare su snapshot ostile
        return {"text": "snapshot non leggibile", "facts": {}}


# -------------------------------------------------------------- classify ---

def classify(question: str) -> dict:
    """Euristica DIFENSIVA questione -> intento. MAI solleva.

    Ritorna `{"intent": "explain"|"action", "action": <nome>|None}`. Le parole
    chiave italiane delle azioni vincono sulla spiegazione; nessun match (o
    input non-stringa) degrada a `{"intent": "explain", "action": None}`.
    """
    try:
        if not isinstance(question, str):
            return {"intent": "explain", "action": None}
        q = question.lower()
        for name, keywords in _ACTION_KEYWORDS:
            for keyword in keywords:
                if keyword in q:
                    if name in ALLOWED_ACTIONS:
                        return {"intent": "action", "action": name}
        for keyword in _EXPLAIN_KEYWORDS:
            if keyword in q:
                return {"intent": "explain", "action": None}
        return {"intent": "explain", "action": None}
    except Exception:  # noqa: BLE001 - input ostile: nessuna azione
        return {"intent": "explain", "action": None}


# ---------------------------------------------------------------- propose ---

def propose(snapshot: dict, question: str, limits: dict | None = None) -> Action | None:
    """Azione eseguibile proposta dall'utente, con parametri RISOLTI. MAI solleva.

    Se `classify` riconosce un'azione, risolve i parametri concreti via
    `operator.resolve_params` e ritorna una `Action(source="user")`. Ritorna
    `None` (fail-closed) se l'intento e' "explain", se l'azione non e'
    riconosciuta o se non c'e' un bersaglio risolvibile nello snapshot: l'utente
    propone l'intento, il manager decide se e' eseguibile.
    """
    try:
        verdict = classify(question)
        if verdict.get("intent") != "action":
            return None
        name = verdict.get("action")
        if not isinstance(name, str) or name not in ALLOWED_ACTIONS:
            return None
        snap = snapshot if isinstance(snapshot, dict) else {}
        params = resolve_params(name, snap, limits)
        if params is None:
            return None
        return Action(
            name=name,
            params=params,
            reason=f"richiesta utente: {name}",
            confidence=_USER_CONFIDENCE,
            source="user",
        )
    except Exception:  # noqa: BLE001 - input ostile: nessuna azione
        return None
