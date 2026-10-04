"""Fast-path triage for media requests: decide BEFORE the big LLM turn.

A full agentic turn on the 27B for "edit this photo" costs tens of
thousands of tokens. This module answers one cheap question:

    given the user text (+ whether an image is attached),
    is this a media generation job, and which preset?

Backends share one interface so the rules engine (zero infra, works now)
can be swapped for a small decision model later without touching callers:

    backend.decide(text, has_image, history_tail) -> TriageDecision

Only HIGH-confidence decisions take the fast path; anything ambiguous
returns media=False and the normal agent turn runs untouched.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Protocol, Sequence

PRESET_CREATE_IMAGE = "create_image"
PRESET_EDIT_IMAGE = "edit_image"
PRESET_VIDEO_PREVIEW = "journey_video_preview"

# Verbs that request a photo EDIT (IT + EN). IT verbs accept clitic suffixes
# (-mi, -la, ...); word boundaries on both ends avoid "cambiamento"-style hits.
_EDIT_RE = re.compile(
    r"\b((?:modifica|cambia|trasforma|rimuovi|togli|aggiungi|migliora|"
    r"sistema|ritocca|sfoca|color[ai]|rendi)(?:mi|mela|melo|la|lo|li|le|"
    r"ne|ci|ti|si)?|edit|change|remove|add|enhance|fix|retouch|"
    r"make\s+it|turn\s+it\s+into)\b",
    re.IGNORECASE,
)

# Verbs that request MOTION/video from a photo.
_MOTION_RE = re.compile(
    r"\b(anima|animami|video|muovi|movimento|moviment[ao]|dagli\s+vita|"
    r"animate|motion|make\s+it\s+move|turn\s+into\s+a\s+video)\b",
    re.IGNORECASE,
)

# Text-only image generation request (no photo attached).
# IT verbs include the "-mi" (for-me) form; EN kept exact to avoid
# "creature"-style false positives.
_GENERATE_RE = re.compile(
    r"\b(genera(?:mi)?|crea(?:mi)?|disegna(?:mi)?|produci(?:mi)?|immagina|"
    r"fammi\s+vedere|generate|create|draw|make\s+(me\s+)?an?\s+image|"
    r"picture\s+of| image\s+of)\b",
    re.IGNORECASE,
)

# Question/analysis intent: the user wants to KNOW, not to GENERATE.
# Always wins over edit verbs when an image is attached.
_ASK_RE = re.compile(
    r"\b(cos['èe]|cosa|chi|dove|quando|perch[eé]|come\s+mai|spiega|"
    r"spiegami|descrivi|descrizione|dimmi\s+(cosa|chi|che)|che\s+cos|"
    r"riconosci|leggi|traduci|quanto|quanti|what|who|where|when|why|"
    r"explain|describe|tell\s+me|recognize|read|translate|how\s+many)\b",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class TriageDecision:
    media: bool = False
    preset: str = ""
    prompt_hint: str = ""
    reason: str = ""
    confidence: float = 0.0


class DecisionBackend(Protocol):
    def decide(
        self,
        text: str,
        has_image: bool,
        history_tail: Sequence[str] = (),
    ) -> TriageDecision: ...


@dataclass
class RulesBackend:
    """Zero-infra backend: regexes + attachment presence. High bar."""

    def decide(
        self,
        text: str,
        has_image: bool,
        history_tail: Sequence[str] = (),
    ) -> TriageDecision:
        clean = (text or "").strip()
        if has_image:
            if _ASK_RE.search(clean):
                return TriageDecision(
                    media=False, reason="analysis-question", confidence=0.9
                )
            if _MOTION_RE.search(clean):
                return TriageDecision(
                    media=True,
                    preset=PRESET_VIDEO_PREVIEW,
                    prompt_hint=clean,
                    reason="motion-verbs+image",
                    confidence=0.9,
                )
            if _EDIT_RE.search(clean):
                return TriageDecision(
                    media=True,
                    preset=PRESET_EDIT_IMAGE,
                    prompt_hint=clean,
                    reason="edit-verbs+image",
                    confidence=0.9,
                )
            # Image attached but no actionable text: do NOT guess.
            # (Blank text today means "look at this" far more often.)
            return TriageDecision(
                media=False, reason="image-without-instruction", confidence=0.7
            )
        if _GENERATE_RE.search(clean):
            return TriageDecision(
                media=True,
                preset=PRESET_CREATE_IMAGE,
                prompt_hint=clean,
                reason="generate-verbs",
                confidence=0.85,
            )
        return TriageDecision(media=False, reason="no-media-signal", confidence=0.8)


DEFAULT_BACKEND = RulesBackend()


def triage(
    text: str,
    has_image: bool,
    history_tail: Sequence[str] = (),
    backend: DecisionBackend | None = None,
) -> TriageDecision:
    return (backend or DEFAULT_BACKEND).decide(text, has_image, history_tail)
