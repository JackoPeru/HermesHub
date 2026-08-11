#!/usr/bin/env bash
set -euo pipefail

# Guarded Hermes Agent updater. Hermes Hub owns only the explicit patch output;
# every other checkout change belongs to the operator and blocks mutation.

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
AGENT_ROOT="${HERMES_HUB_AGENT_ROOT:-$HERMES_HOME/hermes-agent}"
VENV_BIN="${HERMES_HUB_AGENT_VENV_BIN:-$AGENT_ROOT/venv/bin}"
HERMES_CMD="${HERMES_HUB_AGENT_COMMAND:-$VENV_BIN/hermes}"
PATCHER="${HERMES_HUB_PATCHER:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/patch-hermes-gateway-native.py}"
SERVICE_NAME="${HERMES_HUB_SERVICE:-hermes-hub.service}"
STATE_PATH="${HERMES_HUB_RUNTIME_STATE_PATH:-$HERMES_HOME/hub_gateway_runtime.json}"
QUARANTINE_PATH="${HERMES_HUB_AGENT_QUARANTINE_PATH:-$HERMES_HOME/hub_agent_update_quarantine}"
API_KEY_FILE="${API_SERVER_KEY_FILE:-$HERMES_HOME/api_server.key}"
PROBE_URL="${HERMES_HUB_UPDATE_PROBE_URL:-http://127.0.0.1:${HERMES_API_PORT:-8642}/v1/capabilities}"
PROBE_ATTEMPTS="${HERMES_HUB_AGENT_UPDATE_PROBE_ATTEMPTS:-30}"
PROBE_SLEEP_SECONDS="${HERMES_HUB_AGENT_UPDATE_PROBE_SLEEP_SECONDS:-2}"
MODE="check"
TMP_DIR=""
ACTIVE_SHA=""
TARGET_SHA=""
ACTIVE_VERSION=""
API_KEY=""

usage() { echo "Usage: hermes-hub-agent-update [--check|--apply|--auto]"; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --check) MODE="check" ;;
    --apply) MODE="apply" ;;
    --auto) MODE="auto" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

validate_positive_integer() {
  local name="$1" value="$2" maximum="$3"
  case "$value" in *[!0-9]*|'') echo "ERROR: $name must be an integer" >&2; exit 2 ;; esac
  if [ "$value" -lt 1 ] || [ "$value" -gt "$maximum" ]; then
    echo "ERROR: $name must be between 1 and $maximum" >&2
    exit 2
  fi
}

# Validate all numeric values before locks, fetches, worktrees, or state writes.
validate_positive_integer HERMES_HUB_AGENT_UPDATE_PROBE_ATTEMPTS "$PROBE_ATTEMPTS" 300
validate_positive_integer HERMES_HUB_AGENT_UPDATE_PROBE_SLEEP_SECONDS "$PROBE_SLEEP_SECONDS" 60
validate_positive_integer HERMES_API_PORT "${HERMES_API_PORT:-8642}" 65535

for command in git python3 systemctl curl; do
  command -v "$command" >/dev/null 2>&1 || { echo "ERROR: missing required command: $command" >&2; exit 1; }
done
[ -d "$AGENT_ROOT/.git" ] || { echo "ERROR: Hermes Agent git checkout not found: $AGENT_ROOT" >&2; exit 1; }
[ -x "$HERMES_CMD" ] || { echo "ERROR: Hermes command not found: $HERMES_CMD" >&2; exit 1; }
[ -x "$VENV_BIN/python" ] || { echo "ERROR: Hermes Python runtime not found: $VENV_BIN/python" >&2; exit 1; }
[ -f "$PATCHER" ] || { echo "ERROR: Hermes Hub patcher not found: $PATCHER" >&2; exit 1; }

cleanup() {
  if [ -n "$TMP_DIR" ]; then
    git -C "$AGENT_ROOT" worktree remove --force "$TMP_DIR/worktree" >/dev/null 2>&1 || true
    rm -rf "$TMP_DIR"
  fi
}
trap cleanup EXIT

agent_version() { "$HERMES_CMD" --version 2>/dev/null | head -n 1 | tr -d '\r' || true; }

write_state() {
  local status="$1" current="$2" previous="$3" candidate="$4" available="$5" reason="${6:-}" revision="${7:-}"
  python3 - "$STATE_PATH" "$status" "$current" "$previous" "$candidate" "$available" "$reason" "$revision" <<'PY'
import json, os, sys, tempfile, time
from pathlib import Path

path = Path(sys.argv[1]).expanduser()
try:
    state = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
except Exception:
    state = {}
if not isinstance(state, dict): state = {}
status, current, previous, candidate, available, reason, revision = sys.argv[2:]
now = time.time()
state.update({"schema_version": 1, "status": status, "agent_version": current[:80],
              "agent_revision": revision[:80], "previous_version": previous[:80],
              "candidate_version": candidate[:80], "update_available": available == "true",
              "last_checked_at": now})
if status == "healthy":
    state["last_success_at"] = now
    state.pop("failure", None)
elif reason:
    state["failure"] = {"version": candidate[:80], "reason": reason.replace("\n", " ").replace("\r", " ")[:240], "at": now}
path.parent.mkdir(parents=True, exist_ok=True)
with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, prefix=path.name + ".", delete=False) as handle:
    json.dump(state, handle, ensure_ascii=False, sort_keys=True); handle.write("\n"); handle.flush(); os.fsync(handle.fileno()); temporary = Path(handle.name)
os.replace(temporary, path); os.chmod(path, 0o600)
PY
}

preflight_checkout_state() {
  local staged unstaged file
  local -a untracked=()
  staged="$(git -C "$AGENT_ROOT" diff --cached --name-only)"
  if [ -n "$staged" ]; then echo "ERROR: refusing update with staged Hermes Agent changes" >&2; return 1; fi
  mapfile -d '' -t untracked < <(git -C "$AGENT_ROOT" ls-files --others --exclude-standard -z)
  for file in "${untracked[@]}"; do
    if ! is_verified_hub_patch_backup "$file" "$ACTIVE_SHA"; then
      echo "ERROR: refusing update with untracked Hermes Agent file: $file" >&2
      return 1
    fi
  done
  cleanup_hub_patch_backups "$ACTIVE_SHA" || return 1
  unstaged="$(git -C "$AGENT_ROOT" diff --name-only)"
  for file in $unstaged; do
    case "$file" in
      gateway/platforms/api_server.py|agent/chat_completion_helpers.py) ;;
      *) echo "ERROR: refusing update with unrelated Hermes Agent edit: $file" >&2; return 1 ;;
    esac
  done
  [ -z "$unstaged" ] || managed_patch_matches_head
}

is_verified_hub_patch_backup() {
  local file="$1" revision="$2" source suffix
  case "$file" in
    gateway/platforms/api_server.py.bak-hermes-native-*)
      source="gateway/platforms/api_server.py"
      suffix="${file#gateway/platforms/api_server.py.bak-hermes-native-}"
      ;;
    agent/chat_completion_helpers.py.bak-hermes-native-*)
      source="agent/chat_completion_helpers.py"
      suffix="${file#agent/chat_completion_helpers.py.bak-hermes-native-}"
      ;;
    *) return 1 ;;
  esac
  [[ "$suffix" =~ ^[0-9]+-[0-9]+-[0-9]+$ ]] || return 1
  git -C "$AGENT_ROOT" cat-file -e "$revision:$source" 2>/dev/null &&
    git -C "$AGENT_ROOT" show "$revision:$source" | python3 -c '
import sys
from pathlib import Path
expected = sys.stdin.buffer.read().replace(b"\r\n", b"\n")
actual = Path(sys.argv[1]).read_bytes().replace(b"\r\n", b"\n")
raise SystemExit(0 if actual == expected else 1)
' "$AGENT_ROOT/$file"
}

cleanup_hub_patch_backups() {
  local revision="$1" source backup file
  for source in gateway/platforms/api_server.py agent/chat_completion_helpers.py; do
    for backup in "$AGENT_ROOT/$source".bak-hermes-native-*; do
      [ -e "$backup" ] || continue
      file="${backup#"$AGENT_ROOT/"}"
      if ! is_verified_hub_patch_backup "$file" "$revision"; then
        echo "ERROR: refusing to remove unverified Hermes Hub backup: $file" >&2
        return 1
      fi
      rm -f -- "$backup" || { echo "ERROR: failed to remove Hermes Hub backup: $file" >&2; return 1; }
    done
  done
}

managed_patch_matches_head() {
  local source_root="$TMP_DIR/source"
  mkdir -p "$source_root/gateway/platforms" "$source_root/agent"
  git -C "$AGENT_ROOT" show "$ACTIVE_SHA:gateway/platforms/api_server.py" > "$source_root/gateway/platforms/api_server.py" || return 1
  if git -C "$AGENT_ROOT" cat-file -e "$ACTIVE_SHA:agent/chat_completion_helpers.py" 2>/dev/null; then
    git -C "$AGENT_ROOT" show "$ACTIVE_SHA:agent/chat_completion_helpers.py" > "$source_root/agent/chat_completion_helpers.py" || return 1
  fi
  python3 "$PATCHER" --target "$source_root/gateway/platforms/api_server.py" >/dev/null || return 1
  for file in gateway/platforms/api_server.py agent/chat_completion_helpers.py; do
    if git -C "$AGENT_ROOT" diff --quiet -- "$file"; then continue; fi
    if ! cmp -s "$source_root/$file" "$AGENT_ROOT/$file"; then
      echo "ERROR: refusing update; managed path contains non-Hub changes: $file" >&2
      return 1
    fi
  done
}

validate_candidate() {
  local candidate_root="$TMP_DIR/worktree"
  git -C "$AGENT_ROOT" worktree add --detach "$candidate_root" "$TARGET_SHA" >/dev/null || return 1
  "$VENV_BIN/python" -m py_compile "$candidate_root/gateway/platforms/api_server.py" || return 1
  python3 "$PATCHER" --target "$candidate_root/gateway/platforms/api_server.py" --check >/dev/null || return 1
}

apply_hub_patch() {
  local revision="$1"
  if ! python3 "$PATCHER" --target "$AGENT_ROOT/gateway/platforms/api_server.py"; then
    cleanup_hub_patch_backups "$revision" || true
    return 1
  fi
  cleanup_hub_patch_backups "$revision"
}

probe_gateway() {
  local expected_version="$1"
  curl --fail --silent --show-error --connect-timeout 2 --max-time 5 -H "Authorization: Bearer $API_KEY" "$PROBE_URL" |
    python3 -c '
import json, sys
payload = json.load(sys.stdin)
assert isinstance(payload, dict)
assert payload.get("object") == "hermes.api_server.capabilities"
assert payload.get("platform") == "hermes-agent"
auth, runtime, features, endpoints = (payload.get(k) for k in ("auth", "runtime", "features", "endpoints"))
assert isinstance(auth, dict) and auth.get("type") == "bearer"
assert isinstance(runtime, dict) and runtime.get("mode") == "server_agent"
assert isinstance(features, dict) and features.get("hermes_native") is True and features.get("native_responses") is True
assert isinstance(endpoints, dict)
native = endpoints.get("hermes_native") or endpoints.get("responses")
assert isinstance(native, dict) and native.get("method") == "POST" and native.get("path") in ("/v1/hermes/native", "/v1/responses")
version = payload.get("version")
expected = sys.argv[1]
if version is not None and expected and str(version).strip() != expected:
    raise AssertionError("capabilities version does not match candidate")
' "$expected_version"
}

load_api_key() {
  API_KEY="${HERMES_HUB_API_KEY:-${HERMES_API_KEY:-}}"
  if [ -z "$API_KEY" ] && [ -s "$API_KEY_FILE" ]; then
    API_KEY="$(tr -d '[:space:]' < "$API_KEY_FILE")"
  fi
  [ -n "$API_KEY" ]
}

verify_current_gateway() {
  local candidate="$1" available="$2"
  if ! probe_gateway "$ACTIVE_VERSION"; then
    write_state "unhealthy" "$ACTIVE_VERSION" "" "$candidate" "$available" "current gateway readiness probe failed" "$ACTIVE_SHA"
    echo "ERROR: current Hermes Agent readiness probe failed" >&2
    return 1
  fi
}

rollback() {
  local reason="$1" failed=false rollback_reason=""
  if ! printf '%s\n' "$TARGET_SHA" > "$QUARANTINE_PATH"; then failed=true; rollback_reason="quarantine write failed"; fi
  if ! cleanup_hub_patch_backups "$TARGET_SHA"; then failed=true; rollback_reason="candidate patch backup cleanup failed"; fi
  if ! git -C "$AGENT_ROOT" reset --hard "$ACTIVE_SHA" >/dev/null; then failed=true; rollback_reason="git restore failed"; fi
  if ! "$VENV_BIN/python" -m pip install --disable-pip-version-check --no-deps -e "${AGENT_ROOT}[all]" >/dev/null; then failed=true; rollback_reason="editable package restore failed"; fi
  if ! "$VENV_BIN/python" -m pip check >/dev/null; then failed=true; rollback_reason="dependency compatibility restore failed"; fi
  if ! apply_hub_patch "$ACTIVE_SHA" >/dev/null; then failed=true; rollback_reason="Hub patch restore failed"; fi
  if ! systemctl --user restart "$SERVICE_NAME" >/dev/null; then failed=true; rollback_reason="gateway restart during rollback failed"; fi
  if [ "$failed" = false ] && ! probe_gateway "$ACTIVE_VERSION"; then failed=true; rollback_reason="rollback readiness probe failed"; fi
  if [ "$failed" = true ]; then
    write_state "rollback_failed" "$(agent_version)" "$ACTIVE_VERSION" "${TARGET_SHA:0:12}" true "$reason; $rollback_reason" "$ACTIVE_SHA"
    echo "ERROR: Hermes Agent rollback failed: $rollback_reason" >&2
    return 1
  fi
  write_state "rolled_back" "$(agent_version)" "$ACTIVE_VERSION" "${TARGET_SHA:0:12}" true "$reason" "$ACTIVE_SHA"
  echo "ERROR: Hermes Agent update rolled back: $reason" >&2
  return 0
}

mkdir -p "$HERMES_HOME"
TMP_DIR="$(mktemp -d "$HERMES_HOME/.hub-agent-update.XXXXXX")"
LOCK_FILE="$HERMES_HOME/.hub-agent-update.lock"
if command -v flock >/dev/null 2>&1; then
  exec 9>"$LOCK_FILE"
  flock -n 9 || { echo "ERROR: another Hermes Agent update is already running" >&2; exit 75; }
else
  LOCK_DIR="${LOCK_FILE}.d"
  mkdir "$LOCK_DIR" 2>/dev/null || { echo "ERROR: another Hermes Agent update is already running" >&2; exit 75; }
  trap 'rmdir "$LOCK_DIR" 2>/dev/null || true; cleanup' EXIT
fi

ACTIVE_SHA="$(git -C "$AGENT_ROOT" rev-parse HEAD)"
ACTIVE_VERSION="$(agent_version)"
if ! preflight_checkout_state; then
  write_state "blocked" "$ACTIVE_VERSION" "" "" false "checkout contains user-owned changes" "$ACTIVE_SHA"
  exit 2
fi
git -C "$AGENT_ROOT" fetch --quiet origin
TARGET_SHA="$(git -C "$AGENT_ROOT" rev-parse '@{u}' 2>/dev/null || git -C "$AGENT_ROOT" rev-parse origin/main)"
AVAILABLE=false; [ "$ACTIVE_SHA" != "$TARGET_SHA" ] && AVAILABLE=true
if ! load_api_key; then
  write_state "unhealthy" "$ACTIVE_VERSION" "" "${TARGET_SHA:0:12}" "$AVAILABLE" "gateway API key unavailable for readiness probe" "$ACTIVE_SHA"
  echo "ERROR: gateway API key unavailable for readiness probe" >&2
  exit 1
fi

if [ "$MODE" = check ] || { [ "$MODE" = auto ] && [ "${HERMES_HUB_AGENT_AUTO_UPDATE:-false}" != true ]; }; then
  verify_current_gateway "${TARGET_SHA:0:12}" "$AVAILABLE" || exit 1
  write_state healthy "$ACTIVE_VERSION" "" "${TARGET_SHA:0:12}" "$AVAILABLE" "" "$ACTIVE_SHA"
  echo "Hermes Agent active: $ACTIVE_VERSION (${ACTIVE_SHA:0:12})"; echo "Update available: $AVAILABLE"; exit 0
fi
if [ "$AVAILABLE" != true ]; then
  verify_current_gateway "" false || exit 1
  write_state healthy "$ACTIVE_VERSION" "" "" false "" "$ACTIVE_SHA"
  echo "Hermes Agent already current: $ACTIVE_VERSION"
  exit 0
fi
if [ -f "$QUARANTINE_PATH" ] && [ "$(tr -d '[:space:]' < "$QUARANTINE_PATH")" = "$TARGET_SHA" ]; then
  verify_current_gateway "${TARGET_SHA:0:12}" true || exit 1
  write_state healthy "$ACTIVE_VERSION" "" "${TARGET_SHA:0:12}" true "" "$ACTIVE_SHA"
  echo "Quarantined Hermes Agent candidate: ${TARGET_SHA:0:12}" >&2; exit 0
fi
if ! validate_candidate; then
  write_state blocked "$ACTIVE_VERSION" "" "${TARGET_SHA:0:12}" true "candidate validation failed before live mutation" "$ACTIVE_SHA"
  echo "ERROR: Hermes Agent candidate failed isolated validation" >&2; exit 1
fi

write_state updating "$ACTIVE_VERSION" "$ACTIVE_VERSION" "${TARGET_SHA:0:12}" true "" "$ACTIVE_SHA"
if ! git -C "$AGENT_ROOT" reset --hard "$TARGET_SHA" >/dev/null; then rollback "git switch to candidate failed" || true; exit 1; fi
if ! "$VENV_BIN/python" -m pip install --disable-pip-version-check --no-deps -e "${AGENT_ROOT}[all]"; then rollback "candidate editable package install failed" || true; exit 1; fi
if ! "$VENV_BIN/python" -m pip check; then rollback "candidate dependency compatibility failed; operator intervention required" || true; exit 1; fi
if ! apply_hub_patch "$TARGET_SHA"; then rollback "Hermes Hub patch apply failed" || true; exit 1; fi
if ! systemctl --user restart "$SERVICE_NAME"; then rollback "gateway restart failed" || true; exit 1; fi
NEW_VERSION="$(agent_version)"
ready=false
for _ in $(seq 1 "$PROBE_ATTEMPTS"); do if probe_gateway "$NEW_VERSION" >/dev/null 2>&1; then ready=true; break; fi; sleep "$PROBE_SLEEP_SECONDS"; done
if [ "$ready" != true ]; then rollback "gateway readiness probe failed" || true; exit 1; fi
rm -f "$QUARANTINE_PATH"
write_state healthy "$NEW_VERSION" "$ACTIVE_VERSION" "" false "" "$TARGET_SHA"
echo "Hermes Agent updated and verified: $NEW_VERSION"
