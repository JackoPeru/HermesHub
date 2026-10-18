"""Contratto fallback always-operational del gpu-manager (richiesta utente 0.6.278).

Qualunque errore: desired torna AUTO e il recovery va prima a LLM_READY;
il worker non muore mai su eccezioni non gestite (_drive e supervisor
hanno un top-level try/except che entra ERROR).
"""

import ast
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANAGER = ROOT / "gpu-manager" / "manager.py"


def _func_source(module: ast.Module, name: str) -> str:
    for node in ast.walk(module):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == name:
            return ast.unparse(node)
    raise AssertionError(f"funzione {name} assente")


class ManagerErrorFallbackTests(unittest.TestCase):
    def setUp(self):
        self._tree = ast.parse(MANAGER.read_text(encoding="utf-8"))

    def test_set_state_error_resets_desired_to_auto(self):
        source = _func_source(self._tree, "set_state")
        self.assertIn("_state[" + "'desired_mode'" + "] = " + "'AUTO'", source)
        self.assertIn("_persist_desired()", source)
        self.assertIn("fallback: desired reset to AUTO", source)

    def test_error_recovery_tick_restores_llm_first(self):
        source = ast.unparse(self._tree)
        # Nel ramo ERROR di _drive il recovery non prova piu media/direct:
        # sempre LLM prima (agente sempre operativo).
        self.assertIn("restore_llm_with_retries(" + "'error-recovery'" + ")", source)
        self.assertNotIn("error-direct-failed", source)

    def test_worker_survives_unhandled_exceptions(self):
        step = _func_source(self._tree, "drive_auto_once")
        self.assertIn("worker step failed", step)
        self.assertIn("supervisor step failed", step)
        self.assertIn("set_state(" + "'ERROR'" + ", " + "f'worker step failed: {exc!r}'", step)


if __name__ == "__main__":
    unittest.main()
