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
import json
import os
import subprocess
import uuid
from pathlib import Path
from typing import Any

from fastapi import Depends, File, HTTPException, Request, UploadFile

from .store import DEFAULT_ROOT, CharacterStore
from .training import (
    TRAINER_VENV_PY,
    clear_lock,
    pid_alive,
    systemctl,
    training_active,
    utcnow,
    write_lock,
)
from .validation import (
    MAX_FILE_BYTES,
    MAX_IMAGES,
    validate_upload_filename,
    validate_upload_size,
)

PACKAGE_DIR = str(Path(__file__).resolve().parent.parent)
DEFAULT_TOOLS_PYTHON = "/opt/hermes/character-id/tools-venv/bin/python"
DEFAULT_WORKFLOWS_DIR = "/opt/hermes/media-workflows"
DEFAULT_COMFY_INPUT = "/opt/hermes/runtimes/comfyui/app/input"
DEFAULT_LORAS_DIR = "/opt/hermes/runtimes/comfyui/app/models/loras"

# (M6+: tutte le rotte sono implementate; niente piu stub 501.)


def register_character_routes(
    app: Any,
    *,
    require_key: Any,
    require_user: Any,
    root: str = DEFAULT_ROOT,
    tools_python: str = DEFAULT_TOOLS_PYTHON,
    trainer_python: str = TRAINER_VENV_PY,
    workflows_dir: str = DEFAULT_WORKFLOWS_DIR,
    comfy_input_dir: str = DEFAULT_COMFY_INPUT,
    loras_dir: str = DEFAULT_LORAS_DIR,
    submit_cb: Any = None,
    validate_cb: Any = None,
    idle_cb: Any = None,
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

    def _training_busy() -> dict | None:
        locked = training_active(
            store.root,
            lambda jid: (store.get_job(jid) or {}).get("status")
            not in ("ready", "failed", "cancelled", None),
        )
        if locked is not None:
            return locked
        trainings = store.active_training_jobs()
        return {"job_id": trainings[0]["id"], "via": "job"} if trainings else None

    def _spawn(module: str, args: list[str], python: str, log_name: str,
               character_id: str) -> tuple[int, Path]:
        log_file = store.char_dir(character_id) / "logs" / log_name
        log_file.parent.mkdir(parents=True, exist_ok=True)
        handle = open(log_file, "ab")
        try:
            proc = subprocess.Popen(
                [python, "-m", module] + args, cwd=PACKAGE_DIR,
                stdout=handle, stderr=subprocess.STDOUT, start_new_session=True,
            )
        finally:
            handle.close()
        return proc.pid, log_file

    @app.post("/characters/{character_id}/train", status_code=202)
    async def characters_train(character_id: str, request: Request, payload: dict | None = None,
                               _: None = key_dep) -> dict:
        user_dep(request)
        manifest = _manifest_or_404(character_id)
        if manifest["status"] not in ("ready_to_train", "needs_retrain", "failed", "interrupted", "draft"):
            raise HTTPException(409, f"personaggio in stato {manifest['status']}: analizza prima le foto")
        if int(manifest["image_count"]) < 20:
            raise HTTPException(409, "servono almeno 20 foto utilizzabili (analizza prima)")
        if await _run(store.active_job, character_id) is not None:
            raise HTTPException(409, "un job e gia attivo per questo personaggio")
        if _training_busy() is not None:
            raise HTTPException(409, "GPU occupata: training Character ID in corso")
        if idle_cb is not None:
            ok, reason = await idle_cb() if asyncio.iscoroutinefunction(idle_cb) else idle_cb()
            if not ok:
                raise HTTPException(409, f"GPU occupata: {reason}")
        if not Path(trainer_python).is_file():
            raise HTTPException(409, "trainer non installato (setup M4 sul server)")
        version = int((payload or {}).get("version") or (manifest["training_version"] + 1))
        job = await _run(store.create_job, character_id, "train", version)
        await _run(store.set_status, character_id, "training")
        pid, _ = _spawn("character_id.worker_train",
                        [store.root.as_posix(), character_id, job["id"], str(version)],
                        trainer_python, f"train-spawn-{job['id'][:8]}.log", character_id)
        await _run(store.update_job, job["id"], pid=pid, detail="worker training avviato")
        write_lock(store.root, {"job_id": job["id"], "character_id": character_id,
                                "pid": pid, "started": utcnow()})
        return {"job_id": job["id"], "status": "queued", "version": version}

    @app.post("/characters/{character_id}/cancel")
    async def characters_cancel(character_id: str, request: Request, _: None = key_dep) -> dict:
        user_dep(request)
        _manifest_or_404(character_id)
        job = await _run(store.active_job, character_id)
        if job is None:
            raise HTTPException(409, "nessun job attivo")
        pid = int(job.get("pid", 0) or 0)
        if pid_alive(pid):
            try:
                os.killpg(pid, 15)  # SIGTERM al gruppo (start_new_session)
            except (OSError, ProcessLookupError):
                pass
            for _ in range(20):
                await asyncio.sleep(1)
                if not pid_alive(pid):
                    break
            if pid_alive(pid):
                try:
                    os.killpg(pid, 9)
                except (OSError, ProcessLookupError):
                    pass
        await _run(store.update_job, job["id"], status="cancelled", progress=1.0,
                   detail="cancellato dall'utente", error="")
        model = await _run(store.latest_model, character_id, "fl2va")
        await _run(store.set_status, character_id, "ready" if model else "ready_to_train")
        clear_lock(store.root)
        # Ripristina chat: il worker_loop del manager riconcilia comunque.
        try:
            systemctl("start", "hermes-tabby.service")
        except Exception:  # noqa: BLE001 - best effort, il manager riconcilia
            pass
        await _run(store.refresh_manifest, character_id)
        return {"cancelled": job["id"]}

    @app.post("/characters/{character_id}/retrain", status_code=202)
    async def characters_retrain(character_id: str, request: Request, _: None = key_dep) -> dict:
        # Retrain = nuova versione (vN+1), mai sovrascrivere vN: riusa /train.
        return await characters_train(character_id, request, None)

    @app.post("/characters/{character_id}/rollback")
    async def characters_rollback(character_id: str, payload: dict, request: Request,
                                  _: None = key_dep) -> dict:
        from .generate import publish_lora_link

        user_dep(request)
        manifest = _manifest_or_404(character_id)
        try:
            version = int((payload or {}).get("version"))
        except (TypeError, ValueError):
            raise HTTPException(422, "version mancante") from None
        target = store.char_dir(character_id) / "models" / "fl2va" / f"character_v{version}.safetensors"
        if not target.is_file():
            raise HTTPException(404, f"versione v{version} non trovata")
        await _run(store.set_current_version, character_id, version)
        publish_lora_link(Path(loras_dir), manifest["slug"], version, target)
        await _run(store.set_status, character_id, "ready")
        return await _run(store.refresh_manifest, character_id)

    @app.post("/characters/{character_id}/generate", status_code=202)
    async def characters_generate(character_id: str, payload: dict, request: Request,
                                  _: None = key_dep) -> dict:
        from .generate import (
            build_workflow,
            parse_mentions,
            publish_lora_link,
            resolve_request,
            stage_references,
            validate_stack,
        )

        manifest = _manifest_or_404(character_id)
        body = dict(payload or {})
        # @Nome nel prompt: risolvi il primo al personaggio richiesto (coerenza).
        mentions = parse_mentions(body.get("prompt", ""))
        if mentions:
            matches = await _run(store.find_by_name, mentions[0])
            if matches and matches[0]["id"] != character_id:
                raise HTTPException(409, f"@{mentions[0]} e un altro personaggio: usa il suo selettore")
        try:
            spec = resolve_request(manifest, body)
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc
        if _training_busy() is not None:
            raise HTTPException(409, "GPU occupata: training Character ID in corso")
        if submit_cb is None or validate_cb is None:
            raise HTTPException(409, "generazione media non configurata sul server")
        try:
            if spec["engine"] == "lora":
                model = manifest["models"]["fl2va"]
                link_name = publish_lora_link(
                    Path(loras_dir), manifest["slug"], manifest["training_version"],
                    Path(model["path"]))
                chain = validate_stack(
                    [{"path": str(Path(loras_dir) / link_name),
                      "strength": spec["strength"], "family": "fl2va"}]
                    + [{"path": e.get("path", ""), "strength": e.get("strength", 1.0),
                        "family": e.get("family", "fl2va"), "enabled": e.get("enabled", True)}
                       for e in spec["extra_loras"]],
                    "fl2va")
                chain = [{"file": link_name, "strength": spec["strength"]}] + [
                    {"file": Path(e["path"]).name, "strength": e["strength"]} for e in chain[1:]]
                template = json.loads((Path(workflows_dir) / "h3" / "t2v.json").read_text())
                workflow = await _run(build_workflow, template, spec, chain, [])
            else:
                slots = manifest.get("references", {})
                refs = [slots[k] for k in ("front", "three_quarter_left", "three_quarter_right",
                                           "profile_left", "profile_right", "full_body")
                        if slots.get(k)] or slots.get("preferred", [])
                real = [r for r in (refs if isinstance(refs, list) else [refs])
                        if isinstance(r, str) and Path(r).is_file()]
                staged = await _run(stage_references, real[:5],
                                    Path(comfy_input_dir))
                template = json.loads((Path(workflows_dir) / "h3" / "reference.json").read_text())
                workflow = await _run(build_workflow, template, spec, [], staged)
        except (ValueError, OSError) as exc:
            raise HTTPException(422, f"workflow non costruibile: {exc}") from exc
        problem = await _run(validate_cb, workflow) if asyncio.iscoroutinefunction(validate_cb) else validate_cb(workflow)
        if problem:
            raise HTTPException(422, f"workflow rifiutato: {problem}")
        job = await _run(submit_cb, workflow, {"prompt": spec["prompt"], "seed": spec["seed"],
                                               "character": character_id, "engine": spec["engine"]})
        return {"job_id": job["job_id"], "status": "queued", "engine": spec["engine"]}

    @app.get("/characters/{character_id}/metrics")
    async def characters_metrics(character_id: str, _: None = key_dep) -> dict:
        _manifest_or_404(character_id)
        return {"metrics": await _run(store.get_metrics, character_id)}

    @app.get("/characters/{character_id}/previews")
    async def characters_previews(character_id: str, _: None = key_dep) -> dict:
        _manifest_or_404(character_id)
        preview_dir = store.char_dir(character_id) / "previews"
        names = sorted(p.name for p in preview_dir.iterdir() if p.is_file()) if preview_dir.is_dir() else []
        return {"previews": names}

    @app.get("/characters/{character_id}/previews/{name}")
    async def characters_preview_file(character_id: str, name: str, _: None = key_dep) -> Any:
        from fastapi.responses import FileResponse

        _manifest_or_404(character_id)
        if "/" in name or "\\" in name or ".." in name:
            raise HTTPException(422, "nome preview non valido")
        path = store.char_dir(character_id) / "previews" / name
        if not path.is_file() or path.suffix.lower() not in (".jpg", ".jpeg", ".png", ".webp", ".mp4"):
            raise HTTPException(404, "preview non trovata")
        return FileResponse(path)

    @app.post("/characters/{character_id}/export")
    async def characters_export(character_id: str, payload: dict, request: Request,
                                _: None = key_dep) -> dict:
        from .export_pkg import export_character

        user_dep(request)
        manifest = _manifest_or_404(character_id)
        include = bool((payload or {}).get("include_originals", False))
        dest = store.char_dir(character_id) / "cache" / f"{manifest['slug']}.hcid"
        await _run(export_character, store.char_dir(character_id), dest, include)
        return {"file": dest.name, "size": dest.stat().st_size, "include_originals": include}

    @app.get("/characters/{character_id}/export/download")
    async def characters_export_download(character_id: str, _: None = key_dep) -> Any:
        from fastapi.responses import FileResponse

        manifest = _manifest_or_404(character_id)
        dest = store.char_dir(character_id) / "cache" / f"{manifest['slug']}.hcid"
        if not dest.is_file():
            raise HTTPException(404, "esporta prima il personaggio")
        return FileResponse(dest, filename=dest.name)

    @app.post("/characters/import", status_code=201)
    async def characters_import(request: Request, file: UploadFile = File(...),
                                _: None = key_dep) -> dict:  # type: ignore[assignment]
        from .export_pkg import import_package

        user_dep(request)
        data = await file.read(2 * 1024 * 1024 * 1024 + 1)
        if len(data) > 2 * 1024 * 1024 * 1024 or not (file.filename or "").endswith(".hcid"):
            raise HTTPException(422, "package .hcid non valido o troppo grande (max 2 GB)")
        tmp = store.root / "cache" / f"import_{uuid.uuid4().hex}.hcid"
        tmp.write_bytes(data)
        try:
            manifest = await _run(import_package, tmp, store, False)
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc
        finally:
            tmp.unlink(missing_ok=True)
        return manifest

    return store
