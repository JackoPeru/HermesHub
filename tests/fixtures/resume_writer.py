"""Checkpoint storm writer for crash-atomicity tests (killed mid-write)."""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                "..", "scripts", "hermes_hub_gateway"))

import run_resume as rr

store = rr.ResumeStore(os.environ["RESUME_TEST_DB"])
iters = int(os.environ.get("RESUME_TEST_ITERS", "200000"))
for i in range(iters):
    run_id = f"run_{i % 25:03d}"
    store.track_run(run_id, f"sess_{i % 5}", f"goal {i}", i, {"model": "m"})
    store.record_started(run_id, "exec_command", {"command": f"step-{i}"})
    store.record_completed(run_id, "exec_command", {"command": f"step-{i}"},
                           result=f"out-{i}", duration=0.01, history_count=i)
    if i % 50 == 0:
        store.finish(run_id, "completed")
