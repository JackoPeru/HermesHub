"""Guardia anti-kill backend: _cgroup_service_owned non deve mai marcare come
stray un processo di un backend gestito (incidente 2026-10-05: il watchdog
uccideva il tabby live durante i prefill lunghi, loop infinito).

Carica solo la costante + la funzione pura via AST: nessun import di
manager.py (fastapi/yaml non servono).
"""
from __future__ import annotations

import ast
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def load_pure_namespace() -> dict:
    src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    wanted = []
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
            isinstance(t, ast.Name) and t.id == "_MANAGED_CUDA_UNITS" for t in node.targets
        ):
            wanted.append(node)
        elif isinstance(node, ast.FunctionDef) and node.name == "_cgroup_service_owned":
            wanted.append(node)
    assert len(wanted) == 2, "matcher puro non trovato in manager.py"
    mod = ast.Module(body=wanted, type_ignores=[])
    ns: dict = {}
    exec(compile(mod, "manager_matcher", "exec"), ns)  # noqa: S102 - test locale
    return ns


class TestServiceOwnedMatcher(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ns = load_pure_namespace()
        # staticmethod: altrimenti la funzione diventa metodo bound (self).
        cls.owned = staticmethod(cls.ns["_cgroup_service_owned"])

    def test_tabby_system_unit_owned(self):
        self.assertTrue(self.owned("0::/system.slice/hermes-tabby.service\n"))

    def test_tabby_workers_owned(self):
        # Rank tensor-parallel forkati: stesso cgroup del servizio.
        self.assertTrue(self.owned("0::/system.slice/hermes-tabby.service\n"))

    def test_comfy_variants_owned(self):
        self.assertTrue(self.owned("0::/system.slice/hermes-comfyui.service"))
        self.assertTrue(self.owned("0::/system.slice/hermes-comfyui-direct.service"))

    def test_user_units_owned(self):
        self.assertTrue(self.owned("0::/user.slice/user-1000.slice/user@1000.service/app.slice/hermes-hub.service"))
        self.assertTrue(self.owned("0::/user.slice/user-1000.slice/user@1000.service/app.slice/uninote-stt.service"))

    def test_voice_and_laya_owned(self):
        self.assertTrue(self.owned("0::/system.slice/hermes-kokoro-tts.service"))
        self.assertTrue(self.owned("0::/system.slice/hermes-laya.service"))

    def test_orphan_runtime_not_owned(self):
        # Processo orfano fuori da qualsiasi unit: va killato.
        self.assertFalse(self.owned("0::/user.slice/user-1000.slice/session-9.scope"))
        self.assertFalse(self.owned("0::/init.scope"))

    def test_empty_cgroup_not_owned(self):
        self.assertFalse(self.owned(""))
        # Mai fail-open verso il kill: sconosciuto -> non owned -> skip.
        # (cleanup salta solo gli owned; qui si afferma il default False.)


if __name__ == "__main__":
    unittest.main()
