"""La firma upstream di _callback e' migrata a PEP 604 (str | None).

Il deploy 0.6.280 falliva con "Patch anchor not found: runs resume event
hook" contro il nuovo albero agent (d923a5eb). Il patcher deve accettare
entrambe le firme, in modo idempotente. Riferimento: scripts CI #281.
"""

import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
PATCHER_PATH = ROOT / "scripts" / "patch-hermes-gateway-native.py"


def load_patcher():
    spec = importlib.util.spec_from_file_location("runs_resume_anchor_patcher", PATCHER_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def runs_module_text(event_signature: str) -> str:
    track = (
        "    else:\n"
        "        task = self._active_run_tasks[run_id] = asyncio.create_task(_execute_run(self, launch, _api_server=_api_server))\n"
    )
    finish = '        self._set_run_status(run_id, status, **fields, last_event=f"run.{status}", **extra)\n'
    stop = "    self._stopping_run_ids.add(run_id)\n"
    shutdown = (
        "def _mark_shutdown_interrupted_runs(self, run_ids) -> None:\n"
        '    """Publish the shutdown outcome before cooperative interruption can race teardown."""\n'
    )
    return "\n".join(
        [
            "# upstream runs module (signature variant probe)",
            event_signature,
            "        pass",
            track,
            finish,
            stop,
            shutdown,
            "        pass",
        ]
    )


LEGACY_SIGNATURE = (
    "    def _callback(event_type: str, tool_name: str = None, preview: str = None, args=None, **kwargs):\n"
)
PEP604_SIGNATURE = (
    "    def _callback(event_type: str, tool_name: str | None = None, preview: str | None = None, args=None, **kwargs):\n"
)


class RunsResumeAnchorVariantTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.patcher = load_patcher()

    def _assert_event_hook_installed(self, signature: str) -> None:
        patched, _ = self.patcher._patch_runs_resume(runs_module_text(signature))
        self.assertIn("_hermes_hub_rr_event(run_id, event_type, tool_name, preview, args, kwargs)", patched)
        self.assertIn(signature, patched)
        second, changes = self.patcher._patch_runs_resume(patched)
        self.assertEqual(patched, second)
        self.assertEqual([], changes)

    def test_legacy_signature_is_patched_idempotently(self):
        self._assert_event_hook_installed(LEGACY_SIGNATURE)

    def test_pep604_signature_is_patched_idempotently(self):
        self._assert_event_hook_installed(PEP604_SIGNATURE)

    def test_resolver_prefers_present_variant(self):
        resolve = self.patcher._resolve_runs_event_anchor
        self.assertEqual(PEP604_SIGNATURE, resolve(runs_module_text(PEP604_SIGNATURE)))
        self.assertEqual(LEGACY_SIGNATURE, resolve(runs_module_text(LEGACY_SIGNATURE)))
        self.assertIsNone(resolve("no callback here"))


if __name__ == "__main__":
    unittest.main()
