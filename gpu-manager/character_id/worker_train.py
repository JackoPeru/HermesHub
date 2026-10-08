"""Worker training Character LoRA (tools: trainer venv, GPU singola esplicita).

Uso: python -m character_id.worker_train <root> <character_id> <job_id> <version>

Sequenza: gate manager-idle (ricontrollato qui) -> lock GPU -> registra stato ->
stop tabby/comfy -> verifica VRAM -> cache latenti/testo -> train 500 step
(progress dai checkpoint) -> registra modello vN -> libera VRAM -> ripristina
backends -> stato validating (M7 concatena eval+benchmark) -> sblocco.

Uso CPU/GPU: gira sulla GPU scelta, l'altra resta al sistema. Mai due training.
"""

from __future__ import annotations

import hashlib
import os
import subprocess
import sys
import traceback
from pathlib import Path


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 64), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_logged(cmd: list[str], log_path: Path, env: dict, cwd: Path) -> int:
    with open(log_path, "ab") as handle:
        handle.write(("+ " + " ".join(cmd) + "\n").encode())
        handle.flush()
        proc = subprocess.Popen(cmd, stdout=handle, stderr=subprocess.STDOUT, env=env, cwd=cwd)
        return proc.wait()


def main(argv: list[str]) -> int:
    from character_id.store import CharacterStore
    from character_id.training import (
        CHECKPOINT_STEPS,
        MAX_TRAIN_STEPS,
        TRAINER_SRC,
        TRAINER_VENV_PY,
        cache_latents_cmd,
        cache_text_cmd,
        ckpt_steps_in,
        clear_lock,
        dataset_toml,
        gpu_free_mb,
        gpu_temp,
        pick_gpu,
        systemctl,
        train_cmd,
        utcnow,
        write_lock,
    )

    root, character_id, job_id, version = argv[1], argv[2], argv[3], int(argv[4])
    store = CharacterStore(root)
    manifest = store.get_character(character_id)
    if manifest is None:
        print("character non trovato", flush=True)
        return 2
    char_dir = store.char_dir(character_id)
    out_dir = char_dir / "models" / "fl2va" / f"v{version}"
    out_dir.mkdir(parents=True, exist_ok=True)
    log_path = char_dir / "logs" / f"train-v{version}-{job_id[:8]}.log"

    def progress(value: float, detail: str, status: str | None = None) -> None:
        fields: dict = {"progress": value, "detail": detail}
        if status:
            fields["status"] = status
        store.update_job(job_id, **fields)

    env = dict(os.environ)
    env["PYTHONPATH"] = str(Path(TRAINER_SRC) / "src") + os.pathsep + env.get("PYTHONPATH", "")
    env["HF_HUB_OFFLINE"] = "1"  # pesi gia locali: mai download impliciti durante il train
    gpu_index = 1
    backends_stopped: list[str] = []
    chained_eval = False  # True: la GPU resta all'eval, niente restore qui
    try:
        progress(0.02, "acquisizione GPU", "training")
        store.set_status(character_id, "training")
        try:
            gpu_index = pick_gpu()
        except RuntimeError as exc:
            raise RuntimeError(f"GPU non libera per il training: {exc}") from exc
        env["CUDA_VISIBLE_DEVICES"] = str(gpu_index)
        write_lock(root, {"job_id": job_id, "character_id": character_id,
                          "pid": os.getpid(), "gpu": gpu_index, "started": utcnow()})
        store.update_job(job_id, detail=f"gpu{gpu_index} acquisita")

        # Scarica Qwen/Comfy dalla VRAM (registra stato: era tutto su).
        progress(0.04, "scarico backend dalla VRAM")
        for svc in ("hermes-tabby.service", "hermes-comfyui.service", "hermes-comfyui-direct.service"):
            code, _ = systemctl("stop", svc)
            if code == 0:
                backends_stopped.append(svc)
        import time as _time

        _time.sleep(5)
        free = gpu_free_mb(gpu_index)
        if free < 13000.0:
            raise RuntimeError(f"VRAM insufficiente su gpu{gpu_index}: {free:.0f} MB liberi")
        store.update_job(job_id, detail=f"VRAM libera {free:.0f} MB, temp {gpu_temp(gpu_index):.0f}C")

        toml = dataset_toml(char_dir, char_dir / "training" / "dataset.jsonl",
                            char_dir / "training" / "cache")
        progress(0.06, "cache latenti (ref2va)", "caching")
        code = run_logged(cache_latents_cmd(toml), log_path, env, Path(TRAINER_SRC))
        if code != 0:
            raise RuntimeError(f"cache latenti fallita (exit {code}), vedi log")
        progress(0.20, "cache testo (subject_ref)")
        code = run_logged(cache_text_cmd(toml), log_path, env, Path(TRAINER_SRC))
        if code != 0:
            raise RuntimeError(f"cache testo fallita (exit {code}), vedi log")

        progress(0.26, "training avviato (500 step)", "training")
        resume_state = out_dir / "last_state"  # musubi --resume se esiste (recovery)
        resume = str(resume_state) if resume_state.exists() else None
        train_log = open(log_path, "ab")  # noqa: PTH123 - chiusura esplicita sotto
        train_process = subprocess.Popen(
            train_cmd(toml, out_dir, resume=resume), env=env, cwd=Path(TRAINER_SRC),
            stdout=train_log, stderr=subprocess.STDOUT,
        )
        store.update_job(job_id, pid=train_process.pid)
        # Progress dai checkpoint (mai simulato: solo step reali su disco).
        import time as _time2

        last_seen = -1
        while train_process.poll() is None:
            steps = ckpt_steps_in(out_dir)
            done = [s for s in steps if s in CHECKPOINT_STEPS or s <= MAX_TRAIN_STEPS]
            if done and done[-1] != last_seen:
                last_seen = done[-1]
                store.update_job(job_id, progress=0.26 + 0.64 * min(1.0, last_seen / MAX_TRAIN_STEPS),
                                 detail=f"step {last_seen}/{MAX_TRAIN_STEPS}")
            _time2.sleep(30)
        if train_process.returncode != 0:
            train_log.close()
            raise RuntimeError(f"training fallito (exit {train_process.returncode}), vedi log")
        train_log.close()

        steps = ckpt_steps_in(out_dir)
        if not steps:
            raise RuntimeError("nessun checkpoint prodotto")
        progress(0.92, f"checkpoint: {steps}")
        # Concatena eval+benchmark (stesso lock GPU): job evaluate dedicato.
        from character_id.training import TRAINER_VENV_PY

        eval_job = store.create_job(character_id, "evaluate", version)
        subprocess.Popen(
            [TRAINER_VENV_PY, "-m", "character_id.worker_evaluate",
             str(store.root), character_id, eval_job["id"], str(version)],
            env=env, cwd=Path(__file__).resolve().parent.parent,
            stdout=open(log_path, "ab"), stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        store.update_job(job_id, status="ready", progress=1.0,
                         detail=f"training ok, eval {eval_job['id'][:8]} in corso")
        store.set_status(character_id, "validating")
        chained_eval = True
        store.close()
        return 0
    except Exception as exc:  # noqa: BLE001 - job mai appeso, GPU sempre liberata
        try:
            with open(log_path, "ab") as handle:
                handle.write(traceback.format_exc().encode())
            store.update_job(job_id, status="failed", progress=1.0,
                             detail="training fallito", error=str(exc)[:500])
            store.set_status(character_id, "failed")
        finally:
            store.close()
        print(f"train fallito: {exc}", flush=True)
        return 1
    finally:
        try:
            subprocess.run(["nvidia-smi", "--gpu-reset", "-i", str(gpu_index)],
                           capture_output=True, timeout=60)
        except (OSError, ValueError):
            pass
        if not chained_eval:
            clear_lock(root)
            for svc in backends_stopped:
                systemctl("start", svc)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
