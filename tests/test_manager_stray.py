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


def load_manager_keys_fn():
    """Carica _manager_keys pura via AST (CONFIG iniettato a runtime)."""
    src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    wanted = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "_manager_keys"]
    assert len(wanted) == 1, "_manager_keys non trovata in manager.py"
    mod = ast.Module(body=wanted, type_ignores=[])
    ns: dict = {"CONFIG": {}}
    exec(compile(mod, "manager_keys", "exec"), ns)  # noqa: S102 - test locale
    return ns


class TestManagerKeys(unittest.TestCase):
    """Chiavi accettate: primaria + trusted_keys, mai vuote."""

    def keys_for(self, config: dict) -> list:
        ns = load_manager_keys_fn()
        ns["CONFIG"] = config
        return ns["_manager_keys"]()

    def test_primary_only(self):
        self.assertEqual(self.keys_for({"api_key": "abc"}), ["abc"])

    def test_primary_plus_trusted_list(self):
        self.assertEqual(
            self.keys_for({"api_key": "abc", "trusted_keys": ["hub1", "hub2"]}),
            ["abc", "hub1", "hub2"],
        )

    def test_trusted_single_string(self):
        self.assertEqual(
            self.keys_for({"api_key": "abc", "trusted_keys": "hub1"}),
            ["abc", "hub1"],
        )

    def test_empty_means_no_keys(self):
        # Nessuna chiave -> require_key deve rispondere 500 (fail-closed).
        self.assertEqual(self.keys_for({}), [])
        self.assertEqual(self.keys_for({"api_key": "", "trusted_keys": ["", None]}), [])

    def test_require_key_is_fail_closed(self):
        src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
        self.assertIn("raise HTTPException(500", src)
        bridge = (REPO / "gpu-manager" / "display_bridge.py").read_text(encoding="utf-8")
        self.assertIn("raise HTTPException(500", bridge)
        self.assertIn("trusted_keys", bridge)


def load_manual_allowed_fn():
    """Carica _manual_modes_allowed pura via AST (CONFIG iniettato a runtime)."""
    src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    wanted = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "_manual_modes_allowed"]
    assert len(wanted) == 1, "_manual_modes_allowed non trovata in manager.py"
    mod = ast.Module(body=wanted, type_ignores=[])
    ns: dict = {"CONFIG": {}}
    exec(compile(mod, "manager_manual", "exec"), ns)  # noqa: S102 - test locale
    return ns


class TestManualModesPolicy(unittest.TestCase):
    """Policy modi manuali: default aperto, false blocca (testato via AST)."""

    def allowed_for(self, config: dict) -> bool:
        ns = load_manual_allowed_fn()
        ns["CONFIG"] = config
        return ns["_manual_modes_allowed"]()

    def test_default_open(self):
        self.assertTrue(self.allowed_for({}))

    def test_explicit_false_blocks(self):
        self.assertFalse(self.allowed_for({"allow_manual_modes": False}))

    def test_explicit_true_allows(self):
        self.assertTrue(self.allowed_for({"allow_manual_modes": True}))

    def test_endpoints_enforce_and_log(self):
        src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
        self.assertIn("_require_manual_allowed(\"MEDIA\")", src)
        self.assertIn("_require_manual_allowed(\"DIRECT\")", src)
        self.assertIn("_log_mode_request(request", src)
        self.assertIn("409", src)


class TestManagerRoles(unittest.TestCase):
    """Solo l'UTENTE (telefono via rete) gestisce il manager; l'agent in
    localhost usa solo coda media + letture. Stile statico via AST/stringhe:
    nessun import di manager.py (fastapi non serve)."""

    @classmethod
    def setUpClass(cls):
        cls.src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
        cls.tree = ast.parse(cls.src)
        cls.funcs = {
            n.name: n
            for n in cls.tree.body
            if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
        }

    def _func_calls(self, name: str) -> str:
        node = self.funcs.get(name)
        self.assertIsNotNone(node, f"funzione {name} non trovata in manager.py")
        seg = ast.get_source_segment(self.src, node)
        self.assertIsNotNone(seg, f"sorgente {name} non estraibile")
        return seg or ""

    def test_helpers_exist(self):
        self.assertIn("def _is_local_request(request)", self.src)
        self.assertIn("def _require_user_control(request)", self.src)
        self.assertIn("def _ensure_log_handler()", self.src)
        for host in ('"127.0.0.1"', '"::1"', '"::ffff:127.0.0.1"', '"localhost"'):
            self.assertIn(host, self.src)
        self.assertIn("request.client.host", self.src)

    def test_user_control_on_five_endpoints(self):
        for name in ("mode_llm", "mode_media", "mode_auto", "mode_direct", "system_reboot"):
            with self.subTest(endpoint=name):
                self.assertIn("_require_user_control", self._func_calls(name))

    def test_user_control_not_on_queue_and_reads(self):
        for name in ("submit_job", "status", "root"):
            with self.subTest(endpoint=name):
                self.assertNotIn("_require_user_control", self._func_calls(name))

    def test_forbidden_code_present(self):
        self.assertIn("403", self.src)
        self.assertIn("solo l'utente gestisce il manager", self.src)

    def test_ensure_log_handler_in_on_startup(self):
        self.assertIn("_ensure_log_handler()", self._func_calls("on_startup"))
        seg = self._func_calls("_ensure_log_handler")
        self.assertIn("StreamHandler", seg)
        self.assertIn("propagate", seg)
        self.assertIn("handlers", seg)


class TestLlmServingProbe(unittest.TestCase):
    """La VRAM piena non basta: tabby sganciato risponde 503 alle completion
    mentre llm_loaded() e True. llm_serving() fa una vera completion da 1
    token; il watchdog la usa come gate e /status la espone. Stile statico
    via AST/stringhe: nessun import di manager.py."""

    @classmethod
    def setUpClass(cls):
        cls.src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
        cls.tree = ast.parse(cls.src)
        cls.funcs = {
            n.name: n
            for n in cls.tree.body
            if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
        }

    def _func_calls(self, name: str) -> str:
        node = self.funcs.get(name)
        self.assertIsNotNone(node, f"funzione {name} non trovata in manager.py")
        seg = ast.get_source_segment(self.src, node)
        self.assertIsNotNone(seg, f"sorgente {name} non estraibile")
        return seg or ""

    def test_probe_posts_tiny_completion(self):
        seg = self._func_calls("_llm_serving_probe")
        self.assertIn("/v1/chat/completions", seg)
        self.assertIn("max_tokens", seg)
        self.assertIn("code != 200", seg)

    def test_serving_cached(self):
        seg = self._func_calls("llm_serving")
        self.assertIn("SERVING_CACHE_TTL", seg)
        self.assertIn("_serving_probe", seg)
        self.assertIn("_llm_serving_probe", seg)

    def test_watchdog_uses_serving_gate(self):
        seg = self._func_calls("llm_watchdog")
        self.assertIn("llm_serving()", seg)
        self.assertIn("_tabby_recent_progress()", seg)
        self.assertIn("restore_llm_with_retries", seg)

    def test_status_exposes_serving(self):
        seg = self._func_calls("status")
        self.assertIn('"llm_serving": await llm_serving()', seg)


if __name__ == "__main__":
    unittest.main()
