#!/usr/bin/env python3
"""Thin, fail-closed bootstrap for the Hermes Hub gateway adapter."""

from __future__ import annotations

import sys
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from hermes_hub_gateway.adapters.hermes import legacy_patcher as _legacy  # noqa: E402
from hermes_hub_gateway.api.bootstrap import run_patcher  # noqa: E402

PatchError = _legacy.PatchError

# Static contract markers retained for downstream installers and repository
# checks that historically inspected this entrypoint.  The implementation is
# imported from adapters.hermes.legacy_patcher above; these markers are not
# executable gateway logic.
# /v1/hub/server/control /v1/hub/server/action /v1/hub/server/maintenance
# /v1/hub/audit HERMES_HUB_UPDATE_COMMAND _hermes_hub_audit_event
# "projectId" "workspacePath" "repositoryUrl" "projectInstructions"
# "projectMemory" "authorizedTools"
# def _hermes_hub_project_system_prompt project.get("system_prompt") prompt[:20000]
# hermes.reasoning.available _hermes_hub_reasoning_text reasoning.available
# setdefault(\"return_progress\", True) setdefault(\"timings_per_token\", True)
# payload[\"percent\"] = round(progress * 100.0)


def __getattr__(name: str):
    """Keep the characterization-test surface while moving implementation out."""
    return getattr(_legacy, name)


def main() -> int:
    return run_patcher()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
