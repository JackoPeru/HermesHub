"""Imaging Character ID: funzioni pure su file immagine.

Core solo-PIL (testabile ovunque). Blur Laplaciano e face detection vivono nei
backend opzionali (numpy/cv2/insightface) caricati solo dal worker server.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

from PIL import Image, ImageOps

# Lato training: normalizzate RGB, lato max 1536, JPEG q95 (foto di persone).
NORMALIZED_MAX_SIDE = 1536
NORMALIZED_JPEG_QUALITY = 95


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 64), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_rgb(path: Path) -> Image.Image:
    """Apre, corregge orientamento EXIF, converte RGB. Solleva su file corrotto."""
    with Image.open(path) as handle:
        image = ImageOps.exif_transpose(handle)
        rgb = image.convert("RGB")
        return rgb.copy()


def image_size(path: Path) -> tuple[int, int]:
    image = load_rgb(path)
    return image.size


def dhash_hex(path: Path, hash_size: int = 8) -> str:
    """Difference hash 64-bit (solo PIL): stabilie a resize/ricompressione leggeri."""
    image = load_rgb(path).convert("L").resize((hash_size + 1, hash_size), Image.BILINEAR)
    pixels = list(image.getdata())
    bits = []
    for row in range(hash_size):
        base = row * (hash_size + 1)
        for col in range(hash_size):
            bits.append("1" if pixels[base + col] > pixels[base + col + 1] else "0")
    value = int("".join(bits), 2)
    return f"{value:016x}"


def hamming_hex(a: str, b: str) -> int:
    return bin(int(a, 16) ^ int(b, 16)).count("1")


def mean_brightness(path: Path) -> float:
    """Luminosita media 0..1 (solo PIL, via istogramma)."""
    image = load_rgb(path).convert("L")
    hist = image.histogram()
    total = sum(hist)
    if total == 0:
        return 0.0
    weighted = sum(index * count for index, count in enumerate(hist))
    return weighted / (total * 255.0)


def save_normalized(src: Path, dest: Path) -> tuple[int, int]:
    """Copia normalizzata RGB con tetto lato max. Ritorna (w, h)."""
    import os as _os

    image = load_rgb(src)
    width, height = image.size
    longest = max(width, height)
    if longest > NORMALIZED_MAX_SIDE:
        scale = NORMALIZED_MAX_SIDE / longest
        image = image.resize((round(width * scale), round(height * scale)), Image.LANCZOS)
    dest.parent.mkdir(parents=True, exist_ok=True)
    image.save(dest, format="JPEG", quality=NORMALIZED_JPEG_QUALITY)
    _os.chmod(dest, 0o600)  # dati biometrici: mai world-readable
    return image.size


def shot_scale(face_ratio: float) -> str:
    """Scala inquadratura da rapporto area volto/immagine (euristica onesta)."""
    if face_ratio >= 0.12:
        return "close-up"
    if face_ratio >= 0.04:
        return "medium shot"
    return "full shot"

# NOTA: i bucket di posa vivono in dataset.classify_pose (unica fonte di verita,
# nomi da spec). Non duplicare qui.
