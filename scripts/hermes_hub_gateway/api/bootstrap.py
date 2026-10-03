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
    runs_resume_patched: str | None = None
    runs_resume_changed: bool = False
    runs_resume_changes: tuple[str, ...] = ()
    detach_changes: tuple[str, ...] = ()
    detach_routes_changes: tuple[str, ...] = ()
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
    if runs_original is None:
        runs_resume_patched: str | None = None
        runs_resume_changed = False
        runs_resume_changes: tuple[str, ...] = (
            "run lifecycle module not found; persistent resume patch skipped",)
    else:
        runs_resume_patched, resume_changes = legacy_patcher._patch_runs_resume(runs_original)
        runs_resume_changed = runs_resume_patched != runs_original
        runs_resume_changes = tuple(resume_changes)
    if route_original is None and runs_original is None:
        patched, changes = legacy_patcher._patch_text(original)
        route_patched = None
    else:
        patched, route_patched, changes = legacy_patcher._patch_source_texts(
            original,
            route_original,
            runs_original,
        )
    # Continue-on-disconnect applies on top; a layout without the upstream
    # anchors skips detach only (other patches still apply: unknown body
    # fields are ignored by older gateways, degrading to kill-on-disconnect).
    try:
        patched, detach_changes = legacy_patcher._patch_detach_api_server(patched)
    except legacy_patcher.PatchError as exc:
        detach_changes = (f"continue-on-disconnect skipped for api_server: {exc}",)
    detach_routes_changes: tuple[str, ...] = ()
    detach_routes_base = route_patched if route_patched is not None else route_original
    if route_target is not None and detach_routes_base is not None:
        try:
            route_patched, detach_routes_changes = legacy_patcher._patch_detach_openai_routes(
                detach_routes_base)
        except legacy_patcher.PatchError as exc:
            detach_routes_changes = (f"continue-on-disconnect skipped for openai routes: {exc}",)
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
            runs_resume_patched=runs_resume_patched,
            runs_resume_changed=runs_resume_changed,
            runs_resume_changes=runs_resume_changes,
            detach_changes=detach_changes,
            detach_routes_changes=detach_routes_changes,
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
        runs_resume_patched=runs_resume_patched,
        runs_resume_changed=runs_resume_changed,
        runs_resume_changes=runs_resume_changes,
        detach_changes=detach_changes,
        detach_routes_changes=detach_routes_changes,
        helper_target=resolved_helper,
        helper_patched=helper_patched,
        helper_changes=tuple(helper_changes),
    )


def _actionable_detach_changes(plan: GatewayPatchPlan) -> tuple[str, ...]:
    """Detach changes that actually modify files (skip notices are inert)."""
    return tuple(
        change
        for change in (*plan.detach_changes, *plan.detach_routes_changes)
        if "skipped for " not in change
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
        for change in plan.runs_resume_changes:
            print(f"- {change}")
    else:
        print("- run lifecycle module not found; persistent resume patch skipped")
    for change in (*plan.detach_changes, *plan.detach_routes_changes):
        print(f"- {change}")


def run_patcher(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", help="Path to gateway/platforms/api_server.py")
    parser.add_argument("--check", action="store_true", help="Validate patchability without writing")
    args = parser.parse_args(argv)

    target = legacy_patcher._find_target(args.target)
    plan = build_patch_plan(target)
    actionable_resume_changes = tuple(
        change for change in plan.runs_resume_changes
        if not change.endswith("persistent resume patch skipped")
    )
    actionable_detach_changes = _actionable_detach_changes(plan)
    if args.check:
        _print_check(plan)
        # Exit status drives rehub-patch.sh: 0 = already patched (skip),
        # 1 = changes pending (apply), anything else = error.
        if plan.changes or plan.actionable_helper_changes or actionable_resume_changes or actionable_detach_changes:
            return 1
        return 0

    if not plan.target_changed and not plan.route_changed and not plan.actionable_helper_changes and not actionable_resume_changes and not actionable_detach_changes:
        print(f"Hermes native gateway already patched: {plan.target}")
        if plan.route_target is not None:
            print(f"Hermes OpenAI route module already patched: {plan.route_target}")
        if plan.helper_target is not None:
            print(f"Hermes Agent stream helper already patched: {plan.helper_target}")
        if plan.runs_target is not None:
            print(f"Hermes run lifecycle module already patched: {plan.runs_target}")
        return 0

    updates: list[tuple[Path, str]] = []
    if plan.target_changed:
        updates.append((plan.target, plan.patched))
    if plan.route_target is not None and plan.route_changed and plan.route_patched is not None:
        updates.append((plan.route_target, plan.route_patched))
    if plan.helper_target is not None and plan.actionable_helper_changes and plan.helper_patched is not None:
        updates.append((plan.helper_target, plan.helper_patched))
    if plan.runs_target is not None and actionable_resume_changes and plan.runs_resume_patched is not None:
        updates.append((plan.runs_target, plan.runs_resume_patched))
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
    if plan.runs_target is not None and actionable_resume_changes:
        print(f"Hermes run lifecycle module patched: {plan.runs_target}")
        print(f"Backup: {backups[plan.runs_target]}")
        for change in plan.runs_resume_changes:
            print(f"- {change}")
    elif plan.runs_target is not None:
        print(f"Hermes run lifecycle module already patched: {plan.runs_target}")
    else:
        print("- run lifecycle module not found; persistent resume patch skipped")
    for change in actionable_detach_changes:
        print(f"- {change}")
    return 0
