"""Validatori puri Character ID (solo stdlib, testabili senza fastapi)."""

from __future__ import annotations

import re
import uuid
from pathlib import Path

from . import IDENTITY_MODES

MAX_NAME_LEN = 64
MIN_IMAGES = 20
RECOMMENDED_MIN_IMAGES = 25
MAX_IMAGES = 80
ALLOWED_EXTS = (".jpg", ".jpeg", ".png", ".webp")
MAX_FILE_BYTES = 15 * 1024 * 1024
# Lato server M3 rifiuta anche immagini oltre questa risoluzione (anti-OOM).
MAX_IMAGE_SIDE = 4096

# Nomi file: il server rinomina comunque in UUID, conta solo estensione +
# niente traversal. Spazi/parens/unicode ammessi (foto telefono reali).
_SAFE_FILENAME_RE = re.compile(r"^[^\x00-\x1f/\\:*?\"<>|][^\x00-\x1f/\\:*?\"<>|]*$")


_BIDI_CONTROLS = frozenset(
    ["\u202a", "\u202b", "\u202c", "\u202d", "\u202e",
     "\u2066", "\u2067", "\u2068", "\u2069",
     "\u200b", "\u200c", "\u200d", "\u200e", "\u200f", "\ufeff"]
)


def validate_name(name: object) -> str:
    """Ritorna il nome ripulito o solleva ValueError (mai fail-open: solo testo breve)."""
    if not isinstance(name, str):
        raise ValueError("name deve essere una stringa")
    clean = " ".join(name.split())
    if not clean:
        raise ValueError("name vuoto")
    if len(clean) > MAX_NAME_LEN:
        raise ValueError(f"name troppo lungo (max {MAX_NAME_LEN})")
    if any(ord(c) < 32 for c in clean):
        raise ValueError("name contiene caratteri di controllo")
    if any(c in _BIDI_CONTROLS for c in clean):
        raise ValueError("name contiene controlli bidirezionali/invisibili")
    return clean


def validate_default_mode(mode: object) -> str:
    if mode not in IDENTITY_MODES:
        raise ValueError(f"default_mode non valido (attesi {list(IDENTITY_MODES)})")
    return str(mode)


def validate_lora_strength(value: object) -> float:
    try:
        strength = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        raise ValueError("lora_strength deve essere un numero") from None
    if not 0.0 <= strength <= 2.0:
        raise ValueError("lora_strength fuori range 0..2")
    return strength


def validate_upload_filename(filename: object) -> str:
    """Rifiuta traversal, nomi strani ed estensioni non ammesse. Ritorna ext minuscola."""
    if not isinstance(filename, str) or not filename:
        raise ValueError("filename mancante")
    if "/" in filename or "\\" in filename or ".." in filename:
        raise ValueError("filename non sicuro (path traversal)")
    if filename.startswith("."):
        raise ValueError("filename non valido (nascosto)")
    if not _SAFE_FILENAME_RE.match(filename) or "." not in filename:
        raise ValueError("filename non valido")
    ext = "." + filename.rsplit(".", 1)[1].lower()
    if ext not in ALLOWED_EXTS:
        raise ValueError(f"estensione non ammessa (ammesse {list(ALLOWED_EXTS)})")
    return ext


def validate_upload_size(size: object) -> int:
    if isinstance(size, bool) or not isinstance(size, int):
        raise ValueError("size deve essere un intero di byte")
    if size <= 0:
        raise ValueError("file vuoto")
    if size > MAX_FILE_BYTES:
        raise ValueError(f"file troppo grande (max {MAX_FILE_BYTES} byte)")
    return size


def is_uuid(value: object) -> bool:
    if not isinstance(value, str):
        return False
    try:
        parsed = uuid.UUID(value)
    except (ValueError, AttributeError):
        return False
    return str(parsed) == value.lower()


def is_safe_preview_name(name: object) -> bool:
    """Nome file preview senza traversal + estensione servibile."""
    if not isinstance(name, str) or not name:
        return False
    if "/" in name or "\\" in name or ".." in name:
        return False
    return Path(name).suffix.lower() in (".jpg", ".jpeg", ".png", ".webp", ".mp4")


def is_character_worker_cmdline(cmdline: str) -> bool:
    """La cmdline appartiene a un worker character-id? (anti pid-recycling)."""
    return "character_id.worker_" in cmdline
