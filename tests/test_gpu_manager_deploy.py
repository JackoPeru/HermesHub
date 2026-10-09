"""Offline regression tests for GPU manager deployment and training hold."""
from __future__ import annotations

import ast
import os
import shutil
import subprocess
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEPLOY_SCRIPT = REPO / "gpu-manager" / "scripts" / "deploy-gpu-manager.sh"
MANAGER = REPO / "gpu-manager" / "manager.py"


def _bash_executable() -> str | None:
    found = shutil.which("bash")
    if found:
        return found
    candidate = Path(r"C:\Program Files\Git\bin\bash.exe")
    return str(candidate) if candidate.is_file() else None


BASH = _bash_executable()


def _bash_path(path: Path) -> str:
    if os.name != "nt":
        return str(path)
    if BASH:
        cygpath = Path(BASH).parent.parent / "usr" / "bin" / "cygpath.exe"
        if cygpath.is_file():
            return subprocess.check_output(
                [str(cygpath), "-u", str(path)], text=True
            ).strip()
    return str(path).replace("\\", "/")


def run_deploy_shell(body: str) -> subprocess.CompletedProcess[str]:
    if not BASH:
        raise AssertionError("Bash is required for the offline deploy tests")
    script = 'source "$1"\n' + body
    return subprocess.run(
        [BASH, "--noprofile", "--norc", "-c", script, "deploy-test",
         _bash_path(DEPLOY_SCRIPT)],
        text=True,
        capture_output=True,
        timeout=20,
        env=os.environ.copy(),
    )


def hold_function(store):
    tree = ast.parse(MANAGER.read_text(encoding="utf-8"))
    target = next(
        node for node in tree.body
        if isinstance(node, ast.FunctionDef)
        and node.name == "_character_training_hold"
    )
    namespace = {"_character_store": store}
    module = ast.Module(body=[target], type_ignores=[])
    exec(compile(module, str(MANAGER), "exec"), namespace)  # noqa: S102
    return namespace["_character_training_hold"]


class TestCharacterTrainingHold(unittest.TestCase):
    def test_optional_store_and_active_job_states(self):
        self.assertFalse(hold_function(None)())
        self.assertFalse(hold_function(type("Store", (), {
            "active_training_jobs": lambda self: []
        })())())
        self.assertTrue(hold_function(type("Store", (), {
            "active_training_jobs": lambda self: [{"status": "training"}]
        })())())

    def test_store_error_holds_gpu(self):
        # Store illeggibile: decide il lockfile (niente DB), non il panico.
        # - lock vivo -> hold (mai guerra di VRAM col trainer);
        # - nessun lock/stale -> guida normale (non murare chat e media
        #   per un guasto al db ausiliario character).
        import sys
        import tempfile

        sys.path.insert(0, str(REPO / "gpu-manager"))
        try:
            from character_id import training as hcid_training

            real_pid_alive = hcid_training.pid_alive
            with tempfile.TemporaryDirectory() as tmp:
                os.environ["HCID_ROOT"] = tmp

                class BrokenStore:
                    def active_training_jobs(self):
                        raise OSError("offline store")

                try:
                    # Nessun lock: guida normale.
                    self.assertFalse(hold_function(BrokenStore())())
                    # Lock stale (pid morto): guida normale.
                    hcid_training.write_lock(tmp, {"job_id": "j", "pid": 0})
                    self.assertFalse(hold_function(BrokenStore())())
                    # Lock vivo: hold.
                    hcid_training.write_lock(tmp, {"job_id": "j", "pid": 424242})
                    hcid_training.pid_alive = lambda pid: pid == 424242
                    self.assertTrue(hold_function(BrokenStore())())
                finally:
                    hcid_training.pid_alive = real_pid_alive
                    os.environ.pop("HCID_ROOT", None)
        finally:
            sys.path.remove(str(REPO / "gpu-manager"))


SHELL_SANDBOX = r"""
TEST_ROOT=$(mktemp -d /tmp/hgm-deploy-test.XXXXXXXX)
[[ "$TEST_ROOT" == /tmp/hgm-deploy-test.* ]] || exit 90
trap 'rm -rf -- "$TEST_ROOT"' EXIT
mkdir -p "$TEST_ROOT/bin" "$TEST_ROOT/tmp"
OP_LOG="$TEST_ROOT/ops.log"
SYSTEMCTL_LOG="$TEST_ROOT/systemctl.log"
: > "$OP_LOG"
export OP_LOG SYSTEMCTL_LOG
REMOTE_DIR="$TEST_ROOT/opt/hermes/gpu-manager"
BACKUP_DIR="$REMOTE_DIR/backup-case"
REMOTE_STAGE="$TEST_ROOT/tmp/hermes-gpu-manager.stageone"
VENV_PY="$TEST_ROOT/bin/python-stub"
mkdir -p "$REMOTE_DIR" "$REMOTE_STAGE/character_id" "$TEST_ROOT/opt/hermes/character-id"
printf 'persistent-data\n' > "$TEST_ROOT/opt/hermes/character-id/keep.txt"
cat > "$VENV_PY" <<'SH'
#!/usr/bin/env bash
case "$*" in
  *compileall*) exit 37 ;;
  *) exit 0 ;;
esac
SH
chmod +x "$VENV_PY"
cat > "$TEST_ROOT/bin/systemctl" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SYSTEMCTL_LOG"
SH
chmod +x "$TEST_ROOT/bin/systemctl"
PATH="$TEST_ROOT/bin:$PATH"
export PATH

mock_ssh() {
  local command="$1" payload operation target name
  if [[ "$command" == 'sudo bash -s' ]]; then
    payload="$(cat)"
    case "$payload" in
      *'# HGM_OP=backup'*) operation=backup ;;
      *'# HGM_OP=install'*) operation=install ;;
      *'# HGM_OP=rollback'*) operation=rollback ;;
      *) echo 'unknown remote script' >&2; return 91 ;;
    esac
    printf '%s\n' "$operation" >> "$OP_LOG"
    bash -euo pipefail -c "$payload"
    return $?
  fi
  case "$command" in
    "mkdir -m 700 "*)
      # mkdir -m fallisce su Windows/msys (chmod non supportato) pur creando
      # la dir: separa creazione atomica (fallisce se esiste: e la mutua
      # esclusione) dai permessi best-effort.
      if mkdir "$TEST_ROOT/tmp/hermes-gpu-manager-deploy.lock" 2>/dev/null; then
        chmod 700 "$TEST_ROOT/tmp/hermes-gpu-manager-deploy.lock" 2>/dev/null || true
      else
        return 1
      fi
      ;;
    'mktemp -d /tmp/hermes-gpu-manager.XXXXXXXX')
      mkdir -p "$TEST_ROOT/tmp/hermes-gpu-manager.$MOCK_STAGE_NAME"
      printf '/tmp/hermes-gpu-manager.%s\n' "$MOCK_STAGE_NAME"
      ;;
    "chmod 700 "*)
      return 0
      ;;
    "rm -rf -- "*)
      target="$(printf '%s' "$command" | sed -e "s/^rm -rf -- '//" -e "s/'$//")"
      name="$(basename "$target")"
      rm -rf -- "$TEST_ROOT/tmp/$name"
      ;;
    "rmdir -- "*)
      rmdir -- "$TEST_ROOT/tmp/hermes-gpu-manager-deploy.lock"
      ;;
    *)
      echo "unexpected mock SSH command: $command" >&2
      return 92
      ;;
  esac
}
SSH_CMD=(mock_ssh)
"""


class TestDeployRollback(unittest.TestCase):
    @unittest.skipUnless(BASH, "Bash is unavailable")
    def test_backup_failure_prevents_replacement(self):
        body = SHELL_SANDBOX + r"""
REMOTE_LOCK_DIR=/tmp/hermes-gpu-manager-deploy.lock
REMOTE_STAGE="$TEST_ROOT/tmp/hermes-gpu-manager.stageone"
mkdir -p "$REMOTE_DIR/character_id" "$REMOTE_STAGE/character_id"
printf 'old-character-code\n' > "$REMOTE_DIR/character_id/old.py"
printf 'old-display\n' > "$REMOTE_DIR/display_bridge.py"
printf 'new-manager\n' > "$REMOTE_STAGE/manager.py"
printf 'new-display\n' > "$REMOTE_STAGE/display_bridge.py"
printf 'new-character\n' > "$REMOTE_STAGE/character_id/new.py"
BACKUP_COMPLETE=false
LIVE_REPLACEMENT_STARTED=false
if install_with_rollback; then
  echo 'backup failure unexpectedly installed files' >&2
  exit 1
fi
[[ "$BACKUP_COMPLETE" == false ]]
[[ "$LIVE_REPLACEMENT_STARTED" == false ]]
[[ "$(cat "$REMOTE_DIR/character_id/old.py")" == old-character-code ]]
[[ ! -e "$REMOTE_DIR/manager.py" ]]
[[ "$(cat "$OP_LOG")" == backup ]]
[[ ! -e "$SYSTEMCTL_LOG" ]]
[[ "$(cat "$TEST_ROOT/opt/hermes/character-id/keep.txt")" == persistent-data ]]
"""
        result = run_deploy_shell(body)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    @unittest.skipUnless(BASH, "Bash is unavailable")
    def test_compile_failure_restores_all_deployed_code_before_restart(self):
        body = SHELL_SANDBOX + r"""
REMOTE_LOCK_DIR=/tmp/hermes-gpu-manager-deploy.lock
REMOTE_STAGE="$TEST_ROOT/tmp/hermes-gpu-manager.stageone"
mkdir -p "$REMOTE_DIR/character_id" "$REMOTE_STAGE/character_id"
printf 'old-manager\n' > "$REMOTE_DIR/manager.py"
printf 'old-display\n' > "$REMOTE_DIR/display_bridge.py"
printf 'old-character\n' > "$REMOTE_DIR/character_id/old.py"
printf 'new-manager\n' > "$REMOTE_STAGE/manager.py"
printf 'new-display\n' > "$REMOTE_STAGE/display_bridge.py"
printf 'new-character\n' > "$REMOTE_STAGE/character_id/new.py"
BACKUP_COMPLETE=false
LIVE_REPLACEMENT_STARTED=false
if install_with_rollback; then
  echo 'compile failure unexpectedly succeeded' >&2
  exit 1
fi
[[ "$(cat "$OP_LOG")" == $'backup\ninstall\nrollback' ]]
[[ "$(cat "$REMOTE_DIR/manager.py")" == old-manager ]]
[[ "$(cat "$REMOTE_DIR/display_bridge.py")" == old-display ]]
[[ "$(cat "$REMOTE_DIR/character_id/old.py")" == old-character ]]
[[ ! -e "$REMOTE_DIR/character_id/new.py" ]]
[[ "$(cat "$BACKUP_DIR/character_id.state")" == present ]]
[[ "$LIVE_REPLACEMENT_STARTED" == false ]]
[[ "$(cat "$SYSTEMCTL_LOG")" == 'restart hermes-gpu-manager.service' ]]
[[ "$(cat "$TEST_ROOT/opt/hermes/character-id/keep.txt")" == persistent-data ]]
"""
        result = run_deploy_shell(body)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    @unittest.skipUnless(BASH, "Bash is unavailable")
    def test_private_staging_serializes_overlapping_deploys(self):
        body = SHELL_SANDBOX + r"""
REMOTE_LOCK_DIR=/tmp/hermes-gpu-manager-deploy.lock
REMOTE_STAGE=''
REMOTE_LOCK_HELD=false
MOCK_STAGE_NAME=firststage
acquire_deploy_staging
first="$REMOTE_STAGE"
[[ "$REMOTE_LOCK_HELD" == true ]]
[[ -d "$TEST_ROOT/tmp/hermes-gpu-manager.firststage" ]]
if (
  REMOTE_STAGE=''
  REMOTE_LOCK_HELD=false
  MOCK_STAGE_NAME=secondstage
  acquire_deploy_staging
); then
  echo 'overlapping deployment acquired the lock' >&2
  exit 1
fi
[[ ! -e "$TEST_ROOT/tmp/hermes-gpu-manager.secondstage" ]]
release_deploy_staging
[[ "$REMOTE_LOCK_HELD" == false ]]
MOCK_STAGE_NAME=secondstage
acquire_deploy_staging
second="$REMOTE_STAGE"
[[ "$first" != "$second" ]]
[[ -d "$TEST_ROOT/tmp/hermes-gpu-manager.secondstage" ]]
release_deploy_staging
[[ ! -e "$TEST_ROOT/tmp/hermes-gpu-manager-deploy.lock" ]]
[[ ! -e "$TEST_ROOT/tmp/hermes-gpu-manager.firststage" ]]
[[ ! -e "$TEST_ROOT/tmp/hermes-gpu-manager.secondstage" ]]
"""
        result = run_deploy_shell(body)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    @unittest.skipUnless(BASH, "Bash is unavailable")
    def test_verifier_sends_key_only_on_stdin(self):
        body = SHELL_SANDBOX + r"""
CAPTURE_ARGS="$TEST_ROOT/ssh-args.txt"
CAPTURE_STDIN="$TEST_ROOT/ssh-stdin.txt"
export CAPTURE_ARGS CAPTURE_STDIN
mock_verify_ssh() {
  printf '%s\n' "$*" > "$CAPTURE_ARGS"
  cat > "$CAPTURE_STDIN"
  printf 'verification passed\n'
}
SSH_CMD=(mock_verify_ssh)
HERMES_GPU_MANAGER_KEY='offline-test-key-not-for-argv'
verify_remote >/dev/null
[[ "$(cat "$CAPTURE_STDIN")" == "$HERMES_GPU_MANAGER_KEY" ]]
[[ "$(cat "$CAPTURE_ARGS")" != *"$HERMES_GPU_MANAGER_KEY"* ]]
program="$(remote_verify_program)"
[[ "$program" == *'sys.stdin.read()'* ]]
[[ "$program" != *'/tmp/gpu-manager-verify.json'* ]]
[[ "$program" != *'/tmp/hcid-verify.json'* ]]
[[ "$program" != *'curl'* ]]
"""
        result = run_deploy_shell(body)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()