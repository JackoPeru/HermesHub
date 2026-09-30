#!/usr/bin/env python3
"""Hub shadow-session sync driver (stdlib only).

Finds the installed hermes_hub_gateway package and runs the shadow import:
foreign agent sessions (e.g. official desktop app) become shadow Hub
conversations. Safe to run every few minutes; the import itself is a no-op
when the response store is unchanged. Prints the applied change count.
"""

from __future__ import annotations

import os
import sys


CANDIDATES = (
    "/home/matteo/.local/share/hermes-hub-gateway/current",
    "/home/matteo/.hermes/hh187-patcher",
    "/home/matteo/.hermes/hermes-agent",
)


def main() -> int:
    for candidate in CANDIDATES:
        package_dir = os.path.join(candidate, "hermes_hub_gateway")
        module_file = os.path.join(
            package_dir, "adapters", "hermes", "shadow_sessions.py"
        )
        if os.path.isfile(module_file):
            sys.path.insert(0, candidate)
            break
    else:
        print("shadow-sync: no hermes_hub_gateway package found", flush=True)
        return 1
    try:
        from hermes_hub_gateway.adapters.hermes.shadow_sessions import (
            maybe_import_shadows,
        )
    except Exception as exc:
        print(f"shadow-sync: import failed: {exc!r}", flush=True)
        return 1
    try:
        count = maybe_import_shadows()
    except Exception as exc:
        print(f"shadow-sync: import failed: {exc!r}", flush=True)
        return 1
    print(f"shadow-sync: applied {count}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
