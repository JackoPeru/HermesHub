"""Test Fase 4 del supervisore: canale NL (nessun fastapi, no rete).

Importa `recovery.channel` mettendo gpu-manager su sys.path (come gli altri test
recovery): il modulo e' pura stdlib. Ogni pezzo e' testato da solo:
`answer` (testo deterministico e fedele, anche con snapshot ostile),
`classify` (ogni gruppo di parole chiave -> azione giusta, default explain),
`propose` (azione riconosciuta con bersaglio -> Action source="user", altrimenti
None). Chiude con asserzioni a livello di SORGENTE (come gli altri test
recovery) che esistano la route POST /supervisor/ask, che usi `recovery.answer`/
`recovery.propose`, che l'esecuzione sia condizionata a `confirm` E
`allow_execute`, e che `DEFAULT_CONFIG["supervisor"]["channel"]["allow_execute"]`
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
from recovery import answer, channel, classify, propose  # noqa: E402
from recovery.channel import answer as channel_answer  # noqa: E402
from recovery.channel import classify as channel_classify  # noqa: E402
from recovery.channel import propose as channel_propose  # noqa: E402


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
        "job_id": "a",
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


class TestAnswer(unittest.TestCase):
    def test_contiene_i_fatti_reali(self):
        s = snap(
            state="MEDIA_BUSY", desired="AUTO", current_job="a",
            jobs=[job(job_id="a", status="running")],
            media_cooldown_until=1300.0, queue_len=2,
            recent_events=[{"ts": 9990.0, "kind": "error",
                            "severity": "error", "message": "boom"}],
        )
        out = answer(s)
        self.assertIsInstance(out, dict)
        text = out["text"]
        self.assertIsInstance(text, str)
        self.assertTrue(text.strip())
        self.assertIn("MEDIA_BUSY", text)
        self.assertIn("a", text)
        self.assertIn("cooldown", text)
        self.assertIn("300", text)  # 1300 - 1000 residuo cooldown
        facts = out["facts"]
        self.assertEqual("MEDIA_BUSY", facts["state"])
        self.assertEqual("AUTO", facts["desired"])
        self.assertEqual("a", facts["current_job"])
        self.assertEqual(2, facts["queue_len"])
        self.assertEqual(1, facts["recent_errors"])
        self.assertAlmostEqual(300.0, facts["media_cooldown_s"])

    def test_question_vuota_ok(self):
        out = answer(snap())
        self.assertIsInstance(out["text"], str)
        self.assertTrue(out["text"].strip())

    def test_deterministico(self):
        s = snap(state="ERROR", last_error="boom", queue_len=1)
        self.assertEqual(answer(s), answer(s))

    def test_snapshot_ostile_non_solleva(self):
        bad_inputs = (
            None, 42, "snapshot", [], {},
            {"state": 5, "current_job": {"no": "str"}, "jobs": "x",
             "recent_events": {"ts": 1}, "limits": "nope", "now": [],
             "media_cooldown_until": "boh"},
        )
        for bad in bad_inputs:
            with self.subTest(bad=bad):
                out = answer(bad)  # type: ignore[arg-type]
                self.assertIsInstance(out, dict)
                self.assertIsInstance(out["text"], str)
                self.assertIsInstance(out["facts"], dict)

    def test_question_non_stringa_non_solleva(self):
        out = answer(snap(), 42)  # type: ignore[arg-type]
        self.assertIsInstance(out["text"], str)

    def test_non_afferma_fatti_assenti(self):
        # Snapshot vuoto: nessun job, nessun cooldown -> il testo non deve
        # dichiarare job o cooldown inesistenti.
        out = answer({})
        text = out["text"].lower()
        self.assertIn("nessun job in corso", text)
        self.assertNotIn("cooldown per altri", text)


class TestClassify(unittest.TestCase):
    def test_gruppi_di_parole_chiave(self):
        cases = {
            "riavvia l'LLM": "restore_llm",
            "fai restore del modello": "restore_llm",
            "llm down da troppo": "restore_llm",
            "metti in cooldown il media": "cooldown_media",
            "il video non parte": "cooldown_media",
            "il job e' bloccato": "cancel_stale_jobs",
            "situazione di stallo": "cancel_stale_jobs",
            "un job stale": "cancel_stale_jobs",
            "riprova il job perso": "retry_job",
            "serve un retry": "retry_job",
            "avvisa un umano": "notify_user",
            "manda una notifica": "notify_user",
        }
        for question, expected in cases.items():
            with self.subTest(question=question):
                verdict = classify(question)
                self.assertEqual("action", verdict["intent"])
                self.assertEqual(expected, verdict["action"])
                self.assertIn(verdict["action"], ALLOWED_ACTIONS)

    def test_domanda_generica_explain(self):
        for question in ("che succede?", "spiega lo stato", "com'e' la coda?",
                         "perche'?", "riassunto della situazione", ""):
            with self.subTest(question=question):
                verdict = classify(question)
                self.assertEqual("explain", verdict["intent"])
                self.assertIsNone(verdict["action"])

    def test_input_non_stringa_non_solleva(self):
        for bad in (None, 42, [], {}, 3.14):
            with self.subTest(bad=bad):
                verdict = classify(bad)  # type: ignore[arg-type]
                self.assertIsInstance(verdict, dict)
                self.assertEqual("explain", verdict["intent"])
                self.assertIsNone(verdict["action"])


class TestPropose(unittest.TestCase):
    def test_azione_con_bersaglio(self):
        s = snap(jobs=[job(job_id="a", status="failed",
                           error="lost by ComfyUI: no history", retries=0)])
        action = propose(s, "riprova il job perso")
        self.assertIsInstance(action, Action)
        self.assertEqual("retry_job", action.name)
        self.assertEqual("user", action.source)
        self.assertEqual({"job_id": "a"}, action.params)

    def test_azione_senza_bersaglio_e_none(self):
        # Nessun job perso: retry_job non risolvibile -> fail-closed.
        self.assertIsNone(propose(snap(), "riprova il job perso"))

    def test_intento_explain_e_none(self):
        self.assertIsNone(propose(snap(), "che succede?"))
        self.assertIsNone(propose(snap(), ""))

    def test_cancel_stale_jobs(self):
        s = snap(jobs=[job(job_id="a", updated_at=10_000.0 - 601)])
        action = propose(s, "il job e' bloccato")
        self.assertIsNotNone(action)
        assert action is not None
        self.assertEqual("cancel_stale_jobs", action.name)
        self.assertEqual(["a"], action.params["job_ids"])

    def test_cooldown_media(self):
        action = propose(snap(), "metti in cooldown il media")
        self.assertIsNotNone(action)
        assert action is not None
        self.assertEqual("cooldown_media", action.name)
        self.assertEqual({"seconds": DEFAULT_LIMITS["cooldown_media_s"]}, action.params)

    def test_restore_llm(self):
        action = propose(snap(), "riavvia l'LLM")
        self.assertIsNotNone(action)
        assert action is not None
        self.assertEqual("restore_llm", action.name)
        self.assertEqual({}, action.params)

    def test_notify_user(self):
        action = propose(snap(), "avvisa un umano")
        self.assertIsNotNone(action)
        assert action is not None
        self.assertEqual("notify_user", action.name)
        self.assertIsInstance(action.params.get("message"), str)

    def test_question_non_stringa_e_none(self):
        self.assertIsNone(propose(snap(), None))  # type: ignore[arg-type]
        self.assertIsNone(propose(snap(), 42))  # type: ignore[arg-type]

    def test_snapshot_ostile_non_solleva(self):
        for bad in (None, 42, "x", [], {}):
            with self.subTest(bad=bad):
                out = propose(bad, "riprova il job perso")  # type: ignore[arg-type]
                self.assertTrue(out is None or isinstance(out, Action))


class TestPackageExports(unittest.TestCase):
    def test_modulo_e_funzioni_esportati(self):
        self.assertIs(channel.answer, answer)
        self.assertIs(channel.classify, classify)
        self.assertIs(channel.propose, propose)
        self.assertIs(channel_answer, answer)
        self.assertIs(channel_classify, classify)
        self.assertIs(channel_propose, propose)


class TestNoFastapiImport(unittest.TestCase):
    def test_import_in_interprete_fresco(self):
        gp = str(REPO / "gpu-manager")
        code = (
            "import sys;"
            f"sys.path.insert(0, {gp!r});"
            "import recovery, recovery.channel;"
            "assert 'fastapi' not in sys.modules, 'fastapi importato';"
            "print(recovery.channel.classify('che succede?')['intent'])"
        )
        proc = subprocess.run(
            [sys.executable, "-c", code],
            capture_output=True, text=True, cwd=str(REPO),
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("explain", proc.stdout.strip())

    def test_sorgente_channel_senza_fastapi(self):
        text = (REPO / "gpu-manager" / "recovery" / "channel.py").read_text(encoding="utf-8")
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
                    return node
        return None

    def test_route_ask_esiste(self):
        node = self._route("post", "/supervisor/ask")
        self.assertIsNotNone(node, "route POST /supervisor/ask mancante")

    def test_route_usa_answer_e_propose(self):
        node = self._route("post", "/supervisor/ask")
        assert node is not None
        seg = ast.get_source_segment(self.src, node)
        assert seg is not None
        self.assertIn("recovery.answer", seg)
        self.assertIn("recovery.propose", seg)

    def test_esecuzione_gated_da_confirm_e_allow_execute(self):
        node = self._route("post", "/supervisor/ask")
        assert node is not None
        seg = ast.get_source_segment(self.src, node)
        assert seg is not None
        self.assertIn("confirm", seg)
        self.assertIn("allow_execute", seg)
        self.assertIn("is True", seg)
        # Segue lo stile fail-closed: nessuna conferma -> nessuna esecuzione.
        self.assertIn("_supervisor_execute", seg)
        self.assertIn("supervisor_cooldown", seg)

    def test_ask_request_model(self):
        model = next(
            (n for n in self.tree.body
             if isinstance(n, ast.ClassDef) and n.name == "AskRequest"),
            None,
        )
        self.assertIsNotNone(model, "modello AskRequest mancante")
        assert model is not None
        seg = ast.get_source_segment(self.src, model)
        assert seg is not None
        self.assertIn("question", seg)
        self.assertIn("confirm", seg)

    def test_config_channel_default_fail_closed(self):
        node = next(
            n for n in self.tree.body
            if isinstance(n, ast.Assign)
            and any(isinstance(t, ast.Name) and t.id == "DEFAULT_CONFIG" for t in n.targets)
        )
        cfg = ast.literal_eval(node.value)
        channel_cfg = cfg["supervisor"]["channel"]
        self.assertIs(channel_cfg["enabled"], True)
        self.assertIs(channel_cfg["allow_execute"], False)
        self.assertEqual(0.7, channel_cfg["min_confidence"])
        self.assertEqual(15, channel_cfg["timeout"])


if __name__ == "__main__":
    unittest.main()
