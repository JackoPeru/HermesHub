"""Small adapter over Hermes Agent's real profile primitives.

Hermes Hub deliberately keeps no bot runtime of its own.  This module only
validates profile names, delegates profile lifecycle to ``hermes_cli.profiles``
and stores the Bot Mode UI metadata alongside the profile.  It is imported by
the patched API server lazily so older Hermes installations can start and
report ``update_required`` instead of crashing.
"""

from __future__ import annotations

import asyncio
import inspect
import os
import re
import shutil
import stat
import tempfile
import uuid
from pathlib import Path
from typing import Any, Mapping, Sequence


BOT_CHAT_TITLE = "Bot Chat"
GROUP_SESSION_PREFIX = "Group: "
PASS_TOKEN = "PASS"
BOT_METADATA_KEY = "hermes-bots"
DEFAULT_PROFILE = "default"
PROFILE_NAME_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
CONNECTION_ID_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}$")
GROUP_NAME_RE = re.compile(r"^[^\r\n]{1,160}$")
MAX_DESCRIPTION = 2_000
MAX_SOUL = 100_000
MIN_GROUP_MEMBERS = 2
MAX_GROUP_MEMBERS = 6
MAX_GROUP_ROUNDS = 3
MAX_GROUP_MESSAGES = 10


class BotProfileError(ValueError):
    """A safe, client-visible bot/profile contract error."""

    def __init__(self, message: str, *, code: str = "invalid_bot", status: int = 400):
        super().__init__(message)
        self.code = code
        self.status = status


class BotProfileUnsupported(BotProfileError):
    def __init__(self, message: str = "Questa versione di Hermes non supporta i profili bot."):
        super().__init__(message, code="update_required", status=501)


def validate_profile_name(value: Any) -> str:
    name = str(value or "").strip().lower()
    if not PROFILE_NAME_RE.fullmatch(name):
        raise BotProfileError(
            "Il nome deve contenere 1-64 caratteri minuscoli: lettere, numeri, _ o -.",
            code="invalid_bot_name",
        )

    # Keep the local path-segment boundary, then defer reserved-name policy to
    # Hermes when the installed profile module exposes its validator.
    try:
        profiles = _profiles_module()
    except BotProfileUnsupported:
        profiles = None
    if profiles is not None:
        normalize = next(
            (
                getattr(profiles, primitive_name)
                for primitive_name in ("normalize_profile_name", "normalise_profile_name")
                if callable(getattr(profiles, primitive_name, None))
            ),
            None,
        )
        if normalize is not None:
            try:
                try:
                    result = normalize(name)
                except TypeError:
                    result = normalize(name=name)
            except Exception as exc:
                raise BotProfileError(
                    "Nome profilo non valido secondo Hermes.",
                    code="invalid_bot_name",
                ) from exc
            if result is False:
                raise BotProfileError(
                    "Nome profilo non valido secondo Hermes.",
                    code="invalid_bot_name",
                )
            if isinstance(result, str):
                name = result.strip().lower()

        if not PROFILE_NAME_RE.fullmatch(name):
            raise BotProfileError(
                "Nome profilo non valido secondo Hermes.",
                code="invalid_bot_name",
            )

        validate = next(
            (
                getattr(profiles, primitive_name)
                for primitive_name in ("validate_profile_name", "validate_profile", "validate_name")
                if callable(getattr(profiles, primitive_name, None))
            ),
            None,
        )
        if validate is not None:
            try:
                try:
                    result = validate(name)
                except TypeError:
                    result = validate(name=name)
            except Exception as exc:
                raise BotProfileError(
                    "Nome profilo non valido secondo Hermes.",
                    code="invalid_bot_name",
                ) from exc
            if result is False:
                raise BotProfileError(
                    "Nome profilo non valido secondo Hermes.",
                    code="invalid_bot_name",
                )
    return name


def _profiles_module() -> Any:
    try:
        from hermes_cli import profiles
    except Exception as exc:  # pragma: no cover - exercised on old installs
        raise BotProfileUnsupported() from exc
    required = ("profiles_to_serve", "get_profile_dir", "create_profile")
    if any(not callable(getattr(profiles, item, None)) for item in required):
        raise BotProfileUnsupported()
    return profiles


def _hermes_home() -> Path:
    try:
        from hermes_constants import get_hermes_home

        return Path(get_hermes_home()).expanduser()
    except Exception:
        configured = os.environ.get("HERMES_HOME")
        if configured:
            return Path(configured).expanduser()
        return Path.home() / ".hermes"


def multiplex_enabled(adapter: Any) -> bool:
    """Return true only when upstream explicitly enables profile multiplexing."""

    runner = getattr(adapter, "gateway_runner", None)
    config = getattr(runner, "config", None)
    return getattr(config, "multiplex_profiles", False) is True


def _explicit_bot_mode_protocol(profiles: Any) -> bool | None:
    """Read a dedicated upstream signal when the installed version exposes one."""

    for attr in ("bot_mode_protocol", "BOT_MODE_PROTOCOL"):
        value = getattr(profiles, attr, None)
        if isinstance(value, bool):
            return value
        if callable(value):
            try:
                result = value()
            except Exception:
                continue
            if isinstance(result, bool):
                return result
    return None


def _normalise_served(raw: Any) -> list[tuple[str, Path]]:
    result: list[tuple[str, Path]] = []
    if isinstance(raw, Mapping):
        raw = raw.items()
    for item in raw or []:
        if isinstance(item, (tuple, list)) and len(item) >= 2:
            name, path = item[0], item[1]
        else:
            name, path = item, None
        name = str(name or "").strip().lower()
        if not name:
            continue
        result.append((name, Path(path).expanduser() if path else _hermes_home() / "profiles" / name))
    return result


def served_profiles() -> list[tuple[str, Path]]:
    profiles = _profiles_module()
    try:
        rows = profiles.profiles_to_serve(multiplex=True)
    except TypeError:
        rows = profiles.profiles_to_serve()
    result = _normalise_served(rows)
    default_home = _hermes_home().resolve()
    if not any(name == DEFAULT_PROFILE for name, _ in result):
        result.insert(0, (DEFAULT_PROFILE, default_home))
    return result


def _metadata_path(profile_dir: Path) -> Path:
    for candidate in (profile_dir / "profile.yaml", profile_dir / "profile.yml", profile_dir / "config.yaml"):
        if candidate.is_file():
            return candidate
    return profile_dir / "profile.yaml"


def _yaml() -> Any:
    try:
        import yaml
    except Exception as exc:  # pragma: no cover - old installation
        raise BotProfileUnsupported("Il supporto YAML di Hermes non è disponibile.") from exc
    return yaml


def _read_document(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {}
    try:
        value = _yaml().safe_load(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise BotProfileError("Metadati del profilo non leggibili.", code="profile_metadata_invalid", status=500) from exc
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise BotProfileError("Metadati del profilo non validi.", code="profile_metadata_invalid", status=500)
    return value


def _atomic_write_text(path: Path, content: str, mode: int | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if mode is None:
        mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o600
    fd, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    temp_path = Path(temp_name)
    try:
        _set_file_mode(fd, temp_path, mode)
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            fd = -1
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp_path, path)
        os.chmod(path, mode)
    finally:
        if fd >= 0:
            os.close(fd)
        if temp_path.exists():
            temp_path.unlink()


def _set_file_mode(fd: int, path: Path, mode: int) -> None:
    fchmod = getattr(os, "fchmod", None)
    if callable(fchmod):
        fchmod(fd, mode)
    else:
        os.chmod(path, mode)


def _write_document(path: Path, document: dict[str, Any]) -> None:
    yaml = _yaml()
    mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o600
    encoded = yaml.safe_dump(document, sort_keys=False, allow_unicode=True)
    _atomic_write_text(path, encoded, mode)


def _bot_meta(document: dict[str, Any]) -> dict[str, Any]:
    ui_meta = document.get("ui_meta")
    if not isinstance(ui_meta, dict):
        ui_meta = {}
        document["ui_meta"] = ui_meta
    value = ui_meta.get(BOT_METADATA_KEY)
    if not isinstance(value, dict):
        value = {}
        ui_meta[BOT_METADATA_KEY] = value
    return value


def _public_record(name: str, profile_dir: Path, document: dict[str, Any] | None = None) -> dict[str, Any]:
    metadata = _bot_meta(document or {}) if document is not None else {}
    appearance = metadata.get("appearance") if isinstance(metadata.get("appearance"), dict) else {}
    official_display_name = document.get("display_name") if isinstance(document, dict) else None
    official_description = document.get("description") if isinstance(document, dict) else None
    return {
        "profile": name,
        "display_name": str(metadata.get("title") or official_display_name or metadata.get("display_name") or name),
        "description": str(official_description or metadata.get("description") or ""),
        "hidden": bool(metadata.get("hidden", appearance.get("hidden", False))),
        "chat_id": str(metadata.get("chat") or metadata.get("chat_id") or "") or None,
        "chat_title": str(metadata.get("chat_title") or BOT_CHAT_TITLE),
        "is_default": name == DEFAULT_PROFILE or profile_dir.resolve() == _hermes_home().resolve(),
    }


def list_bots() -> list[dict[str, Any]]:
    """Return the upstream profile roster without paths, secrets or config."""

    result: list[dict[str, Any]] = []
    for name, path in served_profiles():
        document = _read_document(_metadata_path(path))
        result.append(_public_record(name, path, document))
    return result


def readiness(adapter: Any) -> dict[str, Any]:
    """Describe exact bot readiness; no multiplex means no bot chat."""

    try:
        profiles = _profiles_module()
        primitives = True
        protocol = _explicit_bot_mode_protocol(profiles)
        delete_available = callable(getattr(profiles, "delete_profile", None))
    except BotProfileUnsupported:
        primitives = False
        protocol = False
        delete_available = False
    enabled = multiplex_enabled(adapter)
    # The profile primitives plus an explicit multiplex flag are the safe
    # routing prerequisite.  ``bot_mode_protocol`` remains informational and
    # is never inferred from the existence of those older primitives.
    chat_supported = primitives and enabled
    return {
        "bot_mode_protocol": protocol is True,
        "bot_mode_protocol_known": protocol is not None,
        "profile_multiplexing": enabled,
        "multiplex_enabled": enabled,
        "chat_supported": chat_supported,
        "capabilities": {
            "roster": primitives,
            "create": primitives,
            "edit": primitives,
            "delete": primitives and delete_available,
            "canonical_chat": chat_supported,
            "profile_scoped_chat": chat_supported,
            "groups": chat_supported,
            "group_turn": chat_supported,
            "cross_machine_connections": False,
        },
        "gaps": [
            *([] if chat_supported else ["groups"]),
            "cross_machine_connections",
        ] + ([] if protocol is not None else ["bot_mode_protocol_unknown"]),
    }


def _profile_path(name: str) -> Path:
    name = validate_profile_name(name)
    for candidate, path in served_profiles():
        if candidate == name:
            return path
    try:
        return Path(_profiles_module().get_profile_dir(name)).expanduser()
    except Exception as exc:
        raise BotProfileError("Profilo bot non trovato.", code="bot_not_found", status=404) from exc


def _safe_profile_path(name: str) -> Path:
    path = _profile_path(name).resolve()
    profiles_root = (_hermes_home() / "profiles").resolve()
    if name == DEFAULT_PROFILE:
        raise BotProfileError("Il profilo predefinito non può essere eliminato.", code="default_bot_protected", status=409)
    if path == _hermes_home().resolve() or profiles_root not in path.parents:
        raise BotProfileError("Percorso profilo non valido.", code="unsafe_profile_path", status=500)
    return path


def _call_create_profile(name: str, clone_from: str | None, no_skills: bool, description: str) -> Any:
    create = _profiles_module().create_profile
    kwargs: dict[str, Any] = {
        "name": name,
        "clone_from": clone_from,
        # Never enable clone_all: upstream may copy the credential file and fork the
        # credential store. clone_config is the supported profile clone path.
        "clone_all": False,
        "clone_config": bool(clone_from),
        "no_skills": no_skills,
        "description": description or None,
    }
    try:
        parameters = inspect.signature(create).parameters
        kwargs = {key: value for key, value in kwargs.items() if key in parameters}
    except (TypeError, ValueError):
        pass
    try:
        return create(**kwargs)
    except TypeError:
        # A narrow compatibility path for older positional primitives.
        return create(name, clone_from=clone_from, no_skills=no_skills)


def _copy_static_env(clone_from: str | None, target: Path) -> None:
    if not clone_from:
        return
    source = _profile_path(clone_from) / ".env"
    destination = target / ".env"
    if not source.is_file() or destination.exists():
        return
    mode = stat.S_IMODE(source.stat().st_mode)
    fd, temp_name = tempfile.mkstemp(prefix=".env.", dir=str(target))
    temp_path = Path(temp_name)
    try:
        _set_file_mode(fd, temp_path, mode)
        with os.fdopen(fd, "wb") as output:
            fd = -1
            with source.open("rb") as input_file:
                shutil.copyfileobj(input_file, output)
                output.flush()
                os.fsync(output.fileno())
        os.replace(temp_path, destination)
        os.chmod(destination, mode)
    finally:
        if fd >= 0:
            os.close(fd)
        if temp_path.exists():
            temp_path.unlink()


def create_bot(
    name: Any,
    *,
    clone_from: Any = None,
    description: Any = "",
    soul: Any = None,
    display_name: Any = None,
    no_skills: Any = False,
) -> dict[str, Any]:
    bot_name = validate_profile_name(name)
    if bot_name == DEFAULT_PROFILE:
        raise BotProfileError("Il profilo predefinito non è un nuovo bot.", code="default_bot_protected", status=409)
    source = validate_profile_name(clone_from) if clone_from else None
    if source == bot_name:
        raise BotProfileError("Il clone non può puntare allo stesso profilo.", code="invalid_clone_source")
    text = str(description or "").strip()
    if len(text) > MAX_DESCRIPTION:
        raise BotProfileError("La descrizione è troppo lunga.", code="description_too_long")
    label = str(display_name or bot_name).strip()
    if len(label) > MAX_DESCRIPTION:
        raise BotProfileError("Il nome visualizzato è troppo lungo.", code="display_name_too_long")
    soul_text = None if soul is None else str(soul)
    if soul_text is not None and len(soul_text) > MAX_SOUL:
        raise BotProfileError("SOUL.md è troppo grande.", code="soul_too_long")
    existing = {item[0] for item in served_profiles()}
    if bot_name in existing:
        raise BotProfileError("Esiste già un bot con questo nome.", code="bot_exists", status=409)
    if source and source not in existing:
        raise BotProfileError("Profilo clone non trovato.", code="clone_not_found", status=404)
    _call_create_profile(bot_name, source, bool(no_skills), text)
    target = _profile_path(bot_name)
    _copy_static_env(source, target)
    if soul_text is not None:
        _atomic_write_text(
            target / "SOUL.md",
            soul_text if soul_text.endswith("\n") else soul_text + "\n",
            0o600,
        )
    document_path = _metadata_path(target)
    document = _read_document(document_path)
    metadata = _bot_meta(document)
    document["display_name"] = label
    document["description"] = text
    metadata["title"] = label
    metadata["description"] = text
    metadata.setdefault("chat_title", BOT_CHAT_TITLE)
    _write_document(document_path, document)
    return _public_record(bot_name, target, document)


def update_bot(name: Any, *, description: Any = None, soul: Any = None, display_name: Any = None) -> dict[str, Any]:
    bot_name = validate_profile_name(name)
    target = _profile_path(bot_name)
    if not target.exists():
        raise BotProfileError("Profilo bot non trovato.", code="bot_not_found", status=404)
    text = None if description is None else str(description).strip()
    if text is not None and len(text) > MAX_DESCRIPTION:
        raise BotProfileError("La descrizione è troppo lunga.", code="description_too_long")
    if soul is not None:
        soul_text = str(soul)
        if len(soul_text) > MAX_SOUL:
            raise BotProfileError("SOUL.md è troppo grande.", code="soul_too_long")
        soul_path = target / "SOUL.md"
        mode = stat.S_IMODE(soul_path.stat().st_mode) if soul_path.exists() else 0o600
        _atomic_write_text(soul_path, soul_text if soul_text.endswith("\n") else soul_text + "\n", mode)
    document_path = _metadata_path(target)
    document = _read_document(document_path)
    metadata = _bot_meta(document)
    if text is not None:
        document["description"] = text
        metadata["description"] = text
    if display_name is not None:
        label = str(display_name).strip()
        if len(label) > MAX_DESCRIPTION:
            raise BotProfileError("Il nome visualizzato è troppo lungo.", code="display_name_too_long")
        if not label:
            raise BotProfileError("Il nome visualizzato è obbligatorio.", code="invalid_display_name")
        document["display_name"] = label
        metadata["title"] = label
    _write_document(document_path, document)
    return _public_record(bot_name, target, document)


def delete_bot(name: Any, confirmation: Any) -> dict[str, Any]:
    bot_name = validate_profile_name(name)
    if bot_name == DEFAULT_PROFILE:
        raise BotProfileError("Il profilo predefinito non può essere eliminato.", code="default_bot_protected", status=409)
    if str(confirmation or "").strip() != bot_name:
        raise BotProfileError("Per eliminare il bot devi confermare il nome esatto.", code="delete_confirmation_required", status=400)
    target = _safe_profile_path(bot_name)
    if not target.exists():
        raise BotProfileError("Profilo bot non trovato.", code="bot_not_found", status=404)
    profiles = _profiles_module()
    delete = getattr(profiles, "delete_profile", None)
    if not callable(delete):
        raise BotProfileUnsupported("Questa versione di Hermes non espone la cancellazione ufficiale dei profili.")
    try:
        delete(bot_name, yes=True)
    except TypeError:
        # Do not retry with an implicit/non-confirmed destructive primitive.
        raise BotProfileUnsupported("La cancellazione ufficiale dei profili richiede un aggiornamento di Hermes.")
    return {"profile": bot_name, "deleted": True}


def _db_create(db: Any, session_id: str, model: Any) -> None:
    try:
        db.create_session(session_id, "api_server", model=model)
        return
    except TypeError:
        db.create_session(session_id, "api_server")


def _set_optional_session_flag(db: Any, session_id: str, names: tuple[str, ...], value: Any) -> bool:
    for method_name in names:
        method = getattr(db, method_name, None)
        if not callable(method):
            continue
        try:
            method(session_id, value)
            return True
        except TypeError:
            try:
                method(session_id, bool(value))
                return True
            except Exception:
                continue
        except Exception:
            continue
    return False


def canonical_chat(adapter: Any, name: Any, *, description: str = "") -> dict[str, Any]:
    """Find or create one real SessionDB conversation named ``Bot Chat``."""

    bot_name = validate_profile_name(name)
    state = readiness(adapter)
    if not state["chat_supported"]:
        if not state["multiplex_enabled"]:
            code = "profile_multiplexing_disabled"
            message = "Il multiplexing dei profili Hermes non è attivo: chat bot rifiutata."
            status = 503
        else:
            code = "update_required"
            message = "Chat bot non disponibile: protocollo Bot Mode upstream non confermato."
            status = 501
        raise BotProfileError(
            message,
            code=code,
            status=status,
        )
    profile_dir = _profile_path(bot_name)
    if not profile_dir.exists():
        raise BotProfileError("Profilo bot non trovato.", code="bot_not_found", status=404)
    scope = getattr(adapter, "_profile_scope", None)
    if not callable(scope):
        raise BotProfileUnsupported("Il gateway non espone lo scope profilo upstream.")
    pinned = False
    hidden = False
    with scope(bot_name):
        db = adapter._ensure_session_db()
        if db is None:
            raise BotProfileError("Archivio sessioni Hermes non disponibile.", code="session_db_unavailable", status=503)
        document_path = _metadata_path(profile_dir)
        document = _read_document(document_path)
        metadata = _bot_meta(document)
        chat_id = str(metadata.get("chat") or metadata.get("chat_id") or "").strip()
        session = db.get_session(chat_id) if chat_id else None
        if not session:
            try:
                sessions = db.list_sessions_rich(limit=200, offset=0, include_children=False, order_by_last_active=True)
            except TypeError:
                sessions = db.list_sessions_rich(limit=200)
            for candidate in sessions or []:
                if str(candidate.get("title") or "") == BOT_CHAT_TITLE:
                    session = candidate
                    break
        if session:
            chat_id = str(session.get("id") or chat_id)
        if not chat_id:
            chat_id = f"bot_{bot_name}_{uuid.uuid4().hex[:16]}"
            _db_create(db, chat_id, getattr(adapter, "_model_name", None))
            session = db.get_session(chat_id) or {"id": chat_id}
        if str(session.get("title") or "") != BOT_CHAT_TITLE:
            try:
                db.set_session_title(chat_id, BOT_CHAT_TITLE)
            except Exception:
                pass
        pinned = _set_optional_session_flag(db, chat_id, ("set_session_pinned", "set_pinned"), True)
        hidden = _set_optional_session_flag(db, chat_id, ("set_session_hidden", "set_hidden"), True)
    metadata["chat"] = chat_id
    metadata["chat_title"] = BOT_CHAT_TITLE
    _write_document(document_path, document)
    return {
        "profile": bot_name,
        "session_id": chat_id,
        "title": BOT_CHAT_TITLE,
        "pinned": pinned,
        "hidden": hidden,
        "multiplex_enabled": True,
        "chat_supported": True,
    }


def validate_group_name(value: Any) -> str:
    name = str(value or "").strip()
    if not GROUP_NAME_RE.fullmatch(name):
        raise BotProfileError(
            "Il nome del gruppo deve contenere 1-160 caratteri e non può contenere righe vuote.",
            code="invalid_group_name",
        )
    return name


def _normalise_connection_id(value: Any) -> str:
    connection_id = str(value or "primary").strip()
    if not CONNECTION_ID_RE.fullmatch(connection_id):
        raise BotProfileError("Identificativo connessione non valido.", code="invalid_connection_id")
    return connection_id


def identity_key(connection_id: Any, profile: Any) -> str:
    return f"{_normalise_connection_id(connection_id)}::{validate_profile_name(profile)}"


def _normalise_handle(value: Any, fallback: str) -> str:
    handle = str(value or fallback).strip().lstrip("@").lower()
    handle = re.sub(r"[^a-z0-9_.-]+", "-", handle).strip("-._")
    return handle[:64] or fallback


def validate_group_members(members: Any) -> list[dict[str, str]]:
    """Validate source-qualified members once at the gateway boundary.

    The connection id is a public routing identity only.  It is never a token
    and must be supplied separately from the platform credential stores.
    """

    if isinstance(members, (str, bytes, Mapping)) or not isinstance(members, Sequence):
        raise BotProfileError("I membri del gruppo devono essere un array.", code="invalid_group_members")
    if not MIN_GROUP_MEMBERS <= len(members) <= MAX_GROUP_MEMBERS:
        raise BotProfileError(
            f"Un gruppo Hermes deve contenere da {MIN_GROUP_MEMBERS} a {MAX_GROUP_MEMBERS} bot.",
            code="group_member_count",
        )
    result: list[dict[str, str]] = []
    identities: set[str] = set()
    for raw in members:
        if not isinstance(raw, Mapping):
            raise BotProfileError("Ogni membro deve essere source-qualified.", code="invalid_group_member")
        profile = validate_profile_name(raw.get("profile", raw.get("name")))
        connection_id = _normalise_connection_id(raw.get("connection_id", raw.get("connectionId", "primary")))
        key = identity_key(connection_id, profile)
        if key in identities:
            raise BotProfileError("Lo stesso bot non può comparire due volte nel gruppo.", code="duplicate_group_member")
        identities.add(key)
        result.append(
            {
                "connection_id": connection_id,
                "profile": profile,
                "display_name": str(raw.get("display_name", raw.get("displayName", profile)) or profile).strip()[:200],
                "handle": _normalise_handle(raw.get("handle"), str(raw.get("display_name", raw.get("profile", profile)))),
                "identity_key": key,
            }
        )
    handles: dict[str, int] = {}
    for member in result:
        handles[member["handle"]] = handles.get(member["handle"], 0) + 1
    used_handles: set[str] = set()
    for member in result:
        if handles[member["handle"]] > 1:
            member["handle"] = _normalise_handle(
                f"{member['handle']}-{member['connection_id']}",
                member["profile"],
            )
        candidate = member["handle"]
        suffix = 0
        while candidate in used_handles:
            suffix += 1
            candidate = _normalise_handle(
                f"{member['handle']}-{member['profile']}-{suffix}",
                member["profile"],
            )
        member["handle"] = candidate
        used_handles.add(candidate)
    return result


def is_exact_pass(value: Any) -> bool:
    """Recognize the complete, case-insensitive pass/silence forms only."""

    normalized = str(value or "").strip().casefold()
    return normalized in {"", "(pass)", "pass", "pass."}


def is_silent_group_reply(value: Any) -> bool:
    """Alias kept explicit for callers that need to distinguish silence."""

    return is_exact_pass(value)


def _group_follow_up_prompt(original_user_message: str) -> str:
    """Keep the original request visible on every bounded follow-up prompt."""

    prefix = "Richiesta originale dell'utente:\n"
    suffix = "\n\nContinua il turno del gruppo usando il contesto recente."
    available = max(1, 20_000 - len(prefix) - len(suffix))
    return f"{prefix}{original_user_message[:available]}{suffix}"


def extract_group_routes(value: Any, members: Sequence[Mapping[str, str]]) -> dict[str, Any]:
    text = str(value or "")
    by_handle = {str(member.get("handle") or "").lower(): member for member in members}
    everyone = bool(re.search(r"(?<![\w])@everyone(?![\w])", text, flags=re.IGNORECASE))
    escalate = bool(re.search(r"(?<![\w])@user(?![\w])", text, flags=re.IGNORECASE))
    mentions = [item.lower() for item in re.findall(r"(?<![\w])@([a-zA-Z0-9][a-zA-Z0-9_.-]{0,63})", text)]
    targets: list[dict[str, str]] = []
    seen: set[str] = set()
    if everyone:
        targets = [dict(member) for member in members]
    else:
        for handle in mentions:
            member = by_handle.get(handle)
            if member is None or member.get("identity_key") in seen:
                continue
            seen.add(str(member.get("identity_key")))
            targets.append(dict(member))
    return {
        "pass": is_exact_pass(text),
        "escalate_to_user": escalate,
        "everyone": everyone,
        "mentions": mentions,
        "targets": targets,
    }


def _group_session(adapter: Any, member: Mapping[str, str], group_name: str) -> str:
    profile = validate_profile_name(member["profile"])
    profile_dir = _profile_path(profile)
    if not profile_dir.exists():
        raise BotProfileError("Profilo bot non trovato.", code="bot_not_found", status=404)
    scope = getattr(adapter, "_profile_scope", None)
    if not callable(scope):
        raise BotProfileUnsupported("Il gateway non espone lo scope profilo upstream.")
    title = f"{GROUP_SESSION_PREFIX}{group_name}"
    key = group_name.casefold()
    document_path = _metadata_path(profile_dir)
    with scope(profile):
        db = adapter._ensure_session_db()
        if db is None:
            raise BotProfileError("Archivio sessioni Hermes non disponibile.", code="session_db_unavailable", status=503)
        document = _read_document(document_path)
        metadata = _bot_meta(document)
        groups = metadata.get("group_sessions")
        if not isinstance(groups, dict):
            groups = {}
            metadata["group_sessions"] = groups
        entry = groups.get(key) if isinstance(groups.get(key), dict) else {}
        session_id = str(entry.get("session_id") or "").strip()
        session = db.get_session(session_id) if session_id else None
        if session is None:
            try:
                sessions = db.list_sessions_rich(limit=500, offset=0, include_children=False, order_by_last_active=True)
            except TypeError:
                sessions = db.list_sessions_rich(limit=500)
            session = next((candidate for candidate in sessions or [] if str(candidate.get("title") or "") == title), None)
        if session is not None:
            session_id = str(session.get("id") or session_id)
        if not session_id:
            session_id = f"group_{re.sub(r'[^a-z0-9]+', '_', key)[:48]}_{uuid.uuid4().hex[:16]}"
            _db_create(db, session_id, getattr(adapter, "_model_name", None))
            session = db.get_session(session_id) or {"id": session_id}
        if str(session.get("title") or "") != title:
            try:
                db.set_session_title(session_id, title)
            except Exception:
                pass
        groups[key] = {"session_id": session_id, "title": title, "name": group_name}
        _write_document(document_path, document)
    return session_id


def _agent_result_text(result: Any) -> str:
    if isinstance(result, tuple) and result:
        result = result[0]
    if isinstance(result, Mapping):
        for key in ("final_response", "response", "text", "message", "content"):
            value = result.get(key)
            if isinstance(value, str):
                return value
    return str(result or "")


async def _run_group_member(
    adapter: Any,
    member: Mapping[str, str],
    group_name: str,
    prompt: str,
    transcript: Sequence[Mapping[str, str]],
) -> tuple[str, str]:
    locks = getattr(adapter, "_hermes_hub_group_member_locks", None)
    if not isinstance(locks, dict):
        locks = {}
        try:
            setattr(adapter, "_hermes_hub_group_member_locks", locks)
        except Exception:
            pass
    key = str(member["identity_key"])
    lock = locks.setdefault(key, asyncio.Lock())
    async with lock:
        session_id = _group_session(adapter, member, group_name)
        system_prompt = (
            f"Sei il bot {member['handle']} nel gruppo {group_name!r}. "
            "Rispondi usando il runtime Hermes e la sessione assegnata. "
            "Scrivi esattamente PASS solo quando il compito è concluso. "
            "Usa @handle per passare il turno, @everyone per coinvolgere tutti, "
            "e @user solo quando serve l'intervento dell'utente."
        )
        if transcript:
            system_prompt += "\nContesto recente del turno:\n" + "\n".join(
                f"{item.get('handle', 'bot')}: {str(item.get('reply', ''))[:2000]}" for item in transcript[-8:]
            )
        kwargs: dict[str, Any] = {
            "user_message": prompt,
            "conversation_history": [],
            "ephemeral_system_prompt": system_prompt,
            "session_id": session_id,
            "gateway_session_key": f"hermes-hub-group:{group_name.casefold()}:{key}",
        }
        run_agent = getattr(adapter, "_run_agent", None)
        if not callable(run_agent):
            raise BotProfileUnsupported("Il runtime Hermes non espone l'esecuzione agente ufficiale.")
        try:
            signature = inspect.signature(run_agent)
            if not any(parameter.kind == inspect.Parameter.VAR_KEYWORD for parameter in signature.parameters.values()):
                kwargs = {key: value for key, value in kwargs.items() if key in signature.parameters}
        except (TypeError, ValueError):
            pass
        result = await run_agent(**kwargs)
        return session_id, _agent_result_text(result)


async def group_turn(
    adapter: Any,
    group_name: Any,
    members: Any,
    user_message: Any,
    *,
    max_rounds: Any = MAX_GROUP_ROUNDS,
    max_messages: Any = MAX_GROUP_MESSAGES,
    local_connection_id: Any = None,
    target_profiles: Any = None,
    transcript: Any = None,
    original_user_message: Any = None,
) -> dict[str, Any]:
    """Run one bounded group turn on Hermes' real profile/session runtime."""

    name = validate_group_name(group_name)
    qualified = validate_group_members(members)
    prompt = str(user_message or "").strip()
    original_prompt = str(user_message if original_user_message is None else original_user_message or "").strip()
    if not prompt or len(prompt) > 20_000 or not original_prompt or len(original_prompt) > 20_000:
        raise BotProfileError("Il messaggio del gruppo è obbligatorio e limitato a 20.000 caratteri.", code="invalid_group_message")
    try:
        rounds_limit = int(max_rounds)
        messages_limit = int(max_messages)
    except (TypeError, ValueError) as exc:
        raise BotProfileError("Limiti gruppo non validi.", code="invalid_group_limits") from exc
    if not 1 <= rounds_limit <= MAX_GROUP_ROUNDS or not 1 <= messages_limit <= MAX_GROUP_MESSAGES:
        raise BotProfileError(
            f"Un turno gruppo è limitato a {MAX_GROUP_ROUNDS} round e {MAX_GROUP_MESSAGES} messaggi bot.",
            code="group_limits_exceeded",
        )
    initial_route = extract_group_routes(prompt, qualified)
    available_targets = qualified
    if local_connection_id is not None:
        connection_id = _normalise_connection_id(local_connection_id)
        available_targets = [member for member in available_targets if member["connection_id"] == connection_id]
    selected = available_targets
    if target_profiles is not None:
        if isinstance(target_profiles, (str, bytes)) or not isinstance(target_profiles, Sequence):
            raise BotProfileError("target_profiles deve essere un array.", code="invalid_group_targets")
        wanted = {validate_profile_name(value) for value in target_profiles}
        selected = [member for member in selected if member["profile"] in wanted]
    elif initial_route["targets"]:
        wanted = {member["identity_key"] for member in initial_route["targets"]}
        selected = [member for member in selected if member["identity_key"] in wanted]
    if not selected:
        return {
            "object": "hermes.hub.group_turn",
            "group_name": name,
            "outcome": "no_target",
            "rounds": 0,
            "bot_messages": 0,
            "reply": "",
            "member_results": [],
            "member_failures": [],
        }
    recent = transcript if isinstance(transcript, Sequence) and not isinstance(transcript, (str, bytes, Mapping)) else []
    pending = list(selected)
    base_targets = list(available_targets)
    results: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []
    failed_identities: set[str] = set()
    last_reply = ""
    next_routes: list[dict[str, str]] = []
    rounds = 0
    bot_messages = 0
    outcome = "reply"
    escalated = False
    while pending and rounds < rounds_limit and bot_messages < messages_limit:
        rounds += 1
        round_targets = list(pending)
        pending = []
        round_non_pass = 0
        round_failures = 0
        next_routes = []
        for member in round_targets:
            # A pass is silence and does not consume the ten posted-message cap,
            # but the rest of the already scheduled round still gets its turn.
            if member["identity_key"] in failed_identities:
                continue
            if bot_messages >= messages_limit:
                break
            try:
                session_id, reply = await _run_group_member(adapter, member, name, prompt, recent)
            except asyncio.CancelledError:
                raise
            except Exception:
                round_failures += 1
                failed_identities.add(member["identity_key"])
                failures.append(
                    {
                        "identity_key": member["identity_key"],
                        "connection_id": member["connection_id"],
                        "profile": member["profile"],
                        "handle": member["handle"],
                        "code": "member_failed",
                        "message": "Il membro non ha completato il turno.",
                    }
                )
                continue
            silent = is_silent_group_reply(reply)
            route = extract_group_routes(reply, qualified)
            if not silent:
                bot_messages += 1
                round_non_pass += 1
                last_reply = reply
            else:
                route = {
                    "pass": True,
                    "escalate_to_user": False,
                    "everyone": False,
                    "mentions": [],
                    "targets": [],
                }
            result = {
                "identity_key": member["identity_key"],
                "connection_id": member["connection_id"],
                "profile": member["profile"],
                "handle": member["handle"],
                "session_id": session_id,
                "reply": reply,
                "silent": silent,
                "non_pass": not silent,
                "route": route,
            }
            results.append(result)
            if not silent:
                recent = [*recent[-8:], result]
            if not silent and route["escalate_to_user"]:
                # @user is deliberately visible immediately; it is the one
                # route that may stop the current serial responder list.
                outcome = "escalation"
                escalated = True
                break
            if not silent and route["targets"]:
                next_routes.extend(route["targets"])
        if escalated:
            break
        if round_non_pass == 0:
            # Only a complete round with no posted/non-pass reply settles the
            # room.  Member failures remain explicit and never become success.
            outcome = "partial" if round_failures or failures else "pass"
            break
        if bot_messages >= messages_limit:
            outcome = "bounded"
            break
        if rounds >= rounds_limit:
            outcome = "bounded"
            break
        # Mentions are routing instructions for the *next* round. With no
        # mention, keep the room alive with the same responders; this avoids
        # treating one unaddressed reply as an implicit settlement.
        available_keys = {item["identity_key"] for item in base_targets} - failed_identities
        routed = [member for member in next_routes if member["identity_key"] in available_keys]
        candidates = routed or [member for member in base_targets if member["identity_key"] not in failed_identities]
        deduped: dict[str, dict[str, str]] = {member["identity_key"]: member for member in candidates}
        pending = list(deduped.values())
        prompt = _group_follow_up_prompt(original_prompt)
    if (bot_messages >= messages_limit or rounds >= rounds_limit) and outcome == "reply":
        outcome = "bounded"
    if failures and results and outcome not in {"pass", "escalation", "partial"}:
        outcome = "partial"
    elif failures and not results:
        outcome = "error"
    return {
        "object": "hermes.hub.group_turn",
        "group_name": name,
        "outcome": outcome,
        "rounds": rounds,
        "bot_messages": bot_messages,
        "reply": last_reply,
        "member_results": results,
        "member_failures": failures,
        "next_targets": next_routes,
        "limits": {"max_rounds": rounds_limit, "max_messages": messages_limit},
    }


def public_error(exc: Exception) -> tuple[dict[str, Any], int]:
    if isinstance(exc, BotProfileError):
        return {"error": {"code": exc.code, "message": str(exc)}}, exc.status
    return {"error": {"code": "bot_operation_failed", "message": "Operazione bot non riuscita."}}, 500
