"""Contratto "Workflow live" (richiesta utente): vedere e toccare il workflow
come nella pagina web di Comfy, SENZA mandare il manager in modalita' manuale.

Due endpoint manager nuovi (0.6.279):
- POST /jobs/{id}/workflow: riscrive il workflow di un job QUEUED (o ripristina
  in coda un cancelled/failed). Non tocca mai stato/desired del manager.
- POST /jobs/{id}/resubmit: interrompe il job in esecuzione (cancel mirror,
  come /cancel) e ne mette in coda una COPIA su un NUOVO job_id (mai lo stesso:
  il tracker del vecchio job vede "cancelled" e si ferma al poll successivo).

Contract test AST-based sul sorgente, pattern test_manager_error_fallback.py:
se una di queste garanzie viene lessata, il test fallisce davvero.
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


class WorkflowLiveEndpointTests(unittest.TestCase):
    def setUp(self):
        self._tree = ast.parse(MANAGER.read_text(encoding="utf-8"))

    # ---- l'intervento utente NON cambia la modalita' del manager ----

    def test_user_endpoints_never_call_set_state(self):
        for name in ("get_job_workflow_api", "set_job_workflow_api", "resubmit_job_api"):
            source = _func_source(self._tree, name)
            self.assertNotIn("set_state", source, f"{name}: niente set_state")
            self.assertNotIn("desired_mode", source, f"{name}: niente desired_mode")
            self.assertNotIn("media_cooldown_until", source, f"{name}: niente cooldown")
            self.assertNotIn("restore_llm", source, f"{name}: niente restore LLM")

    # ---- POST /jobs/{id}/workflow: solo coda, validazione obbligatoria ----

    def test_set_job_workflow_gates(self):
        source = _func_source(self._tree, "set_job_workflow_api")
        # running (o current_job con prompt vivo) -> 409 verso /resubmit
        self.assertIn("usa /jobs/{id}/resubmit", source)
        self.assertIn("_state.get('current_job')", source)
        self.assertIn("_state.get('current_prompt_id')", source)
        # stati consentiti: solo queued/cancelled/failed
        self.assertIn("not in ('queued', 'cancelled', 'failed')", source)
        # il workflow proposto passa SEMPRE da validate_workflow
        self.assertIn("problem = validate_workflow(workflow)", source)
        self.assertIn("raise HTTPException(400, problem)", source)
        # la scritta aggiorna la riga job in coda, non il tracking
        self.assertIn("update_job(job_id, workflow=json.dumps(workflow), status='queued'", source)

    def test_get_job_workflow_returns_workflow_dict(self):
        source = _func_source(self._tree, "get_job_workflow_api")
        self.assertIn("_read_job_workflow(job_id)", source)
        self.assertIn("raise HTTPException(404, 'unknown job')", source)
        self.assertIn("raise HTTPException(409, 'workflow unreadable')", source)

    # ---- POST /jobs/{id}/resubmit: interrupt su nuovo job_id, mai riga unica ----

    def test_resubmit_interrupts_and_clones_new_id(self):
        source = _func_source(self._tree, "resubmit_job_api")
        # interruzione specchio di cancel_job (stesso pattern, zero mode)
        self.assertIn("_state['current_job'] = None", source)
        self.assertIn("await comfy_cancel", source)
        self.assertIn("superseded by resubmit", source)
        # la coda riparte su UN NUOVO job_id creato con create_job
        self.assertIn("new_job = create_job(", source)
        self.assertIn("'cancelled_job': job_id", source)
        self.assertIn("'queued_job': new_job", source)
        # mai il vecchio id rimesso in coda (niente update_job(...status='queued') qui)
        self.assertNotIn("update_job(job_id, status='queued'", source)
        # un job QUEUED non ancora inviato va modificato con /workflow, non clonato
        self.assertIn("usa /jobs/{id}/workflow", source)
        # il workflow replacemente passa validate_workflow anche qui
        self.assertIn("problem = validate_workflow(workflow)", source)

    def test_read_job_workflow_never_returns_non_dict(self):
        source = _func_source(self._tree, "_read_job_workflow")
        self.assertIn("SELECT workflow FROM jobs WHERE id=?", source)
        self.assertIn("_db_lock", source)


if __name__ == "__main__":
    unittest.main()
