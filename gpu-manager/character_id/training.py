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
NETWORK_DIM = 16
NETWORK_ALPHA = 16
LEARNING_RATE = 3e-4
LR_WARMUP_STEPS = 50
MAX_TRAIN_STEPS = 500
SIGMA_MIN = 0.15
LOSS_MAG_WEIGHT = 0.5
LOSS_DC_WEIGHT = 0.3
BLOCKS_TO_SWAP = 48
CHECKPOINT_STEPS = (100, 250, 500)
STRENGTH_SWEEP = (0.7, 0.85, 1.0, 1.15)

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
    os.replace(tmp, path)


def clear_lock(root: str | Path) -> None:
    try:
        lock_path(root).unlink()
    except OSError:
        pass


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
    return [
        TRAINER_VENV_PY, f"{TRAINER_SRC}/minimax_h3_cache_latents.py",
        "--dataset_config", str(toml), "--task", "ref2va", "--one_frame",
        "--dit", DIT_FL2VA, "--video_vae", VIDEO_VAE, "--audio_vae", AUDIO_VAE,
        "--cache_seed", "42", "--skip_existing",
    ]


def cache_text_cmd(toml: Path) -> list[str]:
    return [
        TRAINER_VENV_PY, f"{TRAINER_SRC}/minimax_h3_cache_text_encoder_outputs.py",
        "--dataset_config", str(toml), "--task", "t2va", "--one_frame",
        "--teacher_conditions", "subject_ref",
        "--text_encoder", TEXT_ENCODER, "--skip_existing",
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
        "--max_train_steps", str(max_steps),
        "--h3_teacher_matching", "--h3_teacher_conditions", "subject_ref",
        "--h3_teacher_condition_sigma_min", str(SIGMA_MIN),
        "--h3_teacher_loss_mag_weight", str(LOSS_MAG_WEIGHT),
        "--h3_teacher_loss_dc_weight", str(LOSS_DC_WEIGHT),
        "--mixed_precision", "bf16", "--gradient_checkpointing",
        "--optimizer_type", "adamw8bit", "--blocks_to_swap", str(blocks_to_swap),
        "--output_dir", str(output_dir), "--output_name", "character",
        # Step frequenti per progress reale + eval 100/250/500; i non-vincitori
        # vengono eliminati dopo la selezione (worker_evaluate).
        "--save_every_n_steps", "50", "--save_last_n_steps", "12",
    ]
    if resume:
        launch += ["--resume", resume]
    return launch


def ckpt_steps_in(output_dir: Path) -> list[int]:
    steps = []
    if output_dir.is_dir():
        for child in output_dir.iterdir():
            name = child.name
            if name.startswith("character-") and name.endswith(".safetensors"):
                try:
                    steps.append(int(name[len("character-"):-len(".safetensors")]))
                except ValueError:
                    continue
    return sorted(steps)


def systemctl(*args: str) -> tuple[int, str]:
    proc = subprocess.run(["systemctl", *args], capture_output=True, text=True, timeout=120)
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
