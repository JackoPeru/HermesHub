"""Vocabolario condiviso del supervisore di recupero (congelato per le fasi 1-4).

Qui vivono SOLO tipi e costanti pure (sola stdlib, nessun fastapi): le fasi
successive (self-heal deterministico, advisor laya, operator laya su allowlist,
canale NL) parlano tutte questo vocabolario, quindi ogni evoluzione deve restare
retro-compatibile.
"""

from __future__ import annotations

from dataclasses import dataclass, field

# Livelli di severita, dal piu verboso al piu grave. Una severita fuori da
# questa tupla viene tollerata e normalizzata a "info" dall'EventStore.
SEVERITIES = ("debug", "info", "warning", "error", "critical")

# Allowlist chiusa delle azioni che il supervisore puo proporre/eseguire.
# Fail-closed: qualsiasi nome fuori da qui e un bug, non un'azione da tentare.
ALLOWED_ACTIONS = (
    "restore_llm",
    "cooldown_media",
    "cancel_stale_jobs",
    "retry_job",
    "notify_user",
)

# Sorgenti ammesse per una Action: regole deterministiche, advisor laya (shadow)
# oppure richiesta esplicita dell'utente.
ACTION_SOURCES = ("rules", "laya", "user")


@dataclass
class Action:
    """Azione proposta dal supervisore (mai eseguita qui: solo descritta)."""

    name: str
    params: dict = field(default_factory=dict)
    reason: str = ""
    confidence: float = 0.0
    source: str = ""

    def __post_init__(self) -> None:
        if self.name not in ALLOWED_ACTIONS:
            raise ValueError(f"azione non ammessa: {self.name!r}")
        try:
            confidence = float(self.confidence)
        except (TypeError, ValueError):
            raise ValueError(f"confidence non numerica: {self.confidence!r}")
        if not 0.0 <= confidence <= 1.0:
            raise ValueError(f"confidence fuori range [0,1]: {self.confidence!r}")
