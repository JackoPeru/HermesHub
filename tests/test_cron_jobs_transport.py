"""Round-trip di persistenza Cron sul trasporto esterno /api/jobs.

Specchio fedele di gateway/platforms/api_server.py su main (verificato 2026-09-17):
- POST /api/jobs accetta SOLO name*, schedule*, prompt, deliver, skills, repeat,
  paused/paused_reason; tutto il resto viene ignorato (non letto dal body).
- PATCH /api/jobs/{id} applica la whitelist
  {name, schedule, prompt, deliver, skills, skill, repeat, enabled}; body senza
  campi validi -> 400; resto scartato in silenzio.
- GET lista -> {"jobs": [...]}; GET/POST/PATCH/DELETE singolo -> {"job": {...}} o 404.
- POST .../pause|resume|run, DELETE .../rimuove.
- Routing profilo via /p/<profile>/ con chiave del profilo; chiave default su
  prefisso nominato -> 401 (fail-closed); job isolati per profilo.
- prompt: solo cap lunghezza + injection scan (qui: cap 5000, prompt vuoto OK).

La superficie dashboard /api/cron/jobs (model/provider/reasoning/workdir/script/
no_agent/monitor/context_from/enabled_toolsets/...) vive su listener FastAPI
separato con auth dashboard e ?profile=: NON usata da HermesHub, quindi quei
campi non compaiono nella UI Cron (verificato in audit su main).
"""
from __future__ import annotations

import copy
import re
import unittest

UPDATE_ALLOWED_FIELDS = {"name", "schedule", "prompt", "deliver", "skills", "skill", "repeat", "enabled"}
JOB_ID_RE = re.compile(r"[a-f0-9]{12}")
MAX_PROMPT_LENGTH = 5000

# Campi dashboard-only (superficie /api/cron/jobs, non /api/jobs): il server li scarta.
DASHBOARD_ONLY_FIELDS = {
    "model", "provider", "model_provider", "reasoning_effort", "reasoningEffort",
    "workdir", "script", "no_agent", "monitor", "monitor_script", "monitor_url",
    "context_from", "continuity", "enabled_toolsets", "failure_deliver",
    "base_url", "persistent_memory",
}


class FakeApiJobsServer:
    """Fake minimo ma fedele della semantica /api/jobs su main."""

    def __init__(self) -> None:
        # profile -> {job_id: job dict}; chiavi API per profilo.
        self.keys = {"default": "key-default", "coder": "key-coder"}
        self.jobs: dict[str, dict[str, dict]] = {"default": {}, "coder": {}}
        self._counter = 0

    # -- plumbing profilo/auth -------------------------------------------------
    def _route(self, path: str, api_key: str | None):
        m = re.fullmatch(r"(?:/p/([a-z0-9][a-z0-9_-]*))?(/api/jobs(?:/.*)?)", path)
        assert m, f"path non valido: {path}"
        profile = m.group(1) or "default"
        rest = m.group(2)
        if profile != "default" and api_key != self.keys.get(profile):
            return None, (401, {"error": "unauthorized profile key"})
        if profile == "default" and api_key != self.keys["default"]:
            return None, (401, {"error": "unauthorized"})
        return (profile, rest), None

    def _new_id(self) -> str:
        self._counter += 1
        return f"{self._counter:012x}"[-12:]

    # -- handlers ---------------------------------------------------------------
    def handle(self, method: str, path: str, api_key: str | None, body: dict | None = None):
        routed, err = self._route(path, api_key)
        if err:
            return err
        profile, rest = routed
        store = self.jobs.setdefault(profile, {})
        body = body or {}

        if rest == "/api/jobs" and method == "GET":
            return 200, {"jobs": list(store.values())}
        if rest == "/api/jobs" and method == "POST":
            name = (body.get("name") or "").strip()
            schedule = (body.get("schedule") or "").strip()
            prompt = body.get("prompt", "")
            if not name:
                return 400, {"error": "Name is required"}
            if not schedule:
                return 400, {"error": "Schedule is required"}
            if len(str(prompt)) > MAX_PROMPT_LENGTH:
                return 400, {"error": "Prompt too long"}
            repeat = body.get("repeat")
            if repeat is not None and (not isinstance(repeat, int) or repeat < 1):
                return 400, {"error": "Repeat must be a positive integer"}
            job_id = self._new_id()
            job = {
                "id": job_id,
                "name": name,
                "schedule": schedule,
                "prompt": prompt,
                "deliver": body.get("deliver", "local"),
                "skills": list(body.get("skills") or []),
                "enabled": not bool(body.get("paused", False)),
                "state": "paused" if body.get("paused") else "active",
            }
            if repeat is not None:
                job["repeat"] = repeat
            # Dashboard-only: MAI letti dal body -> mai persistiti.
            for key in DASHBOARD_ONLY_FIELDS:
                assert key not in job, key
            store[job_id] = job
            return 200, {"job": copy.deepcopy(job)}

        m = re.fullmatch(r"/api/jobs/([A-Za-z0-9_-]+)(?:/(pause|resume|run))?", rest)
        assert m, f"path job non valido: {rest}"
        job_id, action = m.group(1), m.group(2)
        if not JOB_ID_RE.fullmatch(job_id) or job_id not in store:
            return 404, {"error": "Job not found"}
        job = store[job_id]
        if action == "pause" and method == "POST":
            job["enabled"] = False
            job["state"] = "paused"
            return 200, {"job": copy.deepcopy(job)}
        if action == "resume" and method == "POST":
            job["enabled"] = True
            job["state"] = "active"
            return 200, {"job": copy.deepcopy(job)}
        if action == "run" and method == "POST":
            job["last_status"] = "triggered"
            return 200, {"job": copy.deepcopy(job)}
        if action is None and method == "GET":
            return 200, {"job": copy.deepcopy(job)}
        if action is None and method == "DELETE":
            del store[job_id]
            return 200, {"job": {"id": job_id, "deleted": True}}
        if action is None and method == "PATCH":
            sanitized = {k: v for k, v in body.items() if k in UPDATE_ALLOWED_FIELDS}
            if not sanitized:
                return 400, {"error": "No valid fields to update"}
            if "skill" in sanitized and "skills" not in sanitized:
                sanitized["skills"] = sanitized.pop("skill")
            job.update(sanitized)
            if "enabled" in sanitized:
                job["state"] = "active" if sanitized["enabled"] else "paused"
            return 200, {"job": copy.deepcopy(job)}
        return 405, {"error": "method not allowed"}


class CronJobsTransportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.srv = FakeApiJobsServer()
        self.key = "key-default"

    def create(self, **fields):
        body = {"name": "N", "schedule": "0 8 * * *", "prompt": "P", **fields}
        code, res = self.srv.handle("POST", "/api/jobs", self.key, body)
        self.assertEqual(200, code, res)
        return res["job"]

    def read(self, job_id: str):
        code, res = self.srv.handle("GET", f"/api/jobs/{job_id}", self.key)
        self.assertEqual(200, code, res)
        return res["job"]

    def test_create_requires_name_and_schedule(self) -> None:
        code, _ = self.srv.handle("POST", "/api/jobs", self.key, {"schedule": "x", "prompt": "p"})
        self.assertEqual(400, code)
        code, _ = self.srv.handle("POST", "/api/jobs", self.key, {"name": "n", "prompt": "p"})
        self.assertEqual(400, code)

    def test_roundtrip_supported_fields(self) -> None:
        job = self.create(deliver="bot-chat:coder", skills=["a", "b"])
        back = self.read(job["id"])
        self.assertEqual("bot-chat:coder", back["deliver"])
        self.assertEqual(["a", "b"], back["skills"])

    def test_update_each_whitelisted_field_roundtrips(self) -> None:
        job = self.create()
        for field, value in [
            ("name", "Rinominato"),
            ("schedule", "0 9 * * *"),
            ("prompt", "Nuovo prompt"),
            ("deliver", "telegram"),
            ("skills", ["x"]),
            ("repeat", 3),
            ("enabled", False),
        ]:
            code, res = self.srv.handle("PATCH", f"/api/jobs/{job['id']}", self.key, {field: value})
            self.assertEqual(200, code, (field, res))
            self.assertEqual(value, self.read(job["id"])[field], field)

    def test_update_empty_body_is_400(self) -> None:
        job = self.create()
        code, _ = self.srv.handle("PATCH", f"/api/jobs/{job['id']}", self.key, {})
        self.assertEqual(400, code)

    def test_dashboard_only_fields_are_dropped_on_create(self) -> None:
        dropped = {
            "model": "m", "provider": "p", "reasoning_effort": "high",
            "workdir": "/tmp", "script": "s.sh", "no_agent": True,
            "monitor_script": "mon.sh", "context_from": ["self"],
            "enabled_toolsets": ["core"], "continuity": True,
        }
        job = self.create(**dropped)
        back = self.read(job["id"])
        for key in dropped:
            self.assertNotIn(key, back, f"il server dovrebbe scartare {key}")

    def test_dashboard_only_fields_are_dropped_on_update(self) -> None:
        job = self.create()
        code, res = self.srv.handle(
            "PATCH", f"/api/jobs/{job['id']}", self.key,
            {"name": "Ok", "model": "m", "reasoning_effort": "max", "workdir": "/x"},
        )
        self.assertEqual(200, code, res)
        back = self.read(job["id"])
        self.assertEqual("Ok", back["name"])
        for key in ("model", "reasoning_effort", "workdir"):
            self.assertNotIn(key, back)

    def test_lifecycle_pause_resume_run_delete(self) -> None:
        job = self.create()
        for action, check in [("pause", False), ("resume", True)]:
            code, res = self.srv.handle("POST", f"/api/jobs/{job['id']}/{action}", self.key, {})
            self.assertEqual(200, code, res)
            self.assertEqual(check, self.read(job["id"])["enabled"])
        code, _ = self.srv.handle("POST", f"/api/jobs/{job['id']}/run", self.key, {})
        self.assertEqual(200, code)
        code, _ = self.srv.handle("DELETE", f"/api/jobs/{job['id']}", self.key, {})
        self.assertEqual(200, code)
        code, _ = self.srv.handle("GET", f"/api/jobs/{job['id']}", self.key)
        self.assertEqual(404, code)

    def test_unknown_job_is_404(self) -> None:
        code, _ = self.srv.handle("GET", "/api/jobs/abcdef012345", self.key)
        self.assertEqual(404, code)

    def test_profile_isolation_fail_closed(self) -> None:
        # Chiave default su prefisso nominato -> 401, mai riuso accidentale.
        code, _ = self.srv.handle("GET", "/p/coder/api/jobs", "key-default")
        self.assertEqual(401, code)
        code, res = self.srv.handle(
            "POST", "/p/coder/api/jobs", "key-coder",
            {"name": "C", "schedule": "0 8 * * *", "prompt": "p"},
        )
        self.assertEqual(200, code, res)
        # Il job del profilo coder non e' visibile dal default.
        code, res = self.srv.handle("GET", "/api/jobs", self.key)
        self.assertEqual(200, code)
        self.assertEqual([], res["jobs"])
        code, res = self.srv.handle("GET", "/p/coder/api/jobs", "key-coder")
        self.assertEqual(1, len(res["jobs"]))


if __name__ == "__main__":
    unittest.main()
