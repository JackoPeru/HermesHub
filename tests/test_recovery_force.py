"""Test della forzatura di modo (recovery.force + wiring /force nel manager).

Fase "canale diretto laya": il bridge/app puo' forzare LLM|MEDIA|AUTO|DIRECT
anche da uno stato bloccato. Qui si testa la logica PURA senza fastapi:

  - `normalize_mode`: canonica, alias, case, input ostile -> None;
  - `apply_force_state`: azzera i blocker, rimuove i timer transitori, gestisce
    la finestra `force_llm_until` (solo LLM/AUTO) e ritorna i campi toccati;
  - `blocker_defaults`: dict fresco a ogni chiamata (niente condivisione).

Chiude con asserzioni a livello di SORGENTE su manager.py: che esista la route
POST /force, che sia protetta da `require_key` e NON da `_require_user_control`
(il bridge gira in locale), che non passi dal gate manuale, che chiami
`_set_desired`/`_force_reset_blockers`, e che `restore_llm_with_retries` e
`llm_watchdog` onorino `_force_llm_active`.
"""
from __future__ import annotations

import ast
import subprocess
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "gpu-manager"))

from recovery import force as force_mod  # noqa: E402
from recovery.force import (  # noqa: E402
    FORCE_MODES,
    TRANSITIONAL_STATES,
    apply_force_state,
    blocker_defaults,
    normalize_mode,
)


class TestNormalizeMode(unittest.TestCase):
    def test_canonica_ogni_case(self):
        for value in FORCE_MODES:
            for token in (value, value.lower(), value.title(), f"  {value}  "):
                with self.subTest(value=token):
                    self.assertEqual(value, normalize_mode(token))

    def test_alias(self):
        cases = {
            "ready": "LLM",
            "llm": "LLM",
            "tabby": "LLM",
            "comfy": "MEDIA",
            "comfyui": "MEDIA",
            "video": "MEDIA",
            "auto": "AUTO",
            "automatico": "AUTO",
            "direct": "DIRECT",
            "diretto": "DIRECT",
        }
        for raw, expected in cases.items():
            with self.subTest(raw=raw):
                self.assertEqual(expected, normalize_mode(raw))

    def test_input_ostile_none(self):
        for bad in (None, 42, [], {}, 3.14, True, "", "   ", "boh"):
            with self.subTest(bad=bad):
                self.assertIsNone(normalize_mode(bad))


class TestApplyForceState(unittest.TestCase):
    def _dirty_state(self) -> dict:
        return {
            "current_state": "ERROR",
            "desired_mode": "MEDIA",
            "state_since": 1.0,
            "last_error": "boom",
            "media_consec_failures": 3,
            "media_cooldown_until": 999.0,
            "cooldown_log_ts": 10.0,
            "error_retry_ts": 55.0,
            "llm_notloaded_fails": 4,
            "llm_watch_ts": 77.0,
            "direct_miss": 2,
            "direct_silent_since": 88.0,
            "supervisor_cooldown": {"restore_llm": 123.0},
        }

    def test_azzera_tutti_i_blocker(self):
        state = self._dirty_state()
        cleared = apply_force_state(state, "LLM", now=1000.0)
        self.assertEqual("", state["last_error"])
        self.assertEqual(0, state["media_consec_failures"])
        self.assertEqual(0.0, state["media_cooldown_until"])
        self.assertEqual(0.0, state["cooldown_log_ts"])
        self.assertEqual(0.0, state["error_retry_ts"])
        self.assertEqual(0, state["llm_notloaded_fails"])
        self.assertEqual(0.0, state["llm_watch_ts"])
        self.assertEqual(0, state["direct_miss"])
        self.assertEqual({}, state["supervisor_cooldown"])
        self.assertNotIn("direct_silent_since", state)
        # Ogni campo azzerato e' segnalato.
        for name in ("last_error", "media_consec_failures", "media_cooldown_until",
                     "cooldown_log_ts", "error_retry_ts", "llm_notloaded_fails",
                     "llm_watch_ts", "direct_miss", "direct_silent_since",
                     "supervisor_cooldown"):
            self.assertIn(name, cleared)

    def test_finestra_llm_per_llm_e_auto(self):
        for mode in ("LLM", "AUTO"):
            with self.subTest(mode=mode):
                state = {}
                apply_force_state(state, mode, now=100.0, llm_window_s=180.0)
                self.assertAlmostEqual(280.0, state["force_llm_until"])

    def test_finestra_rimossa_per_media_e_direct(self):
        for mode in ("MEDIA", "DIRECT"):
            with self.subTest(mode=mode):
                state = {"force_llm_until": 999.0}
                cleared = apply_force_state(state, mode, now=100.0)
                self.assertNotIn("force_llm_until", state)
                self.assertIn("force_llm_until", cleared)

    def test_state_non_dict_non_solleva(self):
        for bad in (None, 42, "x", []):
            with self.subTest(bad=bad):
                self.assertEqual([], apply_force_state(bad, "LLM", now=1.0))

    def test_campi_mancanti_creati(self):
        state: dict = {}
        apply_force_state(state, "LLM", now=1.0)
        self.assertEqual("", state["last_error"])
        self.assertEqual({}, state["supervisor_cooldown"])


class TestBlockerDefaults(unittest.TestCase):
    def test_dict_fresco_ogni_chiamata(self):
        a = blocker_defaults()
        b = blocker_defaults()
        self.assertEqual(a, b)
        self.assertIsNot(a, b)
        a["supervisor_cooldown"]["x"] = 1.0
        self.assertEqual({}, b["supervisor_cooldown"])


class TestPackageExports(unittest.TestCase):
    def test_esportati(self):
        self.assertIs(force_mod.normalize_mode, normalize_mode)
        self.assertIs(force_mod.apply_force_state, apply_force_state)
        self.assertIs(force_mod.blocker_defaults, blocker_defaults)
        self.assertEqual(tuple(force_mod.FORCE_MODES), tuple(FORCE_MODES))
        self.assertEqual(tuple(force_mod.TRANSITIONAL_STATES), tuple(TRANSITIONAL_STATES))


class TestNoFastapiImport(unittest.TestCase):
    def test_import_in_interprete_fresco(self):
        gp = str(REPO / "gpu-manager")
        code = (
            "import sys;"
            f"sys.path.insert(0, {gp!r});"
            "import recovery, recovery.force;"
            "assert 'fastapi' not in sys.modules, 'fastapi importato';"
            "print(recovery.force.normalize_mode('comfy'))"
        )
        proc = subprocess.run(
            [sys.executable, "-c", code],
            capture_output=True, text=True, cwd=str(REPO),
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("MEDIA", proc.stdout.strip())

    def test_sorgente_force_senza_fastapi(self):
        text = (REPO / "gpu-manager" / "recovery" / "force.py").read_text(encoding="utf-8")
        for node in ast.walk(ast.parse(text)):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    self.assertNotEqual("fastapi", alias.name.split(".")[0])
            elif isinstance(node, ast.ImportFrom):
                self.assertNotEqual("fastapi", (node.module or "").split(".")[0])


class TestManagerWiring(unittest.TestCase):
    """Asserzioni a livello di SORGENTE: nessun import di manager.py (fastapi)."""

    @classmethod
    def setUpClass(cls):
        cls.src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
        cls.tree = ast.parse(cls.src)

    def _route(self, method: str, path: str):
        for node in ast.walk(self.tree):
            if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                continue
            for dec in node.decorator_list:
                if not isinstance(dec, ast.Call):
                    continue
                func = dec.func
                if not (isinstance(func, ast.Attribute) and func.attr == method):
                    continue
                if (dec.args and isinstance(dec.args[0], ast.Constant)
                        and dec.args[0].value == path):
                    return node, dec
        return None, None

    def _func(self, name: str):
        for node in ast.walk(self.tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == name:
                return node
        return None

    def test_route_force_esiste(self):
        node, _ = self._route("post", "/force")
        self.assertIsNotNone(node, "route POST /force mancante")

    def test_force_protetta_da_require_key_non_user_control(self):
        node, _ = self._route("post", "/force")
        assert node is not None
        seg = ast.get_source_segment(self.src, node)
        assert seg is not None
        # `Depends(require_key)` vive nella firma: resta l'unico gate.
        self.assertIn("require_key", seg)
        # Il bridge gira in locale: MAI il gate utente-locale, mai il gate manuale.
        self.assertNotIn("_require_user_control", seg)
        self.assertNotIn("_require_manual_allowed", seg)
        # Applica la transizione e azzera i blocker.
        self.assertIn("_set_desired", seg)
        self.assertIn("_force_reset_blockers", seg)

    def test_force_kick_in_background(self):
        node, _ = self._route("post", "/force")
        assert node is not None
        seg = ast.get_source_segment(self.src, node)
        assert seg is not None
        self.assertIn("_force_kick", seg)
        self.assertIn("create_task", seg)

    def test_force_request_model(self):
        model = next(
            (n for n in ast.walk(self.tree)
             if isinstance(n, ast.ClassDef) and n.name == "ForceRequest"),
            None,
        )
        self.assertIsNotNone(model, "modello ForceRequest mancante")
        assert model is not None
        seg = ast.get_source_segment(self.src, model)
        assert seg is not None
        self.assertIn("mode", seg)
        self.assertIn("reason", seg)

    def test_restore_onora_finestra_force(self):
        node = self._func("restore_llm_with_retries")
        assert node is not None
        seg = ast.get_source_segment(self.src, node)
        assert seg is not None
        self.assertIn("_force_llm_active", seg)
        self.assertIn("_tabby_recent_progress", seg)

    def test_watchdog_onora_finestra_force(self):
        node = self._func("llm_watchdog")
        assert node is not None
        seg = ast.get_source_segment(self.src, node)
        assert seg is not None
        self.assertIn("_force_llm_active", seg)

    def test_helper_finestra_definito(self):
        node = self._func("_force_llm_active")
        assert node is not None, "helper _force_llm_active mancante"
        seg = ast.get_source_segment(self.src, node)
        assert seg is not None
        self.assertIn("force_llm_until", seg)


if __name__ == "__main__":
    unittest.main()
