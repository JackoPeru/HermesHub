"""Test Fase 2 del supervisore: advisor laya in ombra (nessun fastapi, no rete).

Importa `recovery.advisor` mettendo gpu-manager su sys.path (come gli altri test
recovery): il modulo e' pura stdlib. Ogni pezzo e' testato da solo
(`build_state` deterministico e cappato, `parse_answer` su payload validi e
ostili, `LayaAdvisor.advise` con opener finto). Chiude con un'asserzione a
livello di SORGENTE (come test_recovery_self_heal) che l'advisor sia invocato da
`_supervisor_step` e che il ramo shadow NON chiami MAI `_supervisor_execute`.
"""
from __future__ import annotations

import ast
import json
import subprocess
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "gpu-manager"))

from recovery import ALLOWED_ACTIONS  # noqa: E402
from recovery.advisor import (  # noqa: E402
    ADVISOR_QID,
    ADVISOR_QUESTIONS,
    Advice,
    LayaAdvisor,
    build_state,
    parse_answer,
)


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
        "limits": {},
    }
    base.update(overrides)
    return base


def payload(choice, confidence, qid: str = ADVISOR_QID) -> dict:
    return {"answers": {qid: {"choice": choice, "confidence": confidence}}}


class TestAdvisorQuestions(unittest.TestCase):
    def test_domanda_tipizzata_e_opzioni(self):
        self.assertIn(ADVISOR_QID, ADVISOR_QUESTIONS)
        question = ADVISOR_QUESTIONS[ADVISOR_QID]
        self.assertEqual("choice", question["type"])
        self.assertTrue(question["instructions"])
        # Le opzioni sono esattamente ("none", *ALLOWED_ACTIONS), in ordine.
        self.assertEqual(("none",) + ALLOWED_ACTIONS, tuple(question["criteria"]))

    def test_ogni_opzione_ha_criterio(self):
        for option, description in ADVISOR_QUESTIONS[ADVISOR_QID]["criteria"].items():
            with self.subTest(option=option):
                self.assertIsInstance(description, str)
                self.assertTrue(description)


class TestBuildState(unittest.TestCase):
    def test_deterministico(self):
        s = snap(state="MEDIA_BUSY", current_job="a",
                 jobs=[{"job_id": "a", "status": "running", "updated_at": 9_000.0}],
                 media_consec_failures=2, queue_len=3,
                 recent_events=[{"ts": 9990.0, "kind": "error",
                                 "severity": "error", "message": "boom"}])
        self.assertEqual(build_state(s), build_state(s))

    def test_contiene_i_fatti_chiave(self):
        s = snap(state="MEDIA_BUSY", desired="AUTO", current_job="a",
                 jobs=[{"job_id": "a", "status": "running", "updated_at": 9_000.0}],
                 media_consec_failures=2, queue_len=3,
                 recent_events=[{"ts": 9990.0, "kind": "error",
                                 "severity": "error", "message": "boom"}])
        text = build_state(s)
        self.assertIn("MEDIA_BUSY", text)
        self.assertIn("AUTO", text)
        self.assertIn("job_corrente=a", text)
        self.assertIn("status=running", text)
        self.assertIn("coda=3", text)
        self.assertIn("fallimenti_media_consecutivi=2", text)
        self.assertIn("errori_recenti=1", text)
        self.assertIn("boom", text)

    def test_cap_lunghezza(self):
        events = [{"ts": 1.0, "kind": "error", "severity": "error",
                   "message": "x" * 5000} for _ in range(50)]
        text = build_state(snap(recent_events=events, last_error="y" * 5000))
        self.assertIsInstance(text, str)
        self.assertLessEqual(len(text), 1500)

    def test_snapshot_malformato_non_solleva(self):
        bad_inputs = (
            None, 42, "snapshot", [], {},
            {"state": 5, "current_job": {"no": "str"}, "jobs": "x",
             "recent_events": {"ts": 1}, "limits": "nope", "now": []},
        )
        for bad in bad_inputs:
            with self.subTest(bad=bad):
                text = build_state(bad)  # type: ignore[arg-type]
                self.assertIsInstance(text, str)


class TestParseAnswer(unittest.TestCase):
    def test_scelta_valida_produce_action(self):
        advice = parse_answer(payload("retry_job", 0.9), 0.7)
        self.assertIsInstance(advice, Advice)
        self.assertEqual(1, len(advice.actions))
        action = advice.actions[0]
        self.assertEqual("retry_job", action.name)
        self.assertEqual("laya", action.source)
        self.assertEqual({}, action.params)
        self.assertAlmostEqual(0.9, action.confidence)
        self.assertEqual("laya", advice.backend)

    def test_none_nessuna_azione(self):
        advice = parse_answer(payload("none", 0.99), 0.0)
        self.assertEqual([], advice.actions)

    def test_choice_ignoto_nessuna_azione(self):
        advice = parse_answer(payload("rm -rf /", 0.99), 0.0)
        self.assertEqual([], advice.actions)

    def test_choice_non_stringa_nessuna_azione(self):
        advice = parse_answer(payload(None, 0.99), 0.0)
        self.assertEqual([], advice.actions)

    def test_sotto_soglia_nessuna_azione(self):
        advice = parse_answer(payload("restore_llm", 0.5), 0.7)
        self.assertEqual([], advice.actions)

    def test_soglia_esatta_accettata(self):
        advice = parse_answer(payload("restore_llm", 0.7), 0.7)
        self.assertEqual(1, len(advice.actions))

    def test_confidence_non_numerica_non_solleva(self):
        advice = parse_answer(payload("notify_user", "boh"))
        self.assertIsInstance(advice, Advice)
        self.assertIsInstance(advice.confidence, float)

    def test_confidence_nan_non_solleva(self):
        advice = parse_answer({"answers": {ADVISOR_QID: {
            "choice": "notify_user", "confidence": float("nan")}}}, 0.0)
        self.assertIsInstance(advice, Advice)
        for action in advice.actions:
            self.assertGreaterEqual(action.confidence, 0.0)
            self.assertLessEqual(action.confidence, 1.0)

    def test_payload_a_caso_nessun_raise(self):
        bad_inputs = (
            None, 42, "x", [], {},
            {"answers": None}, {"answers": []}, {"answers": {}},
            {"answers": {ADVISOR_QID: None}},
            {"answers": {ADVISOR_QID: {}}},
            {"answers": {ADVISOR_QID: {"choice": "notify_user"}}},
            {"answers": {"altra_domanda": {"choice": "notify_user", "confidence": 1.0}}},
        )
        for bad in bad_inputs:
            with self.subTest(bad=bad):
                advice = parse_answer(bad)  # type: ignore[arg-type]
                self.assertIsInstance(advice, Advice)
                self.assertIsInstance(advice.actions, list)


class TestLayaAdvisor(unittest.TestCase):
    def test_advise_con_opener_valido(self):
        captured: dict = {}

        def opener(req):
            captured["url"] = req.full_url
            captured["body"] = json.loads(req.data.decode("utf-8"))
            return json.dumps(payload("cooldown_media", 0.8)).encode("utf-8")

        adv = LayaAdvisor(opener=opener, min_confidence=0.7)
        advice = adv.advise(snap(state="MEDIA_READY"))
        self.assertEqual(1, len(advice.actions))
        self.assertEqual("cooldown_media", advice.actions[0].name)
        self.assertEqual("laya", advice.actions[0].source)
        self.assertEqual({}, advice.actions[0].params)
        self.assertTrue(captured["url"].endswith("/api/decide"))
        self.assertEqual("laya:multilingual", captured["body"]["model"])
        self.assertIn(ADVISOR_QID, captured["body"]["questions"])
        self.assertIsInstance(captured["body"]["state"], str)

    def test_advise_sotto_soglia_nessuna_azione(self):
        def opener(req):
            return json.dumps(payload("retry_job", 0.2)).encode("utf-8")

        advice = LayaAdvisor(opener=opener, min_confidence=0.7).advise(snap())
        self.assertEqual([], advice.actions)

    def test_advise_risponde_none(self):
        def opener(req):
            return json.dumps(payload("none", 1.0)).encode("utf-8")

        advice = LayaAdvisor(opener=opener).advise(snap())
        self.assertEqual([], advice.actions)

    def test_advise_opener_solleva_non_propaga(self):
        def opener(req):
            raise TimeoutError("laya muto")

        advice = LayaAdvisor(opener=opener).advise(snap())
        self.assertIsInstance(advice, Advice)
        self.assertEqual([], advice.actions)
        self.assertEqual(0.0, advice.confidence)
        self.assertEqual({}, advice.raw)
        self.assertIn("errore laya", advice.reasoning)

    def test_advise_json_rotto_non_propaga(self):
        def opener(req):
            return b"non-json"

        advice = LayaAdvisor(opener=opener).advise(snap())
        self.assertEqual([], advice.actions)

    def test_advise_accetta_oggetto_con_read(self):
        class Resp:
            def read(self):
                return json.dumps(payload("notify_user", 0.9)).encode("utf-8")

        advice = LayaAdvisor(opener=lambda req: Resp()).advise(snap())
        self.assertEqual(1, len(advice.actions))
        self.assertEqual("notify_user", advice.actions[0].name)

    def test_snapshot_malformato_non_solleva(self):
        def opener(req):
            return json.dumps(payload("none", 1.0)).encode("utf-8")

        advice = LayaAdvisor(opener=opener).advise(None)  # type: ignore[arg-type]
        self.assertIsInstance(advice, Advice)


class TestNoFastapiImport(unittest.TestCase):
    def test_import_in_interprete_fresco(self):
        gp = str(REPO / "gpu-manager")
        code = (
            "import sys;"
            f"sys.path.insert(0, {gp!r});"
            "import recovery, recovery.advisor;"
            "assert 'fastapi' not in sys.modules, 'fastapi importato';"
            "print(recovery.advisor.ADVISOR_QID)"
        )
        proc = subprocess.run(
            [sys.executable, "-c", code],
            capture_output=True, text=True, cwd=str(REPO),
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("supervisor_action", proc.stdout.strip())

    def test_sorgente_advisor_senza_fastapi(self):
        text = (REPO / "gpu-manager" / "recovery" / "advisor.py").read_text(encoding="utf-8")
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

    def test_supervisor_step_invoca_advisor_shadow(self):
        seg = ast.get_source_segment(self.src, self.funcs["_supervisor_step"])
        assert seg is not None
        self.assertIn("_supervisor_advisor_shadow", seg)

    def test_advisor_shadow_non_esegue_azioni(self):
        node = self.funcs["_supervisor_advisor_shadow"]
        seg = ast.get_source_segment(self.src, node)
        assert seg is not None
        # Nessuna esecuzione: ne' letterale nel testo, ne' come chiamata AST.
        self.assertNotIn("_supervisor_execute", seg)
        called = set()
        for call in (n for n in ast.walk(node) if isinstance(n, ast.Call)):
            if isinstance(call.func, ast.Attribute):
                called.add(call.func.attr)
            elif isinstance(call.func, ast.Name):
                called.add(call.func.id)
        self.assertNotIn("_supervisor_execute", called)
        self.assertNotIn("restore_llm_with_retries", called)

    def test_advisor_shadow_chiama_advise(self):
        seg = ast.get_source_segment(self.src, self.funcs["_supervisor_advisor_shadow"])
        assert seg is not None
        self.assertIn("advise", seg)

    def test_advisor_client_best_effort(self):
        seg = ast.get_source_segment(self.src, self.funcs["_advisor_client"])
        assert seg is not None
        self.assertIn("recovery is None", seg)
        self.assertIn("return None", seg)

    def test_config_advisor_presente(self):
        node = next(
            n for n in self.tree.body
            if isinstance(n, ast.Assign)
            and any(isinstance(t, ast.Name) and t.id == "DEFAULT_CONFIG" for t in n.targets)
        )
        cfg = ast.literal_eval(node.value)
        advisor_cfg = cfg["supervisor"]["advisor"]
        self.assertTrue(advisor_cfg["enabled"])
        self.assertEqual("http://127.0.0.1:11435", advisor_cfg["url"])
        self.assertEqual("laya:multilingual", advisor_cfg["model"])
        self.assertEqual(0.7, advisor_cfg["min_confidence"])
        self.assertEqual(15, advisor_cfg["timeout"])
        self.assertEqual(120, advisor_cfg["interval_s"])


if __name__ == "__main__":
    unittest.main()
