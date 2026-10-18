"""Supervisore di recupero del GPU manager — Fase 0/1/2/3/4 (completo).

Il manager alterna TabbyAPI (LLM) e ComfyUI (media) su due GPU con una macchina
a stati e un worker loop: ogni guasto oggi chiede un intervento umano. Il
supervisore lo rende autonomo in cinque fasi, ognuna con un confine netto:

  1. Event store (Fase 0): storia operativa append-only su sqlite
     (`events`) piu il vocabolario condiviso (`Event`, `Action`, severita,
     allowlist azioni). Solo osservazione: nessun comportamento cambia.
  2. Self-heal deterministico (Fase 1): regole pure event-store -> `Action`
     (`recovery.self_heal`), eseguite dal manager in modo fail-closed
     (nessuna improvvisazione, tutto rivedibile a mano).
  3. Advisor laya shadow (Fase 2): un modello suggerisce `Action`
     (`recovery.advisor`) ma resta in ombra: propone, non tocca la GPU. Serve
     a misurare la qualita dei suggerimenti.
  4. Operator laya su allowlist (Fase 3, `recovery.operator`): i suggerimenti
     diventano eseguibili, ma solo se il nome e in `ALLOWED_ACTIONS` e i
     parametri concreti (es. job_id) si RISOLVONO dallo snapshot: laya non li
     inventa mai. L'esecuzione e' OPT-IN nel manager (fail-closed di default).
  5. Canale NL (Fase 4, `recovery.channel`): un'interfaccia in linguaggio
     naturale verso lo stesso vocabolario. Il TESTO lo compone il manager in
     modo deterministico (grounded su snapshot/event store), non un modello;
      l'AZIONE e' scelta per intento, filtrata dall'allowlist e risolta
      dallo snapshot. L'esecuzione resta OPT-IN (`channel.allow_execute`,
      default False): di default il canale spiega e propone, non esegue.
  6. Forzatura di modo (`recovery.force`): il canale diretto (bridge laya /
     app) puo' chiedere di forzare LLM/MEDIA/AUTO/DIRECT anche da uno stato
     bloccato. `manager` espone `POST /force`, che azzera i blocker e applica
     la transizione ORA (non un pin: "solo transizione"). La logica pura
     (normalizzazione mode + reset dei blocker) vive qui, testabile senza
     fastapi.

Lessico condiviso (congelato in `recovery.types`):
  - `SEVERITIES`: debug < info < warning < error < critical.
  - `ALLOWED_ACTIONS`: insieme CHIUSO di azioni che il supervisore puo proporre.
  - `Action`: azione proposta (nome, parametri, motivo, confidenza, sorgente).
  - `Event`/`EventStore`: fatti registrati e la loro persistenza best-effort.

Vincoli di design: solo stdlib, nessun import di `manager` (niente cicli) e
nessun fastapi. `EventStore.record` non solleva MAI: il supervisore non puo
rompere il manager. Anche `recovery.advisor` e' sola lettura e non solleva mai;
`recovery.operator` risolve/esegue solo l'allowlist e non solleva mai;
`recovery.channel` compone testo e propone azioni senza mai sollevare.
"""

from __future__ import annotations

from . import advisor, channel, force, operator, self_heal
from .advisor import ADVISOR_QUESTIONS, Advice, LayaAdvisor
from .channel import answer, classify, propose
from .events import Event, EventStore
from .force import (
    FORCE_MODES,
    TRANSITIONAL_STATES,
    apply_force_state,
    blocker_defaults,
    normalize_mode,
)
from .operator import decide_actions, resolve_params
from .self_heal import DEFAULT_LIMITS, evaluate, lost_job_id, stale_job_ids
from .types import ALLOWED_ACTIONS, ACTION_SOURCES, SEVERITIES, Action

__all__ = [
    "Event",
    "EventStore",
    "Action",
    "ALLOWED_ACTIONS",
    "ACTION_SOURCES",
    "SEVERITIES",
    "self_heal",
    "DEFAULT_LIMITS",
    "evaluate",
    "stale_job_ids",
    "lost_job_id",
    "advisor",
    "Advice",
    "LayaAdvisor",
    "ADVISOR_QUESTIONS",
    "operator",
    "resolve_params",
    "decide_actions",
    "channel",
    "answer",
    "classify",
    "propose",
    "force",
    "FORCE_MODES",
    "TRANSITIONAL_STATES",
    "normalize_mode",
    "blocker_defaults",
    "apply_force_state",
]
