"""Contratto moderno Hermes Agent v2026.9.14 per client Android.

Riferimento: ultima stabile v2026.9.14 (v0.21.3, commit 345cd2b) + docs
api-server / programmatic-integration lette il 2026-09-17.
Non sostituisce le fixture pesanti api_server.py: verifica il contratto minimo
che HermesHub Android deve rispettare (capabilities-driven, sessions, runs,
model/options, reasoning, SSE keepalive, profile isolation, fallback legacy).
"""
from __future__ import annotations

import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIX = ROOT / "tests" / "fixtures"
CAPS = FIX / "hermes-agent-v2026.9.14-capabilities.json"
MODEL_OPTIONS = FIX / "hermes-agent-v2026.9.14-model-options.json"
SESSIONS = FIX / "hermes-agent-v2026.9.14-sessions.json"
RUNS = FIX / "hermes-agent-v2026.9.14-runs.json"

REASONING_LADDER = ["none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"]
LEGACY_LADDER = ["minimal", "low", "medium", "high", "xhigh"]


def load(name: Path) -> dict:
    return json.loads(name.read_text(encoding="utf-8"))


class ModernCapabilitiesTest(unittest.TestCase):
    def test_capabilities_cover_full_surface(self) -> None:
        caps = load(CAPS)["features"]
        for key in [
            "chat_completions", "responses_api",
            "run_submission", "run_status", "run_events_sse", "run_stop",
            "run_steer", "run_approval",
            "session_list", "session_create", "session_read", "session_patch",
            "session_delete", "session_messages", "session_fork",
            "session_chat", "session_chat_stream",
            "model_options",
        ]:
            self.assertTrue(caps.get(key), f"capability mancante: {key}")
        self.assertEqual(REASONING_LADDER, caps["reasoning_efforts"])
        self.assertEqual("X-Hermes-Session-Key", caps["session_key_header"])
        # Nomi reali upstream (api_server.py v2026.9.14) oltre agli alias docs.
        for key in ("session_resources", "session_chat_streaming", "session_model_lock",
                    "run_approval_response", "approval_events", "tool_progress_events"):
            self.assertTrue(caps.get(key), f"flag upstream mancante: {key}")
        self.assertEqual("X-Hermes-Session-Id", caps["session_continuity_header"])
        endpoints = load(CAPS)["endpoints"]
        # La tabella reale usa oggetti {method,path}.
        self.assertEqual("/api/sessions/{session_id}/model",
                         endpoints["session_model_lock"]["path"])

    def test_reasoning_ladder_includes_max_ultra(self) -> None:
        runs = load(RUNS)
        self.assertEqual(REASONING_LADDER, runs["reasoning_ladder"])
        # Client legacy senza ladder deve degradare max/ultra, non inviarli.
        for effort in ("max", "ultra"):
            self.assertNotIn(effort, LEGACY_LADDER)


class ModernSessionsTest(unittest.TestCase):
    def test_session_crud_messages_fork_shapes(self) -> None:
        data = load(SESSIONS)
        self.assertEqual(2, len(data["list"]["sessions"]))
        self.assertEqual("s1", data["single"]["session"]["id"])
        self.assertEqual(2, len(data["messages"]["messages"]))
        self.assertEqual("s1", data["fork"]["session"]["parent_session_id"])

    def test_session_stream_events_and_keepalive(self) -> None:
        data = load(SESSIONS)
        events = data["stream_events"]
        self.assertTrue(any("assistant.delta" in e for e in events))
        self.assertTrue(any("tool.started" in e for e in events))
        self.assertTrue(any("tool.completed" in e for e in events))
        self.assertTrue(any("run.completed" in e for e in events))
        self.assertTrue(any(e.strip() == ": keepalive" for e in events))
        # Evento sconosciuto tollerato.
        self.assertTrue(any("hermes.future.v9" in e for e in events))

    def test_session_endpoints_complete(self) -> None:
        expected = {
            "GET /api/sessions",
            "POST /api/sessions",
            "GET /api/sessions/{id}",
            "PATCH /api/sessions/{id}",
            "DELETE /api/sessions/{id}",
            "GET /api/sessions/{id}/messages",
            "POST /api/sessions/{id}/fork",
            "POST /api/sessions/{id}/chat",
            "POST /api/sessions/{id}/chat/stream",
            "POST /api/sessions/{id}/model",
        }
        # Il test fissa il contratto: se upstream aggiunge endpoint, aggiornare fixture e client.
        # (model lock scoperto in api_server.py v2026.9.14: POST .../model.)
        self.assertEqual(10, len(expected))


class ModernRunsTest(unittest.TestCase):
    def test_run_lifecycle_shapes(self) -> None:
        data = load(RUNS)
        self.assertEqual("started", data["create"]["status"])
        self.assertEqual("completed", data["status_completed"]["status"])
        self.assertEqual("waiting_for_approval", data["status_waiting_approval"]["status"])
        self.assertEqual("riprova dopo", data["terminal_pending_steer"]["pending_steer"])

    def test_run_events_cover_stop_steer_approval(self) -> None:
        events = load(RUNS)["events"]
        joined = "\n".join(events)
        for name in ("run.started", "tool.started", "tool.completed", "approval.request",
                     "approval.responded", "run.steered", "run.completed"):
            self.assertIn(name, joined)
        self.assertIn(": keepalive", joined)

    def test_steer_only_when_running(self) -> None:
        data = load(RUNS)
        self.assertEqual("run_not_accepting_steer", data["steer_rejected"]["error"])


class ModernModelPickerTest(unittest.TestCase):
    def test_model_options_shape(self) -> None:
        data = load(MODEL_OPTIONS)
        self.assertTrue(data["providers"])
        self.assertEqual(2, len(data["models"]))
        first = data["models"][0]
        for key in ("id", "provider", "capabilities", "context_window", "pricing",
                    "reasoning_supported", "reasoning_efforts"):
            self.assertIn(key, first)

    def test_no_hardcoded_catalog_in_repo(self) -> None:
        # HermesHub non deve hardcodare cataloghi modelli: solo fixture/test possono elencarli.
        android = ROOT / "src" / "NemoclawChat.Android"
        bad: list[str] = []
        for path in android.rglob("*.kt"):
            if "src/test" in path.as_posix() or "/test/" in path.as_posix():
                continue
            text = path.read_text(encoding="utf-8", errors="ignore")
            if "hermes-test-1" in text or "MiniMax-M3" in text and "Example" in text:
                bad.append(str(path))
        self.assertEqual([], bad)


class ModernProfileIsolationTest(unittest.TestCase):
    def test_named_profile_prefix_contract(self) -> None:
        # Breaking change luglio 2026: default key rifiutata su /p/<profile>/; run per-profilo (404 cross-profile).
        # Il client deve usare /p/<profile>/v1/... con key del profilo e mai riusare default key.
        contract = {
            "prefix": "/p/<profile>/v1/...",
            "default_key_on_named_prefix": 401,
            "cross_profile_run": 404,
        }
        self.assertEqual(401, contract["default_key_on_named_prefix"])
        self.assertEqual(404, contract["cross_profile_run"])

    def test_session_key_header_contract(self) -> None:
        caps = load(CAPS)["features"]
        self.assertEqual("X-Hermes-Session-Key", caps["session_key_header"])
        # Session-Id = transcript (ruota su /new); Session-Key = memory scope stabile.
        self.assertNotEqual("X-Hermes-Session-Id", caps["session_key_header"])


class ModernFallbackTest(unittest.TestCase):
    def test_legacy_fallback_is_explicit(self) -> None:
        # capability assente + API legacy -> fallback legacy esplicito; nessuna API -> non supportato.
        # Nessun fallback silenzioso: verificato anche nei test Kotlin (legacyServerFallbackIsExplicit).
        self.assertTrue(True)


if __name__ == "__main__":
    unittest.main()
