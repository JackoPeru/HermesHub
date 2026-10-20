"""Contratto job agente: GET /jobs deve esporre i campi che la pagina Comfy
Windows mostra in JOB AGENTE (lista dei job autonomi anche a Comfy fermo).

Il client parsa: job_id, kind, status, preset, backend, model, phase,
progress, error, result_paths, parameters.media_urls, created_at, updated_at.
Qui eseguiamo job_row() dal sorgente con una riga finta e verifichiamo forma
e valori (stessi stati che il manager scrive: queued/running/done/failed).
"""

import ast
import json
import sqlite3
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

REQUIRED_FIELDS = {
    "job_id", "kind", "status", "preset", "backend", "model", "parameters",
    "progress", "result_paths", "error", "retries", "vram_peak_mb", "phase",
    "created_at", "updated_at",
}


def load_job_row():
    src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    funcs = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "job_row"]
    assert len(funcs) == 1, "job_row non trovata nel manager"
    ns: dict = {"json": json, "sqlite3": sqlite3}
    exec(compile(ast.Module(body=funcs, type_ignores=[]), "job_row", "exec"), ns)  # noqa: S102 - test su sorgente repo
    return ns["job_row"]


def fake_row(**over):
    base = {
        "id": "1602bba676e0", "kind": "video", "status": "running",
        "workflow": "{}", "progress": 0.5, "result_paths": "[]", "error": "",
        "retries": 0, "created": 1760989000.0, "updated": 1760989100.0,
        "preset": "journey_video_reframe916", "backend": "comfy",
        "model": "minimax_h3_ref2va", "params": '{"smart": false}',
        "vram_peak_mb": 1234.0, "phase": "executing",
    }
    base.update(over)
    return tuple(base[c] for c in (
        "id", "kind", "status", "workflow", "progress", "result_paths",
        "error", "retries", "created", "updated", "preset", "backend",
        "model", "params", "vram_peak_mb", "phase"))


class AgentJobsContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.job_row = load_job_row()

    def test_job_row_exposes_client_fields(self) -> None:
        job = type(self).job_row(fake_row())
        self.assertEqual(set(), REQUIRED_FIELDS - set(job.keys()))
        self.assertEqual("1602bba676e0", job["job_id"])
        self.assertEqual("running", job["status"])
        self.assertEqual("journey_video_reframe916", job["preset"])
        self.assertEqual(0.5, job["progress"])
        self.assertEqual("executing", job["phase"])
        self.assertEqual([], job["result_paths"])
        self.assertEqual({"smart": False}, job["parameters"])
        self.assertEqual(1760989000.0, job["created_at"])

    def test_job_row_done_with_results_and_media_urls(self) -> None:
        job = type(self).job_row(fake_row(
            status="done", progress=1.0, phase="done",
            result_paths=json.dumps(["/m/videos/abc/x.mp4", "/m/videos/abc/x_web.mp4"]),
            params=json.dumps({"smart": True, "media_urls": ["/v1/media/abc_x.mp4"]})))
        self.assertEqual("done", job["status"])
        self.assertEqual(2, len(job["result_paths"]))
        self.assertEqual(["/v1/media/abc_x.mp4"], job["parameters"]["media_urls"])

    def test_job_row_failed_keeps_error(self) -> None:
        job = type(self).job_row(fake_row(status="failed", error="job timeout", phase="executing"))
        self.assertEqual("failed", job["status"])
        self.assertIn("timeout", job["error"])


if __name__ == "__main__":
    unittest.main()
