"""Tests for agent-session shadow import (Hub follows desktop sessions)."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from hermes_hub_gateway.adapters.hermes import shadow_sessions  # noqa: E402
from hermes_hub_gateway.adapters.hermes.legacy_patcher import (  # noqa: E402
    _patch_shadow_sessions_v1,
)


class ShadowExtractTests(unittest.TestCase):
    def test_assistant_texts_skip_tool_calls(self):
        response = {
            "output": [
                {"type": "function_call", "name": "x", "arguments": "{}"},
                {
                    "type": "message",
                    "role": "assistant",
                    "content": [{"type": "output_text", "text": "Ciao!"}],
                },
            ]
        }
        self.assertEqual(["Ciao!"], shadow_sessions.extract_assistant_texts(response))
        self.assertEqual([], shadow_sessions.extract_assistant_texts({"output": []}))
        self.assertEqual([], shadow_sessions.extract_assistant_texts({}))

    def test_first_user_text_shapes(self):
        self.assertEqual(
            "hello",
            shadow_sessions.first_user_text([{"role": "user", "content": "hello"}]),
        )
        self.assertEqual(
            "hi",
            shadow_sessions.first_user_text([{"author": "Tu", "text": "hi"}]),
        )
        self.assertEqual("", shadow_sessions.first_user_text([]))
        self.assertEqual("", shadow_sessions.first_user_text(None))

    def test_hub_managed_sessions_skipped(self):
        rows = [
            {
                "session_id": "hub-sess",
                "response_id": "resp_hub",
                "created_at": 1000.0,
                "texts": ["Ciao Hub"],
                "first_user": "ciao",
            },
            {
                "session_id": "desk-sess",
                "response_id": "resp_desk",
                "created_at": 1001.0,
                "texts": ["Ciao Desktop"],
                "first_user": "ciao",
            },
        ]
        shadows = shadow_sessions.build_shadow_conversations(rows, {"resp_hub"})
        self.assertEqual(1, len(shadows))
        shadow = next(iter(shadows.values()))
        self.assertTrue(shadow["id"].startswith("shadow_"))
        self.assertEqual("Ciao Desktop", shadow["messages"][0]["text"])
        self.assertEqual("resp_desk", shadow["previousResponseId"])
        self.assertEqual("ciao", shadow["title"])

    def test_textless_sessions_skipped(self):
        rows = [
            {
                "session_id": "empty",
                "response_id": "resp_x",
                "created_at": 1000.0,
                "texts": [],
                "first_user": "",
            }
        ]
        self.assertEqual({}, shadow_sessions.build_shadow_conversations(rows, set()))

    def test_shadow_id_stable(self):
        self.assertEqual(
            shadow_sessions.shadow_conversation_id("78a70429-c2a4-4679-bf17-0b6624650311"),
            shadow_sessions.shadow_conversation_id("78a70429-c2a4-4679-bf17-0b6624650311"),
        )
        self.assertTrue(
            shadow_sessions.shadow_conversation_id("ABC-123").startswith("shadow_")
        )


class ShadowImportTests(unittest.TestCase):
    def _write_db(self, path, rows):
        import sqlite3 as _sqlite

        db = _sqlite.connect(str(path))
        db.execute("CREATE TABLE responses (response_id TEXT, data TEXT, accessed_at REAL)")
        db.execute("CREATE TABLE conversations (name TEXT, response_id TEXT)")
        for response_id, payload, mapped_name in rows:
            db.execute(
                "INSERT INTO responses VALUES (?, ?, ?)",
                (response_id, json.dumps(payload), 1000.0),
            )
            if mapped_name:
                db.execute(
                    "INSERT INTO conversations VALUES (?, ?)", (mapped_name, response_id)
                )
        db.commit()
        db.close()

    def _response_payload(self, texts, session, created=1000.0):
        return {
            "response": {
                "id": "resp_test",
                "object": "response",
                "status": "completed",
                "created_at": created,
                "model": "hermes-agent",
                "output": [
                    {
                        "type": "message",
                        "role": "assistant",
                        "content": [{"type": "output_text", "text": t}],
                    }
                    for t in texts
                ],
            },
            "conversation_history": [{"role": "user", "content": "ciao"}],
            "session_id": session,
        }

    def test_import_creates_and_is_idempotent(self):
        import tempfile as _tempfile

        from hermes_hub_gateway.infrastructure.sqlite import (  # noqa: E402
            SQLiteHubStateStore,
        )

        with _tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "response_store.db"
            hub_db = Path(tmp) / "hub_state.sqlite3"
            self._write_db(
                db_path,
                [
                    ("resp_1", self._response_payload(["Prima"], "sess-a"), None),
                    ("resp_2", self._response_payload(["Seconda"], "sess-b"), None),
                ],
            )
            count = shadow_sessions.maybe_import_shadows(str(hub_db), str(db_path))
            self.assertEqual(2, count)
            store = SQLiteHubStateStore(str(hub_db))
            try:
                records = store.list_records("conversation")
            finally:
                store.close()
            ids = sorted(r.entity_id for r in records)
            self.assertEqual(2, len(ids))
            self.assertTrue(all(i.startswith("shadow_") for i in ids))
            # Second run: store unchanged, nothing applied.
            self.assertEqual(
                0, shadow_sessions.maybe_import_shadows(str(hub_db), str(db_path))
            )

    def test_import_never_raises(self):
        import tempfile as _tempfile

        with _tempfile.TemporaryDirectory() as tmp:
            hub_db = Path(tmp) / "hub_state.sqlite3"
            self.assertEqual(
                0,
                shadow_sessions.maybe_import_shadows(
                    str(hub_db), str(Path(tmp) / "missing.db")
                ),
            )


class ShadowPatcherTests(unittest.TestCase):
    SYNTHETIC = (
        "import os\n"
        "from typing import Any, Dict, Optional\n"
        "def _hermes_hub_storage_path(env_name, default_name):\n"
        "    return default_name\n"
        "def _hermes_hub_conversations_payload() -> Dict[str, Any]:\n"
        '    path = _hermes_hub_storage_path("HERMES_HUB_CONVERSATIONS_PATH", "hub_conversations.json")\n'
        "    return {'items': []}\n"
    )

    def test_hook_injected_and_idempotent(self):
        patched, changes = _patch_shadow_sessions_v1(self.SYNTHETIC)
        self.assertIn("def _hermes_hub_maybe_import_shadows", patched)
        self.assertIn("\n    _hermes_hub_maybe_import_shadows()\n", patched)
        self.assertTrue(changes)
        compile(patched, "<patched>", "exec")
        patched2, changes2 = _patch_shadow_sessions_v1(patched)
        self.assertEqual(patched, patched2)
        self.assertEqual([], changes2)

    def test_noop_without_payload_helper(self):
        text = "def something_else():\n    pass\n"
        patched, changes = _patch_shadow_sessions_v1(text)
        self.assertEqual(text, patched)
        self.assertEqual([], changes)


if __name__ == "__main__":
    unittest.main()
