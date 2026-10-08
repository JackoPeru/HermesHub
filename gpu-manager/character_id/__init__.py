"""Hermes Character ID — persistent identity subsystem (server side).

Vive dentro hermes-gpu-manager (stesso processo, stessa auth, stesso lock GPU)
come pacchetto separato: manager.py lo aggancia con una sola chiamata a
`character_id.api.register_character_routes`.

Solo stdlib qui dentro: il DB e il filesystem non devono dipendere da fastapi.
"""

from __future__ import annotations

SCHEMA_VERSION = 1

# Stato personaggio (UI Characters + backend). `interrupted` = crash durante job
# (mai `ready` presunto); `needs_retrain` = quality acceptance fallita.
CHARACTER_STATUSES = (
    "draft",
    "analyzing",
    "ready_to_train",
    "training",
    "validating",
    "ready",
    "failed",
    "needs_retrain",
    "interrupted",
)

# Stati job persistiti (spec: un reboot non deve far sparire lo stato).
CHARACTER_JOB_STATUSES = (
    "queued",
    "preparing",
    "caching",
    "smoke_test",
    "training",
    "validating",
    "ready",
    "failed",
    "cancelled",
)

# Modalita identita esposte in UI. `hybrid` riservato al futuro Ref2VA-specific LoRA.
IDENTITY_MODES = ("auto", "lora", "reference")

# Famiglie base LoRA: un LoRA fl2va non va mai applicato a ref2va e viceversa.
LORA_FAMILIES = ("fl2va", "ref2va")

DEFAULT_LORA_STRENGTH = 0.9
