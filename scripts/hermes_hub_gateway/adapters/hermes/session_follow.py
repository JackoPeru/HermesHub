"""Follow-up delle sessioni agent dentro le conversazioni Hub collegate.

Problema: quando un turno continua sul server dopo che il client si e'
disconnesso (chat completions legacy, kill app, rete morta), i nuovi messaggi
restano solo nel session DB dell'agent. L'app mostra una chat ferma e al
rientro "non c'e' niente da mostrare": il binding di resume copre solo
runs/Responses, e le shadow conversations saltano le sessioni Hub-managed.
Zona morta da entrambi i lati.

Questo modulo chiude il lato gateway: per ogni conversazione Hub con
``hermesSessionId`` valorizzato, rilegge le righe nuove dal session DB del
profilo giusto e le accoda ai messaggi Hub (replace last-write-wins con
payload completo, mai drop). Il change feed esistente
(``/v1/hub/conversations/events``) avvisa le app, che tirano con il pull
normale e fondono in locale. Non solleva mai: ogni conversazione e'
isolata in try/except.
"""

from __future__ import annotations

import json
import os
import re
import sqlite3

PROFILE_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
MAX_CONVOS_PER_RUN = 25
MAX_TEXT_CHARS = 8000
MAX_THINKING_CHARS = 2000
MAX_HUB_MESSAGES = 500
MIN_RUN_INTERVAL_S = 45.0
THROTTLE_NAME = ".session_follow_throttle"
CURSOR_NAME = ".session_follow_cursors.json"
CURSOR_OVERLAP = 25
READ_LIMIT = 400


def hermes_home() -> str:
    return os.environ.get("HERMES_HOME") or os.path.expanduser("~/.hermes")


def hub_state_path(home: str | None = None) -> str:
    """Path of the Hub SQLite store (same file the gateway serves)."""
    override = (os.environ.get("HERMES_HUB_STATE_PATH") or "").strip()
    if override and os.path.isfile(override):
        return override
    return os.path.join(home or hermes_home(), "hub_state.sqlite3")


def profile_db_path(bot_identity_key: str, home: str | None = None) -> str | None:
    """Session DB del profilo: 'primary::director' -> profiles/director/state.db.

    Ritorna None se non risolvibile (mai eccezioni verso il chiamante: il
    chiamante salta la conversazione).
    """
    base = home or hermes_home()
    identity = (bot_identity_key or "").strip()
    if not identity:
        return os.path.join(base, "state.db")
    profile = identity.split("::")[-1].strip()
    if not PROFILE_RE.match(profile):
        return None
    candidate = os.path.join(base, "profiles", profile, "state.db")
    return candidate if os.path.isfile(candidate) else None


def _connect_ro(path: str) -> sqlite3.Connection | None:
    try:
        db = sqlite3.connect("file:" + path + "?mode=ro", uri=True, timeout=10)
    except Exception:
        return None
    db.row_factory = sqlite3.Row
    return db


def read_session_status(db_path: str, session_id: str) -> dict:
    """ended_at presente = sessione chiusa; assente = ancora aperta."""
    db = _connect_ro(db_path)
    if db is None:
        return {}
    try:
        row = db.execute(
            "SELECT ended_at, end_reason FROM sessions WHERE id = ?", (session_id,)
        ).fetchone()
    except Exception:
        return {}
    finally:
        try:
            db.close()
        except Exception:
            pass
    if row is None:
        return {}
    return {"ended": row["ended_at"] is not None, "end_reason": row["end_reason"] or ""}


def read_session_rows(
    db_path: str, session_id: str, limit: int = 100, since_id: int = 0
) -> list[dict]:
    """Righe della sessione (id crescenti), da since_id escluso. Solo lettura."""
    db = _connect_ro(db_path)
    if db is None:
        return []
    try:
        rows = db.execute(
            "SELECT id, role, content, tool_call_id, tool_calls, tool_name,"
            " timestamp, finish_reason, reasoning_content, display_kind"
            " FROM messages WHERE session_id = ? AND id > ? ORDER BY id ASC LIMIT ?",
            (session_id, int(since_id), max(1, min(int(limit), 1000))),
        ).fetchall()
    except Exception:
        return []
    finally:
        try:
            db.close()
        except Exception:
            pass
    out = []
    for row in rows:
        try:
            out.append(
                {
                    "id": int(row["id"]),
                    "role": str(row["role"] or ""),
                    "content": str(row["content"] or ""),
                    "tool_call_id": str(row["tool_call_id"] or ""),
                    "tool_calls": str(row["tool_calls"] or ""),
                    "tool_name": str(row["tool_name"] or ""),
                    "timestamp": float(row["timestamp"] or 0.0),
                    "reasoning": str(row["reasoning_content"] or ""),
                    "display_kind": str(row["display_kind"] or ""),
                }
            )
        except Exception:
            continue
    return out


def _throttle_path(hub_db_path: str) -> str:
    parent = os.path.dirname(hub_db_path) or "."
    return os.path.join(parent, THROTTLE_NAME)


def _throttled(hub_db_path: str) -> bool:
    try:
        with open(_throttle_path(hub_db_path), encoding="utf-8") as handle:
            last = float(handle.read().strip() or 0)
    except Exception:
        return False
    try:
        import time as _time

        return (_time.time() - last) < MIN_RUN_INTERVAL_S
    except Exception:
        return False


def _stamp_throttle(hub_db_path: str) -> None:
    try:
        import time as _time

        with open(_throttle_path(hub_db_path), "w", encoding="utf-8") as handle:
            handle.write(str(_time.time()))
    except Exception:
        pass


def _scan_mapped_conversations(hub_db_path: str) -> list[tuple[str, int, str]] | None:
    """(entity_id, updated_at, hermesSessionId) senza caricare i payload.

    None in caso di errore DB (il chiamante non tocca nulla).
    """
    try:
        db = sqlite3.connect("file:" + hub_db_path + "?mode=ro", uri=True, timeout=10)
    except Exception:
        return None
    try:
        try:
            rows = db.execute(
                "SELECT entity_id, updated_at,"
                " json_extract(payload_json, '$.hermesSessionId'),"
                " json_extract(payload_json, '$.followState.active')"
                " FROM hub_entities WHERE entity_type = 'conversation' AND deleted = 0"
                " ORDER BY updated_at DESC LIMIT 1000"
            ).fetchall()
        except Exception:
            return None
    finally:
        try:
            db.close()
        except Exception:
            pass
    out = []
    for entity_id, updated_at, session_id, was_active in rows:
        session_id = str(session_id or "").strip()
        if not session_id:
            continue
        try:
            updated = int(updated_at or 0)
        except Exception:
            updated = 0
        out.append((str(entity_id), updated, session_id, bool(was_active)))
    return out


def _tool_label(row: dict) -> str:
    name = str(row.get("tool_name") or "").strip()
    if name:
        return name
    try:
        calls = json.loads(row.get("tool_calls") or "") if str(row.get("tool_calls") or "").strip() else []
    except Exception:
        calls = []
    if isinstance(calls, list):
        for call in calls:
            if isinstance(call, dict):
                fn = call.get("function") if isinstance(call.get("function"), dict) else None
                candidate = (
                    (fn or {}).get("name") or call.get("name") or call.get("tool_name") or ""
                )
                if isinstance(candidate, str) and candidate.strip():
                    return candidate.strip()
    if str(row.get("tool_call_id") or "").strip():
        return "tool"
    return ""


def _tool_failed(row: dict) -> bool:
    body = str(row.get("content") or "")
    if '"is_error": true' in body or '"exit_code": 1' in body:
        return True
    return 'error": null' not in body and '"error":' in body and '"error": null' not in body


def build_follow_messages(new_rows: list[dict]) -> list[dict]:
    """Righe sessione -> messaggi Hub compatti (stesso fold dell'app).

    - Solo righe assistant/tool (i turni utente li possiede l'app).
    - Testi assistant per intero (cap), reasoning in thinking (cap).
    - Righe tool: solo chip attività (nome + stato), MAI il body output
      (rumore + possibili segreti; l'app riassume comunque in lettura).
    - tool_calls dentro righe assistant saltate (la riga tool che segue
      copre il completamento; niente doppioni).
    """
    messages: list[dict] = []
    pending_text: list[str] = []
    pending_thinking = ""
    pending_timeline: list[dict] = []
    pending_first_id: int | None = None
    pending_ts = 0

    def flush() -> None:
        if pending_first_id is None:
            return
        text = "\n\n".join(t for t in pending_text if t.strip())
        if not text.strip() and not pending_timeline and not pending_thinking.strip():
            return
        messages.append(
            {
                "id": "srv_%d" % pending_first_id,
                "author": "Hermes",
                "text": text[:MAX_TEXT_CHARS],
                "fromUser": False,
                "thinking": pending_thinking[:MAX_THINKING_CHARS],
                # Copia: il chiamante riusa/pulisce le liste pending dopo.
                "activityTimeline": list(pending_timeline),
                "timestamp": int(pending_ts * 1000),
            }
        )

    for row in new_rows:
        role = str(row.get("role", "")).strip().lower()
        if role == "assistant":
            new_content = str(row.get("content") or "").strip()
            new_thinking = str(row.get("reasoning") or "").strip()
            pending_content = (
                any(t.strip() for t in pending_text)
                or bool(pending_timeline)
                or bool(pending_thinking.strip())
            )
            if pending_first_id is not None and pending_content and (new_content or new_thinking):
                flush()
                pending_text.clear()
                pending_timeline.clear()
                pending_thinking = ""
                pending_first_id = None
            if pending_first_id is None:
                try:
                    pending_first_id = int(row.get("id") or 0)
                except Exception:
                    pending_first_id = 0
                try:
                    pending_ts = float(row.get("timestamp") or 0.0)
                except Exception:
                    pending_ts = 0.0
            if new_content:
                pending_text.append(new_content)
            if new_thinking:
                pending_thinking = (pending_thinking + "\n" + new_thinking).strip()
        elif role == "tool":
            if pending_first_id is None:
                pending_first_id = int(row.get("id") or 0)
                try:
                    pending_ts = float(row.get("timestamp") or 0.0)
                except Exception:
                    pending_ts = 0.0
            pending_timeline.append(
                {
                    "kind": "tool",
                    "text": "",
                    "toolId": str(row.get("tool_call_id") or "") or ("tool-%s" % row.get("id")),
                    "toolName": _tool_label(row) or "tool",
                    "toolStatus": "fallito" if _tool_failed(row) else "completato",
                }
            )
        # Altri ruoli (user/system): l'app possiede i turni utente, skip.
    flush()
    return messages


def merge_hub_messages(current: list, follow: list) -> list:
    """Accoda messaggi srv_ mancanti (per id). Mai drop, mai duplicati."""
    seen = set()
    for message in current:
        if isinstance(message, dict) and str(message.get("id") or "").strip():
            seen.add(str(message["id"]))
    merged = list(current)
    for message in follow:
        if not isinstance(message, dict):
            continue
        mid = str(message.get("id") or "").strip()
        if not mid or mid in seen:
            continue
        seen.add(mid)
        merged.append(message)
    if len(merged) > MAX_HUB_MESSAGES:
        # Tieni la testa (turni utente/app) + coda: droppa i srv_ piu' vecchi.
        head = [m for m in merged if not (isinstance(m, dict) and str(m.get("id") or "").startswith("srv_"))]
        tail = [m for m in merged if isinstance(m, dict) and str(m.get("id") or "").startswith("srv_")]
        merged = head + tail[-(MAX_HUB_MESSAGES - len(head)) :]
    return merged


def _cursor_path(hub_db_path: str) -> str:
    parent = os.path.dirname(hub_db_path) or "."
    return os.path.join(parent, CURSOR_NAME)


def _read_cursors(hub_db_path: str) -> dict:
    try:
        with open(_cursor_path(hub_db_path), encoding="utf-8") as handle:
            data = json.loads(handle.read())
    except Exception:
        return {}
    return data if isinstance(data, dict) else {}


def _write_cursors(hub_db_path: str, cursors: dict) -> None:
    try:
        with open(_cursor_path(hub_db_path), "w", encoding="utf-8") as handle:
            json.dump({str(k): int(v) for k, v in cursors.items()}, handle)
    except Exception:
        pass


def sync_conversation_follow(store, conversation: dict, home: str | None = None,
                             cursors: dict | None = None) -> bool:
    """Sincronizza UNA conversazione Hub. Ritorna True se ha scritto.

    Cursore monotono con overlap (recupera clobber recenti); il merge per id
    resta l'unica fonte di verita contro i duplicati. ``cursors`` mutato.
    """
    entity_id = str(conversation.get("id") or "").strip()
    session_id = str(conversation.get("hermesSessionId") or "").strip()
    if not session_id:
        return False
    db_path = profile_db_path(str(conversation.get("botIdentityKey") or ""), home)
    if not db_path:
        return False
    cursor = 0
    if isinstance(cursors, dict):
        try:
            cursor = max(0, int(cursors.get(entity_id, 0) or 0))
        except Exception:
            cursor = 0
    status = read_session_status(db_path, session_id)
    rows = read_session_rows(db_path, session_id, limit=READ_LIMIT,
                             since_id=max(0, cursor - CURSOR_OVERLAP))
    current = conversation.get("messages")
    current = list(current) if isinstance(current, list) else []
    follow = build_follow_messages(rows)
    merged = merge_hub_messages(current, follow)
    active = (not status.get("ended", True)) and bool(rows)
    prev_follow = conversation.get("followState")
    prev_active = bool(isinstance(prev_follow, dict) and prev_follow.get("active"))
    messages_changed = len(merged) != len(current)
    if not messages_changed and active == prev_active:
        return False
    last_ts = 0
    for message in merged:
        try:
            ts = int(message.get("timestamp") or 0) if isinstance(message, dict) else 0
        except Exception:
            ts = 0
        last_ts = max(last_ts, ts)
    try:
        existing_updated = int(conversation.get("updatedAt") or 0)
    except Exception:
        existing_updated = 0
    conversation["messages"] = merged
    conversation["updatedAt"] = max(last_ts, existing_updated + 1)
    conversation["followState"] = {
        "active": active,
        "lastMsgId": max([r["id"] for r in rows] + [0]),
        "checkedAt": last_ts,
    }
    if isinstance(cursors, dict) and rows:
        try:
            cursors[entity_id] = max(int(cursors.get(entity_id, 0) or 0),
                                    max(r["id"] for r in rows))
        except Exception:
            pass
    return True


def maybe_sync_follow_ups(hub_db_path: str | None = None, home: str | None = None) -> int:
    """Accoda l'attivita detached nelle conversazioni Hub collegate.

    Chiamato sul pull Hub (stesso gancio degli shadow). Ritorna il numero di
    conversazioni aggiornate. Non solleva mai.
    """
    try:
        return _maybe_sync_follow_ups(hub_db_path, home)
    except Exception:
        return 0


def _maybe_sync_follow_ups(hub_db_path: str | None = None, home: str | None = None) -> int:
    from hermes_hub_gateway.infrastructure.sqlite import SQLiteHubStateStore

    from hermes_hub_gateway.adapters.hermes import shadow_sessions

    hub_db = hub_db_path or shadow_sessions.hub_state_path(home)
    if _throttled(hub_db):
        return 0
    _stamp_throttle(hub_db)
    mapped = _scan_mapped_conversations(hub_db)
    if mapped is None:
        return 0
    store = SQLiteHubStateStore(hub_db)
    applied = 0
    cursors = _read_cursors(hub_db)
    try:
        mapped_ids = {entity_id for entity_id, _, _, _ in mapped}
        # Prune cursori orfani (conversazioni cancellate/scollegate).
        for stale in [k for k in cursors if k not in mapped_ids]:
            del cursors[stale]
        # Finestra: top recenti + eventuali active fuori finestra (flip a
        # inactive garantito anche per sessioni vecchie ancora marcate).
        window = list(mapped[:MAX_CONVOS_PER_RUN])
        seen = {entity_id for entity_id, _, _, _ in window}
        for entry in mapped[MAX_CONVOS_PER_RUN:]:
            if len(window) >= MAX_CONVOS_PER_RUN + 15:
                break
            if entry[0] not in seen and entry[3]:
                window.append(entry)
                seen.add(entry[0])
        for entity_id, _, _, _ in window:
            try:
                record = store.get("conversation", entity_id)
            except Exception:
                continue
            if record is None:
                continue
            try:
                payload = dict(record.payload) if isinstance(record.payload, dict) else {}
            except Exception:
                continue
            if not str(payload.get("hermesSessionId") or "").strip():
                continue
            try:
                changed = sync_conversation_follow(store, payload, home, cursors)
            except Exception:
                continue
            if not changed:
                continue
            try:
                store.upsert(
                    {
                        "entity_type": "conversation",
                        "entity_id": entity_id,
                        "payload": payload,
                        "updated_at": int(payload.get("updatedAt") or 0),
                        "deleted": False,
                    }
                )
                applied += 1
            except Exception:
                continue
    finally:
        try:
            # Sempre: il file e' pochi byte, il cursore avanzato evita
            # riletture; il prune evita crescita infinita.
            _write_cursors(hub_db, cursors)
        except Exception:
            pass
        try:
            store.close()
        except Exception:
            pass
    return applied
