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
from typing import Any

from fastapi import Depends, HTTPException, Request

from .store import DEFAULT_ROOT, CharacterStore

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


def register_character_routes(app: Any, *, require_key: Any, require_user: Any, root: str = DEFAULT_ROOT) -> CharacterStore:
    store = CharacterStore(root)
    key_dep = Depends(require_key)

    def user_dep(request: Request) -> None:
        require_user(request)

    async def _run(fn, *args):
        return await asyncio.to_thread(fn, *args)

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

    @app.post("/characters/{character_id}/images", status_code=501)
    async def characters_images(character_id: str, request: Request, _: None = key_dep) -> dict:
        user_dep(request)
        _manifest_or_404(character_id)
        return _not_implemented("images")

    @app.delete("/characters/{character_id}/images/{image_id}", status_code=501)
    async def characters_image_delete(character_id: str, request: Request, _: None = key_dep) -> dict:
        user_dep(request)
        _manifest_or_404(character_id)
        return _not_implemented("images")

    @app.post("/characters/{character_id}/analyze", status_code=501)
    async def characters_analyze(character_id: str, request: Request, _: None = key_dep) -> dict:
        user_dep(request)
        _manifest_or_404(character_id)
        return _not_implemented("analyze")

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
