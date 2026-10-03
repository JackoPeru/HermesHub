"""Tests for the continue-on-disconnect gateway patch (no gateway needed)."""

import importlib.util
import sys
import textwrap
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "scripts" / "hermes_hub_gateway" / "adapters" / "hermes"))


def load_legacy_patcher():
    path = REPO / "scripts" / "hermes_hub_gateway" / "adapters" / "hermes" / "legacy_patcher.py"
    spec = importlib.util.spec_from_file_location("hermes_gateway_patcher_detach", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


API_SERVER_FIXTURE = textwrap.dedent('''\
    import asyncio
    import logging

    logger = logging.getLogger("x")

    class Adapter:
        async def _handle_session_chat_stream(self, request):
            ctx, err = await self._prepare_session_chat(request)
            gateway_session_key, session_id = ctx["gateway_session_key"], ctx["session_id"]
            user_message, runtime_request = ctx["user_message"], ctx["runtime_request"]
            message_id = f"msg_{uuid.uuid4().hex}"
            run_id = f"run_{uuid.uuid4().hex}"
            events = _SessionEventQueue(session_id, run_id)
            task = asyncio.create_task(self._run_and_signal())
            response = await self._prepare_sse_response(request, session_id, gateway_session_key)
            try:
                while True:
                    item = await queue.get()
                    if item is None:
                        break
                    await response.write(item)
            except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, OSError):
                await self._drain_session_stream_task_on_disconnect(
                    run_id, task, interrupt_message="SSE client disconnected", shield_wait=False)
                logger.info("Session SSE client disconnected; interrupted live run %s", run_id)
            except asyncio.CancelledError:
                await self._drain_session_stream_task_on_disconnect(
                    run_id, task, interrupt_message="SSE task cancelled", shield_wait=True)
            return response
    ''')

ROUTES_FIXTURE = textwrap.dedent('''\
    import asyncio
    import logging

    logger = logging.getLogger("y")

    class Routes:
        async def _write_sse_chat_completion(self, request, completion_id):
            response = await self._prepare_sse_response(request, None, None)
            try:
                await response.write(b"data: x\\n\\n")
            except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, OSError):
                await _abandon_agent_task(agent_ref, agent_task, "SSE client disconnected")
                logger.info("SSE client disconnected; interrupted agent task %s", completion_id)
            return response

        async def _write_sse_responses(self, request, response_id):
            response = await self._prepare_sse_response(request, None, None)
            try:
                await response.write(b"data: x\\n\\n")
            except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, OSError):
                st.persist_incomplete_if_needed()
                await _abandon_agent_task(agent_ref, agent_task, "SSE client disconnected")
                logger.info("SSE client disconnected; interrupted agent task %s", response_id)
            return response
    ''')


class DetachPatchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.patcher = load_legacy_patcher()

    def test_session_patch_guards_drain_and_compiles(self):
        patched, changes = self.patcher._patch_detach_api_server(API_SERVER_FIXTURE)
        self.assertEqual(changes, ["session chat continue-on-disconnect"])
        self.assertIn("_hermes_hub_detach_on_disconnect", patched)
        self.assertIn("detached run %s continues", patched)
        # Original kill path preserved for unflagged requests.
        self.assertIn("interrupted live run %s", patched)
        compile(patched, "api_server.py", "exec")

    def test_session_patch_is_idempotent(self):
        patched, _ = self.patcher._patch_detach_api_server(API_SERVER_FIXTURE)
        repached, changes = self.patcher._patch_detach_api_server(patched)
        self.assertEqual(changes, [])
        self.assertEqual(repached, patched)

    def test_session_patch_fails_closed(self):
        with self.assertRaises(Exception):
            self.patcher._patch_detach_api_server("def nothing():\n    pass\n")

    def test_routes_patch_guards_both_abandons_and_compiles(self):
        patched, changes = self.patcher._patch_detach_openai_routes(ROUTES_FIXTURE)
        self.assertEqual(changes, ["openai routes continue-on-disconnect"])
        self.assertIn("async def _hermes_hub_detach_requested", patched)
        self.assertIn("detached agent task %s continues", patched)
        # Both original kill paths preserved.
        self.assertEqual(patched.count("interrupted agent task %s"), 2)
        # Incomplete snapshot still persists on responses disconnect.
        self.assertIn("st.persist_incomplete_if_needed()", patched)
        compile(patched, "api_server_openai_routes.py", "exec")

    def test_routes_patch_is_idempotent(self):
        patched, _ = self.patcher._patch_detach_openai_routes(ROUTES_FIXTURE)
        repached, changes = self.patcher._patch_detach_openai_routes(patched)
        self.assertEqual(changes, [])
        self.assertEqual(repached, patched)

    def test_routes_patch_fails_closed(self):
        with self.assertRaises(Exception):
            self.patcher._patch_detach_openai_routes("def nothing():\n    pass\n")


if __name__ == "__main__":
    unittest.main()
