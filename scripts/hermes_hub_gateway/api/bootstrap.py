"""CLI composition for the upstream Hermes gateway adapter.

The bootstrap owns target discovery, patch planning, and transaction wiring;
the upstream-specific transformations live in ``adapters.hermes`` so the
public patch script stays a thin launcher.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path

from ..adapters.hermes import legacy_patcher


@dataclass(frozen=True, slots=True)
class GatewayPatchPlan:
    target: Path
    patched: str
    changes: tuple[str, ...]
    target_changed: bool = False
    route_target: Path | None = None
    route_patched: str | None = None
    route_changed: bool = False
    runs_target: Path | None = None
    runs_preserves_active_tasks: bool = False
    helper_target: Path | None = None
    helper_patched: str | None = None
    helper_changes: tuple[str, ...] = ()

    @property
    def actionable_helper_changes(self) -> tuple[str, ...]:
        return tuple(
            change
            for change in self.helper_changes
            if not change.endswith("not found; raw llama progress passthrough skipped")
        )


def build_patch_plan(target: Path, helper_target: Path | None = None) -> GatewayPatchPlan:
    original = target.read_text(encoding="utf-8")
    route_target = legacy_patcher._find_openai_routes_target(target)
    route_original = route_target.read_text(encoding="utf-8") if route_target is not None else None
    runs_target = legacy_patcher._find_runs_module_target(target)
    runs_original = runs_target.read_text(encoding="utf-8") if runs_target is not None else None
    if route_original is None and runs_original is None:
        patched, changes = legacy_patcher._patch_text(original)
        route_patched = None
    else:
        patched, route_patched, changes = legacy_patcher._patch_source_texts(
            original,
            route_original,
            runs_original,
        )
    resolved_helper = helper_target if helper_target is not None else legacy_patcher._find_agent_chat_completion_helpers(target)
    if resolved_helper is None:
        return GatewayPatchPlan(
            target=target,
            patched=patched,
            changes=tuple(changes),
            target_changed=patched != original,
            route_target=route_target,
            route_patched=route_patched,
            route_changed=route_patched != route_original if route_patched is not None else False,
            runs_target=runs_target,
            runs_preserves_active_tasks=(
                legacy_patcher._runs_module_preserves_active_tasks(runs_original)
                if runs_original is not None else False
            ),
            helper_changes=("agent chat_completion_helpers.py not found; raw llama progress passthrough skipped",),
        )

    helper_original = resolved_helper.read_text(encoding="utf-8")
    helper_patched, helper_changes = legacy_patcher._patch_agent_chat_completion_helpers(helper_original)
    return GatewayPatchPlan(
        target=target,
        patched=patched,
        changes=tuple(changes),
        target_changed=patched != original,
        route_target=route_target,
        route_patched=route_patched,
        route_changed=route_patched != route_original if route_patched is not None else False,
        runs_target=runs_target,
        runs_preserves_active_tasks=(
            legacy_patcher._runs_module_preserves_active_tasks(runs_original)
            if runs_original is not None else False
        ),
        helper_target=resolved_helper,
        helper_patched=helper_patched,
        helper_changes=tuple(helper_changes),
    )


def _print_check(plan: GatewayPatchPlan) -> None:
    state = "already patched" if not plan.changes and not plan.actionable_helper_changes else "patchable"
    print(f"Hermes native gateway patch {state}: {plan.target}")
    for change in plan.changes:
        print(f"- {change}")
    if plan.helper_target is not None:
        print(f"Hermes Agent stream helper: {plan.helper_target}")
        for change in plan.helper_changes:
            print(f"- {change}")
    else:
        print("- agent chat_completion_helpers.py not found; raw llama progress passthrough skipped")
    if plan.route_target is not None:
        print(f"Hermes OpenAI route module: {plan.route_target}")
    if plan.runs_target is not None:
        state = "preserves active run tasks" if plan.runs_preserves_active_tasks else "requires lifecycle patch"
        print(f"Hermes run lifecycle module {state}: {plan.runs_target}")


def run_patcher(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", help="Path to gateway/platforms/api_server.py")
    parser.add_argument("--check", action="store_true", help="Validate patchability without writing")
    args = parser.parse_args(argv)

    target = legacy_patcher._find_target(args.target)
    plan = build_patch_plan(target)
    if args.check:
        _print_check(plan)
        # Exit status drives rehub-patch.sh: 0 = already patched (skip),
        # 1 = changes pending (apply), anything else = error.
        if plan.changes or plan.actionable_helper_changes:
            return 1
        return 0

    if not plan.target_changed and not plan.route_changed and not plan.actionable_helper_changes:
        print(f"Hermes native gateway already patched: {plan.target}")
        if plan.route_target is not None:
            print(f"Hermes OpenAI route module already patched: {plan.route_target}")
        if plan.helper_target is not None:
            print(f"Hermes Agent stream helper already patched: {plan.helper_target}")
        return 0

    updates: list[tuple[Path, str]] = []
    if plan.target_changed:
        updates.append((plan.target, plan.patched))
    if plan.route_target is not None and plan.route_changed and plan.route_patched is not None:
        updates.append((plan.route_target, plan.route_patched))
    if plan.helper_target is not None and plan.actionable_helper_changes and plan.helper_patched is not None:
        updates.append((plan.helper_target, plan.helper_patched))
    backups = legacy_patcher._write_compiled_transaction(updates)

    if plan.target_changed:
        print(f"Hermes native gateway patched: {plan.target}")
        print(f"Backup: {backups[plan.target]}")
    else:
        print(f"Hermes native gateway already patched: {plan.target}")
    if plan.route_target is not None:
        if plan.route_changed:
            print(f"Hermes OpenAI route module patched: {plan.route_target}")
            print(f"Backup: {backups[plan.route_target]}")
        else:
            print(f"Hermes OpenAI route module already patched: {plan.route_target}")
    for change in plan.changes:
        print(f"- {change}")
    if plan.helper_target is not None and plan.actionable_helper_changes:
        print(f"Hermes Agent stream helper patched: {plan.helper_target}")
        print(f"Backup: {backups[plan.helper_target]}")
        for change in plan.helper_changes:
            print(f"- {change}")
    elif plan.helper_target is not None:
        print(f"Hermes Agent stream helper already patched: {plan.helper_target}")
    else:
        print("- agent chat_completion_helpers.py not found; raw llama progress passthrough skipped")
    return 0
