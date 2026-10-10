"""Training Character LoRA: ricetta, GPU utils, lock (solo stdlib + subprocess)."""

from __future__ import annotations

import json
import os
import subprocess
import time
from pathlib import Path

MODELS_DIR = "/opt/hermes/models/minimax-h3"
DIT_FL2VA = f"{MODELS_DIR}/minimax_h3_fl2va_pruned_int8_convrot.safetensors"
DIT_REF2VA = f"{MODELS_DIR}/minimax_h3_ref2va_pruned_int8_convrot.safetensors"
TEXT_ENCODER = f"{MODELS_DIR}/qwen3vl_32b_minimax_h3_int8_convrot.safetensors"
VIDEO_VAE = f"{MODELS_DIR}/minimax_h3_video_vae_fp16.safetensors"
AUDIO_VAE = f"{MODELS_DIR}/minimax_h3_audio_vae_fp32.safetensors"

TRAINER_SRC = "/opt/hermes/character-id/trainer/src"
TRAINER_VENV_PY = "/opt/hermes/character-id/trainer/venv/bin/python"

# Ricetta validata upstream (20 immagini, rank 16, ~500 step).
# V2: stesso recipe, piu epoche (lezione LoRA Sydney: 120 epoche). Gli step
# si calcolano come epoche * immagini_train (es. 120 * 30 = 3600).
NETWORK_DIM = 16
NETWORK_ALPHA = 16
LEARNING_RATE = 3e-4
LR_WARMUP_STEPS = 50
MAX_TRAIN_STEPS = 500
MIN_TRAIN_STEPS = 100
MAX_ALLOWED_STEPS = 7200
SIGMA_MIN = 0.15
LOSS_MAG_WEIGHT = 0.5
LOSS_DC_WEIGHT = 0.3
BLOCKS_TO_SWAP = 48
CHECKPOINT_STEPS = (100, 250, 500)
STRENGTH_SWEEP = (0.7, 0.85, 1.0, 1.15)


def default_eval_steps(max_steps: int) -> tuple[int, ...]:
    """Checkpoint da valutare: fissi per 500 step, quarti (griglia 50) oltre."""
    if max_steps <= 500:
        return CHECKPOINT_STEPS
    return tuple(sorted({max(50, round(max_steps * f / 50) * 50) for f in (0.25, 0.5, 0.75, 1.0)}))

# Suite eval fissa (seed fissi, mai cambiare tra checkpoint).
EVAL_SUITE: tuple[tuple[str, int], ...] = (
    ("close-up portrait, neutral studio lighting, still photo", 101),
    ("walking through Tokyo at night, medium shot, cinematic light", 102),
    ("sitting in a modern kitchen, casual clothes, daylight", 103),
    ("standing on a beach at sunset, full body, golden hour", 104),
    ("dramatic side profile, cinematic lighting, dark background", 105),
    ("talking while walking through an office, medium shot", 106),
    ("running outdoors, dynamic camera, daylight", 107),
    ("low-light interior, close-up, soft lamp light", 108),
)

LOCK_NAME = "training.lock"


def lock_path(root: str | Path) -> Path:
    return Path(root) / LOCK_NAME


def read_lock(root: str | Path) -> dict | None:
    try:
        return json.loads(lock_path(root).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except (OSError, ValueError):
        return False
    return True


def training_active(root: str | Path, is_job_active=None) -> dict | None:
    """Lock vivo + (se fornito) job non terminale. Stale = inattivo."""
    lock = read_lock(root)
    if not lock or not pid_alive(int(lock.get("pid", 0))):
        return None
    if is_job_active is not None and not is_job_active(str(lock.get("job_id", ""))):
        return None
    return lock


def write_lock(root: str | Path, payload: dict) -> None:
    path = lock_path(root)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(payload), encoding="utf-8")
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def clear_lock(root: str | Path) -> None:
    try:
        lock_path(root).unlink()
    except OSError:
        pass


def start_heartbeat(store, job_id: str, interval_s: int = 60):
    """Thread heartbeat: ping DB ogni 60s durante fasi silenziose (cache lunghe).

    Il reaper del manager dichiara morto solo chi non pinga da 15 min E ha
    pid morto: mai falsi positivi su fasi lente ma vive. Ritorna evento stop.
    """
    import threading as _threading

    stop = _threading.Event()

    def _beat() -> None:
        while not stop.wait(interval_s):
            try:
                if not store.ping_job(job_id):
                    return
            except Exception:
                return

    thread = _threading.Thread(target=_beat, name=f"hcid-heartbeat-{job_id[:8]}", daemon=True)
    thread.start()
    return stop


def try_claim_lock(root: str | Path, payload: dict, is_job_active=None) -> bool:
    """Claim esclusivo anti-doppio-training (TOCTOU check-then-act).

    O_CREAT|O_EXCL atomico: due POST /train concorrenti, uno solo vince.
    Il perdente deve fallire il suo job. Lock stale (pid morto + nessun job
    attivo) rubato. Ritorna True se il lock e nostro.
    """
    path = lock_path(root)
    data = json.dumps(payload).encode()
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        pass
    else:
        try:
            os.write(fd, data)
        finally:
            os.close(fd)
        return True
    # Esiste gia: stale? (stessi criteri di training_active).
    if training_active(root, is_job_active) is not None:
        return False
    try:
        path.unlink()
    except OSError:
        return False
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        return False
    try:
        os.write(fd, data)
    finally:
        os.close(fd)
    return True


def terminate_tree(pgid: int, sig_term: int = 15, sig_kill: int = 9, wait_s: int = 20) -> bool:
    """SIGTERM poi SIGKILL a un intero gruppo processi. Ritorna True se spento.

    Best-effort anti-orfani GPU: il chiamante verifica prima che il gruppo sia
    davvero nostro (cmdline worker o lock attivo).
    """
    import time as _time

    if pgid <= 1:
        return False
    try:
        os.killpg(pgid, sig_term)
    except (OSError, ValueError):
        return True  # gruppo inesistente: niente da spegnere
    deadline = _time.monotonic() + wait_s
    while _time.monotonic() < deadline:
        try:
            os.killpg(pgid, 0)
        except (OSError, ValueError):
            return True
        _time.sleep(1)
    try:
        os.killpg(pgid, sig_kill)
    except (OSError, ValueError):
        pass
    return True


def gpu_free_mb(index: int) -> float:
    out = subprocess.run(
        ["nvidia-smi", f"--id={index}", "--query-gpu=memory.free",
         "--format=csv,noheader,nounits"],
        capture_output=True, text=True, timeout=30,
    )
    return float(out.stdout.strip().split()[0])


def gpu_temp(index: int) -> float:
    out = subprocess.run(
        ["nvidia-smi", f"--id={index}", "--query-gpu=temperature.gpu",
         "--format=csv,noheader,nounits"],
        capture_output=True, text=True, timeout=30,
    )
    return float(out.stdout.strip().split()[0])


def pick_gpu(min_free_mb: float = 13000.0, preferred: int = 1) -> int:
    """Una sola GPU, esplicita. Solleva se nessuna ha margine (mai fail-open)."""
    for index in (preferred, 1 - preferred):
        try:
            if gpu_free_mb(index) >= min_free_mb:
                return index
        except (OSError, ValueError, IndexError):
            continue
    raise RuntimeError(f"nessuna GPU con {min_free_mb:.0f} MB liberi per il training")


def dataset_toml(char_dir: Path, jsonl: Path, cache_dir: Path, resolution: int = 1024) -> Path:
    toml_path = char_dir / "training" / "images.toml"
    toml_path.write_text(
        "[general]\n"
        f"resolution = [{resolution}, {resolution}]\n"
        "batch_size = 1\nenable_bucket = true\nbucket_no_upscale = false\n\n"
        "[[datasets]]\n"
        f'image_jsonl_file = "{jsonl}"\n'
        f'cache_directory = "{cache_dir}"\n',
        encoding="utf-8",
    )
    return toml_path


def cache_latents_cmd(toml: Path) -> list[str]:
    # Il latent cache codifica solo con le VAE: niente --dit (il transformer
    # non serve e il flag non esiste: exit 2).
    return [
        TRAINER_VENV_PY, f"{TRAINER_SRC}/minimax_h3_cache_latents.py",
        "--dataset_config", str(toml), "--task", "ref2va", "--one_frame",
        "--video_vae", VIDEO_VAE, "--audio_vae", AUDIO_VAE,
        "--cache_seed", "42", "--skip_existing",
    ]


def cache_text_cmd(toml: Path) -> list[str]:
    return [
        TRAINER_VENV_PY, f"{TRAINER_SRC}/minimax_h3_cache_text_encoder_outputs.py",
        "--dataset_config", str(toml), "--task", "t2va", "--one_frame",
        "--teacher_conditions", "subject_ref",
        "--text_encoder", TEXT_ENCODER,
        # TE 32B (26GB) su 16GB: quasi tutto in streaming da CPU (come Comfy
        # che fa encode una tantum con CPU offload; senza: OOM in load).
        # Max 50 (n. layer TE): 48 lascia embeddings + 2 blocchi residenti.
        "--text_encoder_blocks_to_swap", "48",
        "--skip_existing",
    ]


def train_cmd(toml: Path, output_dir: Path, max_steps: int = MAX_TRAIN_STEPS,
              resume: str | None = None, blocks_to_swap: int = BLOCKS_TO_SWAP) -> list[str]:
    launch = [
        TRAINER_VENV_PY, "-m", "accelerate.commands.launch",
        "--num_cpu_threads_per_process", "1", "--mixed_precision", "bf16",
        f"{TRAINER_SRC}/minimax_h3_train_network.py",
        "--dataset_config", str(toml), "--task", "t2va", "--one_frame", "--video_only",
        "--dit", DIT_FL2VA,
        "--network_module", "networks.lora_minimax_h3",
        "--network_dim", str(NETWORK_DIM), "--network_alpha", str(NETWORK_ALPHA),
        "--learning_rate", str(LEARNING_RATE), "--lr_warmup_steps", str(LR_WARMUP_STEPS),
        "--lr_scheduler", "cosine",
        "--max_train_steps", str(max_steps),
        "--h3_teacher_matching", "--h3_teacher_conditions", "subject_ref",
        "--h3_teacher_condition_sigma_min", str(SIGMA_MIN),
        "--h3_teacher_loss_mag_weight", str(LOSS_MAG_WEIGHT),
        "--h3_teacher_loss_dc_weight", str(LOSS_DC_WEIGHT),
        "--mixed_precision", "bf16", "--gradient_checkpointing",
        # Attention backend: SDPA nativo torch (flash su sm_120), zero dipendenze
        # (sageattention/xformers/flash-attn non installati su Blackwell).
        "--sdpa",
        "--optimizer_type", "adamw8bit", "--blocks_to_swap", str(blocks_to_swap),
        # H2D-only: i blocchi tornano su CPU dopo l'uso (senza: race "expected
        # cuda after wait" su GPU strette).
        "--block_swap_h2d_only",
        "--output_dir", str(output_dir), "--output_name", "character",
        # Step frequenti per progress reale + eval 100/250/500. save_last alto:
        # con save_every=50 un save_last piccolo cancellerebbe 100/250 durante
        # il run (remove_step_no); i non-vincitori vengono eliminati dopo
        # la selezione (worker_evaluate).
        "--save_every_n_steps", "50", "--save_last_n_steps", "600",
    ]
    if resume:
        launch += ["--resume", resume]
    return launch


def ckpt_steps_in(output_dir: Path, output_name: str = "character") -> list[int]:
    """Step con checkpoint su disco (nomi Musubi: <name>-step00000100.safetensors)."""
    import re as _re

    pattern = _re.compile(r"^" + _re.escape(output_name) + r"-step(\d{1,8})\.safetensors$")
    steps = []
    if output_dir.is_dir():
        for child in output_dir.iterdir():
            match = pattern.match(child.name)
            if match:
                try:
                    steps.append(int(match.group(1)))
                except ValueError:
                    continue
    return sorted(steps)


def ckpt_path(output_dir: Path, output_name: str, step: int) -> Path:
    return output_dir / f"{output_name}-step{step:08d}.safetensors"


def systemctl(*args: str) -> tuple[int, str]:
    # Il manager gira come matteo + sudo NOPASSWD (stessa policy di manager.py):
    # systemctl nudo fallirebbe e lascerebbe VRAM occupata.
    proc = subprocess.run(["sudo", "-n", "systemctl", *args],
                          capture_output=True, text=True, timeout=120)
    return proc.returncode, (proc.stdout + proc.stderr)[-500:]


def vram_snapshot() -> dict:
    try:
        out = subprocess.run(
            ["nvidia-smi", "--query-gpu=index,memory.used,temperature.gpu",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=30,
        )
        snapshot = {}
        for line in out.stdout.strip().splitlines():
            parts = [p.strip() for p in line.split(",")]
            if len(parts) == 3:
                snapshot[f"gpu{parts[0]}"] = {"used_mb": float(parts[1]), "temp_c": float(parts[2])}
        return snapshot
    except (OSError, ValueError):
        return {}


def utcnow() -> float:
    return time.time()
