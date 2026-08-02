import asyncio
import importlib.util
import json
import os
import pathlib
import sys
import time
import types
import typing
import unittest
import uuid
from unittest import mock

from jsonschema import Draft202012Validator


ROOT = pathlib.Path(__file__).resolve().parents[1]
PATCHER_PATH = ROOT / "scripts" / "patch-hermes-gateway-native.py"
FIXTURE_PATH = ROOT / "tests" / "fixtures" / "hermes-agent-v2026.7.7.2-api_server.py"


def load_patcher():
    spec = importlib.util.spec_from_file_location("jarvis_gateway_patcher", PATCHER_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def runtime_namespace(patched: str):
    begin = patched.index("# HERMES_HUB_JARVIS_RUNTIME_BEGIN")
    end_marker = "# HERMES_HUB_JARVIS_RUNTIME_END"
    end = patched.index(end_marker, begin) + len(end_marker)
    namespace = {
        "Any": typing.Any,
        "Dict": typing.Dict,
        "List": typing.List,
        "Optional": typing.Optional,
        "asyncio": asyncio,
        "json": json,
        "os": os,
        "sys": sys,
        "time": time,
        "uuid": uuid,
        "redact_sensitive_text": lambda value: value,
        "web": types.SimpleNamespace(json_response=lambda payload, status=200: (status, payload)),
    }
    exec(compile(patched[begin:end], "<jarvis-runtime>", "exec"), namespace)
    return namespace


class JarvisGatewayPatchTests(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        cls.patcher = load_patcher()
        upstream = FIXTURE_PATH.read_text(encoding="utf-8")
        cls.patched, cls.changes = cls.patcher._patch_text(upstream)
        cls.runtime = runtime_namespace(cls.patched)

    def test_patch_is_idempotent_and_routes_stay_in_existing_gateway(self):
        second, changes = self.patcher._patch_text(self.patched)
        self.assertEqual([], changes)
        self.assertEqual(self.patched, second)
        self.assertEqual(1, second.count("# HERMES_HUB_JARVIS_RUNTIME_BEGIN"))
        self.assertEqual(9, second.count("self._handle_jarvis_"))
        for route in (
            "/v1/jarvis/sessions",
            "/frames",
            "/turns",
            "/events",
            "/feedback",
        ):
            self.assertIn(route, second)

    def test_every_jarvis_handler_uses_existing_bearer_auth(self):
        handler_block = self.patched.split("# HERMES_HUB_JARVIS_HANDLERS_BEGIN", 1)[1].split(
            "# HERMES_HUB_JARVIS_HANDLERS_END", 1
        )[0]
        self.assertEqual(8, handler_block.count("auth_error = self._check_auth(request)"))

    def test_capabilities_are_fail_closed_and_do_not_expose_endpoints_or_keys(self):
        capabilities = self.runtime["_hermes_hub_jarvis_capabilities"]
        with mock.patch.dict(os.environ, {}, clear=True):
            disabled = capabilities()
        self.assertFalse(disabled["enabled"])
        self.assertFalse(disabled["fast_model_configured"])
        with mock.patch.dict(
            os.environ,
            {
                "HERMES_JARVIS_ENABLED": "true",
                "HERMES_JARVIS_REASONING_BASE_URL": "http://private-reasoning/v1",
                "HERMES_JARVIS_REASONING_MODEL": "reasoning",
                "HERMES_JARVIS_REASONING_API_KEY": "secret",
            },
            clear=True,
        ):
            enabled = capabilities()
        encoded = json.dumps(enabled)
        self.assertTrue(enabled["enabled"])
        self.assertTrue(enabled["proactive_events"])
        self.assertTrue(enabled["single_model"])
        self.assertTrue(enabled["observer_model_configured"])
        self.assertEqual("adaptive_single_model", enabled["direct_questions_route"])
        self.assertNotIn("secret", encoded)

    def test_dual_model_mode_remains_explicit_opt_in(self):
        capabilities = self.runtime["_hermes_hub_jarvis_capabilities"]
        model_config = self.runtime["_hermes_hub_jarvis_model_config"]
        environment = {
            "HERMES_JARVIS_ENABLED": "true",
            "HERMES_JARVIS_SINGLE_MODEL": "false",
            "HERMES_JARVIS_FAST_BASE_URL": "http://private-fast/v1",
            "HERMES_JARVIS_FAST_MODEL": "fast",
            "HERMES_JARVIS_REASONING_BASE_URL": "http://private-reasoning/v1",
            "HERMES_JARVIS_REASONING_MODEL": "reasoning",
        }
        with mock.patch.dict(os.environ, environment, clear=True):
            enabled = capabilities()
            fast = model_config("fast")
        self.assertFalse(enabled["single_model"])
        self.assertEqual("fast", fast["effective_kind"])
        self.assertTrue(enabled["proactive_events"])
        self.assertEqual("adaptive", enabled["direct_questions_route"])

    def test_fast_output_schema_rejects_malformed_or_out_of_range_values(self):
        validate = self.runtime["_hermes_hub_jarvis_validate_fast_result"]
        valid = validate(
            {
                "action": "ignore",
                "observation": "Stable",
                "reason": "No change",
                "confidence": 0.9,
                "importance": 0.1,
                "urgency": 0,
                "reply": None,
                "recommended_frame_ids": [],
            }
        )
        self.assertEqual("ignore", valid["action"])
        with self.assertRaises(ValueError):
            validate(dict(valid, confidence=1.1))
        with self.assertRaises(ValueError):
            validate(dict(valid, action="speak_forever"))
        fenced = validate(
            "```json\n" + json.dumps({**valid, "action": "ignore", "reply": None}) + "\n```"
        )
        self.assertEqual("ignore", fenced["action"])

    def test_structured_observer_disables_thinking_budget(self):
        self.assertIn('"chat_template_kwargs": {"enable_thinking": False}', self.patched)

    def test_single_model_uses_one_compact_call_before_rare_agent_escalation(self):
        self.assertIn('route = "compact" if single_model or not _hermes_hub_jarvis_requires_reasoning(question)', self.patched)
        self.assertIn('if fast.get("reply") and not fast.get("needs_agent"):', self.patched)
        self.assertIn('"single_model_sem": asyncio.Semaphore(1)', self.patched)
        self.assertIn('if question is None and parsed["action"] == "respond_simple":', self.patched)
        self.assertIn('parsed["reply"] = None', self.patched)
        self.assertNotIn("previous.cancel()", self.patched)

    async def test_simple_direct_question_uses_exactly_one_compact_call(self):
        create = self.runtime["_hermes_hub_jarvis_new_session"]
        direct = self.runtime["_hermes_hub_jarvis_direct_turn"]
        session = create({"mode": "assistive"})
        session["frames"]["f1"] = {
            "id": "f1",
            "jpeg": b"jpeg",
            "captured_at": time.time(),
            "received_at": time.time(),
        }
        calls = {"compact": 0, "agent": 0}

        async def fake_compact(_adapter, _session, _frame, _question):
            calls["compact"] += 1
            return (
                {
                    "action": "respond_simple",
                    "observation": "La vite e' allineata.",
                    "reason": "Risposta visiva diretta.",
                    "confidence": 0.95,
                    "importance": 0.5,
                    "urgency": 0.1,
                    "utility": 0.9,
                    "reply": "Si, e' allineata.",
                    "needs_agent": False,
                    "event_key": "vite-allineata",
                    "memory_update": None,
                    "recommended_frame_ids": ["f1"],
                },
                {"total_ms": 10.0},
            )

        async def fail_agent(*_args, **_kwargs):
            calls["agent"] += 1
            raise AssertionError("Hermes Agent non doveva essere chiamato")

        original_compact = self.runtime["_hermes_hub_jarvis_fast_call"]
        original_agent = self.runtime["_hermes_hub_jarvis_reasoning_call"]
        self.runtime["_hermes_hub_jarvis_fast_call"] = fake_compact
        self.runtime["_hermes_hub_jarvis_reasoning_call"] = fail_agent
        try:
            with mock.patch.dict(
                os.environ,
                {"HERMES_JARVIS_MOCK_MODE": "1", "HERMES_JARVIS_SINGLE_MODEL": "1"},
                clear=False,
            ):
                result = await direct(types.SimpleNamespace(), session, "Questa vite e' allineata?", ["f1"])
        finally:
            self.runtime["_hermes_hub_jarvis_fast_call"] = original_compact
            self.runtime["_hermes_hub_jarvis_reasoning_call"] = original_agent
        self.assertEqual("compact", result["route"])
        self.assertEqual("Si, e' allineata.", result["text"])
        self.assertEqual({"compact": 1, "agent": 0}, calls)

    def test_initiative_policy_enforces_mode_cooldown_and_deduplication(self):
        create = self.runtime["_hermes_hub_jarvis_new_session"]
        decide = self.runtime["_hermes_hub_jarvis_initiative_decision"]
        proposal = {
            "confidence": 0.99,
            "importance": 1.0,
            "urgency": 0.9,
            "utility": 1.0,
            "text": "Wrong connector",
            "event_key": "wrong-connector",
        }
        with mock.patch.dict(os.environ, {}, clear=True):
            questions = create({"mode": "questions_only"})
            self.assertEqual((False, "questions_only", 0.0), decide(questions, proposal, 1_000.0))
            assistive = create({"mode": "assistive"})
            first = decide(assistive, proposal, 1_000.0)
            second = decide(assistive, proposal, 1_100.0)
        self.assertTrue(first[0])
        self.assertFalse(second[0])
        self.assertEqual("duplicate", second[1])

    def test_reactor_memory_is_bounded_structured_and_situation_is_last(self):
        create = self.runtime["_hermes_hub_jarvis_new_session"]
        record = self.runtime["_hermes_hub_jarvis_record_perception"]
        set_trigger = self.runtime["_hermes_hub_jarvis_set_trigger"]
        context = self.runtime["_hermes_hub_jarvis_reactor_context"]
        prompt = self.runtime["_hermes_hub_jarvis_reactor_prompt"]
        with mock.patch.dict(os.environ, {"HERMES_JARVIS_MAX_PERCEPTIONS": "16"}, clear=True):
            session = create({"mode": "assistive", "goal": "Monta il computer"})
            for index in range(24):
                record(session, "video", "observation", f"Evento {index}")
        trigger = set_trigger(
            session,
            {
                "type": "escalate",
                "source": "video",
                "reason": "Connettore ambiguo",
                "importance": 0.9,
                "urgency": 0.7,
            },
        )
        payload = context(session, trigger)
        self.assertEqual(16, len(session["perceptions"]))
        self.assertEqual("situation", list(payload)[-1])
        rendered = prompt(session, "test", trigger=trigger)
        self.assertGreater(rendered.rfind("SITUATION"), rendered.rfind("CONTEXT"))
        self.assertNotIn('"data"', json.dumps(payload))

    def test_incremental_summary_schema_and_semantic_intervention_dedupe(self):
        create = self.runtime["_hermes_hub_jarvis_new_session"]
        apply_memory = self.runtime["_hermes_hub_jarvis_apply_memory_update"]
        decide = self.runtime["_hermes_hub_jarvis_initiative_decision"]
        remember = self.runtime["_hermes_hub_jarvis_remember_intervention"]
        session = create({"mode": "assistive"})
        summary = apply_memory(
            session,
            {
                "summary": "L'utente sta montando la scheda madre.",
                "topic": "Montaggio PC",
                "open_loop": True,
                "notable_facts": ["Il connettore non è ancora verificato."],
            }
        )
        self.assertTrue(summary["open_loop"])
        self.assertEqual(1, len(summary["notable_facts"]))
        self.assertEqual(summary["summary"], session["short_term_summary"])
        self.assertEqual("memory.summary", session["events"][-1]["type"])
        proposal = {
            "confidence": 0.99,
            "importance": 1.0,
            "urgency": 0.9,
            "utility": 1.0,
            "text": "Controlla il connettore della scheda madre",
            "event_key": "connector-a",
        }
        environment = {
            "HERMES_JARVIS_ASSISTIVE_COOLDOWN_SECONDS": "0",
            "HERMES_JARVIS_SEMANTIC_DEDUPE_THRESHOLD": "0.8",
        }
        with mock.patch.dict(os.environ, environment, clear=True):
            session = create({"mode": "assistive"})
            self.assertTrue(decide(session, proposal, 1_000.0)[0])
            remember(session, "1", proposal["text"], proposal["event_key"])
            repeated = dict(
                proposal,
                text="Scheda madre: controlla il connettore",
                event_key="connector-b",
            )
            blocked = decide(session, repeated, 1_010.0)
        self.assertFalse(blocked[0])
        self.assertEqual("semantic_duplicate", blocked[1])

    def test_invalid_inline_memory_does_not_discard_compact_reply(self):
        validate = self.runtime["_hermes_hub_jarvis_validate_fast_result"]
        result = validate(
            {
                "action": "respond_simple",
                "observation": "La vite e' allineata.",
                "reason": "Risposta visiva diretta.",
                "confidence": 0.9,
                "importance": 0.5,
                "urgency": 0.1,
                "utility": 0.8,
                "reply": "Si, e' allineata.",
                "needs_agent": False,
                "event_key": "vite-allineata",
                "memory_update": {"open_loop": "non valido"},
                "recommended_frame_ids": [],
            }
        )
        self.assertEqual("Si, e' allineata.", result["reply"])
        self.assertIsNone(result["memory_update"])

    def test_summary_json_schema_matches_runtime_contract(self):
        schema = json.loads(
            (ROOT / "config" / "jarvis-summary-output.schema.json").read_text(encoding="utf-8")
        )
        validator = Draft202012Validator(schema)
        valid = {
            "summary": "L'utente sta montando un computer.",
            "topic": "Montaggio PC",
            "open_loop": True,
            "notable_facts": ["Il connettore deve ancora essere verificato."],
        }
        self.assertEqual([], list(validator.iter_errors(valid)))
        invalid = dict(valid, open_loop="yes", unexpected=True)
        self.assertGreaterEqual(len(list(validator.iter_errors(invalid))), 2)

    def test_memory_update_is_inline_and_has_no_extra_summarizer_inference(self):
        jarvis = self.patched.split("# HERMES_HUB_JARVIS_RUNTIME_BEGIN", 1)[1].split(
            "# HERMES_HUB_JARVIS_RUNTIME_END", 1
        )[0]
        self.assertIn("_hermes_hub_jarvis_apply_memory_update", jarvis)
        self.assertIn('fast.get("memory_update")', jarvis)
        self.assertNotIn("_hermes_hub_jarvis_summarizer", jarvis)
        self.assertNotIn("SUMMARY_TIMEOUT_SECONDS", jarvis)

    async def test_observer_worker_finishes_current_inference_then_processes_only_latest_frame(self):
        create = self.runtime["_hermes_hub_jarvis_new_session"]
        runtime_for = self.runtime["_hermes_hub_jarvis_runtime"]
        schedule = self.runtime["_hermes_hub_jarvis_schedule_observer"]
        drop = self.runtime["_hermes_hub_jarvis_drop_session"]
        adapter = types.SimpleNamespace()
        runtime = runtime_for(adapter)
        session = create({"mode": "assistive"})
        runtime["sessions"][session["id"]] = session
        started = asyncio.Event()
        release = asyncio.Event()
        seen = []

        async def fake_observe(_adapter, _session, frame_id):
            seen.append(frame_id)
            if len(seen) == 1:
                started.set()
                await release.wait()
            return {"total_ms": 1.0}

        original = self.runtime["_hermes_hub_jarvis_observe"]
        self.runtime["_hermes_hub_jarvis_observe"] = fake_observe
        environment = {
            "HERMES_JARVIS_OBSERVER_LATENCY_MULTIPLIER": "0",
            "HERMES_JARVIS_OBSERVER_MIN_GAP_SECONDS": "0",
            "HERMES_JARVIS_OBSERVER_MAX_GAP_SECONDS": "0",
        }
        try:
            with mock.patch.dict(os.environ, environment, clear=False):
                await schedule(adapter, session, "f1")
                await asyncio.wait_for(started.wait(), timeout=1)
                first_task = session["observer_task"]
                await schedule(adapter, session, "f2")
                await schedule(adapter, session, "f3")
                self.assertIs(first_task, session["observer_task"])
                self.assertFalse(first_task.cancelled())
                release.set()
                for _ in range(100):
                    if seen == ["f1", "f3"]:
                        break
                    await asyncio.sleep(0.01)
            self.assertEqual(["f1", "f3"], seen)
        finally:
            self.runtime["_hermes_hub_jarvis_observe"] = original
            await drop(runtime, session["id"], "test")

    def test_feedback_requires_a_real_unrated_autonomous_intervention(self):
        create = self.runtime["_hermes_hub_jarvis_new_session"]
        remember = self.runtime["_hermes_hub_jarvis_remember_intervention"]
        apply_feedback = self.runtime["_hermes_hub_jarvis_apply_feedback"]
        session = create({"mode": "assistive"})
        remember(session, "42", "Controlla il connettore.", "connector")
        with mock.patch.dict(os.environ, {"HERMES_JARVIS_FEEDBACK_STEP": "0.04"}, clear=True):
            self.assertEqual(-0.04, apply_feedback(session, "42", True))
        self.assertTrue(session["interventions"][0]["helpful"])
        self.assertEqual(1, len(session["feedback"]))
        self.assertEqual("intervention_rating", session["perceptions"][-1]["kind"])
        with self.assertRaisesRegex(ValueError, "feedback_already_recorded"):
            apply_feedback(session, "42", False)
        with self.assertRaisesRegex(ValueError, "intervention_not_found"):
            apply_feedback(session, "missing", True)

    def test_no_periodic_idle_message_loop_is_present(self):
        jarvis = self.patched.split("# HERMES_HUB_JARVIS_RUNTIME_BEGIN", 1)[1].split(
            "# HERMES_HUB_JARVIS_RUNTIME_END", 1
        )[0]
        self.assertNotIn("idle_message", jarvis)
        self.assertNotIn("150 seconds", jarvis)

    def test_android_release_keeps_reflective_meta_dat_entrypoints(self):
        rules = (ROOT / "src" / "NemoclawChat.Android" / "app" / "proguard-rules.pro").read_text(
            encoding="utf-8"
        )
        self.assertIn("com.nemoclaw.chat.jarvis.meta.MetaWearablesFrameSource", rules)
        self.assertIn("com.nemoclaw.chat.jarvis.meta.MetaWearablesSetupBridgeImpl", rules)
        gradle = (ROOT / "src" / "NemoclawChat.Android" / "app" / "build.gradle.kts").read_text(
            encoding="utf-8"
        )
        self.assertIn('dir("src/metaDat/java").asFile.absolutePath', gradle)
        self.assertIn("kotlin.directories.add(metaDatSources)", gradle)

    def test_android_jarvis_startup_is_cancellable_and_fail_closed(self):
        controller = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "jarvis"
            / "JarvisSessionController.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("private var startupJob: Job? = null", controller)
        self.assertGreaterEqual(controller.count("startupJob?.cancel()"), 3)
        self.assertIn("withContext(NonCancellable)", controller)
        source_ready = controller.index("withTimeout(FRAME_SOURCE_START_TIMEOUT_MILLIS)")
        active = controller.index("phase = JarvisPhase.ACTIVE", source_ready)
        self.assertLess(source_ready, active)
        self.assertIn("private const val JARVIS_STT_BEAM_SIZE = 1", controller)
        self.assertIn("synthesizeAndPlayVoiceStream", controller)
        self.assertNotIn("frameUploadJob?.cancelAndJoin()\n        frameUploadJob = controllerScope.launch", controller)

        voice = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "VoiceModeScreen.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("VoiceEndSilenceMillis = 420L", voice)
        self.assertIn('.put("stream", true)', voice)
        self.assertIn("application/vnd.hermes.framed-wav", voice)

        screen = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "jarvis"
            / "ui"
            / "JarvisModeScreen.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("val missing = requiredPermissions.filter", screen)
        self.assertNotIn("grants.values.all", screen)

        dat_source = (
            ROOT
            / "src"
            / "NemoclawChat.Android"
            / "app"
            / "src"
            / "metaDat"
            / "java"
            / "com"
            / "nemoclaw"
            / "chat"
            / "jarvis"
            / "meta"
            / "MetaWearablesFrameSource.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("const val DAT_FRAME_RATE = 7", dat_source)
        self.assertLess(dat_source.index("frame.lumaSignature()"), dat_source.index("frame.toJpeg(76)"))

    async def test_session_cleanup_cancels_tasks_and_erases_frames(self):
        create = self.runtime["_hermes_hub_jarvis_new_session"]
        drop = self.runtime["_hermes_hub_jarvis_drop_session"]
        session = create({"mode": "assistive"})
        session["frames"]["f1"] = {"data": b"private", "received_at": time.time()}
        self.runtime["_hermes_hub_jarvis_record_perception"](
            session, "video", "observation", "private scene"
        )
        sleeper = asyncio.create_task(asyncio.sleep(60))
        session["tasks"].add(sleeper)
        runtime = {"sessions": {session["id"]: session}}
        self.assertTrue(await drop(runtime, session["id"], "test"))
        self.assertTrue(sleeper.cancelled())
        self.assertEqual({}, session["frames"])
        self.assertEqual([], list(session["perceptions"]))
        self.assertEqual({}, runtime["sessions"])

    def test_removed_session_is_rejected_after_request_body_io(self):
        create = self.runtime["_hermes_hub_jarvis_new_session"]
        is_live = self.runtime["_hermes_hub_jarvis_session_is_live"]
        session = create({"mode": "assistive"})
        adapter = types.SimpleNamespace()
        runtime = self.runtime["_hermes_hub_jarvis_runtime"](adapter)
        runtime["sessions"][session["id"]] = session
        self.assertTrue(is_live(adapter, session))
        runtime["sessions"].pop(session["id"])
        self.assertFalse(is_live(adapter, session))

        handler_block = self.patched.split("# HERMES_HUB_JARVIS_HANDLERS_BEGIN", 1)[1].split(
            "# HERMES_HUB_JARVIS_HANDLERS_END", 1
        )[0]
        self.assertEqual(
            4,
            handler_block.count("if not _hermes_hub_jarvis_session_is_live(self, session):"),
        )

    def test_frame_reader_is_streamed_and_bounded(self):
        self.assertIn('request.content.iter_chunked(64 * 1024)', self.patched)
        self.assertIn('if len(payload) > max_bytes:', self.patched)
        self.assertIn('"HERMES_JARVIS_MAX_FRAME_BYTES", 1_000_000', self.patched)
        self.assertNotIn("hub_uploads", self.patched.split("# HERMES_HUB_JARVIS_RUNTIME_BEGIN", 1)[1].split("# HERMES_HUB_JARVIS_RUNTIME_END", 1)[0])

    def test_launcher_manages_all_required_jarvis_configuration(self):
        launcher = (ROOT / "scripts" / "hermes-hub-linux.sh").read_text(encoding="utf-8")
        for key in (
            "HERMES_JARVIS_ENABLED",
            "HERMES_JARVIS_SINGLE_MODEL",
            "HERMES_JARVIS_FAST_BASE_URL",
            "HERMES_JARVIS_FAST_MODEL",
            "HERMES_JARVIS_REASONING_BASE_URL",
            "HERMES_JARVIS_REASONING_MODEL",
            "HERMES_JARVIS_MAX_FRAME_BYTES",
            "HERMES_JARVIS_FRAME_TTL_SECONDS",
            "HERMES_JARVIS_SESSION_TTL_SECONDS",
            "HERMES_JARVIS_MAX_CONCURRENT_FAST",
            "HERMES_JARVIS_MAX_CONCURRENT_REASONING",
            "HERMES_JARVIS_MAX_PERCEPTIONS",
            "HERMES_JARVIS_CONVERSATION_WINDOW_SECONDS",
            "HERMES_JARVIS_SEMANTIC_DEDUPE_THRESHOLD",
            "HERMES_JARVIS_OBSERVER_LATENCY_MULTIPLIER",
            "HERMES_JARVIS_OBSERVER_MIN_GAP_SECONDS",
            "HERMES_JARVIS_OBSERVER_MAX_GAP_SECONDS",
        ):
            self.assertGreaterEqual(launcher.count(key), 3, key)


if __name__ == "__main__":
    unittest.main()
