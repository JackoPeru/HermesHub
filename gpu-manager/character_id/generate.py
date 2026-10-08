"""Generation Character ID: resolve identita -> workflow ComfyUI (solo stdlib+json).

Il frontend non costruisce workflow: passa {prompt, character_id/@Nome,
identity_mode, duration, aspect_ratio, seed}. Qui si risolve engine (auto),
trigger, LoRA (+stack validato) o reference pack, e si produce un workflow
dict validabile dal manager + lista input da stagiare.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import uuid
from pathlib import Path

from . import DEFAULT_LORA_STRENGTH

MENTION_RE = re.compile(r"@([\w.'-]{2,64})")
MAX_LORAS = 4
IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp")


def parse_mentions(prompt: str) -> list[str]:
    """Nomi @Menzionati nel prompt (risoluzione UUID a cura del chiamante)."""
    return MENTION_RE.findall(prompt or "")


def strip_mentions(prompt: str) -> str:
    return MENTION_RE.sub("", prompt or "").strip()


def inject_trigger(prompt: str, trigger: str) -> str:
    clean = strip_mentions(prompt)
    prefix = f"{trigger} photo of <Subject 1>, "
    if clean.startswith(trigger):
        return clean
    return prefix + clean


def validate_lora_file(path: Path) -> str:
    """Verifica strutturale LoRA H3: safetensors + pesi lora_unet_*. Ritorna '' se ok."""
    if not path.is_file():
        return f"lora mancante: {path}"
    if path.suffix.lower() != ".safetensors":
        return f"lora non safetensors: {path}"
    if path.stat().st_size < 1024 * 1024:
        return f"lora troppo piccola: {path}"
    try:
        with open(path, "rb") as handle:
            header_len = int.from_bytes(handle.read(8), "little")
            if header_len <= 0 or header_len > 100 * 1024 * 1024:
                return f"lora header non valido: {path}"
            header = json.loads(handle.read(header_len))
        keys = [k for k in header.keys() if k != "__metadata__"]
        if not keys:
            return f"lora vuota: {path}"
        if not any(k.startswith("lora_unet_") or ".lora_down." in k or ".lora_A." in k for k in keys):
            return f"lora non-H3 (chiavi inattese): {path}"
    except (OSError, ValueError, KeyError) as exc:
        return f"lora illeggibile: {exc}"
    return ""


def validate_stack(entries: list[dict], family: str) -> list[dict]:
    """Valida stack multi-LoRA: max 4, famiglie compatibili, strength 0..2, file ok."""
    active = [e for e in entries if e.get("enabled", True)]
    if len(active) > MAX_LORAS:
        raise ValueError(f"max {MAX_LORAS} LoRA per stack")
    cleaned = []
    for entry in active:
        path = str(entry.get("path", ""))
        if not path:
            raise ValueError("stack entry senza path")
        entry_family = str(entry.get("family", family))
        if entry_family != family:
            raise ValueError(f"LoRA {path}: family {entry_family} incompatibile con base {family}")
        problem = validate_lora_file(Path(path))
        if problem:
            raise ValueError(problem)
        try:
            strength = float(entry.get("strength", 1.0))
        except (TypeError, ValueError):
            raise ValueError(f"strength non numerica: {path}")
        if not 0.0 <= strength <= 2.0:
            raise ValueError(f"strength fuori range 0..2: {path}")
        cleaned.append({"path": path, "strength": strength, "family": entry_family})
    return cleaned


def aspect_to_size(aspect: str) -> tuple[int, int]:
    table = {"16:9": (1344, 768), "9:16": (768, 1344), "1:1": (1024, 1024),
             "4:3": (1152, 864), "3:4": (864, 1152), "21:9": (1568, 672)}
    return table.get((aspect or "16:9").strip(), (1344, 768))


def duration_to_length(duration_s: float) -> int:
    frames = int(float(duration_s or 5) * 24)
    grid_len = ((frames - 5 + 16) // 17) * 17 + 5
    return max(22, min(124, grid_len))


def resolve_request(manifest: dict, body: dict) -> dict:
    """Risolve engine/prompt/seed. Ritorna spec generazione."""
    mode = str(body.get("identity_mode", "auto") or "auto")
    if mode not in ("auto", "lora", "reference"):
        raise ValueError("identity_mode non valido (auto|lora|reference)")
    engine = manifest["identity"]["recommended_engine"] if mode == "auto" else (
        "lora" if mode == "lora" else "reference")
    if engine == "lora" and manifest["models"]["fl2va"] is None:
        if mode != "auto":
            raise ValueError("nessun Character LoRA pronto per questo personaggio")
        engine = "reference"
    prompt = inject_trigger(str(body.get("prompt", "") or ""), manifest["trigger_token"])
    width, height = aspect_to_size(str(body.get("aspect_ratio", "16:9")))
    try:
        seed = int(body.get("seed", 42))
    except (TypeError, ValueError):
        raise ValueError("seed non valido") from None
    return {
        "engine": engine,
        "prompt": prompt[:2000],
        "width": width,
        "height": height,
        "length": duration_to_length(body.get("duration", 5)),
        "seed": seed,
        "strength": float(manifest["identity"].get("lora_strength", DEFAULT_LORA_STRENGTH)),
        "extra_loras": body.get("additional_loras") or [],
    }


def build_workflow(template: dict, spec: dict, lora_chain: list[dict],
                   ref_staged: list[str]) -> dict:
    """Inietta LoRA chain (LoraLoaderModelOnly) o reference nel template t2v/reference."""
    workflow = json.loads(json.dumps(template))
    nodes = workflow
    unet_id = next((k for k, v in nodes.items()
                    if isinstance(v, dict) and v.get("class_type") == "UNETLoader"), None)
    sampler_id = next((k for k, v in nodes.items()
                       if isinstance(v, dict) and v.get("class_type") == "KSampler"), None)
    if unet_id is None or sampler_id is None:
        raise ValueError("template senza UNETLoader/KSampler")
    last_model: list = [unet_id, 0]
    next_id = max([int(k) for k in nodes if str(k).isdigit()] + [0]) + 1
    for entry in lora_chain:
        node_id = str(next_id)
        next_id += 1
        nodes[node_id] = {
            "class_type": "LoraLoaderModelOnly",
            "inputs": {
                "model": last_model,
                "lora_name": entry["file"],
                "strength_model": entry["strength"],
                "strength_clip": 0.0,
            },
        }
        last_model = [node_id, 0]
    nodes[sampler_id]["inputs"]["model"] = last_model
    for node in nodes.values():
        if not isinstance(node, dict):
            continue
        inputs = node.get("inputs", {})
        if node.get("class_type", "").startswith("MiniMaxH3"):
            inputs["prompt"] = spec["prompt"]
            inputs["width"] = spec["width"]
            inputs["height"] = spec["height"]
            inputs["length"] = spec["length"]
        if node.get("class_type") == "KSampler":
            inputs["seed"] = spec["seed"]
            inputs["steps"] = 25
            inputs["cfg"] = 1.0
    if ref_staged:
        for node in nodes.values():
            if isinstance(node, dict) and node.get("class_type") == "LoadImage":
                node["inputs"]["image"] = ref_staged[0]
    if "{{" in json.dumps(workflow):
        raise ValueError("placeholder irrisolti nel workflow")
    return workflow


def publish_lora_link(loras_dir: Path, slug: str, version: int, target: Path) -> str:
    """Symlink <slug>_v<version>.safetensors in ComfyUI/models/loras."""
    loras_dir.mkdir(parents=True, exist_ok=True)
    link = loras_dir / f"{slug}_v{version}.safetensors"
    if link.is_symlink() or link.exists():
        link.unlink()
    os.symlink(target, link)
    return link.name


def stage_references(refs: list[str], dest_dir: Path) -> list[str]:
    """Copie reali (mai symlink: il manager rifiuta symlink fuori trusted roots)."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    staged = []
    for ref in refs[:5]:
        src = Path(ref)
        if not src.is_file() or src.suffix.lower() not in IMAGE_EXTS:
            continue
        name = f"hcid_{uuid.uuid4().hex[:8]}_in{len(staged) + 1}{src.suffix.lower()}"
        shutil.copy2(src, dest_dir / name)
        staged.append(name)
    if not staged:
        raise ValueError("nessuna reference utilizzabile")
    return staged
