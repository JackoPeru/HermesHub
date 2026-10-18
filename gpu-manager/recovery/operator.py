"""Operator laya su allowlist (Fase 3).

Terzo stadio del supervisore: prende le `Action` PROPOSTE dall'advisor laya
(`recovery.advisor`, source="laya") e le rende ESEGUIBILI, ma con due garanzie
fail-closed:

  1. il nome deve stare nell'allowlist chiusa (`ALLOWED_ACTIONS`);
  2. i parametri concreti li risolve QUI l'operator a partire dallo snapshot:
     laya non inventa MAI un job_id, e se non c'e' un bersaglio valido l'azione
     viene scartata (non eseguita).

Questo modulo e' LOGICA PURA: nessun fastapi, nessun import di `manager` (niente
cicli), solo stdlib piu' il vocabolario di `recovery.types` e gli helper
condivisi di `recovery.self_heal` (`stale_job_ids`, `lost_job_id`). Non tocca
mai la GPU: DESCRIVE azioni eseguibili; e' il manager, a valle, a eseguirle.
`resolve_params` e `decide_actions` non sollevano mai, a prova di input ostile.
"""

from __future__ import annotations

from typing import Any

from .self_heal import DEFAULT_LIMITS, lost_job_id, stale_job_ids
from .types import ALLOWED_ACTIONS, Action

# Messaggio stabile per `notify_user` (non varia da tick a tick: niente spam di
# testi diversi quando laya chiede un umano).
_NOTIFY_MESSAGE = "supervisore laya: attenzione richiesta"


def _num(mapping: Any, key: str, default: float) -> float:
    """Numero difensivo da `mapping[key]` (default se assente o non numerico)."""
    raw = mapping.get(key) if isinstance(mapping, dict) else None
    if raw is None:
        return float(default)
    try:
        return float(raw)
    except (TypeError, ValueError):
        return float(default)


def _effective_limits(snapshot: Any, limits: dict | None) -> dict:
    """DEFAULT_LIMITS fuso con `snapshot["limits"]` e poi con `limits` esplicito.

    Precedenza: default < snapshot < argomento `limits` (l'override piu' vicino
    alla chiamata vince). Difensiva: chiavi/tipi inattesi non sollevano.
    """
    merged = dict(DEFAULT_LIMITS)
    raw = snapshot.get("limits") if isinstance(snapshot, dict) else None
    if isinstance(raw, dict):
        merged.update(raw)
    if isinstance(limits, dict):
        merged.update(limits)
    return merged


def resolve_params(name: str, snapshot: dict, limits: dict | None = None) -> dict | None:
    """Risolve i parametri concreti di `name` dallo snapshot.

    Ritorna un dict di parametri pronti per `manager._supervisor_execute`, oppure
    `None` se l'azione NON e' eseguibile (nome fuori allowlist o nessun bersaglio
    valido nello snapshot: fail-closed). PURA e mai sollevante: non inventa mai
    un job_id, lo prende solo dai job reali dello snapshot.

    Mappa:
      - `restore_llm`      -> {}
      - `cooldown_media`   -> {"seconds": limits["cooldown_media_s"]}
      - `cancel_stale_jobs`-> {"job_ids": [...], "cause": "laya"} (job stale,
                              stessa nozione di R1 via `stale_job_ids`)
      - `retry_job`        -> {"job_id": <piu' vecchio job perso sotto il cap>}
      - `notify_user`      -> {"message": <breve e stabile>}
    """
    if not isinstance(name, str) or name not in ALLOWED_ACTIONS:
        return None
    eff = _effective_limits(snapshot, limits)
    try:
        if name == "restore_llm":
            return {}
        if name == "cooldown_media":
            seconds = _num(eff, "cooldown_media_s", DEFAULT_LIMITS["cooldown_media_s"])
            return {"seconds": seconds}
        if name == "cancel_stale_jobs":
            job_ids = stale_job_ids(snapshot)
            if not job_ids:
                return None
            return {"job_ids": job_ids, "cause": "laya"}
        if name == "retry_job":
            job_id = lost_job_id(snapshot)
            if job_id is None:
                return None
            return {"job_id": job_id}
        if name == "notify_user":
            return {"message": _NOTIFY_MESSAGE}
    except Exception:  # noqa: BLE001 - snapshot ostile: azione non eseguibile
        return None
    return None


def decide_actions(
    advice,
    snapshot: dict,
    limits: dict | None = None,
    *,
    min_confidence: float = 0.8,
    allow: tuple | None = None,
) -> list[Action]:
    """Trasforma un `Advice` in azioni eseguibili con parametri risolti. PURA.

    Accetta un `Advice` (usa `.actions`) oppure una lista/tupla di `Action`.
    Tiene SOLO le azioni con: `name` in `allow` (default `ALLOWED_ACTIONS`),
    `confidence >= min_confidence` e `resolve_params` non-None. Ritorna NUOVE
    `Action` con `params` risolti e `source="laya"`: mai piu' di una per `name`
    (niente duplicati), ordine stabile (ordine di input). Mai solleva: un input
    malformato degrada a [].
    """
    try:
        if isinstance(advice, (list, tuple)):
            raw_actions = list(advice)
        else:
            raw_actions = list(getattr(advice, "actions", None) or [])
        allowed = tuple(allow) if allow is not None else ALLOWED_ACTIONS
        try:
            threshold = float(min_confidence)
        except (TypeError, ValueError):
            threshold = 0.8
        if threshold != threshold:  # NaN
            threshold = 0.8

        out: list[Action] = []
        seen: set[str] = set()
        for raw in raw_actions:
            name = getattr(raw, "name", None)
            if not isinstance(name, str) or name not in allowed or name in seen:
                continue
            try:
                confidence = float(getattr(raw, "confidence", 0.0) or 0.0)
            except (TypeError, ValueError):
                continue
            if confidence != confidence:  # NaN
                continue
            confidence = max(0.0, min(1.0, confidence))
            if confidence < threshold:
                continue
            params = resolve_params(name, snapshot, limits)
            if params is None:
                continue
            reason = getattr(raw, "reason", "")
            if not isinstance(reason, str) or not reason:
                reason = f"operator laya: {name}"
            try:
                action = Action(
                    name=name, params=params, reason=reason,
                    confidence=confidence, source="laya",
                )
            except Exception:  # noqa: BLE001 - costruzione imposibile: scarta
                continue
            seen.add(name)
            out.append(action)
        return out
    except Exception:  # noqa: BLE001 - mai sollevare, qualunque input
        return []
