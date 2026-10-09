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


# Flag extra per ogni invocazione generate: durate sperimentali (one-frame e
# video brevi 39f sono sotto i 5s rilasciati: senza, il gate rifiuta tutto).
EVAL_EXTRA_ARGS: tuple[str, ...] = ("--allow_experimental_duration",)


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
        ckpt_path,
        clear_lock,
        read_lock,
        systemctl,
    )

    if len(argv) != 5:
        print("uso: worker_evaluate <root> <character_id> <job_id> <version>", flush=True)
        return 2
    try:
        version_arg = int(argv[4])
    except ValueError:
        print(f"version non valida: {argv[4]}", flush=True)
        return 2
    root, character_id, job_id, version = argv[1], argv[2], argv[3], version_arg
    store = CharacterStore(root)
    store.update_job(job_id, pid=os.getpid(), detail="eval avviata")
    manifest = store.get_character(character_id)
    if manifest is None:
        store.update_job(job_id, status="failed", progress=1.0,
                         detail="character eliminato prima dell'eval",
                         error="character non trovato")
        store.close()
        print(f"character non trovato: {character_id}", flush=True)
        return 2
    char_dir = store.char_dir(character_id)
    out_dir = char_dir / "models" / "fl2va" / f"v{version}"
    eval_dir = char_dir / "metrics" / f"v{version}"
    eval_dir.mkdir(parents=True, exist_ok=True)
    log_path = char_dir / "logs" / f"eval-v{version}-{job_id[:8]}.log"
    trigger = manifest["trigger_token"]
    centroid = store.identity_centroid(character_id)

    env = dict(os.environ)
    env["PYTHONPATH"] = str(Path(TRAINER_SRC) / "src") + os.pathsep + env.get("PYTHONPATH", "")
    # Rete ON: generate CLI + processor come nel train (pesi da path locali).
    lock = read_lock(root) or {}
    env["CUDA_VISIBLE_DEVICES"] = str(lock.get("gpu", 1))

    def progress(value: float, detail: str) -> None:
        store.update_job(job_id, progress=value, detail=detail)

    heartbeat = None

    def _base_cmd(dit: str, lora: Path | None, strength: float,
                  refs: list[str] | None) -> list[str]:
        cmd = [
            sys.executable, f"{TRAINER_SRC}/minimax_h3_generate_video.py",
            "--task", "t2va" if not refs else "ref2va",
            "--dit", dit,
            "--text_encoder", TEXT_ENCODER,
            "--video_vae", VIDEO_VAE, "--audio_vae", AUDIO_VAE,
            "--blocks_to_swap", "48",
            "--save_path", str(eval_dir),
        ]
        cmd += list(EVAL_EXTRA_ARGS)
        if lora is not None:
            cmd += ["--lora_weight", str(lora), "--lora_multiplier", str(strength)]
        for ref in refs or []:
            cmd += ["--ref", ref]
        return cmd

    def _single(prompt: str, seed: int, name: str, frames: int, steps: int,
                dit: str, lora: Path | None, strength: float,
                refs: list[str] | None) -> Path | None:
        dest = eval_dir / (f"{name}.png" if frames == 1 else f"{name}.mp4")
        cmd = _base_cmd(dit, lora, strength, refs) + [
            "--prompt", prompt, "--seed", str(seed),
            "--video_size", "768", "768",
            "--infer_steps", str(steps),
            "--video_length", str(frames),
            "--save_path", str(dest),
        ]
        code = sh(cmd, log_path, env, Path(TRAINER_SRC))
        return dest if code == 0 and dest.is_file() else None

    def _batch(items: list[dict], dit: str, lora: Path | None, strength: float,
               refs: list[str] | None) -> dict[str, Path | None]:
        """Una invocazione --from_file (modelli caricati una volta) + fallback singoli.

        items: [{prompt, seed, name, frames, steps}]. Ritorna {name: path|None}.
        """
        lines_path = eval_dir / f"batch_{lora.name if lora else 'base'}_{len(items)}.txt"
        with open(lines_path, "w", encoding="utf-8") as handle:
            for item in items:
                frames = int(item.get("frames", 1))
                handle.write(
                    f"{item['prompt']} --w 768 --h 768 --f {frames}"
                    f" --s {int(item.get('steps', 20))} --d {int(item['seed'])}"
                    f" --o {item['name']}\n"
                )
        cmd = _base_cmd(dit, lora, strength, refs) + ["--from_file", str(lines_path)]
        code = sh(cmd, log_path, env, Path(TRAINER_SRC))
        results: dict[str, Path | None] = {}
        for item in items:
            name = str(item["name"])
            frames = int(item.get("frames", 1))
            for suffix in (".png", ".mp4") if frames == 1 else (".mp4", ".png"):
                candidate = eval_dir / f"{name}{suffix}"
                if candidate.is_file():
                    results[name] = candidate
                    break
            else:
                results[name] = None
        if code != 0 or not any(results.values()):
            # Fallback onesto: righe singole (pattern documentato upstream).
            for item in items:
                if results[str(item["name"])] is None:
                    results[str(item["name"])] = _single(
                        str(item["prompt"]), int(item["seed"]), str(item["name"]),
                        int(item.get("frames", 1)), int(item.get("steps", 20)),
                        dit, lora, strength, refs)
        return results

    try:
        try:
            from insightface.app import FaceAnalysis

            face_app = FaceAnalysis(name="buffalo_l",
                                    root="/opt/hermes/character-id/models/face")
            face_app.prepare(ctx_id=-1)
        except ImportError as exc:
            raise RuntimeError(f"insightface non disponibile nel trainer venv: {exc}") from exc
        if centroid is None:
            raise RuntimeError("nessun embedding nel dataset: riesegui analyze prima dell'eval")

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

        def identity_of(image: Path) -> float | None:
            emb = embed(image)
            return round(cosine(emb, centroid), 3) if emb else None

        store.set_status(character_id, "validating")
        store.update_job(job_id, status="validating", progress=0.03,
                         detail="suite eval avviata")
        from character_id.training import start_heartbeat as _start_heartbeat

        heartbeat = _start_heartbeat(store, job_id)

        ckpts = {s: ckpt_path(out_dir, "character", s) for s in CHECKPOINT_STEPS}
        ckpts = {s: p for s, p in ckpts.items() if p.is_file()}
        if not ckpts:
            raise RuntimeError("nessun checkpoint da valutare")
        results: dict[str, dict] = {}
        total_units = len(ckpts) * (len(EVAL_SUITE) + 2 + 2)  # suite + containment-lora + video
        done_units = 0

        # Containment base (senza LoRA): stessi prompt/seed per tutti i ckpt,
        # un solo batch globale invece di ripeterlo per ogni checkpoint.
        base_items = [
            {"prompt": prompt, "seed": seed, "name": f"base_c{cindex}",
             "frames": 1, "steps": 20}
            for cindex, (prompt, seed) in enumerate((EVAL_SUITE[0], EVAL_SUITE[3]))
        ]
        base_out = _batch(base_items, DIT_FL2VA, None, 1.0, None)
        progress(0.08, "baseline senza LoRA pronta")

        for step, ckpt in sorted(ckpts.items()):
            items = [
                {"prompt": f"{trigger} photo of <Subject 1>, {prompt}", "seed": seed,
                 "name": f"ckpt{step}_p{index}", "frames": 1, "steps": 20}
                for index, (prompt, seed) in enumerate(EVAL_SUITE)
            ]
            items += [
                {"prompt": f"{trigger} photo of <Subject 1>, {prompt}", "seed": seed,
                 "name": f"ckpt{step}_cl{cindex}", "frames": 1, "steps": 20}
                for cindex, (prompt, seed) in enumerate((EVAL_SUITE[0], EVAL_SUITE[3]))
            ]
            items += [
                {"prompt": f"{trigger} photo of <Subject 1>, {prompt}", "seed": seed,
                 "name": f"ckpt{step}_v{vindex}", "frames": 39, "steps": 8}
                for vindex, (prompt, seed) in enumerate((EVAL_SUITE[1], EVAL_SUITE[5]))
            ]
            out = _batch(items, DIT_FL2VA, ckpt, 1.0, None)
            done_units += len(items)
            progress(0.08 + 0.47 * done_units / total_units, f"ckpt {step}: {sum(1 for v in out.values() if v)}/{len(items)} output")
            if not any(out.values()):
                raise RuntimeError(f"generate CLI rotto su ckpt {step}: zero output (vedi log)")
            sims: list[float] = []
            for index in range(len(EVAL_SUITE)):
                image = out.get(f"ckpt{step}_p{index}")
                if image is None:
                    continue
                sim = identity_of(image)
                if sim is not None:
                    sims.append(sim)
            # Temporal: 2 video brevi, frame consecutivi.
            temp: list[float] = []
            for vindex in range(2):
                video = out.get(f"ckpt{step}_v{vindex}")
                if video is None:
                    continue
                embs = [e for f in extract_frames(video) if (e := embed(f))]
                temp += [cosine(embs[i], embs[i + 1]) for i in range(len(embs) - 1)]
            # Containment + preservation: prompt SENZA trigger, con e senza LoRA.
            cont: list[float] = []
            pres_faces = 0
            for cindex in range(2):
                plain = base_out.get(f"base_c{cindex}")
                lora_img = out.get(f"ckpt{step}_cl{cindex}")
                if plain is None or lora_img is None or centroid is None:
                    continue
                sim_plain = identity_of(plain)
                sim_lora = identity_of(lora_img)
                if sim_lora is not None:
                    cont.append(max(0.0, 1.0 - sim_lora))
                if sim_plain is not None and sim_lora is not None and sim_plain < 0.35:
                    # Il base non somiglia al character e il LoRA non deve crearne
                    # uno dal nulla; se il base somiglia gia, la coppia non giudica.
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

        if not any(r["overall"] is not None for r in results.values()):
            raise RuntimeError("eval senza misure valide: generate o volti falliti ovunque")
        best_step = max(results, key=lambda s: results[s]["overall"] if results[s]["overall"] is not None else -1.0)
        best_ckpt = ckpts[int(best_step)]
        # Sweep strength sul migliore (identita su 2 prompt, un batch per strength).
        best_strength, best_id = 0.9, -1.0
        for sindex, strength in enumerate(STRENGTH_SWEEP):
            sweep_items = [
                {"prompt": f"{trigger} photo of <Subject 1>, {prompt}", "seed": seed,
                 "name": f"strength_{strength}_p{index}", "frames": 1, "steps": 20}
                for index, (prompt, seed) in enumerate((EVAL_SUITE[0], EVAL_SUITE[4]))
            ]
            sweep_out = _batch(sweep_items, DIT_FL2VA, best_ckpt, strength, None)
            progress(0.60 + 0.15 * (sindex + 1) / len(STRENGTH_SWEEP),
                     f"sweep strength {strength}: {sum(1 for v in sweep_out.values() if v)}/{len(sweep_items)}")
            sweep_sims: list[float] = []
            for index in range(2):
                image = sweep_out.get(f"strength_{strength}_p{index}")
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
            ref_items = [
                {"prompt": prompt, "seed": seed, "name": f"ref2va_p{index}",
                 "frames": 1, "steps": 20}
                for index, (prompt, seed) in enumerate(EVAL_SUITE)
            ]
            ref_out = _batch(ref_items, DIT_REF2VA, None, 1.0, ref_paths)
            for index in range(len(EVAL_SUITE)):
                image = ref_out.get(f"ref2va_p{index}")
                progress(0.75 + 0.10 * (index + 1) / len(EVAL_SUITE), f"benchmark ref2va {index + 1}/8")
                if image is None:
                    continue
                sim = identity_of(image)
                if sim is not None:
                    ref_sims.append(sim)
        ref_identity = _avg(ref_sims)
        lora_overall = results[best_step]["overall"] or 0.0
        # Ref2VA non ha video temporali in benchmark: 0.5*identita + 0.5 neutro.
        # E un confronto pratico (stessi prompt/seed), non una metrica pura.
        ref_overall = (0.5 * ref_identity + 0.5 * 1.0) if ref_identity is not None else None
        engine = "lora"
        if ref_overall is not None and ref_overall > lora_overall:
            engine = "reference"

        # Promozione modello + manifest (copia atomica: mai final parziali letti da export).
        final = char_dir / "models" / "fl2va" / f"character_v{version}.safetensors"
        import shutil as _shutil

        tmp_final = final.with_suffix(".safetensors.tmp")
        _shutil.copy2(best_ckpt, tmp_final)
        os.replace(tmp_final, final)
        # Gate Fase 11: vN attiva solo se >= v(N-1), altrimenti resta la vecchia.
        prev_overall: float | None = None
        if version > 1:
            prev = store.latest_model(character_id, "fl2va")
            if prev:
                try:
                    prev_overall = float((json.loads(prev.get("metrics_json") or "{}")).get("eval_overall"))
                except (ValueError, TypeError):
                    prev_overall = None
        keep_new = prev_overall is None or lora_overall >= prev_overall - 1e-9
        store.add_model(character_id, version, "fl2va", "character_lora", str(final),
                        rank=16, alpha=16, strength=best_strength,
                        metrics={"eval": results, "best_step": best_step,
                                 "eval_overall": lora_overall,
                                 "ref2va_identity": ref_identity,
                                 "superseded": not keep_new})
        if not keep_new:
            # vN peggiore di v(N-1): resta attiva la precedente (rollback automatico).
            store.set_current_version(character_id, version - 1)
        # Preview automatiche (Fase 10): ritratto, mezzo busto, figura intera.
        preview_dir = char_dir / "previews"
        preview_dir.mkdir(parents=True, exist_ok=True)
        preview_items = [
            {"prompt": f"{trigger} photo of <Subject 1>, close-up portrait, neutral studio lighting, still photo",
             "seed": 907, "name": "preview_portrait", "frames": 1, "steps": 20},
            {"prompt": f"{trigger} photo of <Subject 1>, medium shot, soft daylight, still photo",
             "seed": 908, "name": "preview_medium", "frames": 1, "steps": 20},
            {"prompt": f"{trigger} photo of <Subject 1>, full body, outdoor daylight, still photo",
             "seed": 909, "name": "preview_fullbody", "frames": 1, "steps": 20},
        ]
        preview_out = _batch(preview_items, DIT_FL2VA, best_ckpt, best_strength, None)
        for pname in ("portrait", "medium", "fullbody"):
            image = preview_out.get(f"preview_{pname}")
            if image is not None:
                dest = preview_dir / f"{pname}.png"
                if dest.exists():
                    dest.unlink()
                image.rename(dest)
        # Cleanup: solo il vincitore resta (tutti gli step intermedi via).
        winner_name = f"character-step{int(best_step):08d}.safetensors"
        for stale in sorted(out_dir.glob("character-step*.safetensors")):
            if stale.name != winner_name:
                try:
                    stale.unlink()
                except OSError:
                    pass
        store.record_engine(character_id, engine, best_strength,
                            lora_score=lora_overall, ref_score=ref_overall)
        (char_dir / "metrics" / f"eval-v{version}.json").write_text(
            json.dumps({"results": results, "best_step": best_step,
                        "best_strength": best_strength, "ref2va_identity": ref_identity,
                        "recommended_engine": engine}, indent=2), encoding="utf-8")
        progress(0.95, f"engine={engine} ckpt={best_step} s={best_strength}")
        metrics = results[best_step]
        if (not keep_new or (metrics["identity"] or 0) < 0.5
                or (metrics["containment"] or 0) < 0.5 or (metrics["preservation"] or 0) < 0.5):
            store.set_status(character_id, "needs_retrain")
            store.update_job(job_id, status="ready", progress=1.0,
                             detail=f"needs_retrain: {metrics} keep_new={keep_new}")
        else:
            store.set_status(character_id, "ready")
            store.update_job(job_id, status="ready", progress=1.0,
                             detail=f"ready (engine {engine}, ckpt {best_step})")
        store.close()
        if heartbeat is not None:
            heartbeat.set()
        clear_lock(root)
        systemctl("start", "hermes-tabby.service")
        return 0
    except Exception as exc:  # noqa: BLE001
        try:
            if heartbeat is not None:
                heartbeat.set()
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
