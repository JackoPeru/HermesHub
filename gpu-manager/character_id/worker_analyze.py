"""Worker analyze Character ID (eseguito dal tools-venv server, CPU-only).

Uso: python -m character_id.worker_analyze <root> <character_id> <job_id>

Pipeline: originals -> EXIF/RGB/sha256/dhash/duplicati -> normalizzate ->
blur/volto/embedding -> cluster identita -> classificazione accepted/warning/
rejected -> split 80/20 stratificato -> reference pack (symlink) ->
training/dataset.jsonl. Aggiorna job progress + manifest. Mai GPU.
"""

from __future__ import annotations

import json
import os
import sys
import traceback
from pathlib import Path

ALLOWED_EXTS = {".jpg", ".jpeg", ".png", ".webp"}


def log(handle, message: str) -> None:
    handle.write(message + "\n")
    handle.flush()
    print(message, flush=True)


def laplacian_variance(image_path: Path):
    try:
        import cv2

        gray = cv2.imread(str(image_path), cv2.IMREAD_GRAYSCALE)
        if gray is None:
            return None
        return float(cv2.Laplacian(gray, cv2.CV_64F).var())
    except ImportError:
        return None


def main(argv: list[str]) -> int:
    from character_id.dataset import (
        DUP_HAMMING,
        build_caption,
        centroid,
        classify_asset,
        classify_pose,
        cosine,
        diversity_report,
        pick_references,
        pick_subject_refs,
        quality_score,
        stratified_split,
        write_dataset_jsonl,
    )
    from character_id.face_backend import available_backends, detect_faces
    from character_id.imaging import (
        dhash_hex,
        hamming_hex,
        image_size,
        mean_brightness,
        save_normalized,
        sha256_file,
        shot_scale,
    )
    from character_id.store import CharacterStore

    root, character_id, job_id = argv[1], argv[2], argv[3]
    store = CharacterStore(root)
    manifest = store.get_character(character_id)
    if manifest is None:
        print(f"character non trovato: {character_id}", flush=True)
        return 2
    char_dir = store.char_dir(character_id)
    log_path = char_dir / "logs" / f"analyze-{job_id[:8]}.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)

    def progress(value: float, detail: str) -> None:
        store.update_job(job_id, progress=value, detail=detail)

    try:
        with open(log_path, "w", encoding="utf-8") as handle:
            store.update_job(job_id, status="preparing", progress=0.02, detail="scansione originali")
            previous_status = (manifest.get("status") or "draft")
            store.set_status(character_id, "analyzing")
            # Re-analyze pulita: niente doppi conteggi (righe + normalizzate + embedding).
            for old in store.list_assets(character_id):
                store.delete_asset(character_id, old["id"])
            for stale in (char_dir / "normalized").glob("*.jpg"):
                try:
                    stale.unlink()
                except OSError:
                    pass
            for stale in (char_dir / "cache").glob("*.emb.json"):
                try:
                    stale.unlink()
                except OSError:
                    pass
            # Reference pack e dataset precedenti: mai stale in giro.
            refs_dir = char_dir / "references"
            if refs_dir.is_dir():
                for stale in refs_dir.iterdir():
                    try:
                        if stale.is_file() or stale.is_symlink():
                            stale.unlink()
                    except OSError:
                        pass
            else:
                refs_dir.mkdir(parents=True, exist_ok=True)
            originals = sorted(
                p for p in (char_dir / "originals").iterdir()
                if p.is_file() and p.suffix.lower() in ALLOWED_EXTS
            )
            log(handle, f"backend volti: {available_backends()} | file: {len(originals)}")
            if not originals:
                raise RuntimeError("nessuna foto caricata")

            infos: list[dict] = []
            seen_hashes: list[tuple[str, str]] = []  # (asset_id, dhash)
            total = len(originals)
            for index, src in enumerate(originals):
                asset_id = f"{job_id[:8]}-{index:03d}"
                info: dict = {"id": asset_id, "original_path": str(src)}
                try:
                    info["sha256"] = sha256_file(src)
                    width, height = image_size(src)
                    info["width"], info["height"] = width, height
                    info["dhash"] = dhash_hex(src)
                    info["phash"] = info["dhash"]  # persistito in character_assets.phash
                    for other_id, other_hash in seen_hashes:
                        if hamming_hex(info["dhash"], other_hash) <= DUP_HAMMING:
                            info["duplicate_of"] = other_id
                            break
                    seen_hashes.append((asset_id, info["dhash"]))
                    norm_path = char_dir / "normalized" / f"{asset_id}.jpg"
                    if not info.get("duplicate_of"):
                        width, height = save_normalized(src, norm_path)
                        info["normalized_path"] = str(norm_path)
                        info["width"], info["height"] = width, height
                        info["brightness"] = round(mean_brightness(norm_path), 3)
                        info["blur_score"] = laplacian_variance(norm_path)
                        faces = detect_faces(norm_path)
                        info["face_count"] = len(faces)
                        if faces:
                            best = max(faces, key=lambda f: (f["bbox"][2] - f["bbox"][0]) * (f["bbox"][3] - f["bbox"][1]))
                            x1, y1, x2, y2 = best["bbox"]
                            area = max(0, x2 - x1) * max(0, y2 - y1)
                            info["face_ratio"] = round(area / max(1, width * height), 4)
                            info["face_quality"] = round(float(best["score"]), 3)
                            info["yaw"] = best["yaw"]
                            info["pitch"] = best["pitch"]
                            info["dominant_subject"] = len(faces) == 1 or area > 0.5 * sum(
                                (f["bbox"][2] - f["bbox"][0]) * (f["bbox"][3] - f["bbox"][1]) for f in faces
                            )
                            if best["embedding"]:
                                emb_path = char_dir / "cache" / f"{asset_id}.emb.json"
                                emb_path.write_text(json.dumps(best["embedding"]), encoding="utf-8")
                                os.chmod(emb_path, 0o600)  # vettore biometrico
                                info["embedding_path"] = str(emb_path)
                        info["angle_bucket"] = classify_pose(
                            info.get("yaw"), float(info.get("face_ratio", 0.0)), width, height
                        )
                        info["shot"] = shot_scale(float(info.get("face_ratio", 0.0)))
                except Exception as exc:  # noqa: BLE001 - singolo file rotto non ferma tutto
                    info["corrupt"] = str(exc)[:200]
                    log(handle, f"{src.name}: CORROTTO ({info['corrupt']})")
                infos.append(info)
                progress(0.02 + 0.55 * (index + 1) / total, f"analisi {index + 1}/{total}")

            # Cluster identita: centroide embedding volti principali.
            store.update_job(job_id, status="preparing", progress=0.60, detail="cluster identita")
            vectors: dict[str, list[float]] = {}
            for info in infos:
                emb_file = info.get("embedding_path")
                if emb_file and os.path.isfile(emb_file):
                    try:
                        vectors[info["id"]] = json.loads(Path(emb_file).read_text(encoding="utf-8"))
                    except ValueError:
                        continue
            center = centroid(list(vectors.values()))
            have_embedding = center is not None
            for info in infos:
                if info["id"] in vectors and center is not None:
                    info["identity_sim"] = round(cosine(vectors[info["id"]], center), 3)
                verdict, reason = classify_asset(info, have_embedding)
                info["accepted"] = verdict
                info["rejection_reason"] = reason
                # face_quality in DB = score 0..1 per ranking (non piu detection score).
                info["face_quality"] = quality_score(info)
                store.insert_asset(character_id, info)
            accepted = [i for i in infos if i["accepted"] in ("accepted", "warning")]
            log(handle, f"accepted={sum(1 for i in infos if i['accepted']=='accepted')} "
                        f"warning={sum(1 for i in infos if i['accepted']=='warning')} "
                        f"rejected={sum(1 for i in infos if i['accepted']=='rejected')} "
                        f"embedding={'si' if have_embedding else 'no'}")

            # Split stratificato + reference pack (symlink, mai copie).
            usable = [
                {"id": i["id"], "angle_bucket": i.get("angle_bucket", "unknown"),
                 "face_quality": i.get("face_quality", 0.0)}
                for i in accepted
            ]
            split = stratified_split(usable)
            store.set_split_many(split)
            refs_dir.mkdir(parents=True, exist_ok=True)
            picks = pick_references(usable)
            ref_slots: dict[str, dict] = {}
            for bucket, ids in picks.items():
                for pos, asset_id in enumerate(ids):
                    info = next(i for i in infos if i["id"] == asset_id)
                    norm = info.get("normalized_path")
                    if not norm:
                        continue
                    link = refs_dir / f"{bucket}{'-' + str(pos + 1) if pos else ''}.jpg"
                    try:
                        if link.exists() or link.is_symlink():
                            link.unlink()
                        os.symlink(norm, link)
                    except OSError:
                        import shutil

                        shutil.copy2(norm, link)
                    slot = bucket if pos == 0 else f"{bucket}_{pos + 1}"
                    ref_slots[slot] = {"asset_id": asset_id, "path": str(link)}
            store.set_references(character_id, ref_slots)
            progress(0.80, "dataset e reference pronti")

            # Caption + subject_refs + JSONL (solo split train).
            trigger = manifest["trigger_token"]
            norm_of = {i["id"]: i.get("normalized_path", "") for i in infos}
            records = []
            for info in accepted:
                if split.get(info["id"]) != "train" or not info.get("normalized_path"):
                    continue
                refs = [
                    {"type": "image", "path": norm_of[r]}
                    for r in pick_subject_refs(info["id"], usable)
                    if norm_of.get(r)
                ]
                if not refs:
                    continue  # Musubi richiede >=1 reference per record
                records.append(
                    {
                        "image_path": info["normalized_path"],
                        "caption": build_caption(trigger, info),
                        "references": refs,
                    }
                )
            # Sempre scritto (anche vuoto): mai dataset stale di run precedenti.
            write_dataset_jsonl(char_dir / "training" / "dataset.jsonl", records)
            store.record_dataset_stats(character_id, split, diversity_report(usable))
            progress(0.95, f"{len(records)} record training")

            usable_count = len(accepted)
            if usable_count >= 20:
                store.set_status(character_id, "ready_to_train")
                store.update_job(job_id, status="ready", progress=1.0,
                                 detail=f"{usable_count} foto utilizzabili")
                log(handle, f"OK: {usable_count} foto utilizzabili, dataset.jsonl con {len(records)} record")
            else:
                store.set_status(character_id, "draft")
                store.update_job(job_id, status="failed", progress=1.0,
                                 detail=f"solo {usable_count} foto utilizzabili (minimo 20)",
                                 error=f"foto insufficienti: {usable_count}/20")
                log(handle, f"INSUFFICIENTI: {usable_count}/20")
        store.close()
        return 0
    except Exception as exc:  # noqa: BLE001 - il job non deve mai restare appeso
        try:
            store.update_job(job_id, status="failed", progress=1.0, detail="errore pipeline",
                             error=str(exc)[:500])
            # Non degradare un personaggio che aveva gia un modello: torna allo
            # stato precedente (solo draft/analyzing -> failed).
            try:
                fallback = previous_status
            except NameError:
                fallback = "failed"
            store.set_status(character_id, fallback if fallback not in ("draft", "analyzing") else "failed")
            with open(log_path, "a", encoding="utf-8") as handle:
                handle.write(traceback.format_exc())
        finally:
            store.close()
        print(f"analyze fallita: {exc}", flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
