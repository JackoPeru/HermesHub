"""Follow-up sessioni agent -> conversazioni Hub (session_follow).

Spec eseguibile del sync gateway: righe nuove dal session DB del profilo
giusto accodate ai messaggi Hub, mai drop, mai duplicati, mai raise.
"""

import json
import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "scripts"))

from hermes_hub_gateway.adapters.hermes import session_follow as follow


def make_session_db(path, session_id, ended=False, rows=()):
    path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(str(path))
    db.execute(
        "CREATE TABLE sessions (id TEXT PRIMARY KEY, source TEXT, ended_at REAL, end_reason TEXT,"
        " started_at REAL, message_count INTEGER DEFAULT 0)"
    )
    db.execute(
        "CREATE TABLE messages (id INTEGER PRIMARY KEY, session_id TEXT, role TEXT, content TEXT,"
        " tool_call_id TEXT, tool_calls TEXT, tool_name TEXT, timestamp REAL,"
        " finish_reason TEXT, reasoning_content TEXT, display_kind TEXT)"
    )
    db.execute(
        "INSERT INTO sessions (id, source, ended_at, end_reason, started_at) VALUES (?,?,?,?,?)",
        (session_id, "api_server", 999.0 if ended else None, "done" if ended else None, 1.0),
    )
    for row in rows:
        db.execute(
            "INSERT INTO messages (id, session_id, role, content, tool_call_id, tool_calls,"
            " tool_name, timestamp, reasoning_content) VALUES (?,?,?,?,?,?,?,?,?)",
            (
                row["id"], session_id, row.get("role", "assistant"), row.get("content", ""),
                row.get("tool_call_id", ""), row.get("tool_calls", ""), row.get("tool_name", ""),
                row.get("timestamp", 100.0), row.get("reasoning", ""),
            ),
        )
    db.commit()
    db.close()


def tool_row(i, name="terminal", failed=False, ts=100.0):
    body = {"output": "ok", "exit_code": 1 if failed else 0, "error": "boom" if failed else None}
    return {
        "id": i, "role": "tool", "content": json.dumps(body),
        "tool_call_id": "call_%d" % i, "tool_name": name, "timestamp": ts,
    }


class SessionFollowTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.home = self.tmp / "hermes"
        os.environ["HERMES_HOME"] = str(self.home)

    def hub_path(self):
        return str(self.tmp / "hub.sqlite3")

    def test_profile_resolution(self):
        (self.home / "profiles" / "director").mkdir(parents=True)
        (self.home / "profiles" / "director" / "state.db").write_bytes(b"")
        self.assertTrue(
            follow.profile_db_path("primary::director", str(self.home)).endswith(
                os.path.join("profiles", "director", "state.db")
            )
        )
        self.assertTrue(
            follow.profile_db_path("", str(self.home)).endswith("state.db")
        )
        self.assertIsNone(follow.profile_db_path("primary::../escape", str(self.home)))
        self.assertIsNone(follow.profile_db_path("primary::nope", str(self.home)))

    def test_build_messages_text_reasoning_tools(self):
        rows = [
            {"id": 1, "role": "user", "content": "fai X", "timestamp": 100.0},
            {"id": 2, "role": "assistant", "content": "Controllo i file.",
             "reasoning": "prima guardo la cartella", "timestamp": 101.0},
            {"id": 3, "role": "assistant", "content": "", "timestamp": 102.0,
             "tool_calls": json.dumps([{"id": "c1", "function": {"name": "terminal"}}])},
            tool_row(4, name="terminal", ts=103.0),
            tool_row(5, name="vision_analyze", failed=True, ts=104.0),
            {"id": 6, "role": "assistant", "content": "Fatto.", "timestamp": 105.0},
        ]
        messages = follow.build_follow_messages(rows)
        # user saltata, tool_calls dentro assistant saltate (niente doppioni),
        # righe assistant vuote accumulate (tool uniti al testo del turno).
        self.assertEqual(2, len(messages))
        self.assertEqual("srv_2", messages[0]["id"])
        self.assertEqual("Controllo i file.", messages[0]["text"])
        self.assertEqual("prima guardo la cartella", messages[0]["thinking"])
        self.assertEqual(2, len(messages[0]["activityTimeline"]))
        self.assertEqual("terminal", messages[0]["activityTimeline"][0]["toolName"])
        self.assertEqual("completato", messages[0]["activityTimeline"][0]["toolStatus"])
        self.assertEqual("fallito", messages[0]["activityTimeline"][1]["toolStatus"])
        # Body output MAI incluso (rumore + segreti).
        blob = json.dumps(messages)
        self.assertNotIn("exit_code", blob)
        self.assertEqual("srv_6", messages[1]["id"])
        self.assertEqual("Fatto.", messages[1]["text"])

    def test_tool_row_without_assistant_gets_holder(self):
        messages = follow.build_follow_messages([tool_row(9, name="terminal")])
        self.assertEqual(1, len(messages))
        self.assertEqual("srv_9", messages[0]["id"])
        self.assertEqual("", messages[0]["text"])
        self.assertEqual(1, len(messages[0]["activityTimeline"]))

    def test_merge_never_drops_nor_duplicates(self):
        current = [
            {"id": "local-1", "text": "a"},
            {"id": "srv_2", "text": "b"},
        ]
        follow_msgs = [
            {"id": "srv_2", "text": "b"},
            {"id": "srv_3", "text": "c"},
        ]
        merged = follow.merge_hub_messages(current, follow_msgs)
        self.assertEqual(["local-1", "srv_2", "srv_3"], [m["id"] for m in merged])

    def test_end_to_end_idempotent(self):
        from hermes_hub_gateway.infrastructure.sqlite import SQLiteHubStateStore

        session_id = "api_1"
        make_session_db(
            self.home / "profiles" / "director" / "state.db", session_id,
            rows=[
                {"id": 1, "role": "user", "content": "vai", "timestamp": 100.0},
                {"id": 2, "role": "assistant", "content": "Inizio.", "timestamp": 101.0},
                tool_row(3, ts=102.0),
            ],
        )
        hub = self.hub_path()
        store = SQLiteHubStateStore(hub)
        try:
            store.upsert({
                "entity_type": "conversation",
                "entity_id": "conv_1",
                "payload": {
                    "id": "conv_1", "hermesSessionId": session_id,
                    "botIdentityKey": "primary::director", "updatedAt": 50,
                    "messages": [{"id": "local-1", "author": "Tu", "text": "vai", "fromUser": True}],
                },
                "updated_at": 50,
                "deleted": False,
            })
        finally:
            store.close()
        first = follow.maybe_sync_follow_ups(hub, str(self.home))
        self.assertEqual(1, first)
        second = follow.maybe_sync_follow_ups(hub, str(self.home))
        self.assertEqual(0, second)
        store = SQLiteHubStateStore(hub)
        try:
            record = store.get("conversation", "conv_1")
        finally:
            store.close()
        payload = dict(record.payload)
        ids = [m["id"] for m in payload["messages"]]
        self.assertIn("local-1", ids)
        # Riga tool accumulata nel messaggio assistant (niente doppioni).
        self.assertIn("srv_2", ids)
        self.assertNotIn("srv_3", ids)
        self.assertEqual(1, len(payload["messages"][1]["activityTimeline"]))
        self.assertGreater(payload["updatedAt"], 50)
        self.assertTrue(payload["followState"]["active"])

    def test_closed_session_marks_inactive(self):
        from hermes_hub_gateway.infrastructure.sqlite import SQLiteHubStateStore

        make_session_db(
            self.home / "profiles" / "director" / "state.db", "api_9", ended=True,
            rows=[{"id": 1, "role": "assistant", "content": "Fine.", "timestamp": 100.0}],
        )
        hub = self.hub_path()
        store = SQLiteHubStateStore(hub)
        try:
            store.upsert({
                "entity_type": "conversation",
                "entity_id": "conv_9",
                "payload": {"id": "conv_9", "hermesSessionId": "api_9",
                            "botIdentityKey": "primary::director", "updatedAt": 10, "messages": []},
                "updated_at": 10,
                "deleted": False,
            })
        finally:
            store.close()
        self.assertEqual(1, follow.maybe_sync_follow_ups(hub, str(self.home)))
        store = SQLiteHubStateStore(hub)
        try:
            payload = dict(store.get("conversation", "conv_9").payload)
        finally:
            store.close()
        self.assertFalse(payload["followState"]["active"])
        self.assertEqual(1, len(payload["messages"]))

    def test_never_raises(self):
        # DB assenti, conversazioni senza sessione, profili ignoti: sempre 0.
        hub = self.hub_path()
        self.assertEqual(0, follow.maybe_sync_follow_ups(hub, str(self.home)))
        self.assertEqual([], follow.read_session_rows("/nonexistent.db", "x"))
        self.assertEqual({}, follow.read_session_status("/nonexistent.db", "x"))
        self.assertIsNone(follow.profile_db_path("primary::nope", str(self.home)))
        self.assertFalse(follow.sync_conversation_follow(None, {"hermesSessionId": ""}))


if __name__ == "__main__":
    unittest.main()
