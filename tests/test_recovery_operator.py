"""Test Fase 3 del supervisore: operator laya su allowlist (nessun fastapi, no rete).

Importa `recovery.operator` mettendo gpu-manager su sys.path (come gli altri test
recovery): il modulo e' pura stdlib e non tocca ne' la GPU ne' fastapi. Ogni
pezzo e' testato da solo (`resolve_params` per ogni azione, con e senza
bersaglio; `decide_actions` per allowlist, soglia, bersaglio e duplicati). Chiude
con asserzioni a livello di SORGENTE (come test_recovery_advisor) che
`_supervisor_operator_step` esista, sia invocata da `_supervisor_step`, sia gated
da `operator.enabled`, e che `DEFAULT_CONFIG["supervisor"]["operator"]["enabled"]`
sia False (fail-closed di default).
"""
from __future__ import annotations

import ast
import subprocess
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "gpu-manager"))

from recovery import Action, ALLOWED_ACTIONS, DEFAULT_LIMITS  # noqa: E402
from recovery import operator  # noqa: E402
from recovery.advisor import Advice  # noqa: E402
from recovery.operator import decide_actions, resolve_params  # noqa: E402
from recovery.self_heal import lost_job_id, stale_job_ids  # noqa: E402


def snap(**overrides) -> dict:
    """Snapshot del contratto valido, con override per singolo test."""
    base = {
        "now": 1000.0,
        "wall_now": 10_000.0,
        "state": "LLM_READY",
        "state_since": 500.0,
        "desired": "AUTO",
        "last_error": "",
        "current_job": None,
        "current_prompt_id": None,
        "queue_len": 0,
        "llm_loaded": True,
        "llm_serving": True,
        "media_online": True,
        "media_consec_failures": 0,
        "media_cooldown_until": 0.0,
        "jobs": [],
        "recent_events": [],
        "limits": dict(DEFAULT_LIMITS),
    }
    base.update(overrides)
    return base


def job(**overrides) -> dict:
    base = {
        "job_id": "j1",
        "kind": "image",
        "status": "running",
        "retries": 0,
        "error": "",
        "phase": "",
        "updated_at": 10_000.0,
        "created_at": 9_000.0,
        "preset": "",
    }
    base.update(overrides)
    return base


def act(name: str, confidence: float = 0.9, reason: str = "laya") -> Action:
    return Action(name=name, params={}, reason=reason,
                  confidence=confidence, source="laya")


class TestResolveParams(unittest.TestCase):
    def test_restore_llm_senza_bersaglio(self):
        self.assertEqual({}, resolve_params("restore_llm", snap()))

    def test_cooldown_media_default(self):
        params = resolve_params("cooldown_media", snap())
        self.assertEqual({"seconds": DEFAULT_LIMITS["cooldown_media_s"]}, params)

    def test_cooldown_media_override(self):
        params = resolve_params("cooldown_media", snap(), {"cooldown_media_s": 60.0})
        self.assertEqual({"seconds": 60.0}, params)

    def test_cooldown_media_override_snapshot(self):
        s = snap(limits={"cooldown_media_s": 90.0})
        self.assertEqual({"seconds": 90.0}, resolve_params("cooldown_media", s))

    def test_notify_user_messaggio_stabile(self):
        first = resolve_params("notify_user", snap())
        second = resolve_params("notify_user", snap(state="ERROR"))
        self.assertIsInstance(first["message"], str)
        self.assertTrue(first["message"])
        self.assertEqual(first, second)  # stabile, non varia da tick a tick

    def test_cancel_stale_jobs_con_bersaglio(self):
        s = snap(jobs=[job(job_id="a", updated_at=10_000.0 - 601)])
        params = resolve_params("cancel_stale_jobs", s)
        self.assertEqual({"job_ids": ["a"], "cause": "laya"}, params)

    def test_cancel_stale_jobs_senza_bersaglio(self):
        # Nessun job stale -> None (azione non eseguibile).
        self.assertIsNone(resolve_params("cancel_stale_jobs", snap()))

    def test_cancel_stale_jobs_fase_saving_esclusa(self):
        s = snap(jobs=[job(job_id="a", phase="saving", updated_at=10_000.0 - 601)])
        self.assertIsNone(resolve_params("cancel_stale_jobs", s))

    def test_retry_job_con_bersaglio(self):
        s = snap(jobs=[job(job_id="a", status="failed",
                           error="lost by ComfyUI: no history", retries=0)])
        self.assertEqual({"job_id": "a"}, resolve_params("retry_job", s))

    def test_retry_job_senza_bersaglio(self):
        self.assertIsNone(resolve_params("retry_job", snap()))

    def test_retry_job_cap_retry(self):
        s = snap(jobs=[job(job_id="a", status="failed", error="lost by comfyui",
                           retries=DEFAULT_LIMITS["max_job_retries"])])
        self.assertIsNone(resolve_params("retry_job", s))

    def test_nome_fuori_allowlist(self):
        self.assertIsNone(resolve_params("rm -rf /", snap()))
        self.assertIsNone(resolve_params("none", snap()))
        self.assertIsNone(resolve_params(None, snap()))  # type: ignore[arg-type]

    def test_snapshot_malformato_non_solleva(self):
        for bad in (None, 42, "x", [], {}, {"jobs": "no", "wall_now": []}):
            with self.subTest(bad=bad):
                for name in ALLOWED_ACTIONS:
                    resolve_params(name, bad)  # type: ignore[arg-type]

    def test_coerente_con_helper_self_heal(self):
        s = snap(jobs=[job(job_id="a", updated_at=10_000.0 - 601),
                       job(job_id="b", status="failed", error="lost by comfyui")])
        self.assertEqual(["a"], stale_job_ids(s))
        self.assertEqual("b", lost_job_id(s))
        self.assertEqual(["a"], resolve_params("cancel_stale_jobs", s)["job_ids"])
        self.assertEqual("b", resolve_params("retry_job", s)["job_id"])


class TestDecideActions(unittest.TestCase):
    def test_advice_con_bersaglio_valido(self):
        s = snap(jobs=[job(job_id="a", updated_at=10_000.0 - 601)])
        advice = Advice(actions=[act("cancel_stale_jobs", 0.9)])
        out = decide_actions(advice, s)
        self.assertEqual(1, len(out))
        self.assertEqual("cancel_stale_jobs", out[0].name)
        self.assertEqual("laya", out[0].source)
        self.assertEqual({"job_ids": ["a"], "cause": "laya"}, out[0].params)

    def test_filtra_fuori_allowlist(self):
        s = snap(jobs=[job(job_id="a", updated_at=10_000.0 - 601)])
        advice = Advice(actions=[act("restore_llm", 0.9)])
        # `allow` piu' stretto: restore_llm non e' ammesso qui.
        self.assertEqual([], decide_actions(advice, s, allow=("notify_user",)))

    def test_soglia_min_confidence(self):
        advice = Advice(actions=[act("restore_llm", 0.5)])
        self.assertEqual([], decide_actions(advice, snap(), min_confidence=0.8))
        self.assertEqual(1, len(decide_actions(advice, snap(), min_confidence=0.5)))

    def test_soglia_esatta_accettata(self):
        advice = Advice(actions=[act("restore_llm", 0.8)])
        self.assertEqual(1, len(decide_actions(advice, snap(), min_confidence=0.8)))

    def test_skip_se_resolve_params_none(self):
        # retry_job senza job perso: resolve_params -> None -> scartata.
        advice = Advice(actions=[act("retry_job", 0.99)])
        self.assertEqual([], decide_actions(advice, snap()))

    def test_nessun_duplicato_per_name(self):
        s = snap(jobs=[job(job_id="a", updated_at=10_000.0 - 601)])
        advice = Advice(actions=[act("cancel_stale_jobs", 0.9),
                                 act("cancel_stale_jobs", 0.95)])
        out = decide_actions(advice, s)
        self.assertEqual(1, len(out))

    def test_accetta_lista_di_action(self):
        actions = [act("restore_llm", 0.9)]
        out = decide_actions(actions, snap())
        self.assertEqual(1, len(out))
        self.assertEqual("restore_llm", out[0].name)

    def test_ordine_stabile(self):
        s = snap(jobs=[job(job_id="a", status="failed", error="lost by comfyui")])
        advice = Advice(actions=[act("notify_user", 0.9), act("retry_job", 0.9)])
        names = [a.name for a in decide_actions(advice, s)]
        self.assertEqual(["notify_user", "retry_job"], names)

    def test_input_malformato_nessun_raise(self):
        bad_inputs = (
            None, 42, "x", [], {},
            Advice(actions=None),  # type: ignore[arg-type]
            [None, 1, "x", {}],
            Advice(actions=[None, "x"]),  # type: ignore[list-item]
        )
        for bad in bad_inputs:
            with self.subTest(bad=bad):
                out = decide_actions(bad, snap())  # type: ignore[arg-type]
                self.assertIsInstance(out, list)

    def test_snapshot_malformato_nessun_raise(self):
        advice = Advice(actions=[act("notify_user", 0.9)])
        for bad in (None, 42, "x", [], {"jobs": "no"}):
            with self.subTest(bad=bad):
                out = decide_actions(advice, bad)  # type: ignore[arg-type]
                self.assertIn(len(out), (0, 1))


class TestPackageExports(unittest.TestCase):
    def test_modulo_e_funzioni_esportati(self):
        # `recovery.operator` e le sue funzioni sono raggiungibili dal package.
        self.assertIs(operator.resolve_params, resolve_params)
        self.assertIs(operator.decide_actions, decide_actions)


class TestNoFastapiImport(unittest.TestCase):
    def test_import_in_interprete_fresco(self):
        gp = str(REPO / "gpu-manager")
        code = (
            "import sys;"
            f"sys.path.insert(0, {gp!r});"
            "import recovery, recovery.operator;"
            "assert 'fastapi' not in sys.modules, 'fastapi importato';"
            "print(len(recovery.operator.decide_actions(None, {})))"
        )
        proc = subprocess.run(
            [sys.executable, "-c", code],
            capture_output=True, text=True, cwd=str(REPO),
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("0", proc.stdout.strip())

    def test_sorgente_operator_senza_fastapi(self):
        text = (REPO / "gpu-manager" / "recovery" / "operator.py").read_text(encoding="utf-8")
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
        cls.funcs = {
            n.name: n for n in ast.walk(cls.tree)
            if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
        }

    def test_operator_step_esiste(self):
        self.assertIn("_supervisor_operator_step", self.funcs)

    def test_supervisor_step_invoca_operator_step(self):
        seg = ast.get_source_segment(self.src, self.funcs["_supervisor_step"])
        assert seg is not None
        self.assertIn("_supervisor_operator_step", seg)

    def test_operator_step_gated_da_enabled(self):
        seg = ast.get_source_segment(self.src, self.funcs["_supervisor_operator_step"])
        assert seg is not None
        self.assertIn("operator_cfg", seg)
        self.assertIn(".get(", seg)
        self.assertIn('"enabled"', seg)
        self.assertIn("return False", seg)

    def test_operator_step_fail_closed_su_acted_this_tick(self):
        seg = ast.get_source_segment(self.src, self.funcs["_supervisor_operator_step"])
        assert seg is not None
        self.assertIn("acted_this_tick", seg)

    def test_operator_step_riusa_execute_e_cooldown(self):
        seg = ast.get_source_segment(self.src, self.funcs["_supervisor_operator_step"])
        assert seg is not None
        self.assertIn("_supervisor_execute", seg)
        self.assertIn("supervisor_cooldown", seg)

    def test_config_operator_default_off(self):
        node = next(
            n for n in self.tree.body
            if isinstance(n, ast.Assign)
            and any(isinstance(t, ast.Name) and t.id == "DEFAULT_CONFIG" for t in n.targets)
        )
        cfg = ast.literal_eval(node.value)
        operator_cfg = cfg["supervisor"]["operator"]
        self.assertIs(operator_cfg["enabled"], False)
        self.assertEqual(0.8, operator_cfg["min_confidence"])
        self.assertEqual(120, operator_cfg["interval_s"])

    def test_shadow_non_toccata(self):
        # L'advisor shadow resta in ombra: NON chiama l'execute ne' l'operator.
        seg = ast.get_source_segment(self.src, self.funcs["_supervisor_advisor_shadow"])
        assert seg is not None
        self.assertNotIn("_supervisor_execute", seg)
        self.assertNotIn("_supervisor_operator_step", seg)


if __name__ == "__main__":
    unittest.main()
