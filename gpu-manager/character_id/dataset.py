"""Dataset Character ID: scoring, identita, split, reference pack, caption (solo stdlib).

Niente aggettivi identitari permanenti nelle caption (occhi/capelli/naso/mascella
mai descritti): solo trigger + scena misurata (scala, angolo, luce).
"""

from __future__ import annotations

import json
import math
import os
from pathlib import Path

# Soglie auto-reject (spec): solo casi oggettivi, i borderline vanno in warning.
MIN_FACE_RATIO = 0.015  # volto < 1.5% dell'immagine = troppo piccolo
SEVERE_BLUR = 8.0  # varianza Laplaciano sotto = blur gravissimo
DUP_HAMMING = 4  # distanza dhash <= 4 = quasi-duplicato
IDENTITY_REJECT_COS = 0.28  # similarita al centroide sotto = persona diversa
IDENTITY_WARN_COS = 0.45  # sotto = warning, non auto-reject

ANGLE_BUCKETS = (
    "front",
    "left_three_quarter",
    "right_three_quarter",
    "profile_left",
    "profile_right",
    "full_body",
    "unknown",
)


def cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / (na * nb)


def centroid(vectors: list[list[float]]) -> list[float] | None:
    if not vectors:
        return None
    dim = len(vectors[0])
    return [sum(v[i] for v in vectors) / len(vectors) for i in range(dim)]


def classify_asset(info: dict, have_embedding: bool) -> tuple[str, str]:
    """(accepted|warning|rejected, motivo). Mai auto-reject borderline."""
    if info.get("pre_reject"):
        return "rejected", str(info["pre_reject"])
    if info.get("corrupt"):
        return "rejected", "file corrotto"
    if info.get("duplicate_of"):
        return "rejected", "duplicato quasi identico"
    faces = int(info.get("face_count", 0))
    if faces == 0:
        return "rejected", "nessun volto utilizzabile"
    if faces > 1 and not info.get("dominant_subject"):
        return "rejected", "piu persone senza soggetto dominante"
    if float(info.get("face_ratio", 0.0)) < MIN_FACE_RATIO:
        return "rejected", "volto troppo piccolo"
    blur = info.get("blur_score")
    if blur is not None and float(blur) < SEVERE_BLUR:
        return "rejected", "blur gravissimo"
    if have_embedding:
        sim = info.get("identity_sim")
        if sim is not None and float(sim) < IDENTITY_REJECT_COS:
            return "rejected", "identita incompatibile col cluster principale"
        if sim is not None and float(sim) < IDENTITY_WARN_COS:
            return "warning", "somiglianza bassa al cluster principale"
    if blur is not None and float(blur) < SEVERE_BLUR * 3:
        return "warning", "foto mossa"
    return "accepted", ""


def angle_bucket(yaw: float | None) -> str:
    """Bucket yaw -> nomi da spec (unica fonte di verita per le pose)."""
    if yaw is None:
        return "unknown"
    a = abs(yaw)
    if a < 18:
        return "front"
    if a < 45:
        return "left_three_quarter" if yaw < 0 else "right_three_quarter"
    return "profile_left" if yaw < 0 else "profile_right"


def classify_pose(yaw: float | None, face_ratio: float, width: int, height: int) -> str:
    """Bucket posa: euristica full-body (volto piccolo + foto verticale) sopra lo yaw.

    Onesto per costruzione: e un proxy geometrico, non un body detector (VLM futuro).
    """
    if (
        yaw is not None
        and abs(yaw) < 45
        and 0.0 < face_ratio < 0.03
        and height > width
    ):
        return "full_body"
    return angle_bucket(yaw)


def quality_score(info: dict) -> float:
    """0..1 per ranking reference pack (face grande + nitida + frontale = meglio)."""
    score = 0.5
    score += min(0.25, float(info.get("face_ratio", 0.0)) * 1.5)
    blur = info.get("blur_score")
    if blur is not None:
        score += min(0.15, float(blur) / 400.0)
    yaw = info.get("yaw")
    if yaw is not None:
        score += max(0.0, 0.10 * (1.0 - abs(float(yaw)) / 90.0))
    if info.get("accepted") == "warning":
        score -= 0.15
    return round(max(0.0, min(1.0, score)), 3)


def diversity_report(items: list[dict]) -> dict:
    """Evita che il dataset impari vestito/sfondo invece della persona."""
    total = len(items) or 1
    by_angle: dict[str, int] = {}
    for item in items:
        bucket = item.get("angle_bucket", "unknown")
        by_angle[bucket] = by_angle.get(bucket, 0) + 1
    report = {"buckets": by_angle, "warnings": []}
    for bucket, count in by_angle.items():
        if count / total >= 0.70:
            report["warnings"].append(f"il {count/total:.0%} delle foto e '{bucket}': aggiungi varieta")
    missing = [b for b in ANGLE_BUCKETS if b not in ("unknown",) and b not in by_angle]
    if missing:
        report["warnings"].append("angoli mancanti: " + ", ".join(missing))
    return report


def stratified_split(items: list[dict], train_ratio: float = 0.8) -> dict[str, str]:
    """80/20 stratificato per angolo (mai tutti i frontali nel train)."""
    groups: dict[str, list[str]] = {}
    for item in items:
        groups.setdefault(item.get("angle_bucket", "unknown"), []).append(item["id"])
    split: dict[str, str] = {}
    for bucket in sorted(groups):
        ids = sorted(groups[bucket])
        n_train = max(1, round(len(ids) * train_ratio)) if len(ids) > 1 else 1
        for asset_id in ids[:n_train]:
            split[asset_id] = "train"
        for asset_id in ids[n_train:]:
            split[asset_id] = "validation"
    # Garantisce almeno 1 validation se possibile.
    if all(v == "train" for v in split.values()) and len(split) > 4:
        last = sorted(split)[-1]
        split[last] = "validation"
    return split


def pick_references(items: list[dict], per_bucket: int = 1) -> dict[str, list[str]]:
    """Migliori per bucket (hardlink/symlink a cura del worker, mai copie)."""
    ranked = sorted(items, key=lambda i: (i.get("angle_bucket", ""), -float(i.get("face_quality", 0.0))))
    picks: dict[str, list[str]] = {}
    for item in ranked:
        bucket = item.get("angle_bucket", "unknown")
        picks.setdefault(bucket, [])
        if len(picks[bucket]) < per_bucket:
            picks[bucket].append(item["id"])
    return picks


def build_caption(trigger: str, info: dict) -> str:
    """Caption student per Teacher Matching: trigger + scena misurata, MAI tratti identitari.

    Include `<Subject 1>` (ricetta upstream validata): l'auto-wrap del teacher lo
    definisce sul lato teacher legandolo alla foto, mentre sullo student text-only
    resta un trigger come gli altri.
    """
    parts = [trigger, "photo of <Subject 1>,"]
    shot = info.get("shot")
    if shot:
        parts.append(shot + ",")
    bucket = info.get("angle_bucket")
    if bucket and bucket != "unknown":
        parts.append(bucket.replace("_", " ") + ",")
    brightness = info.get("brightness")
    if brightness is not None:
        parts.append("bright daylight" if brightness > 0.65 else "low light" if brightness < 0.3 else "soft light")
    parts.append("still photo")
    return " ".join(parts)


def pick_subject_refs(target_id: str, items: list[dict], count: int = 3) -> list[str]:
    """2-4 reference complementari: mai il target stesso, angoli diversi prima."""
    target = next((i for i in items if i["id"] == target_id), None)
    target_angle = (target or {}).get("angle_bucket")
    others = [i for i in items if i["id"] != target_id]
    others.sort(
        key=lambda i: (
            i.get("angle_bucket") == target_angle,
            -float(i.get("face_quality", 0.0)),
        )
    )
    return [i["id"] for i in others[: max(2, min(4, count))]]


def write_dataset_jsonl(path: Path, records: list[dict]) -> None:
    """Schema Musubi image JSONL: image_path + caption + references[{type,path}].

    Valida tutti i path prima di scrivere: nessun path mancante al training.
    """
    for record in records:
        for key in ("image_path", "caption", "references"):
            if key not in record:
                raise ValueError(f"record senza '{key}': {record}")
        if not os.path.isfile(record["image_path"]):
            raise ValueError(f"target mancante: {record['image_path']}")
        for ref in record["references"]:
            if not isinstance(ref, dict) or ref.get("type") != "image" or "path" not in ref:
                raise ValueError(f"reference non valida: {ref}")
            if not os.path.isfile(ref["path"]):
                raise ValueError(f"reference mancante: {ref['path']}")
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, sort_keys=True) + "\n")
