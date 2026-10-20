"""Contract test varianti firma _callback run-resume (deploy 0.6.281).

hermes-agent ha migrato `def _callback(...)` da `tool_name: str = None` a
`tool_name: str | None = None`: il patcher deve accettare entrambe le
varianti, altrimenti il service gateway non parte (Patch anchor not found:
runs resume event hook).
"""

import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
PATCHER_PATH = ROOT / "scripts" / "patch-hermes-gateway-native.py"


def load_patcher():
    spec = importlib.util.spec_from_file_location("runs_resume_patch_patcher", PATCHER_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


LEGACY_CALLBACK = "    def _callback(event_type: str, tool_name: str = None, preview: str = None, args=None, **kwargs):\n"
MODERN_CALLBACK = "    def _callback(event_type: str, tool_name: str | None = None, preview: str | None = None, args=None, **kwargs):\n"

TRACK = "    else:\n        task = self._active_run_tasks[run_id] = asyncio.create_task(_execute_run(self, launch, _api_server=_api_server))\n"
FINISH = '        self._set_run_status(run_id, status, **fields, last_event=f"run.{status}", **extra)\n'
STOP = "    self._stopping_run_ids.add(run_id)\n"
SHUTDOWN = "def _mark_shutdown_interrupted_runs(self, run_ids) -> None:\n    \"\"\"Publish the shutdown outcome before cooperative interruption can race teardown.\"\"\"\n"


def runs_module(callback_line: str) -> str:
    return (
        "import asyncio\n\n\n"
        "def _execute_run(self, launch, _api_server=None):\n"
        "    pass\n\n\n"
        "class Runs:\n"
        + callback_line
        + "        return None\n\n"
        + TRACK
        + FINISH
        + STOP
        + SHUTDOWN
        + "    return None\n"
    )


class RunsResumePatchVariantsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.patcher = load_patcher()

    def _assert_hooks(self, callback_line: str):
        patched, changes = self.patcher._apply_runs_resume_hooks(runs_module(callback_line))
        self.assertIn("runs resume event hook", changes)
        self.assertIn("_hermes_hub_rr_event(run_id, event_type, tool_name, preview, args, kwargs)", patched)
        second, changes2 = self.patcher._apply_runs_resume_hooks(patched)
        self.assertEqual(patched, second)
        self.assertEqual([], changes2)

    def test_legacy_callback_signature_supported(self):
        self._assert_hooks(LEGACY_CALLBACK)

    def test_modern_pep604_callback_signature_supported(self):
        self._assert_hooks(MODERN_CALLBACK)


if __name__ == "__main__":
    unittest.main()
