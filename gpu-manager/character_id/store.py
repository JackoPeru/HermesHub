"""Storage Character ID: SQLite + filesystem (solo stdlib).

Root di default `/opt/hermes/character-id` (vedi baseline §11: /var/lib/hermes
non esiste sul server, si riusa la convenzione /opt/hermes).

Layout per personaggio (UUID, mai il nome visualizzato):
  <root>/characters/<uuid>/{manifest.json,originals/,normalized/,training/,
  references/,validation/,models/{fl2va/,ref2va/},previews/,metrics/,logs/}
"""

from __future__ import annotations

import json
import os
import re
import secrets
import shutil
import sqlite3
import threading
import time
import uuid
from pathlib import Path

from . import (
    CHARACTER_JOB_STATUSES,
    CHARACTER_STATUSES,
    DEFAULT_LORA_STRENGTH,
    SCHEMA_VERSION,
)
from .validation import is_uuid, validate_default_mode, validate_lora_strength, validate_name

DEFAULT_ROOT = "/opt/hermes/character-id"

_SCHEMA = """
CREATE TABLE IF NOT EXISTS characters(
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  slug TEXT NOT NULL UNIQUE,
  trigger_token TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL DEFAULT 'draft',
  current_version INTEGER NOT NULL DEFAULT 0,
  default_mode TEXT NOT NULL DEFAULT 'auto',
  created_at REAL NOT NULL,
  updated_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS character_assets(
  id TEXT PRIMARY KEY,
  character_id TEXT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
  original_path TEXT NOT NULL,
  normalized_path TEXT DEFAULT '',
  sha256 TEXT NOT NULL DEFAULT '',
  phash TEXT NOT NULL DEFAULT '',
  width INTEGER NOT NULL DEFAULT 0,
  height INTEGER NOT NULL DEFAULT 0,
  face_count INTEGER NOT NULL DEFAULT 0,
  face_quality REAL NOT NULL DEFAULT 0,
  blur_score REAL NOT NULL DEFAULT 0,
  yaw REAL NOT NULL DEFAULT 0,
  pitch REAL NOT NULL DEFAULT 0,
  embedding_path TEXT NOT NULL DEFAULT '',
  accepted TEXT NOT NULL DEFAULT 'pending',
  rejection_reason TEXT NOT NULL DEFAULT '',
  dataset_split TEXT NOT NULL DEFAULT '',
  created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS character_models(
  id TEXT PRIMARY KEY,
  character_id TEXT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
  version INTEGER NOT NULL,
  family TEXT NOT NULL,
  model_type TEXT NOT NULL,
  path TEXT NOT NULL,
  sha256 TEXT NOT NULL DEFAULT '',
  rank INTEGER NOT NULL DEFAULT 0,
  alpha INTEGER NOT NULL DEFAULT 0,
  strength REAL NOT NULL DEFAULT 0.9,
  metrics_json TEXT NOT NULL DEFAULT '{}',
  created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS character_jobs(
  id TEXT PRIMARY KEY,
  character_id TEXT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,
  status TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 0,
  progress REAL NOT NULL DEFAULT 0.0,
  detail TEXT NOT NULL DEFAULT '',
  error TEXT NOT NULL DEFAULT '',
  pid INTEGER NOT NULL DEFAULT 0,
  created_at REAL NOT NULL,
  updated_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS character_metrics(
  id TEXT PRIMARY KEY,
  character_id TEXT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
  version INTEGER NOT NULL DEFAULT 0,
  suite TEXT NOT NULL DEFAULT '',
  scores_json TEXT NOT NULL DEFAULT '{}',
  created_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_assets_character ON character_assets(character_id);
CREATE INDEX IF NOT EXISTS idx_models_character ON character_models(character_id);
CREATE INDEX IF NOT EXISTS idx_jobs_character ON character_jobs(character_id);
CREATE INDEX IF NOT EXISTS idx_metrics_character ON character_metrics(character_id);
"""

_SUBDIRS = (
    "originals",
    "normalized",
    "training",
    "references",
    "validation",
    "models/fl2va",
    "models/ref2va",
    "previews",
    "metrics",
    "logs",
    "cache",
)


def _slugify(name: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return slug or "character"


def _utcnow() -> float:
    return time.time()


class CharacterStore:
    """Thread-safe (lock + sqlite check_same_thread=False)."""

    def __init__(self, root: str | Path = DEFAULT_ROOT) -> None:
        self.root = Path(root)
        self._lock = threading.Lock()
        self.root.mkdir(parents=True, exist_ok=True)
        os.chmod(self.root, 0o700)
        (self.root / "characters").mkdir(exist_ok=True)
        (self.root / "cache").mkdir(exist_ok=True)
        self._db = sqlite3.connect(str(self.root / "characters.db"), check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        with self._lock, self._db:
            self._db.executescript(_SCHEMA)

    def close(self) -> None:
        with self._lock:
            self._db.close()

    # -- path interni (mai input utente nei path: solo UUID) -----------------

    def char_dir(self, character_id: str) -> Path:
        if not is_uuid(character_id):
            raise ValueError("character_id non valido")
        return self.root / "characters" / character_id

    def _write_manifest(self, manifest: dict) -> None:
        path = self.char_dir(manifest["id"]) / "manifest.json"
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(manifest, indent=2, sort_keys=True), encoding="utf-8")
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)

    # -- manifest -------------------------------------------------------------

    def build_manifest(self, row: sqlite3.Row) -> dict:
        cid = row["id"]
        with self._lock:
            assets = self._db.execute(
                "SELECT COUNT(*) AS n FROM character_assets WHERE character_id=? AND accepted IN ('accepted','warning')",
                (cid,),
            ).fetchone()["n"]
            model = self._db.execute(
                "SELECT * FROM character_models WHERE character_id=? AND family='fl2va' ORDER BY version DESC LIMIT 1",
                (cid,),
            ).fetchone()
            job = self._db.execute(
                "SELECT * FROM character_jobs WHERE character_id=? AND status NOT IN ('ready','failed','cancelled') ORDER BY updated_at DESC LIMIT 1",
                (cid,),
            ).fetchone()
            metrics = self._db.execute(
                "SELECT scores_json FROM character_metrics WHERE character_id=? ORDER BY created_at DESC LIMIT 1",
                (cid,),
            ).fetchone()
        scores: dict = {}
        if metrics:
            try:
                scores = json.loads(metrics["scores_json"])
            except (ValueError, TypeError):
                scores = {}
        manifest = {
            "schema_version": SCHEMA_VERSION,
            "id": cid,
            "name": row["name"],
            "slug": row["slug"],
            "trigger_token": row["trigger_token"],
            "status": row["status"],
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
            "image_count": assets,
            "training_version": row["current_version"],
            "identity": {
                "default_mode": row["default_mode"],
                "lora_strength": float(scores.get("lora_strength", DEFAULT_LORA_STRENGTH)),
                "recommended_engine": scores.get("recommended_engine", "lora"),
            },
            "models": {
                "fl2va": (
                    {
                        "type": model["model_type"],
                        "path": model["path"],
                        "rank": model["rank"],
                        "alpha": model["alpha"],
                        "sha256": model["sha256"],
                    }
                    if model
                    else None
                ),
                # Futuro Ref2VA-specific LoRA (V1: sempre null, mai il LoRA FL2VA).
                "ref2va": None,
            },
            "references": {
                "preferred": [],
                "front": None,
                "left_three_quarter": None,
                "right_three_quarter": None,
                "profile_left": None,
                "profile_right": None,
                "full_body": None,
            },
            "metrics": {
                "lora_identity_score": scores.get("lora_identity_score"),
                "reference_identity_score": scores.get("reference_identity_score"),
                "default_identity_score": scores.get("default_identity_score"),
            },
            "active_job": (
                {"id": job["id"], "kind": job["kind"], "status": job["status"], "progress": job["progress"]}
                if job
                else None
            ),
        }
        return manifest

    def summary(self, row: sqlite3.Row) -> dict:
        manifest = self.build_manifest(row)
        return {
            "id": manifest["id"],
            "name": manifest["name"],
            "slug": manifest["slug"],
            "status": manifest["status"],
            "current_version": manifest["training_version"],
            "default_mode": manifest["identity"]["default_mode"],
            "image_count": manifest["image_count"],
            "identity_score": manifest["metrics"]["default_identity_score"],
            "recommended_engine": manifest["identity"]["recommended_engine"],
            "updated_at": manifest["updated_at"],
        }

    # -- CRUD ------------------------------------------------------------------

    def _unique_slug(self, base: str) -> str:
        slug, n = base, 1
        with self._lock:
            while self._db.execute("SELECT 1 FROM characters WHERE slug=?", (slug,)).fetchone():
                n += 1
                slug = f"{base}-{n}"
        return slug

    def _unique_trigger(self) -> str:
        for _ in range(5):
            token = "HCID_" + secrets.token_hex(3).upper()
            with self._lock:
                if not self._db.execute(
                    "SELECT 1 FROM characters WHERE trigger_token=?", (token,)
                ).fetchone():
                    return token
        raise RuntimeError("trigger token: troppe collisioni")

    def create_character(self, name: str) -> dict:
        clean = validate_name(name)
        cid = str(uuid.uuid4())
        now = _utcnow()
        slug = self._unique_slug(_slugify(clean))
        trigger = self._unique_trigger()
        char_dir = self.char_dir(cid)
        char_dir.mkdir(parents=True, exist_ok=False)
        os.chmod(char_dir, 0o700)
        for sub in _SUBDIRS:
            (char_dir / sub).mkdir(parents=True, exist_ok=True)
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO characters(id,name,slug,trigger_token,status,current_version,"
                "default_mode,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                (cid, clean, slug, trigger, "draft", 0, "auto", now, now),
            )
        row = self.get_row(cid)
        manifest = self.build_manifest(row)
        self._write_manifest(manifest)
        return manifest

    def get_row(self, character_id: str) -> sqlite3.Row | None:
        if not is_uuid(character_id):
            return None
        with self._lock:
            return self._db.execute("SELECT * FROM characters WHERE id=?", (character_id,)).fetchone()

    def get_character(self, character_id: str) -> dict | None:
        row = self.get_row(character_id)
        return self.build_manifest(row) if row else None

    def list_characters(self) -> list[dict]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM characters ORDER BY updated_at DESC").fetchall()
        return [self.summary(r) for r in rows]

    def _refresh_manifest(self, character_id: str) -> dict:
        row = self.get_row(character_id)
        if row is None:  # pragma: no cover - chiamato dopo check esistenza
            raise KeyError(character_id)
        manifest = self.build_manifest(row)
        self._write_manifest(manifest)
        return manifest

    def rename_character(self, character_id: str, name: str) -> dict | None:
        clean = validate_name(name)
        if self.get_row(character_id) is None:
            return None
        slug = self._unique_slug(_slugify(clean))
        with self._lock, self._db:
            self._db.execute(
                "UPDATE characters SET name=?, slug=?, updated_at=? WHERE id=?",
                (clean, slug, _utcnow(), character_id),
            )
        return self._refresh_manifest(character_id)

    def patch_character(self, character_id: str, patch: dict) -> dict | None:
        if self.get_row(character_id) is None:
            return None
        allowed = {"name", "default_mode", "lora_strength"}
        unknown = set(patch) - allowed
        if unknown:
            raise ValueError(f"campi non ammessi: {sorted(unknown)}")
        with self._lock, self._db:
            if "name" in patch:
                clean = validate_name(patch["name"])
                self._db.execute(
                    "UPDATE characters SET name=?, slug=?, updated_at=? WHERE id=?",
                    (clean, self._unique_slug(_slugify(clean)), _utcnow(), character_id),
                )
            if "default_mode" in patch:
                mode = validate_default_mode(patch["default_mode"])
                self._db.execute(
                    "UPDATE characters SET default_mode=?, updated_at=? WHERE id=?",
                    (mode, _utcnow(), character_id),
                )
            if "lora_strength" in patch:
                strength = validate_lora_strength(patch["lora_strength"])
                self._db.execute(
                    "INSERT INTO character_metrics(id,character_id,version,suite,scores_json,created_at)"
                    " VALUES (?,?,?,?,?,?)",
                    (
                        str(uuid.uuid4()),
                        character_id,
                        0,
                        "identity_settings",
                        json.dumps({"lora_strength": strength}),
                        _utcnow(),
                    ),
                )
                self._db.execute(
                    "UPDATE characters SET updated_at=? WHERE id=?", (_utcnow(), character_id)
                )
        return self._refresh_manifest(character_id)

    def set_status(self, character_id: str, status: str) -> dict | None:
        if status not in CHARACTER_STATUSES:
            raise ValueError(f"status non valido: {status}")
        if self.get_row(character_id) is None:
            return None
        with self._lock, self._db:
            self._db.execute(
                "UPDATE characters SET status=?, updated_at=? WHERE id=?",
                (status, _utcnow(), character_id),
            )
        return self._refresh_manifest(character_id)

    def delete_character(self, character_id: str) -> bool:
        if self.get_row(character_id) is None:
            return False
        with self._lock, self._db:
            for table in (
                "character_assets",
                "character_models",
                "character_jobs",
                "character_metrics",
            ):
                self._db.execute(f"DELETE FROM {table} WHERE character_id=?", (character_id,))
            self._db.execute("DELETE FROM characters WHERE id=?", (character_id,))
        # Cancellazione completa: dataset, cache, modelli, log del personaggio.
        shutil.rmtree(self.char_dir(character_id), ignore_errors=True)
        return True

    # -- job (stato persistente per M6; API minima gia pronta) ------------------

    def create_job(self, character_id: str, kind: str, version: int = 0) -> dict:
        if self.get_row(character_id) is None:
            raise KeyError(character_id)
        if kind not in ("analyze", "train", "evaluate", "benchmark", "retrain"):
            raise ValueError(f"job kind non valido: {kind}")
        jid = str(uuid.uuid4())
        now = _utcnow()
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO character_jobs(id,character_id,kind,status,version,progress,"
                "detail,error,pid,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                (jid, character_id, kind, "queued", version, 0.0, "", "", 0, now, now),
            )
        return self.get_job(jid)  # type: ignore[return-value]

    def update_job(self, job_id: str, **fields: object) -> dict | None:
        allowed = {"status", "progress", "detail", "error", "pid", "version"}
        unknown = set(fields) - allowed
        if unknown:
            raise ValueError(f"campi job non ammessi: {sorted(unknown)}")
        if "status" in fields and fields["status"] not in CHARACTER_JOB_STATUSES:
            raise ValueError(f"job status non valido: {fields['status']}")
        with self._lock, self._db:
            row = self._db.execute("SELECT * FROM character_jobs WHERE id=?", (job_id,)).fetchone()
            if row is None:
                return None
            sets = ", ".join(f"{k}=?" for k in fields) + ", updated_at=?"
            self._db.execute(
                f"UPDATE character_jobs SET {sets} WHERE id=?",
                (*fields.values(), _utcnow(), job_id),
            )
        return self.get_job(job_id)

    def get_job(self, job_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM character_jobs WHERE id=?", (job_id,)).fetchone()
        return dict(row) if row else None

    def active_job(self, character_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute(
                "SELECT * FROM character_jobs WHERE character_id=? AND status NOT IN"
                " ('ready','failed','cancelled') ORDER BY updated_at DESC LIMIT 1",
                (character_id,),
            ).fetchone()
        return dict(row) if row else None
