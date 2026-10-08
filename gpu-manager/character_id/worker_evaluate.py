"""Worker evaluation + benchmark Character LoRA (trainer venv, GPU del training lock).

Uso: python -m character_id.worker_evaluate <root> <character_id> <job_id> <version>

Per checkpoint 100/250/500: suite 8 prompt (seed fissi) one-frame + 2 video brevi
(temporal) + 2 prompt senza trigger (containment/preservation vs base).
Poi sweep strength sul migliore, benchmark Ref2VA, selezione checkpoint,
copia character_vN + symlink Comfy, recommended_engine, ready/needs_retrain.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import traceback
from pathlib import Path


def sh(cmd: list[str], log_path: Path, env: dict, cwd: Path) -> int:
    with open(log_path, "ab") as handle:
        handle.write(("+ " + " ".join(cmd) + "\n").encode())
        handle.flush()
        return subprocess.Popen(cmd, stdout=handle, stderr=subprocess.STDOUT,
                                env=env, cwd=cwd).wait()


def extract_frames(video: Path, count: int = 4) -> list[Path]:
    """Frame equidistanti via av (trainer venv)."""
    import av

    out: list[Path] = []
    container = av.open(str(video))
    stream = container.streams.video[0]
    total = stream.frames or 0
    picks = set()
    if total > 0:
        picks = {round(total * (i + 1) / (count + 1)) for i in range(count)}
    frames = []
    for index, frame in enumerate(container.decode(stream)):
        if not picks or index in picks:
            frames.append(frame)
        if len(frames) >= count:
            break
    container.close()
    for i, frame in enumerate(frames[:count]):
        dest = video.parent / f"{video.stem}_f{i}.png"
        frame.to_image().save(dest)
        out.append(dest)
    return out


def main(argv: list[str]) -> int:
    from character_id.dataset import cosine
    from character_id.store import CharacterStore
    from character_id.training import (
        AUDIO_VAE,
        CHECKPOINT_STEPS,
        DIT_FL2VA,
        DIT_REF2VA,
        EVAL_SUITE,
        STRENGTH_SWEEP,
        TEXT_ENCODER,
        TRAINER_SRC,
        VIDEO_VAE,
        clear_lock,
        read_lock,
        systemctl,
    )

    root, character_id, job_id, version = argv[1], argv[2], argv[3], int(argv[4])
    store = CharacterStore(root)
    manifest = store.get_character(character_id)
    if manifest is None:
        print("character non trovato", flush=True)
        return 2
    char_dir = store.char_dir(character_id)
    out_dir = char_dir / "models" / "fl2va" / f"v{version}"
    eval_dir = char_dir / "metrics" / f"v{version}"
    eval_dir.mkdir(parents=True, exist_ok=True)
    log_path = char_dir / "logs" / f"eval-v{version}-{job_id[:8]}.log"
    trigger = manifest["trigger_token"]
    centroid = store.identity_centroid(character_id)

    try:
        from insightface.app import FaceAnalysis

        face_app = FaceAnalysis(name="buffalo_l",
                                root="/opt/hermes/character-id/models/face")
        face_app.prepare(ctx_id=-1)
    except ImportError:
        face_app = None

    def progress(value: float, detail: str) -> None:
        store.update_job(job_id, progress=value, detail=detail)

    def embed(image: Path) -> list[float] | None:
        if face_app is None:
            return None
        try:
            import cv2

            picture = cv2.imread(str(image))
            if picture is None:
                return None
            faces = face_app.get(picture)
            if not faces:
                return None
            best = max(faces, key=lambda f: float(getattr(f, "det_score", 0.0)))
            emb = best.get("embedding")
            return [float(v) for v in emb] if emb is not None else None
        except Exception:  # noqa: BLE001 - singolo frame fallito, si salta
            return None

    env = dict(os.environ)
    env["PYTHONPATH"] = str(Path(TRAINER_SRC) / "src") + os.pathsep + env.get("PYTHONPATH", "")
    env["HF_HUB_OFFLINE"] = "1"
    lock = read_lock(root) or {}
    env["CUDA_VISIBLE_DEVICES"] = str(lock.get("gpu", 1))

    def generate(prompt: str, seed: int, name: str, lora: Path | None = None,
                 strength: float = 1.0, dit: str = DIT_FL2VA, refs: list[str] | None = None,
                 video_frames: int = 0) -> Path | None:
        dest = eval_dir / f"{name}.png" if not video_frames else eval_dir / f"{name}.mp4"
        cmd = [
            sys.executable, f"{TRAINER_SRC}/minimax_h3_generate_video.py",
            "--task", "t2va" if not refs else "ref2va",
            "--dit", dit,
            "--text_encoder", TEXT_ENCODER,
            "--video_vae", VIDEO_VAE, "--audio_vae", AUDIO_VAE,
            "--prompt", prompt, "--seed", str(seed),
            "--video_size", "768", "768",
            "--infer_steps", "20" if not video_frames else "8",
            "--blocks_to_swap", "48",
            "--save_path", str(dest),
        ]
        if video_frames:
            cmd += ["--video_length", str(video_frames)]
        else:
            cmd += ["--video_length", "1"]
        if lora is not None:
            cmd += ["--lora_weight", str(lora), "--lora_multiplier", str(strength)]
        for ref in refs or []:
            cmd += ["--ref", ref]
        code = sh(cmd, log_path, env, Path(TRAINER_SRC))
        return dest if code == 0 and dest.is_file() else None

    def identity_of(image: Path) -> float | None:
        if centroid is None:
            return None
        emb = embed(image)
        return round(cosine(emb, centroid), 3) if emb else None

    try:
        store.set_status(character_id, "validating")
        ckpts = {s: out_dir / f"character-{s}.safetensors" for s in CHECKPOINT_STEPS}
        ckpts = {s: p for s, p in ckpts.items() if p.is_file()}
        if not ckpts:
            raise RuntimeError("nessun checkpoint da valutare")
        results: dict[str, dict] = {}
        total_units = len(ckpts) * (len(EVAL_SUITE) + 2 + 2)
        done_units = 0

        for step, ckpt in sorted(ckpts.items()):
            sims: list[float] = []
            for index, (prompt, seed) in enumerate(EVAL_SUITE):
                image = generate(f"{trigger} photo of <Subject 1>, {prompt}", seed,
                                 f"ckpt{step}_p{index}", lora=ckpt)
                done_units += 1
                progress(0.05 + 0.55 * done_units / total_units, f"ckpt {step} prompt {index + 1}/8")
                if image is None:
                    continue
                sim = identity_of(image)
                if sim is not None:
                    sims.append(sim)
            # Temporal: 2 video brevi, frame consecutivi.
            temp: list[float] = []
            for vindex, (prompt, seed) in enumerate((EVAL_SUITE[1], EVAL_SUITE[5])):
                video = generate(f"{trigger} photo of <Subject 1>, {prompt}", seed,
                                 f"ckpt{step}_v{vindex}", lora=ckpt, video_frames=39)
                done_units += 1
                if video is None:
                    continue
                embs = [e for f in extract_frames(video) if (e := embed(f))]
                temp += [cosine(embs[i], embs[i + 1]) for i in range(len(embs) - 1)]
            # Containment + preservation: prompt SENZA trigger, con e senza LoRA.
            cont: list[float] = []
            pres_faces = 0
            for cindex, (prompt, seed) in enumerate((EVAL_SUITE[0], EVAL_SUITE[3])):
                plain = generate(prompt, seed, f"ckpt{step}_c{cindex}")
                lora_img = generate(prompt, seed, f"ckpt{step}_cl{cindex}", lora=ckpt)
                done_units += 2
                if plain is None or lora_img is None or centroid is None:
                    continue
                sim_plain = identity_of(plain)
                sim_lora = identity_of(lora_img)
                if sim_lora is not None:
                    cont.append(max(0.0, 1.0 - sim_lora))
                if sim_plain is not None and sim_lora is not None and sim_plain < 0.35:
                    # Il base non somiglia al character: il LoRA non deve crearne uno.
                    pres_faces += 1
            identity = _avg(sims)
            temporal = _avg(temp) if temp else None
            containment = _avg(cont) if cont else None
            preservation = pres_faces / 2.0
            results[str(step)] = {
                "identity": identity, "identity_median": _median(sims),
                "identity_p10": _p10(sims), "temporal": temporal,
                "containment": containment, "preservation": round(preservation, 3),
                "n_identity_frames": len(sims),
            }
            results[str(step)]["overall"] = _overall(results[str(step)])

        best_step = max(results, key=lambda s: results[s]["overall"] if results[s]["overall"] is not None else -1.0)
        best_ckpt = ckpts[int(best_step)]
        # Sweep strength sul migliore (identita su 2 prompt).
        best_strength, best_id = 0.9, -1.0
        for strength in STRENGTH_SWEEP:
            sweep_sims: list[float] = []
            for index, (prompt, seed) in enumerate((EVAL_SUITE[0], EVAL_SUITE[4])):
                image = generate(f"{trigger} photo of <Subject 1>, {prompt}", seed,
                                 f"strength_{strength}_p{index}", lora=best_ckpt, strength=strength)
                if image is None:
                    continue
                sim = identity_of(image)
                if sim is not None:
                    sweep_sims.append(sim)
            avg = _avg(sweep_sims)
            if avg is not None and avg > best_id:
                best_id, best_strength = avg, strength
        progress(0.75, f"migliore ckpt {best_step} strength {best_strength}")

        # Benchmark Ref2VA: stessi prompt/seed, reference pack, niente LoRA.
        ref_paths = [str(p) for p in sorted((char_dir / "references").glob("*.jpg"))[:5]]
        ref_sims: list[float] = []
        if ref_paths:
            for index, (prompt, seed) in enumerate(EVAL_SUITE):
                image = generate(prompt, seed, f"ref2va_p{index}", dit=DIT_REF2VA, refs=ref_paths)
                progress(0.75 + 0.10 * (index + 1) / len(EVAL_SUITE), f"benchmark ref2va {index + 1}/8")
                if image is None:
                    continue
                sim = identity_of(image)
                if sim is not None:
                    ref_sims.append(sim)
        ref_identity = _avg(ref_sims)
        lora_overall = results[best_step]["overall"] or 0.0
        ref_overall = (0.5 * ref_identity + 0.5 * 1.0) if ref_identity is not None else None
        engine = "lora"
        if ref_overall is not None and ref_overall > lora_overall:
            engine = "reference"

        # Promozione modello + manifest.
        final = char_dir / "models" / "fl2va" / f"character_v{version}.safetensors"
        import shutil as _shutil

        _shutil.copy2(best_ckpt, final)
        store.add_model(character_id, version, "fl2va", "character_lora", str(final),
                        rank=16, alpha=16, strength=best_strength,
                        metrics={"eval": results, "best_step": best_step,
                                 "ref2va_identity": ref_identity})
        store.record_engine(character_id, engine, best_strength,
                            lora_score=lora_overall, ref_score=ref_overall)
        (char_dir / "metrics" / f"eval-v{version}.json").write_text(
            json.dumps({"results": results, "best_step": best_step,
                        "best_strength": best_strength, "ref2va_identity": ref_identity,
                        "recommended_engine": engine}, indent=2), encoding="utf-8")
        progress(0.95, f"engine={engine} ckpt={best_step} s={best_strength}")
        metrics = results[best_step]
        if ((metrics["identity"] or 0) >= 0.5 and (metrics["containment"] or 0) >= 0.5
                and (metrics["preservation"] or 0) >= 0.5):
            store.set_status(character_id, "ready")
            store.update_job(job_id, status="ready", progress=1.0,
                             detail=f"ready (engine {engine}, ckpt {best_step})")
        else:
            store.set_status(character_id, "needs_retrain")
            store.update_job(job_id, status="ready", progress=1.0,
                             detail=f"needs_retrain: {metrics}")
        store.close()
        clear_lock(root)
        systemctl("start", "hermes-tabby.service")
        return 0
    except Exception as exc:  # noqa: BLE001
        try:
            with open(log_path, "ab") as handle:
                handle.write(traceback.format_exc().encode())
            store.update_job(job_id, status="failed", progress=1.0,
                             detail="eval fallita", error=str(exc)[:500])
            store.set_status(character_id, "failed")
        finally:
            store.close()
        clear_lock(root)
        try:
            systemctl("start", "hermes-tabby.service")
        except Exception:  # noqa: BLE001 - il manager riconcilia comunque
            pass
        print(f"eval fallita: {exc}", flush=True)
        return 1


def _avg(values: list[float]) -> float | None:
    if not values:
        return None
    return round(sum(values) / len(values), 3)


def _median(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    mid = len(ordered) // 2
    return round((ordered[mid] + ordered[~mid]) / 2.0, 3)


def _p10(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[max(0, (len(ordered) - 1) // 10)], 3)


def _overall(m: dict) -> float | None:
    if m.get("identity") is None:
        return None
    temporal = m.get("temporal") if m.get("temporal") is not None else m["identity"]
    containment = m.get("containment") if m.get("containment") is not None else 0.5
    preservation = m.get("preservation", 0.5)
    return round(0.5 * m["identity"] + 0.2 * temporal + 0.15 * containment + 0.15 * preservation, 3)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
