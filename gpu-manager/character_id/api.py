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
    try_claim_lock,
    utcnow,
)
from .validation import (
    MAX_FILE_BYTES,
    MAX_IMAGES,
    is_safe_preview_name,
    validate_upload_filename,
    validate_upload_size,
)

PACKAGE_DIR = str(Path(__file__).resolve().parent.parent)
DEFAULT_TOOLS_PYTHON = "/opt/hermes/character-id/tools-venv/bin/python"
DEFAULT_WORKFLOWS_DIR = "/opt/hermes/media-workflows"
DEFAULT_COMFY_INPUT = "/opt/hermes/runtimes/comfyui/app/input"
DEFAULT_LORAS_DIR = "/opt/hermes/runtimes/comfyui/app/models/loras"

# (M6+: tutte le rotte sono implementate; niente piu stub 501.)


def _is_character_worker(pid: int) -> bool:
    """Il pid appartiene davvero a un worker character-id? Anti pid-recycling.

    Legge /proc/<pid>/cmdline (solo POSIX/server). Sconosciuto = False
    (mai uccidere alla cieca).
    """
    from .validation import is_character_worker_cmdline

    try:
        cmdline = Path(f"/proc/{pid}/cmdline").read_bytes().decode("utf-8", "ignore")
    except (OSError, ValueError):
        return False
    return is_character_worker_cmdline(cmdline.replace("\x00", " "))


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
        from .generate import remove_character_links

        user_dep(request)
        before = _manifest_or_404(character_id)
        try:
            manifest = await _run(store.patch_character, character_id, payload or {})
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc
        if manifest is None:
            raise HTTPException(404, "character non trovato")
        if manifest.get("slug") != before.get("slug"):
            # Lo slug e nel nome link: pulisci gli orfani del vecchio slug.
            try:
                remove_character_links(Path(loras_dir), before.get("slug", ""))
            except OSError:
                pass
        return manifest

    @app.delete("/characters/{character_id}")
    async def characters_delete(character_id: str, request: Request, _: None = key_dep) -> dict:
        from .generate import remove_character_links

        user_dep(request)
        manifest = _manifest_or_404(character_id)
        # Mai orfani GPU: termina prima l'albero del worker attivo (come /cancel).
        job = await _run(store.active_job, character_id)
        if job is not None:
            from .training import read_lock as _read_lock, terminate_tree as _terminate_tree

            pid = int(job.get("pid", 0) or 0)
            if pid_alive(pid) and _is_character_worker(pid):
                try:
                    await _run(_terminate_tree, os.getpgid(pid))
                except (OSError, ValueError):
                    pass
            lock = await _run(_read_lock, store.root)
            if lock and lock.get("character_id") == character_id:
                pgid = int(lock.get("pgid", 0) or 0)
                if pgid > 1:
                    await _run(_terminate_tree, pgid)
        deleted = await _run(store.delete_character, character_id)
        if not deleted:
            raise HTTPException(404, "character non trovato")
        # Pulizia link LoRA orfani (solo symlink <slug>_v*, mai file veri).
        try:
            remove_character_links(Path(loras_dir), manifest.get("slug", ""))
        except OSError:
            pass
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
        failed: list[str] = []
        for upload in files:
            try:
                ext = validate_upload_filename(upload.filename)
            except ValueError as exc:
                failed.append(f"{upload.filename}: {exc}")
                continue
            data = await upload.read(MAX_FILE_BYTES + 1)
            try:
                validate_upload_size(len(data))
            except ValueError as exc:
                failed.append(f"{upload.filename}: {exc}")
                continue
            asset_id = str(uuid.uuid4())
            dest = dest_dir / f"{asset_id}{ext}"
            try:
                # I/O disco fuori dall'event loop (15MB × N file).
                await _run(dest.write_bytes, data)
                await _run(os.chmod, dest, 0o600)
                await _run(
                    store.insert_asset,
                    character_id,
                    {"id": asset_id, "original_path": str(dest),
                     "sha256": hashlib.sha256(data).hexdigest(), "accepted": "pending"},
                )
            except OSError as exc:
                try:
                    dest.unlink(missing_ok=True)
                except OSError:
                    pass
                failed.append(f"{upload.filename}: {exc}")
                continue
            uploaded += 1
        await _run(store.refresh_manifest, character_id)
        return {"uploaded": uploaded, "total": existing + uploaded, "failed": failed}

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

    async def _training_busy() -> dict | None:
        locked = await _run(
            training_active,
            store.root,
            lambda jid: (store.get_job(jid) or {}).get("status")
            not in ("ready", "failed", "cancelled", None),
        )
        if locked is not None:
            return locked
        trainings = await _run(store.active_training_jobs)
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
            hint = "usa /retrain per una nuova versione" if manifest["status"] == "ready" else "analizza prima le foto"
            raise HTTPException(409, f"personaggio in stato {manifest['status']}: {hint}")
        if int(manifest["image_count"]) < 20:
            raise HTTPException(409, "servono almeno 20 foto utilizzabili (analizza prima)")
        if await _run(store.active_job, character_id) is not None:
            raise HTTPException(409, "un job e gia attivo per questo personaggio")
        if await _training_busy() is not None:
            raise HTTPException(409, "GPU occupata: training Character ID in corso")
        if idle_cb is not None:
            ok, reason = await idle_cb() if asyncio.iscoroutinefunction(idle_cb) else idle_cb()
            if not ok:
                raise HTTPException(409, f"GPU occupata: {reason}")
        if not Path(trainer_python).is_file():
            raise HTTPException(409, "trainer non installato (setup M4 sul server)")
        version = int((payload or {}).get("version") or (manifest["training_version"] + 1))
        if not 1 <= version <= 999:
            raise HTTPException(422, "version fuori range 1..999")
        job = await _run(store.create_job, character_id, "train", version)
        await _run(store.set_status, character_id, "training")
        # Claim esclusivo PRIMA dello spawn: due POST concorrenti, uno solo vince.
        claimed = await _run(
            try_claim_lock, store.root,
            {"job_id": job["id"], "character_id": character_id,
             "pid": os.getpid(), "started": utcnow()},
            lambda jid: (store.get_job(jid) or {}).get("status")
            not in ("ready", "failed", "cancelled", None),
        )
        if not claimed:
            await _run(store.update_job, job["id"], status="cancelled",
                       detail="un altro training ha vinto la GPU")
            await _run(store.set_status, character_id, "ready_to_train")
            raise HTTPException(409, "GPU occupata: training Character ID in corso")
        try:
            pid, _ = _spawn("character_id.worker_train",
                            [store.root.as_posix(), character_id, job["id"], str(version)],
                            trainer_python, f"train-spawn-{job['id'][:8]}.log", character_id)
        except Exception as exc:
            # Spawn fallito: rilascia il claim, mai lock fantasma.
            await _run(store.update_job, job["id"], status="failed",
                       error=f"spawn worker: {exc}"[:300])
            await _run(store.set_status, character_id, "ready_to_train")
            clear_lock(store.root)
            raise HTTPException(500, f"avvio worker fallito: {exc}") from exc
        await _run(store.update_job, job["id"], pid=pid, detail="worker training avviato")
        return {"job_id": job["id"], "status": "queued", "version": version}

    @app.post("/characters/{character_id}/cancel")
    async def characters_cancel(character_id: str, request: Request, _: None = key_dep) -> dict:
        user_dep(request)
        _manifest_or_404(character_id)
        job = await _run(store.active_job, character_id)
        if job is None:
            raise HTTPException(409, "nessun job attivo")
        from .training import read_lock as _read_lock, terminate_tree as _terminate_tree

        killed = False
        targets: set[int] = set()
        pid = int(job.get("pid", 0) or 0)
        if pid_alive(pid) and _is_character_worker(pid):
            try:
                targets.add(os.getpgid(pid))
            except (OSError, ValueError):
                targets.add(pid)
        # Gruppo del lock (copre eval concatenata e figli musubi orfani del train).
        lock = await _run(_read_lock, store.root)
        if lock and lock.get("character_id") == character_id:
            pgid = int(lock.get("pgid", 0) or 0)
            if pgid > 1:
                targets.add(pgid)
        for target in targets:
            if await _run(_terminate_tree, target):
                killed = True
        await _run(store.update_job, job["id"], status="cancelled", progress=1.0,
                   detail="cancellato dall'utente" if killed else
                   "tracking annullato (worker gia morto o pid riciclato)",
                   error="")
        model = await _run(store.latest_model, character_id, "fl2va")
        await _run(store.set_status, character_id, "ready" if model else "ready_to_train")
        clear_lock(store.root)
        # Ripristina chat: il worker_loop del manager riconcilia comunque.
        try:
            await _run(systemctl, "start", "hermes-tabby.service")
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
        if await _run(store.active_job, character_id) is not None:
            raise HTTPException(409, "job attivo: rollback a training finito")
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
            is_trusted_lora_path,
            parse_mentions,
            publish_extra_link,
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
        if await _training_busy() is not None:
            raise HTTPException(409, "GPU occupata: training Character ID in corso")
        if submit_cb is None or validate_cb is None:
            raise HTTPException(409, "generazione media non configurata sul server")
        try:
            if spec["engine"] == "lora":
                model = manifest["models"]["fl2va"]
                link_name = publish_lora_link(
                    Path(loras_dir), manifest["slug"], manifest["training_version"],
                    Path(model["path"]))
                raw_extras = spec["extra_loras"]
                if not isinstance(raw_extras, list):
                    raise ValueError("additional_loras deve essere una lista")
                for entry in raw_extras:
                    if not isinstance(entry, dict) or not is_trusted_lora_path(
                            str(entry.get("path", ""))):
                        raise ValueError("extra LoRA fuori dalle root fidate")
                chain = validate_stack(
                    [{"path": str(Path(loras_dir) / link_name),
                      "strength": spec["strength"], "family": "fl2va"}]
                    + [{"path": e.get("path", ""), "strength": e.get("strength", 1.0),
                        "family": e.get("family", "fl2va"), "enabled": e.get("enabled", True)}
                       for e in raw_extras if isinstance(e, dict)],
                    "fl2va")
                published = [{"file": link_name, "strength": spec["strength"]}]
                for entry in chain[1:]:
                    published.append({
                        "file": publish_extra_link(
                            Path(loras_dir), manifest["slug"], Path(entry["path"])),
                        "strength": entry["strength"],
                    })
                template = json.loads((Path(workflows_dir) / "h3" / "t2v.json").read_text())
                workflow = await _run(build_workflow, template, spec, published, [])
            else:
                slots = manifest.get("references", {})
                refs = [slots[k] for k in ("front", "left_three_quarter", "right_three_quarter",
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
        if not is_safe_preview_name(name):
            raise HTTPException(422, "nome preview non valido")
        path = store.char_dir(character_id) / "previews" / name
        if not path.is_file():
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
        has_model = manifest.get("models", {}).get("fl2va") is not None
        return {"file": dest.name, "size": dest.stat().st_size,
                "include_originals": include, "has_model": has_model}

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
        if not str(file.filename or "").lower().endswith(".hcid"):
            raise HTTPException(422, "package .hcid non valido (max 2 GB)")
        # Scrittura a chunk: mai 2 GB in RAM.
        tmp = store.root / "cache" / f"import_{uuid.uuid4().hex}.hcid"
        size = 0
        try:
            with open(tmp, "wb") as handle:
                while True:
                    chunk = await file.read(1024 * 1024)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > 2 * 1024 * 1024 * 1024:
                        raise HTTPException(422, "package troppo grande (max 2 GB)")
                    handle.write(chunk)
            manifest = await _run(import_package, tmp, store, False)
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc
        finally:
            tmp.unlink(missing_ok=True)
        return manifest

    return store
