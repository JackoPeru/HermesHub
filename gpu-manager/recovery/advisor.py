"""Advisor laya in sola lettura (shadow) del supervisore (Fase 2).

Secondo stadio del supervisore: un piccolo modello di decisione locale (laya,
lo stesso servizio del triage) osserva lo snapshot e PROPONE un'azione fra
quelle dell'allowlist. Resta in ombra: non esegue nulla e non tocca mai la GPU.
Serve a misurare, senza rischi, la qualita dei suggerimenti rispetto alle regole
deterministiche di `recovery.self_heal` prima di lasciarli eseguire (Fase 4).

Confini netti:
  - sola lettura: `advise` ritorna un `Advice` e nient'altro;
  - nessun import di `manager` (niente cicli), solo stdlib (`json`,
    `urllib.request`, `dataclasses`, `time`);
  - nessun fastapi e nessuna dipendenza di rete obbligatoria: se laya e' giu'
    o risponde male, l'eccezione viene catturata e si ritorna un Advice vuoto.

Convenzione laya (riusata da `triage.LayaBackend`): POST `{url}/api/decide` con
`{"model", "state", "questions"}`; risposta `{"answers": {qid: {"choice",
"confidence"}}}`. Il context e' ~1024 token, quindi `build_state` produce un
riassunto compatto, deterministico e cappato.
"""

from __future__ import annotations

import json
import urllib.request
from dataclasses import dataclass, field
from typing import Any

from .types import ALLOWED_ACTIONS, Action

# Id dell'unica domanda posta a laya e insieme delle opzioni ammesse.
ADVISOR_QID = "supervisor_action"
ADVISOR_OPTIONS = ("none",) + ALLOWED_ACTIONS

# Domanda tipizzata (stessa forma di `triage.LAYA_QUESTIONS`): per ogni opzione
# una riga che spiega QUANDO sceglierla. Il wording resta semplice e in
# italiano; le opzioni coincidono con l'allowlist chiusa delle azioni.
ADVISOR_QUESTIONS = {
    ADVISOR_QID: {
        "type": "choice",
        "instructions": (
            "Sei il supervisore di un GPU manager che alterna LLM e media. "
            "Scegli UNA sola azione per la situazione descritta, oppure none "
            "se va tutto bene. Usa la minima azione necessaria e, nel dubbio "
            "o se la situazione e pericolosa, scegli notify_user."
        ),
        "criteria": {
            "none": "tutto ok, nessuna azione serve",
            "restore_llm": "LLM giu ma dovrebbe essere attivo, o unload/load bloccato",
            "cooldown_media": "il media fallisce ripetutamente o e giu da troppo",
            "cancel_stale_jobs": "un job e bloccato senza progressi",
            "retry_job": "un job perso per un guasto transitorio del backend",
            "notify_user": "situazione incerta o pericolosa: serve un umano",
        },
    }
}

# Cap di `build_state`: laya ha ~1024 token di context, oltre e' rumore.
_MAX_STATE_CHARS = 1500
# Quanti eventi recenti riportare nel riassunto (i piu' recenti prima).
_RECENT_EVENTS = 8
# Lunghezza massima dei campi liberi (messaggi) per non mangiare il budget.
_MAX_FIELD_CHARS = 120


@dataclass
class Advice:
    """Suggerimento dell'advisor laya: sola lettura, mai eseguito qui."""

    actions: list[Action] = field(default_factory=list)
    confidence: float = 0.0
    reasoning: str = ""
    backend: str = ""
    raw: dict = field(default_factory=dict)


# ------------------------------------------------------------- accessori ---
# Helper difensivi (come in `recovery.self_heal`): qualunque chiave mancante o
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


# ------------------------------------------------------------- build_state ---

def build_state(snapshot: dict) -> str:
    """Riassunto compatto e DETERMINISTICO dello snapshot, cappato (~1500 char).

    Legge solo valori dello snapshot (nessun orologio, nessun I/O): lo stesso
    snapshot produce la stessa stringa. Uno snapshot malformato non solleva
    MAI: le chiavi mancanti o di tipo inatteso degradano a default e il
    risultato resta una stringa valida (troncata al cap).
    """
    try:
        snap = snapshot if isinstance(snapshot, dict) else {}
        now = _num(snap, "now", 0.0)
        wall_now = _num(snap, "wall_now", 0.0)
        lines: list[str] = []

        state = _str(snap, "state", "?") or "?"
        desired = _str(snap, "desired", "?") or "?"
        since = _num(snap, "state_since", 0.0)
        if now > 0 and since > 0:
            lines.append(f"stato={state} da {now - since:.0f}s (desiderato={desired})")
        else:
            lines.append(f"stato={state} (desiderato={desired})")

        last_error = _str(snap, "last_error", "")
        if last_error:
            lines.append(f"ultimo_errore={_clip(last_error, _MAX_FIELD_CHARS)}")

        current = snap.get("current_job")
        if isinstance(current, str) and current:
            job = _find_job(snap, current)
            status = _str(job, "status", "?") if job else "?"
            age = ""
            if job is not None and wall_now > 0:
                updated = _num(job, "updated_at", 0.0)
                if updated > 0:
                    age = f" aggiornato {wall_now - updated:.0f}s fa"
            lines.append(f"job_corrente={current} status={status}{age}")
        else:
            lines.append("job_corrente=nessuno")

        queue_len = _int(snap, "queue_len", 0)
        fails = _int(snap, "media_consec_failures", 0)
        cooldown = _num(snap, "media_cooldown_until", 0.0)
        if cooldown > 0 and now > 0 and cooldown > now:
            cd = f"attivo per {cooldown - now:.0f}s"
        else:
            cd = "spento"
        lines.append(
            f"coda={queue_len} fallimenti_media_consecutivi={fails} cooldown_media={cd}"
        )

        events = _list(snap, "recent_events")
        errors = 0
        for event in events:
            if isinstance(event, dict) and _str(event, "kind") == "error":
                errors += 1
        lines.append(f"errori_recenti={errors}")

        tail: list[str] = []
        for event in events[:_RECENT_EVENTS]:
            if not isinstance(event, dict):
                continue
            kind = _str(event, "kind", "?") or "?"
            severity = _str(event, "severity", "?") or "?"
            message = _clip(_str(event, "message", ""), _MAX_FIELD_CHARS)
            tail.append(f"{kind}/{severity}: {message}")
        if tail:
            lines.append("eventi: " + " | ".join(tail))

        text = "\n".join(lines)
        if len(text) > _MAX_STATE_CHARS:
            text = text[:_MAX_STATE_CHARS]
        return text
    except Exception:  # noqa: BLE001 - mai sollevare su snapshot ostile
        return "snapshot non leggibile"


# ------------------------------------------------------------- parse_answer ---

def _empty_advice(reasoning: str = "", raw: dict | None = None,
                  confidence: float = 0.0) -> Advice:
    return Advice(actions=[], confidence=confidence, reasoning=reasoning,
                  backend="laya", raw=raw if isinstance(raw, dict) else {})


def parse_answer(payload: dict, min_confidence: float = 0.0) -> Advice:
    """Trasforma la risposta laya in un `Advice` (MAI solleva).

    Legge `payload["answers"][ADVISOR_QID]`, valida `choice` in
    ("none", *ALLOWED_ACTIONS) e la confidence. Con scelta `none`, payload
    malformato o confidence sotto `min_confidence` ritorna un Advice SENZA
    azioni. L'unica `Action` prodotta ha `source="laya"` e `params={}`: i
    parametri concreti (es. job_id) li decidera' l'operator in Fase 3 a partire
    dallo snapshot, non vengono inventati qui.
    """
    if not isinstance(payload, dict):
        return _empty_advice("payload non e' un dict")

    answers = payload.get("answers")
    if not isinstance(answers, dict):
        return _empty_advice("risposta senza answers", payload)

    entry = answers.get(ADVISOR_QID)
    if not isinstance(entry, dict):
        return _empty_advice(f"nessuna risposta per {ADVISOR_QID}", payload)

    choice = entry.get("choice")
    if not isinstance(choice, str) or choice not in ADVISOR_OPTIONS:
        return _empty_advice(f"scelta non valida: {choice!r}", payload)

    try:
        confidence = float(entry.get("confidence", 0.0))
    except (TypeError, ValueError):
        confidence = 0.0
    if confidence != confidence:  # NaN
        confidence = 0.0
    confidence = max(0.0, min(1.0, confidence))

    try:
        threshold = float(min_confidence)
    except (TypeError, ValueError):
        threshold = 0.0
    if threshold != threshold:  # NaN
        threshold = 0.0

    if choice == "none":
        return _empty_advice("laya: nessuna azione", payload, confidence)
    if confidence < threshold:
        return _empty_advice(
            f"confidence {confidence:.2f} sotto soglia {threshold:.2f}",
            payload, confidence,
        )

    action = Action(
        name=choice,
        params={},
        reason=f"advisor laya: {choice}",
        confidence=confidence,
        source="laya",
    )
    return Advice(
        actions=[action],
        confidence=confidence,
        reasoning=f"laya propone {choice}",
        backend="laya",
        raw=payload,
    )


# -------------------------------------------------------------- LayaAdvisor ---

class LayaAdvisor:
    """Client laya in sola lettura: costruisce lo state, chiede, interpreta.

    `opener` e' iniettabile per i test (callable `(req) -> bytes-like`); di
    default si usa `urllib.request.urlopen`. `advise` NON solleva MAI: ogni
    errore (timeout, JSON rotto, backend assente) degrada a un Advice vuoto.
    """

    def __init__(
        self,
        url: str = "http://127.0.0.1:11435",
        model: str = "laya:multilingual",
        *,
        min_confidence: float = 0.7,
        timeout: int = 15,
        opener=None,
    ) -> None:
        self.url = str(url)
        self.model = str(model)
        try:
            self.min_confidence = float(min_confidence)
        except (TypeError, ValueError):
            self.min_confidence = 0.7
        try:
            self.timeout = int(timeout)
        except (TypeError, ValueError):
            self.timeout = 15
        self.opener = opener

    def _ask(self, state: str) -> dict:
        """POST a laya e ritorna il payload JSON. Puo' sollevare (catturato sopra)."""
        body = json.dumps(
            {"model": self.model, "state": state, "questions": ADVISOR_QUESTIONS}
        ).encode()
        req = urllib.request.Request(
            self.url + "/api/decide",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        if self.opener is not None:
            resp = self.opener(req)
        else:
            resp = urllib.request.urlopen(req, timeout=self.timeout)
        data = resp.read() if hasattr(resp, "read") else resp
        if isinstance(data, (bytes, bytearray)):
            data = bytes(data).decode("utf-8")
        return json.loads(data)

    def advise(self, snapshot: dict) -> Advice:
        """Costruisce lo state, interroga laya e interpreta. Sola lettura, mai raise."""
        state = build_state(snapshot)
        try:
            payload = self._ask(state)
        except Exception as exc:  # noqa: BLE001 - laya giu'/rotto: nessuna azione
            return _empty_advice(f"errore laya: {exc!r}")
        return parse_answer(payload, self.min_confidence)
