"""Mirror foreign agent sessions into the Hermes Hub conversations store.

Hermes Hub clients (Android/Windows) sync through the Hub state store.
Sessions from other clients (e.g. the official desktop app) never enter that
store, so Hub cannot see them. This module rebuilds read-only "shadow"
conversations from the agent ``response_store.db``:

- one shadow conversation per agent ``session_id`` not managed by Hub,
- assistant message texts only (no user turns),
- ``previousResponseId`` set to the latest response so Hub can continue the
  same server-side chain,
- stable ids (``shadow_<session>``) so re-imports update instead of duplicating.

Writes go through ``SQLiteHubStateStore.apply_import`` — the same last-write-
wins path as the Hub import endpoint — so apps are notified via the standard
change feed. Hub-managed sessions (any response already owned by a named
conversation) are skipped.
"""

from __future__ import annotations

import json
import os
import re
import sqlite3

SHADOW_PREFIX = "shadow_"
MARKER_NAME = ".shadow_import_mtime"
MAX_SESSIONS = 50
MAX_MESSAGES_PER_SHADOW = 100
MAX_TEXT_CHARS = 20000


def hermes_home() -> str:
    return os.environ.get("HERMES_HOME") or os.path.expanduser("~/.hermes")


def response_store_path(home: str | None = None) -> str:
    return os.path.join(home or hermes_home(), "response_store.db")


def hub_state_path(home: str | None = None) -> str:
    """Path of the Hub SQLite store (same file the gateway serves)."""
    override = (os.environ.get("HERMES_HUB_STATE_PATH") or "").strip()
    if override and os.path.isfile(override):
        return override
    return os.path.join(home or hermes_home(), "hub_state.sqlite3")


def sanitize_session_id(session_id: str) -> str:
    cleaned = re.sub(r"[^a-z0-9]", "", session_id.lower())
    return cleaned[:32] or "unknown"


def shadow_conversation_id(session_id: str) -> str:
    return SHADOW_PREFIX + sanitize_session_id(session_id)


def extract_assistant_texts(response: dict) -> list[str]:
    """Assistant message texts from a Responses API envelope."""
    texts: list[str] = []
    output = response.get("output")
    if not isinstance(output, list):
        return texts
    for item in output:
        if not isinstance(item, dict):
            continue
        if item.get("type") != "message":
            continue
        if str(item.get("role", "")) not in ("assistant", ""):
            continue
        for chunk in item.get("content") or []:
            if not isinstance(chunk, dict):
                continue
            if chunk.get("type") not in ("output_text", "text"):
                continue
            text = str(chunk.get("text") or "")
            if text.strip():
                texts.append(text)
    return texts


def first_user_text(history: object) -> str:
    """First user turn from a conversation history (OpenAI or Hub shape)."""
    if not isinstance(history, list):
        return ""
    for turn in history:
        if not isinstance(turn, dict):
            continue
        role = str(turn.get("role") or ("user" if turn.get("fromUser") else "")).lower()
        author = str(turn.get("author") or "").lower()
        if role not in ("user", "tu") and author not in ("tu", "user"):
            continue
        for key in ("content", "text", "message"):
            value = turn.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
            if isinstance(value, list):
                parts = [
                    str(p.get("text", ""))
                    for p in value
                    if isinstance(p, dict) and str(p.get("text", "")).strip()
                ]
                if parts:
                    return " ".join(parts).strip()
    return ""


def collect_agent_rows(db_path: str) -> list[dict]:
    """All stored responses with their session."""
    rows: list[dict] = []
    try:
        db = sqlite3.connect("file:" + db_path + "?mode=ro", uri=True, timeout=10)
    except Exception:
        return rows
    try:
        try:
            data_rows = db.execute(
                "SELECT response_id, data FROM responses"
            ).fetchall()
        except Exception:
            return rows
        for response_id, raw in data_rows:
            try:
                data = json.loads(raw) if isinstance(raw, str) else {}
            except Exception:
                continue
            if not isinstance(data, dict):
                continue
            session_id = str(data.get("session_id") or "").strip()
            if not session_id:
                continue
            response = data.get("response")
            if not isinstance(response, dict):
                continue
            texts = extract_assistant_texts(response)
            created = response.get("created_at") or data.get("created_at") or 0
            try:
                created_at = float(created)
            except Exception:
                created_at = 0.0
            rows.append(
                {
                    "session_id": session_id,
                    "response_id": str(response_id),
                    "created_at": created_at,
                    "texts": texts,
                    "first_user": first_user_text(data.get("conversation_history")),
                }
            )
    finally:
        try:
            db.close()
        except Exception:
            pass
    return rows


def mapped_response_ids(db_path: str) -> set[str]:
    """Response ids already owned by a named conversation (Hub-managed)."""
    try:
        db = sqlite3.connect("file:" + db_path + "?mode=ro", uri=True, timeout=10)
    except Exception:
        return set()
    try:
        try:
            return set(
                str(r[0]) for r in db.execute("SELECT response_id FROM conversations")
            )
        except Exception:
            return set()
    finally:
        try:
            db.close()
        except Exception:
            pass


def build_shadow_conversations(
    rows: list[dict], hub_managed_ids: set[str]
) -> dict[str, dict]:
    """Group rows by session, skipping Hub-managed sessions entirely."""
    by_session: dict[str, list[dict]] = {}
    for row in rows:
        by_session.setdefault(row["session_id"], []).append(row)
    shadows: dict[str, dict] = {}
    ordered = sorted(
        by_session.items(),
        key=lambda kv: max(r["created_at"] for r in kv[1]),
        reverse=True,
    )
    for session_id, session_rows in ordered[:MAX_SESSIONS]:
        if any(r["response_id"] in hub_managed_ids for r in session_rows):
            continue
        ordered_rows = sorted(session_rows, key=lambda r: r["created_at"])
        messages: list[dict] = []
        for row in ordered_rows:
            for text in row["texts"][:MAX_MESSAGES_PER_SHADOW]:
                clipped = text if len(text) <= MAX_TEXT_CHARS else text[:MAX_TEXT_CHARS] + "…"
                messages.append(
                    {
                        "id": row["response_id"],
                        "author": "Hermes",
                        "text": clipped,
                        "fromUser": False,
                        "timestamp": int(row["created_at"] * 1000),
                    }
                )
                if len(messages) >= MAX_MESSAGES_PER_SHADOW:
                    break
            if len(messages) >= MAX_MESSAGES_PER_SHADOW:
                break
        if not messages:
            continue
        latest = max(ordered_rows, key=lambda r: r["created_at"])
        first_user = next(
            (r["first_user"] for r in ordered_rows if r["first_user"]), ""
        )
        title_source = first_user or messages[0]["text"]
        shadows[shadow_conversation_id(session_id)] = {
            "id": shadow_conversation_id(session_id),
            "title": title_source.strip().replace("\n", " ")[:90] or "Sessione agent",
            "kind": "Chat",
            "description": "Risposte agente importate automaticamente (solo testo assistente).",
            "prompt": "",
            "messages": messages,
            "updatedAt": int(latest["created_at"] * 1000),
            "previousResponseId": latest["response_id"],
        }
    return shadows


def _read_marker(marker_path: str) -> float:
    try:
        return float(open(marker_path, encoding="utf-8").read().strip() or 0)
    except Exception:
        return 0.0


def _write_marker(marker_path: str, value: float) -> None:
    try:
        with open(marker_path, "w", encoding="utf-8") as handle:
            handle.write(str(value))
    except Exception:
        pass


def maybe_import_shadows(
    hub_db_path: str | None = None, db_path: str | None = None
) -> int:
    """Import new/updated shadow conversations. Returns applied change count.

    Skips work when the response store is unchanged since the last import.
    Never raises: failures return 0 so Hub pulls keep working.
    """
    try:
        return _maybe_import_shadows(hub_db_path, db_path)
    except Exception:
        return 0


def _maybe_import_shadows(
    hub_db_path: str | None = None, db_path: str | None = None
) -> int:
    from hermes_hub_gateway.infrastructure.sqlite import SQLiteHubStateStore

    responses_db = db_path or response_store_path()
    try:
        db_mtime = os.path.getmtime(responses_db)
    except OSError:
        return 0
    hub_db = hub_db_path or hub_state_path()
    marker = os.path.join(os.path.dirname(hub_db) or ".", MARKER_NAME)
    if _read_marker(marker) >= db_mtime:
        return 0
    rows = collect_agent_rows(responses_db)
    if rows:
        shadows = build_shadow_conversations(rows, mapped_response_ids(responses_db))
    else:
        shadows = {}
    store = SQLiteHubStateStore(hub_db)
    try:
        changes = store.apply_import(
            [
                {
                    "entity_type": "conversation",
                    "entity_id": cid,
                    "payload": convo,
                    "updated_at": int(convo.get("updatedAt") or 0),
                    "deleted": False,
                }
                for cid, convo in shadows.items()
            ]
        )
    finally:
        try:
            store.close()
        except Exception:
            pass
    _write_marker(marker, db_mtime)
    return len(changes)
