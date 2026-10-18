"""Test Fase 1 del supervisore: motore di self-heal deterministico (nessun fastapi).

Importa `recovery.self_heal` mettendo gpu-manager su sys.path (come
test_recovery_events): il package e' pura stdlib. Ogni regola e' testata da
sola (snapshot costruiti a mano, positivo e negativo), poi `evaluate` combinato,
snapshot malformato, rispetto di `limits` e il cap di retry. Aggiunge
un'asserzione a livello di sorgente (come test_manager_safety) che
`drive_auto_once` chiami `_supervisor_step` prima di `_drive`, e che le soglie
in DEFAULT_CONFIG restino identiche a DEFAULT_LIMITS.
"""
from __future__ import annotations

import ast
import subprocess
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "gpu-manager"))

from recovery import Action, DEFAULT_LIMITS, evaluate, self_heal  # noqa: E402


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


def err(ts: float, kind: str = "error") -> dict:
    return {"ts": ts, "kind": kind, "severity": "error", "message": "x"}


class TestDefaultLimits(unittest.TestCase):
    def test_soglie_congelate(self):
        self.assertEqual(600.0, DEFAULT_LIMITS["stale_job_s"])
        self.assertEqual(180.0, DEFAULT_LIMITS["media_down_s"])
        self.assertEqual(3, DEFAULT_LIMITS["error_burst_n"])
        self.assertEqual(600.0, DEFAULT_LIMITS["error_burst_window_s"])
        self.assertEqual(2, DEFAULT_LIMITS["max_job_retries"])
        self.assertEqual(180.0, DEFAULT_LIMITS["llm_transition_s"])
        # R5/R6 opt-in, R4 attiva di default.
        self.assertFalse(DEFAULT_LIMITS["enable_media_rules"])
        self.assertTrue(DEFAULT_LIMITS["enable_current_job_rule"])


class TestR1OrphanJobs(unittest.TestCase):
    def test_positivo_job_running_orfano(self):
        s = snap(jobs=[job(job_id="a", updated_at=10_000.0 - 601)])
        action = self_heal._rule_orphan_jobs(s)
        assert action is not None
        self.assertEqual("cancel_stale_jobs", action.name)
        self.assertEqual({"job_ids": ["a"], "cause": "orphan"}, action.params)
        self.assertEqual("rules", action.source)

    def test_negativo_aggiornato_di_recente(self):
        s = snap(jobs=[job(job_id="a", updated_at=10_000.0 - 10)])
        self.assertIsNone(self_heal._rule_orphan_jobs(s))

    def test_negativo_current_job_escluso(self):
        s = snap(current_job="a", jobs=[job(job_id="a", updated_at=10_000.0 - 601)])
        self.assertIsNone(self_heal._rule_orphan_jobs(s))

    def test_negativo_status_non_running(self):
        s = snap(jobs=[job(job_id="a", status="queued", updated_at=10_000.0 - 601)])
        self.assertIsNone(self_heal._rule_orphan_jobs(s))

    def test_plurale_piu_job(self):
        s = snap(jobs=[
            job(job_id="a", updated_at=10_000.0 - 601),
            job(job_id="b", updated_at=10_000.0 - 900),
            job(job_id="c", updated_at=10_000.0 - 10),
        ])
        action = self_heal._rule_orphan_jobs(s)
        assert action is not None
        self.assertEqual(["a", "b"], action.params["job_ids"])

    def test_rispetta_soglia_custom(self):
        s = snap(jobs=[job(job_id="a", updated_at=10_000.0 - 601)],
                 limits={"stale_job_s": 5000.0})
        self.assertIsNone(self_heal._rule_orphan_jobs(s))
        s2 = snap(jobs=[job(job_id="a", updated_at=10_000.0 - 601)],
                  limits={"stale_job_s": 10.0})
        self.assertIsNotNone(self_heal._rule_orphan_jobs(s2))


class TestR2RetryLostJob(unittest.TestCase):
    def test_positivo_job_perso(self):
        s = snap(jobs=[job(job_id="a", status="failed",
                           error="lost by ComfyUI: no history", retries=0)])
        action = self_heal._rule_retry_lost_job(s)
        assert action is not None
        self.assertEqual("retry_job", action.name)
        self.assertEqual({"job_id": "a"}, action.params)

    def test_sceglie_il_piu_vecchio(self):
        s = snap(jobs=[
            job(job_id="nuovo", status="failed", error="lost by comfyui", created_at=9500.0),
            job(job_id="vecchio", status="failed", error="LOST BY COMFYUI", created_at=9000.0),
        ])
        action = self_heal._rule_retry_lost_job(s)
        assert action is not None
        self.assertEqual("vecchio", action.params["job_id"])

    def test_negativo_altro_errore(self):
        s = snap(jobs=[job(job_id="a", status="failed", error="job timeout")])
        self.assertIsNone(self_heal._rule_retry_lost_job(s))

    def test_negativo_status_non_failed(self):
        s = snap(jobs=[job(job_id="a", status="running", error="lost by comfyui")])
        self.assertIsNone(self_heal._rule_retry_lost_job(s))

    def test_cap_retry_rispettato(self):
        # max_job_retries default 2: retries 2 non ritenta piu', 1 si'.
        s2 = snap(jobs=[job(job_id="a", status="failed", error="lost by comfyui", retries=2)])
        self.assertIsNone(self_heal._rule_retry_lost_job(s2))
        s1 = snap(jobs=[job(job_id="a", status="failed", error="lost by comfyui", retries=1)])
        self.assertIsNotNone(self_heal._rule_retry_lost_job(s1))

    def test_cap_retry_override(self):
        s = snap(jobs=[job(job_id="a", status="failed", error="lost by comfyui", retries=2)],
                 limits={"max_job_retries": 5})
        self.assertIsNotNone(self_heal._rule_retry_lost_job(s))

    def test_negativo_current_job(self):
        s = snap(current_job="a",
                 jobs=[job(job_id="a", status="failed", error="lost by comfyui", retries=0)])
        self.assertIsNone(self_heal._rule_retry_lost_job(s))


class TestR3ErrorBurst(unittest.TestCase):
    def test_positivo_burst(self):
        s = snap(state="ERROR", recent_events=[err(9900.0), err(9950.0), err(9990.0)])
        action = self_heal._rule_error_burst(s)
        assert action is not None
        self.assertEqual("notify_user", action.name)
        self.assertIsInstance(action.params["message"], str)
        self.assertTrue(action.params["message"])

    def test_negativo_sotto_soglia(self):
        s = snap(state="ERROR", recent_events=[err(9900.0), err(9950.0)])
        self.assertIsNone(self_heal._rule_error_burst(s))

    def test_negativo_fuori_finestra(self):
        s = snap(state="ERROR", recent_events=[err(10_000.0 - 601), err(10_000.0 - 700),
                                               err(10_000.0 - 800)])
        self.assertIsNone(self_heal._rule_error_burst(s))

    def test_negativo_state_non_error(self):
        s = snap(state="LLM_READY", recent_events=[err(9990.0)] * 3)
        self.assertIsNone(self_heal._rule_error_burst(s))

    def test_conta_solo_kind_error(self):
        s = snap(state="ERROR", recent_events=[
            err(9990.0), err(9991.0), {"ts": 9992.0, "kind": "state", "severity": "error"},
        ])
        self.assertIsNone(self_heal._rule_error_burst(s))

    def test_soglia_override(self):
        s = snap(state="ERROR", recent_events=[err(9990.0)],
                 limits={"error_burst_n": 1})
        self.assertIsNotNone(self_heal._rule_error_burst(s))


class TestR4StuckCurrent(unittest.TestCase):
    def test_positivo_media_giu_e_job_stale(self):
        s = snap(state="MEDIA_BUSY", current_job="a", media_online=False,
                 jobs=[job(job_id="a", status="running", updated_at=10_000.0 - 601)])
        action = self_heal._rule_stuck_current_job(s)
        assert action is not None
        self.assertEqual("cancel_stale_jobs", action.name)
        self.assertEqual({"job_ids": ["a"], "cause": "current-lost"}, action.params)

    def test_negativo_media_su(self):
        s = snap(current_job="a", media_online=True,
                 jobs=[job(job_id="a", status="running", updated_at=10_000.0 - 601)])
        self.assertIsNone(self_heal._rule_stuck_current_job(s))

    def test_negativo_fase_saving(self):
        s = snap(current_job="a", media_online=False,
                 jobs=[job(job_id="a", status="running", phase="saving",
                           updated_at=10_000.0 - 601)])
        self.assertIsNone(self_heal._rule_stuck_current_job(s))

    def test_negativo_disattivata(self):
        s = snap(current_job="a", media_online=False,
                 jobs=[job(job_id="a", status="running", updated_at=10_000.0 - 601)],
                 limits={"enable_current_job_rule": False})
        self.assertIsNone(self_heal._rule_stuck_current_job(s))

    def test_negativo_non_stale(self):
        s = snap(current_job="a", media_online=False,
                 jobs=[job(job_id="a", status="running", updated_at=10_000.0 - 10)])
        self.assertIsNone(self_heal._rule_stuck_current_job(s))


class TestR5StuckUnload(unittest.TestCase):
    def test_positivo(self):
        s = snap(state="LLM_UNLOADING", state_since=500.0, now=500.0 + 181,
                 llm_loaded=False, limits={"enable_media_rules": True})
        action = self_heal._rule_stuck_unload(s)
        assert action is not None
        self.assertEqual("restore_llm", action.name)
        self.assertEqual({}, action.params)

    def test_negativo_opt_in_spenta(self):
        s = snap(state="LLM_UNLOADING", state_since=500.0, now=500.0 + 181,
                 llm_loaded=False)
        self.assertIsNone(self_heal._rule_stuck_unload(s))

    def test_negativo_llm_caricato(self):
        s = snap(state="LLM_UNLOADING", state_since=500.0, now=500.0 + 181,
                 llm_loaded=True, limits={"enable_media_rules": True})
        self.assertIsNone(self_heal._rule_stuck_unload(s))

    def test_negativo_non_bloccato(self):
        s = snap(state="LLM_UNLOADING", state_since=500.0, now=500.0 + 10,
                 llm_loaded=False, limits={"enable_media_rules": True})
        self.assertIsNone(self_heal._rule_stuck_unload(s))


class TestR6MediaDown(unittest.TestCase):
    def test_positivo(self):
        s = snap(state="MEDIA_READY", state_since=500.0, now=500.0 + 181,
                 media_online=False, limits={"enable_media_rules": True})
        action = self_heal._rule_media_down_cooldown(s)
        assert action is not None
        self.assertEqual("cooldown_media", action.name)
        self.assertEqual(DEFAULT_LIMITS["cooldown_media_s"], action.params["seconds"])

    def test_negativo_opt_in_spenta(self):
        s = snap(state="MEDIA_READY", state_since=500.0, now=500.0 + 181, media_online=False)
        self.assertIsNone(self_heal._rule_media_down_cooldown(s))

    def test_negativo_media_su(self):
        s = snap(state="MEDIA_READY", state_since=500.0, now=500.0 + 181,
                 media_online=True, limits={"enable_media_rules": True})
        self.assertIsNone(self_heal._rule_media_down_cooldown(s))

    def test_negativo_state_sbagliato(self):
        s = snap(state="LLM_READY", state_since=500.0, now=500.0 + 181,
                 media_online=False, limits={"enable_media_rules": True})
        self.assertIsNone(self_heal._rule_media_down_cooldown(s))

    def test_secondi_override(self):
        s = snap(state="MEDIA_BUSY", state_since=500.0, now=500.0 + 181, media_online=False,
                 limits={"enable_media_rules": True, "cooldown_media_s": 60.0})
        action = self_heal._rule_media_down_cooldown(s)
        assert action is not None
        self.assertEqual(60.0, action.params["seconds"])


class TestEvaluateCombined(unittest.TestCase):
    def test_vuoto(self):
        self.assertEqual([], evaluate(snap()))

    def test_ordine_regole(self):
        s = snap(
            state="ERROR",
            current_job="a",
            media_online=False,
            jobs=[job(job_id="a", status="running", updated_at=10_000.0 - 601),
                  job(job_id="b", status="running", updated_at=10_000.0 - 700)],
            recent_events=[err(9990.0)] * 3,
        )
        names = [a.name for a in evaluate(s)]
        # R1 (orfano b) -> R3 (burst) -> R4 (current a), nell'ordine delle regole.
        self.assertEqual(["cancel_stale_jobs", "notify_user", "cancel_stale_jobs"], names)

    def test_nessun_duplicato_stesso_params(self):
        # Due regole identiche (name+params) non duplicano.
        original = self_heal._RULES

        def dup(_s):
            return Action(name="notify_user", params={"message": "m"},
                          reason="dup", confidence=0.5, source="rules")

        try:
            self_heal._RULES = (dup, dup)
            actions = evaluate(snap(state="ERROR"))
        finally:
            self_heal._RULES = original
        self.assertEqual(1, len(actions))

    def test_params_diversi_non_deduplicati(self):
        original = self_heal._RULES

        def rule_x(_s):
            return Action(name="retry_job", params={"job_id": "x"},
                          reason="x", confidence=0.5, source="rules")

        def rule_y(_s):
            return Action(name="retry_job", params={"job_id": "y"},
                          reason="y", confidence=0.5, source="rules")

        try:
            self_heal._RULES = (rule_x, rule_y)
            actions = evaluate(snap())
        finally:
            self_heal._RULES = original
        self.assertEqual(2, len(actions))


class TestMalformedSnapshot(unittest.TestCase):
    def test_evaluate_none_e_non_dict(self):
        self.assertEqual([], evaluate(None))
        self.assertEqual([], evaluate(42))
        self.assertEqual([], evaluate("snapshot"))

    def test_evaluate_dict_vuoto(self):
        self.assertEqual([], evaluate({}))

    def test_evaluate_tipi_sbagliati_non_solleva(self):
        weird = {
            "now": "x", "wall_now": None, "state": 5, "state_since": [],
            "current_job": {"no": "str"}, "jobs": "notalist",
            "recent_events": {"ts": 1}, "limits": "notadict",
            "media_online": "si", "llm_loaded": 1,
        }
        self.assertEqual([], evaluate(weird))

    def test_regole_singole_mai_sollevano(self):
        for rule in self_heal._RULES:
            with self.subTest(rule=rule.__name__):
                self.assertIsNone(rule(None))
                self.assertIsNone(rule({}))
                self.assertIsNone(rule({"jobs": [None, 1, "x"], "recent_events": [None]}))


class TestNoFastapiImport(unittest.TestCase):
    def test_import_in_interprete_fresco(self):
        gp = str(REPO / "gpu-manager")
        code = (
            "import sys;"
            f"sys.path.insert(0, {gp!r});"
            "import recovery.self_heal as sh;"
            "assert 'fastapi' not in sys.modules, 'fastapi importato';"
            "print(sh.DEFAULT_LIMITS['stale_job_s'])"
        )
        proc = subprocess.run(
            [sys.executable, "-c", code],
            capture_output=True, text=True, cwd=str(REPO),
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("600.0", proc.stdout.strip())

    def test_sorgente_self_heal_senza_fastapi(self):
        text = (REPO / "gpu-manager" / "recovery" / "self_heal.py").read_text(encoding="utf-8")
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

    def test_drive_auto_once_chiama_supervisor_step_prima_di_drive(self):
        seg = ast.get_source_segment(self.src, self.funcs["drive_auto_once"])
        assert seg is not None
        self.assertIn("_supervisor_step", seg)
        self.assertIn("_drive()", seg)
        self.assertLess(seg.index("_supervisor_step"), seg.index("_drive()"))

    def test_drive_non_modificato_nel_recupero_error(self):
        seg = ast.get_source_segment(self.src, self.funcs["_drive"])
        assert seg is not None
        self.assertIn("error-recovery", seg)
        self.assertIn("restore_llm_with_retries", seg)

    def test_set_state_aggiorna_state_since(self):
        seg = ast.get_source_segment(self.src, self.funcs["set_state"])
        assert seg is not None
        self.assertIn('_state["state_since"]', seg)
        self.assertIn("time.monotonic()", seg)

    def test_helper_supervisore_presenti(self):
        for name in ("_all_jobs", "_supervisor_limits", "_supervisor_probes",
                     "_supervisor_snapshot", "_supervisor_execute", "_supervisor_step"):
            with self.subTest(helper=name):
                self.assertIn(name, self.funcs)

    def test_all_jobs_sotto_db_lock(self):
        seg = ast.get_source_segment(self.src, self.funcs["_all_jobs"])
        assert seg is not None
        self.assertIn(".execute(", seg)
        self.assertIn("with _db_lock:", seg)

    def test_config_supervisor_coerente_con_default_limits(self):
        node = next(
            n for n in self.tree.body
            if isinstance(n, ast.Assign)
            and any(isinstance(t, ast.Name) and t.id == "DEFAULT_CONFIG" for t in n.targets)
        )
        cfg = ast.literal_eval(node.value)
        self_heal_cfg = cfg["supervisor"]["self_heal"]
        self.assertTrue(self_heal_cfg["enabled"])
        for key, value in DEFAULT_LIMITS.items():
            with self.subTest(limit=key):
                self.assertEqual(value, self_heal_cfg[key])


if __name__ == "__main__":
    unittest.main()
