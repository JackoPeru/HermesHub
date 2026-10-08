"""Export/import Character (.hcid): manifest + LoRA + reference pack + metriche + preview.

Niente retraining all'import se modello e base family sono compatibili.
Originali inclusi solo su richiesta esplicita.
"""

from __future__ import annotations

import json
import shutil
import tempfile
import zipfile
from pathlib import Path

EXPORT_VERSION = 1
REQUIRED_MEMBERS = ("manifest.json",)


def export_character(char_dir: Path, dest: Path, include_originals: bool = False) -> Path:
    manifest = json.loads((char_dir / "manifest.json").read_text(encoding="utf-8"))
    dest.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        stage = Path(tmp) / "pkg"
        stage.mkdir(parents=True, exist_ok=True)
        (stage / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        for sub in ("metrics", "previews"):
            src = char_dir / sub
            if src.is_dir():
                shutil.copytree(src, stage / sub, ignore_dangling_symlinks=True)
        refs_dir = stage / "references"
        refs_dir.mkdir(parents=True, exist_ok=True)
        for key, value in (manifest.get("references") or {}).items():
            if key == "preferred" or not isinstance(value, str):
                continue
            src = Path(value)
            if src.is_file():
                shutil.copy2(src, refs_dir / f"{key}{src.suffix.lower()}")
        models_dir = stage / "models"
        models_dir.mkdir(parents=True, exist_ok=True)
        fl2va = (manifest.get("models") or {}).get("fl2va") or {}
        if fl2va.get("path") and Path(fl2va["path"]).is_file():
            shutil.copy2(fl2va["path"], models_dir / Path(fl2va["path"]).name)
        if include_originals and (char_dir / "originals").is_dir():
            shutil.copytree(char_dir / "originals", stage / "originals",
                            ignore_dangling_symlinks=True)
        (stage / "export.json").write_text(
            json.dumps({"export_version": EXPORT_VERSION,
                        "include_originals": include_originals}), encoding="utf-8")
        if dest.exists():
            dest.unlink()
        with zipfile.ZipFile(dest, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(stage.rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(stage))
    return dest


def _safe_members(archive: zipfile.ZipFile) -> list[str]:
    """Solo path relativi interni (anti zip-slip): niente assoluti, niente '..'."""
    safe = []
    for name in archive.namelist():
        parts = Path(name).parts
        if not parts or Path(name).is_absolute() or ".." in parts:
            raise ValueError(f".hcid con membro non sicuro: {name}")
        safe.append(name)
    return safe


def inspect_package(pkg: Path) -> dict:
    """Valida .hcid senza scrivere nulla. Solleva ValueError se incompatibile."""
    if not zipfile.is_zipfile(pkg):
        raise ValueError("non e un archivio .hcid valido")
    with zipfile.ZipFile(pkg) as archive:
        names = set(_safe_members(archive))
        for required in REQUIRED_MEMBERS:
            if required not in names:
                raise ValueError(f".hcid senza {required}")
        manifest = json.loads(archive.read("manifest.json"))
    if manifest.get("schema_version") != 1:
        raise ValueError("schema manifest non supportato")
    models = manifest.get("models") or {}
    if models.get("ref2va") is not None:
        raise ValueError("package hybrid non supportato in V1")
    return manifest


def import_package(pkg: Path, store, include_originals: bool = False) -> dict:
    """Ricrea il personaggio (nuovo UUID) senza retraining. Ritorna manifest."""
    manifest = inspect_package(pkg)
    created = store.create_character(manifest.get("name", "imported"))
    cid = created["id"]
    char_dir = store.char_dir(cid)
    with zipfile.ZipFile(pkg) as archive:
        _safe_members(archive)  # valida prima di estrarre (zip-slip)
        archive.extractall(char_dir / "import_tmp")
    try:
        staged = char_dir / "import_tmp"
        fl2va = (manifest.get("models") or {}).get("fl2va") or {}
        model_path = None
        if fl2va.get("path"):
            candidate = staged / "models" / Path(fl2va["path"]).name
            if candidate.is_file():
                dest_dir = char_dir / "models" / "fl2va"
                dest_dir.mkdir(parents=True, exist_ok=True)
                dest = dest_dir / f"character_v{1}.safetensors"
                shutil.copy2(candidate, dest)
                model_path = str(dest)
        for sub in ("metrics", "previews"):
            src = staged / sub
            if src.is_dir():
                shutil.copytree(src, char_dir / sub, dirs_exist_ok=True)
        refs_src = staged / "references"
        if refs_src.is_dir():
            refs_dir = char_dir / "references"
            refs_dir.mkdir(parents=True, exist_ok=True)
            slots = {}
            for photo in sorted(refs_src.iterdir()):
                if photo.is_file():
                    dest = refs_dir / photo.name
                    shutil.copy2(photo, dest)
                    slots[photo.stem] = {"asset_id": "", "path": str(dest)}
            if slots:
                store.set_references(cid, slots)
        if include_originals and (staged / "originals").is_dir():
            shutil.copytree(staged / "originals", char_dir / "originals", dirs_exist_ok=True)
        if model_path:
            from .generate import lora_base_family, read_lora_header, validate_lora_file

            problem = validate_lora_file(Path(model_path))
            if problem:
                raise ValueError(problem)
            _problem, _keys, meta = read_lora_header(Path(model_path))
            declared = lora_base_family(meta)
            if declared is not None and declared != "fl2va":
                raise ValueError(f"package con LoRA per {declared}: V1 importa solo fl2va")
            try:
                rank = int(fl2va.get("rank", 0) or 0)
                alpha = int(fl2va.get("alpha", 0) or 0)
            except (TypeError, ValueError):
                raise ValueError("manifest package con rank/alpha non validi") from None
            store.add_model(cid, 1, "fl2va", "character_lora", model_path,
                            rank=rank, alpha=alpha,
                            strength=0.9, metrics={"imported": True})
            engine = (manifest.get("identity") or {}).get("recommended_engine", "lora")
            store.record_engine(cid, engine if engine in ("lora", "reference") else "lora", 0.9)
            store.set_status(cid, "ready")
        else:
            store.set_status(cid, "draft")
    finally:
        shutil.rmtree(char_dir / "import_tmp", ignore_errors=True)
    return store.refresh_manifest(cid)
