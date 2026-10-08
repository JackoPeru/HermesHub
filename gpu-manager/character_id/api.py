"""Rotte HTTP Character ID per hermes-gpu-manager (FastAPI).

Aggancio in manager.py (una sola chiamata, mai rompere il boot):
    from character_id.api import register_character_routes
    register_character_routes(app, require_key=require_key,
                              require_user=_require_user_control, root=...)

Ruoli (come il resto del manager): letture con sola chiave; ogni scrittura
(POST/PATCH/DELETE/train/cancel) anche con controllo-utente (localhost 403:
solo l'utente gestisce il manager, mai l'agente).
"""

from __future__ import annotations

import asyncio
import hashlib
import os
import uuid
from pathlib import Path
from typing import Any

from fastapi import Depends, File, HTTPException, Request, UploadFile

from .store import DEFAULT_ROOT, CharacterStore
from .validation import (
    MAX_FILE_BYTES,
    MAX_IMAGES,
    validate_upload_filename,
    validate_upload_size,
)

PACKAGE_DIR = str(Path(__file__).resolve().parent.parent)
DEFAULT_TOOLS_PYTHON = "/opt/hermes/character-id/tools-venv/bin/python"

# Fasi non ancora implementate: risposta onesta e machine-readable (mai 404
# fuorviante, mai successo finto).
_NOT_YET = {
    "images": "M3 (upload + preprocessing)",
    "analyze": "M3 (pipeline foto)",
    "train": "M6 (automazione training)",
    "cancel": "M12 (cancel + recovery)",
    "retrain": "M11 (retrain + versioning)",
    "metrics": "M7 (evaluation)",
    "previews": "M10 (preview)",
}


def _not_implemented(feature: str) -> dict[str, Any]:
    return {"error": "not_implemented_yet", "phase": _NOT_YET[feature]}


def register_character_routes(
    app: Any,
    *,
    require_key: Any,
    require_user: Any,
    root: str = DEFAULT_ROOT,
    tools_python: str = DEFAULT_TOOLS_PYTHON,
) -> CharacterStore:
    store = CharacterStore(root)
    key_dep = Depends(require_key)

    def user_dep(request: Request) -> None:
        require_user(request)

    async def _run(fn, *args, **kwargs):
        return await asyncio.to_thread(fn, *args, **kwargs)

    def _manifest_or_404(character_id: str) -> dict:
        manifest = store.get_character(character_id)
        if manifest is None:
            raise HTTPException(404, "character non trovato")
        return manifest

    @app.get("/characters")
    async def characters_list(_: None = key_dep) -> dict:
        return {"characters": await _run(store.list_characters)}

    @app.post("/characters", status_code=201)
    async def characters_create(payload: dict, request: Request, _: None = key_dep) -> dict:
        user_dep(request)
        name = (payload or {}).get("name")
        try:
            manifest = await _run(store.create_character, name)
        except (ValueError, RuntimeError) as exc:
            raise HTTPException(422, str(exc)) from exc
        return manifest

    @app.get("/characters/{character_id}")
    async def characters_get(character_id: str, _: None = key_dep) -> dict:
        return _manifest_or_404(character_id)

    @app.patch("/characters/{character_id}")
    async def characters_patch(character_id: str, payload: dict, request: Request, _: None = key_dep) -> dict:
        user_dep(request)
        try:
            manifest = await _run(store.patch_character, character_id, payload or {})
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc
        if manifest is None:
            raise HTTPException(404, "character non trovato")
        return manifest

    @app.delete("/characters/{character_id}")
    async def characters_delete(character_id: str, request: Request, _: None = key_dep) -> dict:
        user_dep(request)
        deleted = await _run(store.delete_character, character_id)
        if not deleted:
            raise HTTPException(404, "character non trovato")
        return {"deleted": character_id}

    @app.get("/characters/{character_id}/status")
    async def characters_status(character_id: str, _: None = key_dep) -> dict:
        manifest = _manifest_or_404(character_id)
        job = await _run(store.active_job, character_id)
        return {
            "id": character_id,
            "status": manifest["status"],
            "current_version": manifest["training_version"],
            "active_job": job,
        }

    @app.get("/characters/{character_id}/images")
    async def characters_images_list(character_id: str, _: None = key_dep) -> dict:
        _manifest_or_404(character_id)
        assets = await _run(store.list_assets, character_id)
        return {
            "images": [
                {
                    "id": a["id"],
                    "accepted": a["accepted"],
                    "rejection_reason": a["rejection_reason"],
                    "width": a["width"],
                    "height": a["height"],
                    "face_count": a["face_count"],
                    "face_quality": a["face_quality"],
                    "dataset_split": a["dataset_split"],
                }
                for a in assets
            ]
        }

    @app.post("/characters/{character_id}/images", status_code=201)
    async def characters_images_upload(
        character_id: str,
        request: Request,
        files: list[UploadFile] = File(...),
        _: None = key_dep,  # type: ignore[assignment]
    ) -> dict:
        user_dep(request)
        _manifest_or_404(character_id)
        if not files:
            raise HTTPException(422, "nessun file inviato")
        existing = await _run(store.count_assets, character_id)
        if existing + len(files) > MAX_IMAGES:
            raise HTTPException(422, f"massimo {MAX_IMAGES} foto per personaggio")
        dest_dir = store.char_dir(character_id) / "originals"
        dest_dir.mkdir(parents=True, exist_ok=True)
        uploaded = 0
        for upload in files:
            try:
                ext = validate_upload_filename(upload.filename)
            except ValueError as exc:
                raise HTTPException(422, f"{upload.filename}: {exc}") from exc
            data = await upload.read(MAX_FILE_BYTES + 1)
            try:
                validate_upload_size(len(data))
            except ValueError as exc:
                raise HTTPException(422, f"{upload.filename}: {exc}") from exc
            asset_id = str(uuid.uuid4())
            dest = dest_dir / f"{asset_id}{ext}"
            dest.write_bytes(data)
            os.chmod(dest, 0o600)
            await _run(
                store.insert_asset,
                character_id,
                {"id": asset_id, "original_path": str(dest),
                 "sha256": hashlib.sha256(data).hexdigest(), "accepted": "pending"},
            )
            uploaded += 1
        await _run(store.refresh_manifest, character_id)
        return {"uploaded": uploaded, "total": existing + uploaded}

    @app.delete("/characters/{character_id}/images/{image_id}")
    async def characters_image_delete(character_id: str, image_id: str, request: Request, _: None = key_dep) -> dict:
        user_dep(request)
        _manifest_or_404(character_id)
        removed = await _run(store.delete_asset, character_id, image_id)
        if removed is None:
            raise HTTPException(404, "immagine non trovata")
        for key in ("original_path", "normalized_path", "embedding_path"):
            path = removed.get(key)
            if path:
                try:
                    os.unlink(path)
                except OSError:
                    pass
        await _run(store.refresh_manifest, character_id)
        return {"deleted": image_id}

    @app.post("/characters/{character_id}/analyze", status_code=202)
    async def characters_analyze(character_id: str, request: Request, _: None = key_dep) -> dict:
        user_dep(request)
        _manifest_or_404(character_id)
        if await _run(store.active_job, character_id) is not None:
            raise HTTPException(409, "un job e gia attivo per questo personaggio")
        if not Path(tools_python).is_file():
            raise HTTPException(409, "tools di analisi non installati (setup M3 sul server)")
        job = await _run(store.create_job, character_id, "analyze")
        await _run(store.set_status, character_id, "analyzing")
        log_file = store.char_dir(character_id) / "logs" / f"analyze-spawn-{job['id'][:8]}.log"
        log_file.parent.mkdir(parents=True, exist_ok=True)
        handle = open(log_file, "ab")
        try:
            proc = await asyncio.create_subprocess_exec(
                tools_python, "-m", "character_id.worker_analyze",
                store.root.as_posix(), character_id, job["id"],
                cwd=PACKAGE_DIR, stdout=handle, stderr=asyncio.subprocess.STDOUT,
                start_new_session=True,
            )
        except OSError as exc:
            handle.close()
            await _run(store.update_job, job["id"], status="failed", error=str(exc)[:300])
            await _run(store.set_status, character_id, "draft")
            raise HTTPException(500, f"avvio worker fallito: {exc}") from exc
        handle.close()
        await _run(store.update_job, job["id"], pid=proc.pid, detail="worker avviato")
        return {"job_id": job["id"], "status": "queued"}

    @app.post("/characters/{character_id}/train", status_code=501)
    async def characters_train(character_id: str, request: Request, _: None = key_dep) -> dict:
        user_dep(request)
        _manifest_or_404(character_id)
        return _not_implemented("train")

    @app.post("/characters/{character_id}/cancel", status_code=501)
    async def characters_cancel(character_id: str, request: Request, _: None = key_dep) -> dict:
        user_dep(request)
        _manifest_or_404(character_id)
        return _not_implemented("cancel")

    @app.post("/characters/{character_id}/retrain", status_code=501)
    async def characters_retrain(character_id: str, request: Request, _: None = key_dep) -> dict:
        user_dep(request)
        _manifest_or_404(character_id)
        return _not_implemented("retrain")

    @app.get("/characters/{character_id}/metrics", status_code=501)
    async def characters_metrics(character_id: str, _: None = key_dep) -> dict:
        _manifest_or_404(character_id)
        return _not_implemented("metrics")

    @app.get("/characters/{character_id}/previews", status_code=501)
    async def characters_previews(character_id: str, _: None = key_dep) -> dict:
        _manifest_or_404(character_id)
        return _not_implemented("previews")

    return store
