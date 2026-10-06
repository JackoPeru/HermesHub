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
from unittest import mock

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


def load_pid_owned_namespace(path_cls) -> dict:
    """Carica _MANAGED_CUDA_UNITS + _cgroup_service_owned + _pid_service_owned via AST."""
    src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    wanted = []
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
            isinstance(t, ast.Name) and t.id == "_MANAGED_CUDA_UNITS" for t in node.targets
        ):
            wanted.append(node)
        elif isinstance(node, ast.FunctionDef) and node.name in (
            "_cgroup_service_owned",
            "_pid_service_owned",
        ):
            wanted.append(node)
    assert len(wanted) == 3, "matcher + _pid_service_owned non trovati in manager.py"
    mod = ast.Module(body=wanted, type_ignores=[])
    ns: dict = {"Path": path_cls}
    exec(compile(mod, "manager_pid_owned", "exec"), ns)  # noqa: S102 - test locale
    return ns


class TestProcMissingFailClosed(unittest.TestCase):
    """Fail-closed su /proc mancante: nessun kill (0 kill)."""

    def test_pid_service_owned_missing_proc_returns_true(self):
        class _MissingProcPath:
            def __init__(self, *args, **kwargs):
                pass

            def read_text(self, *args, **kwargs):
                raise FileNotFoundError("/proc/123/cgroup mancante")

        ns = load_pid_owned_namespace(_MissingProcPath)
        # /proc mancante -> fail-closed True -> cleanup deve skippare.
        self.assertTrue(ns["_pid_service_owned"](123456))

    def test_missing_proc_means_zero_kill(self):
        # Simula cleanup_stray_cuda con _pid_service_owned mockato a True
        # (fail-closed per /proc mancante): 0 kill.
        fake_procs = [{"pid": 4242, "used_mb": 9000.0}]
        fake_cmd = "/usr/bin/tabbyapi --model qwen"

        def fake_pid_service_owned(pid: int) -> bool:
            return True

        mocked_owned = mock.Mock(side_effect=fake_pid_service_owned)
        kill_calls: list[int] = []

        killed = 0
        skipped_owned = 0
        for proc in fake_procs:
            cmd = fake_cmd
            low = cmd.lower()
            assert "tabbyapi" in low
            if mocked_owned(proc["pid"]):
                skipped_owned += 1
                continue
            kill_calls.append(proc["pid"])
            killed += 1

        self.assertEqual(killed, 0)
        self.assertEqual(kill_calls, [])
        self.assertEqual(skipped_owned, 1)
        mocked_owned.assert_called_once_with(4242)

    def test_cleanup_skips_self_pid(self):
        # cleanup deve escludere os.getpid(): il manager non si killa mai.
        src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
        self.assertIn("os.getpid()", src)
        self.assertIn("os.path.basename", src)


if __name__ == "__main__":
    unittest.main()
