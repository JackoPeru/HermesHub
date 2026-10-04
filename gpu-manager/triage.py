"""Fast-path triage for media requests: decide BEFORE the big LLM turn.

CANONICAL SOURCE: scripts/hermes_hub_gateway/triage.py
This copy exists so hermes-gpu-manager stays self-contained (it cannot rely
on the gateway package path). Keep the two in sync on every change.

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

import json
import os
import re
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path
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

# Verbs that request MOTION/video from a photo (infinitive + imperative).
_MOTION_RE = re.compile(
    r"\b(anima|animare|animami|video|muovi|muovere|movimento|moviment[ao]|"
    r"dagli\s+vita|dare\s+vita|animate|motion|make\s+it\s+move|"
    r"turn\s+into\s+a\s+video)\b",
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


# Question set Baker: vince il wording semplice (V1) nei test live IT.
# Non alzare le aspettative: sotto soglia si torna alle regole.
LAYA_QUESTIONS = {
    "media_task": {
        "type": "choice",
        "instructions": (
            "Decidi cosa vuole l utente. FOTO ALLEGATA indica se c e una foto. "
            "Se il testo chiede movimento, animazione o un video scegli "
            "video_preview. Se chiede di cambiare la foto ferma scegli "
            "edit_image. Se chiede di creare dal nulla scegli generate_image. "
            "Altrimenti no_media."
        ),
        "criteria": {
            "generate_image": "creare una immagine da zero, nessuna foto allegata",
            "edit_image": "cambiare la foto ferma allegata",
            "video_preview": "dare movimento alla foto, animarla, crearne un video",
            "no_media": "altro: domande, analisi, chat, nessuna generazione",
        },
    }
}

LAYA_OPTION_TO_PRESET = {
    "generate_image": PRESET_CREATE_IMAGE,
    "edit_image": PRESET_EDIT_IMAGE,
    "video_preview": PRESET_VIDEO_PREVIEW,
    "no_media": "",
}


def _log_decision(record: dict) -> None:
    """Decision log for calibration (best effort, never raises)."""
    try:
        path = Path(
            os.environ.get(
                "HERMES_TRIAGE_LOG",
                str(Path.home() / ".hermes" / "laya-decisions.jsonl"),
            )
        )
        path.parent.mkdir(parents=True, exist_ok=True)
        record = dict(record)
        record["ts"] = time.time()
        with path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")
    except Exception:
        pass


@dataclass
class LayaBackend:
    """Router via ollaya/llama decision model (typed choice, ms latency).

    Trusts laya only above min_confidence; anything lower (or any error)
    falls back to the rules backend, which is conservative by design.
    """

    url: str = "http://127.0.0.1:11435"
    model: str = "laya:multilingual"
    min_confidence: float = 0.75
    timeout: int = 15
    fallback: DecisionBackend | None = None

    def _ask(self, state: str) -> tuple[str, float]:
        body = json.dumps(
            {"model": self.model, "state": state, "questions": LAYA_QUESTIONS}
        ).encode()
        req = urllib.request.Request(
            self.url + "/api/decide",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        answer = data["answers"]["media_task"]
        return str(answer["choice"]), float(answer["confidence"])

    def decide(
        self,
        text: str,
        has_image: bool,
        history_tail: Sequence[str] = (),
    ) -> TriageDecision:
        clean = (text or "").strip()
        state = f"FOTO ALLEGATA: {'si' if has_image else 'no'}. TESTO: {clean}"
        choice, confidence = "", 0.0
        try:
            choice, confidence = self._ask(state)
        except Exception as exc:  # noqa: BLE001 - fallback covers everything
            _log_decision(
                {"backend": "laya", "error": repr(exc)[:160],
                 "text": clean[:200], "has_image": has_image}
            )
        if choice in LAYA_OPTION_TO_PRESET and confidence >= self.min_confidence:
            preset = LAYA_OPTION_TO_PRESET[choice]
            decision = TriageDecision(
                media=bool(preset),
                preset=preset,
                prompt_hint=clean,
                reason=f"laya:{choice}@{confidence:.2f}",
                confidence=confidence,
            )
            _log_decision(
                {"backend": "laya", "choice": choice, "confidence": confidence,
                 "preset": preset, "text": clean[:200], "has_image": has_image}
            )
            return decision
        fb = self.fallback or RulesBackend()
        decision = fb.decide(clean, has_image, history_tail)
        decision = TriageDecision(
            media=decision.media,
            preset=decision.preset,
            prompt_hint=decision.prompt_hint,
            reason=f"laya-fallback:{choice}@{confidence:.2f}+{decision.reason}",
            confidence=decision.confidence,
        )
        _log_decision(
            {"backend": "laya-fallback", "choice": choice,
             "confidence": confidence, "preset": decision.preset,
             "text": clean[:200], "has_image": has_image}
        )
        return decision


def triage(
    text: str,
    has_image: bool,
    history_tail: Sequence[str] = (),
    backend: DecisionBackend | None = None,
) -> TriageDecision:
    return (backend or DEFAULT_BACKEND).decide(text, has_image, history_tail)
