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
import time
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

    if len(argv) not in (5, 6, 7):
        print("uso: worker_train <root> <character_id> <job_id> <version> [max_steps] [eval_steps_csv]", flush=True)
        return 2
    try:
        version_arg = int(argv[4])
        steps_arg = int(argv[5]) if len(argv) >= 6 else MAX_TRAIN_STEPS
        eval_csv = argv[6] if len(argv) == 7 else ""
    except ValueError:
        print(f"version/max_steps non validi: {argv[4:]}", flush=True)
        return 2
    from character_id.training import MAX_ALLOWED_STEPS, MIN_TRAIN_STEPS, default_eval_steps

    if not MIN_TRAIN_STEPS <= steps_arg <= MAX_ALLOWED_STEPS:
        print(f"max_steps fuori range {MIN_TRAIN_STEPS}..{MAX_ALLOWED_STEPS}", flush=True)
        return 2
    root, character_id, job_id, version = argv[1], argv[2], argv[3], version_arg
    max_steps = steps_arg
    # Watchdog proporzionato: 45s/step stimati + 1h margine (minimo 14h).
    stall_limit_s = 6 * 3600
    absolute_limit_s = max(14 * 3600, int(max_steps * 45 + 3600))
    max_steps = steps_arg
    store = CharacterStore(root)
    manifest = store.get_character(character_id)
    if manifest is None:
        store.update_job(job_id, status="failed", progress=1.0,
                         detail="character eliminato prima del training",
                         error="character non trovato")
        store.close()
        print(f"character non trovato: {character_id}", flush=True)
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
    env["PYTORCH_CUDA_ALLOC_CONF"] = "expandable_segments:True"
    # Rete ON per le cache: il processor Qwen3-VL va preso dall'hub al primo
    # giro (pochi KB, poi restano in cache). OFF solo per il train lungo
    # (pesi tutti locali: mai download a sorpresa di GB durante 500 step).
    gpu_index = 1
    backends_stopped: list[str] = []
    chained_eval = False  # True: la GPU resta all'eval, niente restore qui
    heartbeat = None
    try:
        from character_id.training import start_heartbeat

        heartbeat = start_heartbeat(store, job_id)
        progress(0.02, "acquisizione GPU", "training")
        store.set_status(character_id, "training")
        # Prima si scaricano i backend, POI si misura: con Qwen residente
        # nessuna GPU passa mai il gate (fail stupido prima di iniziare).
        write_lock(root, {"job_id": job_id, "character_id": character_id,
                          "pid": os.getpid(), "pgid": os.getpgid(0),
                          "gpu": -1, "started": utcnow()})
        progress(0.04, "scarico backend dalla VRAM")
        for svc in ("hermes-tabby.service", "hermes-comfyui.service", "hermes-comfyui-direct.service"):
            code, _ = systemctl("stop", svc)
            if code == 0:
                backends_stopped.append(svc)
        time.sleep(10)
        try:
            gpu_index = pick_gpu()
        except RuntimeError as exc:
            raise RuntimeError(f"GPU non libera per il training: {exc}") from exc
        env["CUDA_VISIBLE_DEVICES"] = str(gpu_index)
        write_lock(root, {"job_id": job_id, "character_id": character_id,
                          "pid": os.getpid(), "pgid": os.getpgid(0),
                          "gpu": gpu_index, "started": utcnow()})
        store.update_job(job_id, detail=f"gpu{gpu_index} acquisita")
        free = gpu_free_mb(gpu_index)
        if free < 13000.0:
            raise RuntimeError(f"VRAM insufficiente su gpu{gpu_index}: {free:.0f} MB liberi")
        store.update_job(job_id, detail=f"VRAM libera {free:.0f} MB, temp {gpu_temp(gpu_index):.0f}C")

        toml = dataset_toml(char_dir, char_dir / "training" / "dataset.jsonl",
                            char_dir / "training" / f"cache-v{version}")
        if not (char_dir / "training" / "dataset.jsonl").is_file():
            raise RuntimeError("dataset.jsonl assente: riesegui analyze prima del training")
        progress(0.06, "cache latenti (ref2va)", "caching")
        code = run_logged(cache_latents_cmd(toml), log_path, env, Path(TRAINER_SRC))
        if code != 0:
            raise RuntimeError(f"cache latenti fallita (exit {code}), vedi log")
        progress(0.20, "cache testo (subject_ref)")
        code = run_logged(cache_text_cmd(toml), log_path, env, Path(TRAINER_SRC))
        if code != 0:
            raise RuntimeError(f"cache testo fallita (exit {code}), vedi log")

        progress(0.26, f"training avviato ({max_steps} step)", "training")
        # Recovery onesta: ripartenza pulita (cache presenti via --skip_existing,
        # checkpoint precedenti conservati in out_dir), mai resume presunto.
        env_train = dict(env, HF_HUB_OFFLINE="1")
        try:
            train_log = open(log_path, "ab")  # noqa: PTH123 - chiusura esplicita sotto
            train_process = subprocess.Popen(
                train_cmd(toml, out_dir, max_steps=max_steps), env=env_train, cwd=Path(TRAINER_SRC),
                stdout=train_log, stderr=subprocess.STDOUT,
            )
        except OSError as exc:
            raise RuntimeError(f"avvio trainer fallito: {exc}") from exc
        store.update_job(job_id, detail=f"musubi pid {train_process.pid} (gruppo: worker {os.getpid()})")
        # NOTA: il pid job resta quello del worker (leader del gruppo): /cancel
        # fa killpg su di esso. Il pid musubi e solo informativo nel detail.
        # Progress dai checkpoint (mai simulato: solo step reali su disco).
        # Watchdog: stall recupero (nessun ckpt per 6h) o tetto proporzionato.
        last_seen = -1
        last_ckpt_ts = time.monotonic()
        started_ts = time.monotonic()
        while train_process.poll() is None:
            steps = ckpt_steps_in(out_dir)
            done = [s for s in steps if s <= max_steps]
            if done and done[-1] != last_seen:
                last_seen = done[-1]
                last_ckpt_ts = time.monotonic()
                store.update_job(job_id, progress=0.26 + 0.64 * min(1.0, last_seen / max_steps),
                                 detail=f"step {last_seen}/{max_steps}")
            now = time.monotonic()
            if now - started_ts > absolute_limit_s or (last_seen > 0 and now - last_ckpt_ts > stall_limit_s):
                train_process.terminate()
                try:
                    train_process.wait(timeout=300)
                except subprocess.TimeoutExpired:
                    train_process.kill()
                train_log.close()
                raise RuntimeError("training stallato (watchdog): ucciso, checkpoint conservati")
            time.sleep(30)
        if train_process.returncode != 0:
            train_log.close()
            raise RuntimeError(f"training fallito (exit {train_process.returncode}), vedi log")
        train_log.close()

        steps = ckpt_steps_in(out_dir)
        if not steps:
            raise RuntimeError("nessun checkpoint prodotto")
        progress(0.92, f"checkpoint: {steps}")
        # Concatena eval+benchmark (stesso lock GPU): job evaluate dedicato.
        # Stesso gruppo processi del train (niente start_new_session): /cancel
        # uccide tutto l'albero, mai orfani che tengono la GPU.
        from character_id.training import TRAINER_VENV_PY

        eval_job = store.create_job(character_id, "evaluate", version)
        chained_eval = True  # da qui: niente restore/reset, la GPU resta all'eval
        eval_steps = eval_csv or ",".join(str(s) for s in default_eval_steps(max_steps))
        try:
            eval_log = open(log_path, "ab")  # noqa: PTH123 - il worker esce subito dopo
            subprocess.Popen(
                [TRAINER_VENV_PY, "-m", "character_id.worker_evaluate",
                 str(store.root), character_id, eval_job["id"], str(version), eval_steps],
                env=env, cwd=Path(__file__).resolve().parent.parent,
                stdout=eval_log, stderr=subprocess.STDOUT,
            )
        except OSError as exc:
            chained_eval = False
            store.update_job(eval_job["id"], status="failed", error=f"spawn eval: {exc}")
            raise RuntimeError(f"spawn eval fallito: {exc}") from exc
        finally:
            try:
                eval_log.close()
            except (NameError, OSError):
                pass
        store.update_job(job_id, status="ready", progress=1.0,
                         detail=f"training ok, eval {eval_job['id'][:8]} in corso")
        store.set_status(character_id, "validating")
        if heartbeat is not None:
            heartbeat.set()
        store.close()
        return 0
    except Exception as exc:  # noqa: BLE001 - job mai appeso, GPU sempre liberata
        try:
            if heartbeat is not None:
                heartbeat.set()
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
        if not chained_eval:
            try:
                subprocess.run(["nvidia-smi", "--gpu-reset", "-i", str(gpu_index)],
                               capture_output=True, timeout=60)
            except (OSError, ValueError):
                pass
            clear_lock(root)
            for svc in backends_stopped:
                systemctl("start", svc)
        # Se chained: reset/lock/restore li fa worker_evaluate alla fine
        # (la GPU non deve mai resettarsi sotto i piedi dell'eval).


if __name__ == "__main__":
    sys.exit(main(sys.argv))
