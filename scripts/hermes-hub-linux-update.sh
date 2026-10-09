#!/usr/bin/env bash
set -euo pipefail

# Transactional updater for the Hermes Hub Linux gateway helper.

REPO="${HERMES_HUB_REPO:-JackoPeru/HermesHub}"
INSTALL_DIR="${HERMES_HUB_INSTALL_DIR:-$HOME/.local/share/hermes-hub-gateway}"
BIN_DIR="${HERMES_HUB_BIN_DIR:-$HOME/.local/bin}"
SERVICE_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
SERVICE_NAME="${HERMES_HUB_SERVICE:-hermes-hub.service}"
CHANNEL="${HERMES_HUB_CHANNEL:-latest}"
CURL_CONNECT_TIMEOUT="${HERMES_HUB_UPDATE_CONNECT_TIMEOUT:-10}"
CURL_API_MAX_TIME="${HERMES_HUB_UPDATE_API_MAX_TIME:-60}"
CURL_DOWNLOAD_MAX_TIME="${HERMES_HUB_UPDATE_DOWNLOAD_MAX_TIME:-900}"
CURL_RETRIES="${HERMES_HUB_UPDATE_RETRIES:-4}"
MAX_RELEASE_PAGES="${HERMES_HUB_UPDATE_MAX_RELEASE_PAGES:-5}"
MAX_ASSET_MB="${HERMES_HUB_UPDATE_MAX_ASSET_MB:-256}"
PROBE_ATTEMPTS="${HERMES_HUB_UPDATE_PROBE_ATTEMPTS:-30}"
PROBE_SLEEP_SECONDS="${HERMES_HUB_UPDATE_PROBE_SLEEP_SECONDS:-2}"
PROBE_URL="${HERMES_HUB_UPDATE_PROBE_URL:-http://127.0.0.1:${HERMES_API_PORT:-8642}/v1/capabilities}"
GATEWAY_BASE_URL="${HERMES_HUB_GATEWAY_URL:-${PROBE_URL%/v1/capabilities}}"
GATEWAY_HEALTH_URL="${GATEWAY_BASE_URL%/}/health/detailed"
API_SERVER_KEY_FILE="${API_SERVER_KEY_FILE:-$HOME/.hermes/api_server.key}"
HERMES_ENV_FILE="${HERMES_ENV_FILE:-$HOME/.hermes/.env}"
MANAGER_URL="${HERMES_HUB_MANAGER_URL:-http://127.0.0.1:8643}"
COMFY_URL="${HERMES_HUB_COMFY_URL:-http://127.0.0.1:8188}"
BUSY_LEASE_FILE="${HERMES_HUB_BUSY_LEASE:-$HOME/.hermes/hub_busy.lock}"

FORCE=false
CHECK_ONLY=false
RESTART=false
ALLOW_DOWNGRADE=false
TMP_DIR=""
LOCK_DIR=""
TRANSACTION_ACTIVE=false
COMMITTED=false
BACKUP_TIMER_WAS_ENABLED=false
BACKUP_BUNDLE_PRESENT=false
PREVIOUS_TARGET=""
FINAL_RELEASE_DIR=""
OLD_VERSION_PRESENT=false
OLD_VERSION=""
FAILED_RELEASE_FILE="$INSTALL_DIR/failed-release"
PENDING_FILE="$INSTALL_DIR/.pending-update"

usage() {
  cat <<'EOF'
Usage: hermes-hub-linux-update [--check] [--force] [--allow-downgrade] [--restart] [--no-restart]

Env:
  HERMES_HUB_REPO=JackoPeru/HermesHub
  HERMES_HUB_INSTALL_DIR=$HOME/.local/share/hermes-hub-gateway
  HERMES_HUB_SERVICE=hermes-hub.service
  HERMES_HUB_CHANNEL=latest
  HERMES_HUB_UPDATE_MAX_RELEASE_PAGES=5
  HERMES_HUB_UPDATE_MAX_ASSET_MB=256
  HERMES_HUB_UPDATE_PROBE_URL=http://127.0.0.1:8642/v1/capabilities
  HERMES_HUB_MANAGER_API_KEY or HERMES_GPU_MANAGER_KEY
  HERMES_HUB_MANAGER_KEY_FILE=/path/to/owner-only-key-file
  HERMES_HUB_MANAGER_URL=http://127.0.0.1:8643
  HERMES_HUB_COMFY_URL=http://127.0.0.1:8188
  HERMES_HUB_BUSY_LEASE=$HOME/.hermes/hub_busy.lock
  GH_TOKEN or GITHUB_TOKEN for private/rate-limited GitHub API calls
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --check) CHECK_ONLY=true ;;
    --force) FORCE=true ;;
    --allow-downgrade) ALLOW_DOWNGRADE=true ;;
    --restart) RESTART=true ;;
    --no-restart) RESTART=false ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "ERROR: missing required command: $1" >&2
    exit 1
  fi
}

read_env_value() {
  local file="$1"
  local key="$2"
  local value first last
  value="$(awk -v key="$key" 'index($0, key "=") == 1 {sub(/^[^=]*=/, ""); found=$0} END {print found}' "$file" 2>/dev/null || true)"
  value="${value%$'\r'}"
  value="$(printf '%s' "$value" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  if [ "${#value}" -ge 2 ]; then
    first="${value:0:1}"
    last="${value: -1}"
    if { [ "$first" = '"' ] && [ "$last" = '"' ]; } || { [ "$first" = "'" ] && [ "$last" = "'" ]; }; then
      value="${value:1:${#value}-2}"
    fi
  fi
  printf '%s' "$value"
}

is_uint() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

is_positive_uint() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]]
}

for value in "$CURL_CONNECT_TIMEOUT" "$CURL_API_MAX_TIME" "$CURL_DOWNLOAD_MAX_TIME" "$MAX_RELEASE_PAGES" "$MAX_ASSET_MB" "$PROBE_ATTEMPTS" "$PROBE_SLEEP_SECONDS"; do
  if ! is_positive_uint "$value"; then
    echo "ERROR: updater timeout/probe values must be positive integers" >&2
    exit 2
  fi
done
if (( 10#$MAX_RELEASE_PAGES > 10 )); then
  echo "ERROR: updater release page limit cannot exceed 10" >&2
  exit 2
fi
if ! is_uint "$CURL_RETRIES"; then
  echo "ERROR: updater retry count must be a non-negative integer" >&2
  exit 2
fi

need_cmd curl
need_cmd python3
need_cmd find
need_cmd install

mkdir -p "$INSTALL_DIR" "$INSTALL_DIR/releases" "$BIN_DIR" "$SERVICE_DIR"

LOCK_FILE="$INSTALL_DIR/.update.lock"
if command -v flock >/dev/null 2>&1; then
  exec 9>"$LOCK_FILE"
  if ! flock -n 9; then
    echo "ERROR: another Hermes Hub gateway update is already running" >&2
    exit 75
  fi
else
  LOCK_DIR="$LOCK_FILE.d"
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    lock_pid="$(cat "$LOCK_DIR/pid" 2>/dev/null || echo "")"
    lock_age=$(( $(date +%s) - $(stat -c %Y "$LOCK_DIR" 2>/dev/null || echo 0) ))
    if [ -z "$lock_pid" ] || ! kill -0 "$lock_pid" 2>/dev/null || [ "$lock_age" -gt 7200 ]; then
      echo "WARN: removing stale update lock (pid=$lock_pid age=${lock_age}s)" >&2
      rm -rf "$LOCK_DIR"
      mkdir "$LOCK_DIR" 2>/dev/null || { echo "ERROR: another Hermes Hub gateway update is already running" >&2; exit 75; }
    else
      echo "ERROR: another Hermes Hub gateway update is already running" >&2
      exit 75
    fi
  fi
  echo "$$ $(date +%s)" > "$LOCK_DIR/pid"
fi

TMP_DIR="$(mktemp -d "$INSTALL_DIR/.update-tmp.XXXXXX")"
chmod 700 "$TMP_DIR"

atomic_symlink() {
  local target="$1"
  local link="$2"
  local tmp_link="${link}.new.$$"
  rm -f "$tmp_link"
  ln -s "$target" "$tmp_link"
  mv -Tf "$tmp_link" "$link"
}

atomic_install() {
  local source="$1"
  local destination="$2"
  local mode="$3"
  local tmp_destination="${destination}.new.$$"
  install -m "$mode" "$source" "$tmp_destination"
  mv -f "$tmp_destination" "$destination"
}

write_version() {
  local value="$1"
  local tmp_version="$INSTALL_DIR/.VERSION.new.$$"
  printf '%s\n' "$value" > "$tmp_version"
  mv -f "$tmp_version" "$INSTALL_DIR/VERSION"
}

record_failed_release() {
  if [ -z "${LATEST_VERSION:-}" ] || [ -z "${ASSET_DIGEST:-}" ]; then
    return
  fi
  local tmp_failed="$INSTALL_DIR/.failed-release.new.$$"
  printf '%s|%s\n' "$LATEST_VERSION" "$ASSET_DIGEST" > "$tmp_failed"
  mv -f "$tmp_failed" "$FAILED_RELEASE_FILE"
}

resolve_probe_key() {
  local key="${HERMES_HUB_API_KEY:-${HERMES_API_KEY:-}}"
  if [ -z "$key" ] && [ -s "$HERMES_ENV_FILE" ]; then
    local key_name
    for key_name in HERMES_HUB_API_KEY HERMES_GATEWAY_API_KEY API_SERVER_KEY HERMES_API_KEY HERMESAPIKEY; do
      key="$(read_env_value "$HERMES_ENV_FILE" "$key_name")"
      if [ -n "$key" ]; then
        break
      fi
    done
  fi
  if [ -z "$key" ] && [ -s "$API_SERVER_KEY_FILE" ]; then
    key="$(tr -d '[:space:]' < "$API_SERVER_KEY_FILE")"
  fi
  printf '%s' "$key"
}

env_file_has_key() {
  local file="$1"
  local key_name="$2"
  awk -v key="$key_name" 'index($0, key "=") == 1 {found=1} END {exit !found}' "$file" 2>/dev/null
}

is_safe_bearer_key() {
  local key="$1"
  local bearer_pattern='^[A-Za-z0-9._~+/-]+={0,}$'
  [ "${#key}" -le 4096 ] && [[ "$key" =~ $bearer_pattern ]]
}

read_manager_key_file() {
  local key_file="$1"
  local metadata mode owner current_owner size key
  if [ -z "$key_file" ] || [ -L "$key_file" ] || [ ! -f "$key_file" ]; then
    return 1
  fi
  metadata="$(stat -c '%a %u' -- "$key_file" 2>/dev/null)" || return 1
  mode="${metadata%% *}"
  owner="${metadata##* }"
  [[ "$mode" =~ ^[0-7]{3,4}$ ]] || return 1
  current_owner="$(id -u 2>/dev/null)" || return 1
  [ "$owner" = "$current_owner" ] || return 1
  if (( (8#$mode & 077) != 0 || (8#$mode & 0400) == 0 || (8#$mode & 0111) != 0 )); then
    return 1
  fi
  size="$(wc -c < "$key_file" 2>/dev/null | tr -d '[:space:]')" || return 1
  [[ "$size" =~ ^[0-9]+$ ]] || return 1
  if (( 10#$size == 0 || 10#$size > 4097 )); then
    return 1
  fi
  key="$(cat -- "$key_file" 2>/dev/null)" || return 1
  is_safe_bearer_key "$key" || return 1
  printf '%s' "$key"
}

resolve_manager_key() {
  local gateway_key="$1"
  local key_name key key_file

  for key_name in HERMES_HUB_MANAGER_API_KEY HERMES_GPU_MANAGER_KEY; do
    if [[ ${!key_name+x} ]]; then
      key="${!key_name}"
      is_safe_bearer_key "$key" || return 2
      printf '%s' "$key"
      return 0
    fi
  done

  if [[ ${HERMES_HUB_MANAGER_KEY_FILE+x} ]]; then
    key_file="$HERMES_HUB_MANAGER_KEY_FILE"
    key="$(read_manager_key_file "$key_file")" || return 2
    printf '%s' "$key"
    return 0
  fi

  if [ -e "$HERMES_ENV_FILE" ] && [ ! -r "$HERMES_ENV_FILE" ]; then
    return 2
  fi
  if [ -s "$HERMES_ENV_FILE" ]; then
    for key_name in HERMES_HUB_MANAGER_API_KEY HERMES_GPU_MANAGER_KEY; do
      if env_file_has_key "$HERMES_ENV_FILE" "$key_name"; then
        key="$(read_env_value "$HERMES_ENV_FILE" "$key_name")"
        is_safe_bearer_key "$key" || return 2
        printf '%s' "$key"
        return 0
      fi
    done
    if env_file_has_key "$HERMES_ENV_FILE" HERMES_HUB_MANAGER_KEY_FILE; then
      key_file="$(read_env_value "$HERMES_ENV_FILE" HERMES_HUB_MANAGER_KEY_FILE)"
      key="$(read_manager_key_file "$key_file")" || return 2
      printf '%s' "$key"
      return 0
    fi
  fi

  if [ -n "$gateway_key" ]; then
    is_safe_bearer_key "$gateway_key" || return 2
    printf '%s' "$gateway_key"
    return 0
  fi
  return 1
}

write_curl_auth_config() {
  local key="$1"
  local config_file="$2"
  is_safe_bearer_key "$key" || return 1
  chmod 600 "$config_file" || return 1
  printf 'header = "Authorization: Bearer %s"\n' "$key" > "$config_file" || return 1
  chmod 600 "$config_file" || return 1
}

# --- update busy gate -------------------------------------------------
# The updater must never restart the hub while work is in flight: a
# restart wipes in-memory agent runs. Three independent signals, first
# hit wins. Every checker prints a human reason (or nothing) and always
# returns 0 so `set -e` never trips on an idle answer.
#
# Cooperative lease protocol for long agent tasks (JSON file):
#   {"owner": "<agent>", "task": "<label>", "expires_at": <unix>}
# The agent creates it before starting, refreshes expires_at as a
# heartbeat, and deletes it when done. Expired or unreadable leases are
# removed and ignored so a crashed agent can never block updates forever.

lease_busy_reason() {
  if [ -z "${BUSY_LEASE_FILE:-}" ]; then
    return 0
  fi
  if [ ! -f "$BUSY_LEASE_FILE" ]; then
    return 0
  fi
  local verdict_file="$TMP_DIR/busy-lease.out"
  rm -f "$verdict_file"
  python3 - "$BUSY_LEASE_FILE" >"$verdict_file" 2>/dev/null <<'PY' || true
import json
import sys
import time
try:
    with open(sys.argv[1], encoding="utf-8") as lease_file:
        lease = json.load(lease_file)
    exp = float(lease.get("expires_at") or 0)
    if exp > time.time():
        owner = str(lease.get("owner") or "?")
        task = str(lease.get("task") or "?")
        print("LEASE busy lease by %s: %s" % (owner, task))
    else:
        print("STALE")
except Exception:
    print("GARBAGE")
PY
  local verdict
  verdict="$(cat "$verdict_file" 2>/dev/null || true)"
  rm -f "$verdict_file"
  if [ "$verdict" = "STALE" ]; then
    echo "Removing expired busy lease: $BUSY_LEASE_FILE" >&2
    rm -f "$BUSY_LEASE_FILE" || true
    return 0
  fi
  if [ "$verdict" = "GARBAGE" ]; then
    echo "Removing unreadable busy lease: $BUSY_LEASE_FILE" >&2
    rm -f "$BUSY_LEASE_FILE" || true
    return 0
  fi
  if [ "${verdict#LEASE }" != "$verdict" ]; then
    printf '%s' "${verdict#LEASE }"
  fi
  return 0
}

manager_busy_reason() {
  local key="$1"
  if [ -z "$key" ]; then
    printf 'no API key available for manager busy check'
    return 0
  fi
  if ! is_safe_bearer_key "$key"; then
    printf 'manager API key configuration invalid'
    return 0
  fi
  local body_file curl_cfg status reason
  # Chiave in un file 600 per curl (-K), mai in argv o log.
  curl_cfg="$(mktemp "$TMP_DIR/manager-auth.XXXXXX")" || { printf 'manager auth configuration unavailable'; return 0; }
  body_file="$(mktemp "$TMP_DIR/manager-status.XXXXXX")" || {
    rm -f "$curl_cfg"
    printf 'manager status unavailable'
    return 0
  }
  if ! write_curl_auth_config "$key" "$curl_cfg"; then
    rm -f "$curl_cfg" "$body_file"
    printf 'manager API key configuration invalid'
    return 0
  fi
  status="$(curl --silent --connect-timeout 3 --max-time 8 \
    -K "$curl_cfg" --output "$body_file" --write-out '%{http_code}' \
    "$MANAGER_URL/status" 2>/dev/null || true)"
  rm -f "$curl_cfg"
  case "$status" in
    401|403)
      rm -f "$body_file"
      printf 'manager authentication rejected (HTTP %s)' "$status"
      return 0
      ;;
    000|'')
      rm -f "$body_file"
      printf 'manager unreachable'
      return 0
      ;;
    200) ;;
    [0-9][0-9][0-9])
      rm -f "$body_file"
      printf 'manager unavailable (HTTP %s)' "$status"
      return 0
      ;;
    *)
      rm -f "$body_file"
      printf 'manager status unreadable'
      return 0
      ;;
  esac

  reason="$(python3 - "$body_file" <<'PY' 2>/dev/null || true
import json
import sys
try:
    with open(sys.argv[1], encoding="utf-8") as status_file:
        st = json.load(status_file)
except Exception:
    print("manager status unreadable")
    raise SystemExit(0)
if not isinstance(st, dict):
    print("manager status unreadable")
    raise SystemExit(0)
queued = st.get("queue_length")
state = st.get("current_state")
job = st.get("current_job")
known_states = {"LLM_READY", "MEDIA_READY", "MEDIA_BUSY", "LLM_BUSY", "LLM_LOADING"}
if (
    type(queued) is not int or queued < 0 or
    not isinstance(state, str) or state not in known_states or
    "current_job" not in st or not (job is None or isinstance(job, (str, bool)))
):
    print("manager status unreadable")
elif queued > 0:
    print("manager queue holds %d job(s)" % queued)
elif isinstance(job, str) and job:
    print("manager has an active job")
elif job is True:
    print("manager has an active job")
elif state == "LLM_READY":
    print("__MANAGER_IDLE__")
else:
    print("manager state %s" % state)
PY
  )"
  rm -f "$body_file"
  if [ "$reason" = "__MANAGER_IDLE__" ]; then
    return 0
  elif [ -z "$reason" ]; then
    printf 'manager status unreadable'
  else
    printf '%s' "$reason"
  fi
}

gateway_busy_reason() {
  local key="$1"
  if [ -z "$key" ]; then
    printf 'no API key available for gateway health check'
    return 0
  fi
  if ! is_safe_bearer_key "$key"; then
    printf 'gateway API key configuration invalid'
    return 0
  fi
  local body_file curl_cfg status reason
  curl_cfg="$(mktemp "$TMP_DIR/gateway-auth.XXXXXX")" || { printf 'gateway auth configuration unavailable'; return 0; }
  body_file="$(mktemp "$TMP_DIR/gateway-health.XXXXXX")" || {
    rm -f "$curl_cfg"
    printf 'gateway health unavailable'
    return 0
  }
  if ! write_curl_auth_config "$key" "$curl_cfg"; then
    rm -f "$curl_cfg" "$body_file"
    printf 'gateway API key configuration invalid'
    return 0
  fi
  status="$(curl --silent --connect-timeout 3 --max-time 8 \
    -K "$curl_cfg" --output "$body_file" --write-out '%{http_code}' \
    "$GATEWAY_HEALTH_URL" 2>/dev/null || true)"
  rm -f "$curl_cfg"
  case "$status" in
    401|403)
      rm -f "$body_file"
      printf 'gateway authentication rejected (HTTP %s)' "$status"
      return 0
      ;;
    000|'')
      rm -f "$body_file"
      printf 'gateway health endpoint unreachable'
      return 0
      ;;
    200) ;;
    [0-9][0-9][0-9])
      rm -f "$body_file"
      printf 'gateway health unavailable (HTTP %s)' "$status"
      return 0
      ;;
    *)
      rm -f "$body_file"
      printf 'gateway health unreadable'
      return 0
      ;;
  esac

  reason="$(python3 - "$body_file" <<'PY' 2>/dev/null || true
import json
import sys
try:
    with open(sys.argv[1], encoding="utf-8") as health_file:
        health = json.load(health_file)
except Exception:
    print("gateway health unreadable")
    raise SystemExit(0)
if not isinstance(health, dict):
    print("gateway health unreadable")
    raise SystemExit(0)
status = health.get("status")
gateway_state = health.get("gateway_state")
active_agents = health.get("active_agents")
gateway_busy = health.get("gateway_busy")
gateway_drainable = health.get("gateway_drainable")
api_server_present = "api_server" in health
api_server = health.get("api_server")
readiness = health.get("readiness")
checks = readiness.get("checks") if isinstance(readiness, dict) else None
queues_present = isinstance(checks, dict) and "background_queues" in checks
background_queues = checks.get("background_queues") if queues_present else None
active_runs = None
queue_counts = {}
schema_invalid = (
    not isinstance(status, str) or
    not isinstance(gateway_state, str) or
    type(active_agents) is not int or active_agents < 0 or
    type(gateway_busy) is not bool or
    type(gateway_drainable) is not bool or
    not isinstance(readiness, dict) or
    not isinstance(readiness.get("status"), str) or
    (isinstance(readiness, dict) and "checks" in readiness and not isinstance(checks, dict))
)
if api_server_present:
    if (
        not isinstance(api_server, dict) or
        type(api_server.get("active_runs")) is not int or api_server["active_runs"] < 0
    ):
        schema_invalid = True
    else:
        active_runs = api_server["active_runs"]
elif not queues_present:
    schema_invalid = True

if queues_present:
    if not isinstance(background_queues, dict):
        schema_invalid = True
    else:
        for counter in ("active_api_runs", "process_completions", "active_delegations"):
            count = background_queues.get(counter)
            if type(count) is not int or count < 0:
                schema_invalid = True
                break
            queue_counts[counter] = count

if schema_invalid:
    print("gateway health unreadable")
elif status != "ok":
    print("gateway status %s" % status)
elif gateway_state != "running":
    print("gateway state %s" % gateway_state)
elif active_agents > 0:
    print("gateway has %d active agent(s)" % active_agents)
elif gateway_busy:
    print("gateway reports busy")
elif not gateway_drainable:
    print("gateway is not drainable")
elif readiness["status"] != "ok":
    print("gateway readiness %s" % readiness["status"])
elif active_runs is not None and active_runs > 0:
    print("gateway API has %d active run(s)" % active_runs)
elif any(queue_counts.values()):
    print("gateway background queues are active")
else:
    print("__GATEWAY_IDLE__")
PY
  )"
  rm -f "$body_file"
  if [ "$reason" = "__GATEWAY_IDLE__" ]; then
    return 0
  elif [ -z "$reason" ]; then
    printf 'gateway health unreadable'
  else
    printf '%s' "$reason"
  fi
}

comfy_busy_reason() {
  local body
  body="$(curl --fail --silent --connect-timeout 3 --max-time 8 "$COMFY_URL/queue" 2>/dev/null || true)"
  if [ -z "$body" ]; then
    return 0
  fi
  python3 - "$body" <<'PY' 2>/dev/null || true
import json
import sys
try:
    q = json.loads(sys.argv[1])
    running = q.get("queue_running") or {}
    pending = q.get("queue_pending") or []
    total = len(running) + len(pending)
    if total > 0:
        print("ComfyUI holds %d prompt(s)" % total)
except Exception:
    pass
PY
  return 0
}

hub_busy_reason() {
  local gateway_key="$1"
  local manager_key resolve_status reason
  reason="$(lease_busy_reason)"
  if [ -n "$reason" ]; then
    printf '%s' "$reason"
    return 0
  fi
  reason="$(gateway_busy_reason "$gateway_key")"
  if [ -n "$reason" ]; then
    printf '%s' "$reason"
    return 0
  fi
  if manager_key="$(resolve_manager_key "$gateway_key")"; then
    reason="$(manager_busy_reason "$manager_key")"
  else
    resolve_status=$?
    if [ "$resolve_status" -eq 1 ]; then
      reason="no API key available for manager busy check"
    else
      reason="manager API key configuration invalid"
    fi
  fi
  if [ -n "$reason" ]; then
    printf '%s' "$reason"
    return 0
  fi
  reason="$(comfy_busy_reason)"
  if [ -n "$reason" ]; then
    printf '%s' "$reason"
    return 0
  fi
  return 0
}

mark_update_pending() {
  local current=""
  if [ -f "$PENDING_FILE" ]; then
    current="$(cut -d'|' -f1 < "$PENDING_FILE" 2>/dev/null || true)"
  fi
  if [ "$current" != "$1" ]; then
    printf '%s|%s|%s\n' "$1" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$2" > "$PENDING_FILE.new.$$"
    mv -f "$PENDING_FILE.new.$$" "$PENDING_FILE"
  fi
}

clear_update_pending() {
  rm -f "$PENDING_FILE"
}

defer_update() {
  echo "Update $1 deferred: $2. Marked pending; the timer will retry." >&2
  mark_update_pending "$1" "$2"
  exit 0
}

restore_units() {
  local name
  for name in hermes-hub.service hermes-hub-linux-update.service hermes-hub-linux-update.timer hermes-hub-agent-update.service hermes-hub-agent-update.timer hermes-hub-backup.service hermes-hub-backup.timer hermes-power-monitor.service; do
    if [ -f "$TMP_DIR/unit-backup/$name" ]; then
      atomic_install "$TMP_DIR/unit-backup/$name" "$SERVICE_DIR/$name" 0644
    elif [ -f "$TMP_DIR/unit-backup/$name.missing" ]; then
      rm -f "$SERVICE_DIR/$name"
    fi
  done
  local link_file link_path safe target_file regular_file missing_file restore_target restore_tmp
  for link_file in "$TMP_DIR/link-backup/"*.link; do
    [ -e "$link_file" ] || continue
    link_path="$(cat "$link_file")"
    [ -n "$link_path" ] || continue
    safe="$(basename "$link_file" .link)"
    target_file="$TMP_DIR/link-backup/$safe.target"
    regular_file="$TMP_DIR/link-backup/$safe.regular"
    missing_file="$TMP_DIR/link-backup/$safe.missing"
    if [ -f "$target_file" ]; then
      restore_target=""
      if IFS= read -r -d '' restore_target < "$target_file" && [ -n "$restore_target" ]; then
        atomic_symlink "$restore_target" "$link_path" || echo "WARN: failed to restore managed symlink: $link_path" >&2
      else
        echo "WARN: refusing to restore empty managed symlink target: $link_path" >&2
      fi
    elif [ -f "$regular_file" ]; then
      restore_tmp="${link_path}.restore.$$"
      rm -f -- "$restore_tmp"
      if cp -p -- "$regular_file" "$restore_tmp" && mv -fT -- "$restore_tmp" "$link_path"; then
        :
      else
        rm -f -- "$restore_tmp"
        echo "WARN: failed to restore managed regular file: $link_path" >&2
      fi
    elif [ -f "$missing_file" ]; then
      rm -f "$link_path"
    fi
  done
  if [ "$BACKUP_BUNDLE_PRESENT" = "true" ]; then
    if [ -f "$TMP_DIR/helper-backup/hermes-hub-backup" ]; then
      restore_tmp="$BIN_DIR/hermes-hub-backup.restore.$$"
      rm -f -- "$restore_tmp"
      if cp -p -- "$TMP_DIR/helper-backup/hermes-hub-backup" "$restore_tmp" &&
        mv -fT -- "$restore_tmp" "$BIN_DIR/hermes-hub-backup"; then
        :
      else
        rm -f -- "$restore_tmp"
        echo "WARN: failed to restore hermes-hub-backup helper" >&2
      fi
    elif [ -f "$TMP_DIR/helper-backup/hermes-hub-backup.missing" ]; then
      rm -f "$BIN_DIR/hermes-hub-backup"
    fi
  fi
}

snapshot_managed_links() {
  local managed_path safe target_file regular_file missing_file target
  if ! mkdir -p "$TMP_DIR/link-backup"; then
    echo "ERROR: cannot create private managed-link snapshot directory" >&2
    return 1
  fi
  for managed_path in "$HOME/hermes-hub-linux.sh" "$HOME/patch-hermes-gateway-native.py" "$BIN_DIR/hermes-hub-linux-update" "$BIN_DIR/hermes-hub-agent-update" "$BIN_DIR/hermes-wait-tailscale.sh" "$BIN_DIR/hermes-wait-llama.sh" "$BIN_DIR/hermes-wait-tailscale" "$BIN_DIR/hermes-wait-llama" "$BIN_DIR/hermes-power-monitor.sh" "$BIN_DIR/hermes-power-monitor"; do
    if ! safe="$(printf '%s' "$managed_path" | tr -c 'A-Za-z0-9' '_')"; then
      echo "ERROR: cannot name managed path snapshot: $managed_path" >&2
      return 1
    fi
    target_file="$TMP_DIR/link-backup/$safe.target"
    regular_file="$TMP_DIR/link-backup/$safe.regular"
    missing_file="$TMP_DIR/link-backup/$safe.missing"
    if [ -L "$managed_path" ]; then
      if ! readlink -z -- "$managed_path" > "$target_file"; then
        echo "ERROR: cannot snapshot managed symlink target: $managed_path" >&2
        return 1
      fi
      target=""
      if ! IFS= read -r -d '' target < "$target_file" || [ -z "$target" ]; then
        echo "ERROR: empty managed symlink target: $managed_path" >&2
        return 1
      fi
    elif [ -f "$managed_path" ]; then
      if ! cp -p -- "$managed_path" "$regular_file"; then
        echo "ERROR: cannot snapshot managed regular file: $managed_path" >&2
        return 1
      fi
    elif [ -e "$managed_path" ]; then
      echo "ERROR: unsupported managed path type: $managed_path" >&2
      return 1
    elif ! : > "$missing_file"; then
      echo "ERROR: cannot snapshot missing managed path: $managed_path" >&2
      return 1
    fi
    if ! printf '%s' "$managed_path" > "$TMP_DIR/link-backup/$safe.link"; then
      echo "ERROR: cannot record managed path snapshot: $managed_path" >&2
      return 1
    fi
  done
  return 0
}

rollback() {
  set +e
  echo "ERROR: update failed; rolling back gateway release" >&2
  if [ -n "$PREVIOUS_TARGET" ] && [ -d "$PREVIOUS_TARGET" ]; then
    atomic_symlink "$PREVIOUS_TARGET" "$INSTALL_DIR/current"
  else
    rm -f "$INSTALL_DIR/current"
  fi
  restore_units
  if [ "$OLD_VERSION_PRESENT" = "true" ]; then
    write_version "$OLD_VERSION"
  else
    rm -f "$INSTALL_DIR/VERSION"
  fi
  if command -v systemctl >/dev/null 2>&1; then
    systemctl --user daemon-reload >/dev/null 2>&1 || true
    if [ -n "$PREVIOUS_TARGET" ]; then
      systemctl --user restart "$SERVICE_NAME" >/dev/null 2>&1 || true
    fi
  fi
  if [ -n "$FINAL_RELEASE_DIR" ] && [ -d "$FINAL_RELEASE_DIR" ]; then
    case "$FINAL_RELEASE_DIR" in
      "$INSTALL_DIR"/releases/*) rm -rf "$FINAL_RELEASE_DIR" ;;
      *) echo "WARN: refusing to remove unsafe rollback path: $FINAL_RELEASE_DIR" >&2 ;;
    esac
  fi
  record_failed_release
}

cleanup() {
  local status=$?
  trap - EXIT
  if [ "$TRANSACTION_ACTIVE" = "true" ] && [ "$COMMITTED" != "true" ]; then
    rollback
  elif [ "$COMMITTED" != "true" ] && [ -n "$FINAL_RELEASE_DIR" ] && [ -d "$FINAL_RELEASE_DIR" ]; then
    case "$FINAL_RELEASE_DIR" in
      "$INSTALL_DIR"/releases/*) rm -rf "$FINAL_RELEASE_DIR" || true ;;
    esac
  fi
  if [ -n "$TMP_DIR" ] && [ -d "$TMP_DIR" ]; then
    rm -rf "$TMP_DIR"
  fi
  if [ -n "$LOCK_DIR" ]; then
    rm -f "$LOCK_DIR/pid" 2>/dev/null || true
    rmdir "$LOCK_DIR" 2>/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT

TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
CURL_COMMON=(
  --fail --silent --show-error --location
  --connect-timeout "$CURL_CONNECT_TIMEOUT"
  --retry "$CURL_RETRIES" --retry-delay 2 --retry-connrefused
  --proto '=https' --tlsv1.2
  -H "User-Agent: HermesHub-Linux-Updater"
  -H "Accept: application/vnd.github+json"
)
if [ -n "$TOKEN" ]; then
  # Never pass the token on the command line (visible in ps): curl -K
  # reads it from a 600 file inside TMP_DIR (removed by cleanup trap).
  GITHUB_AUTH_CONF="$TMP_DIR/github-auth.conf"
  printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" > "$GITHUB_AUTH_CONF"
  chmod 600 "$GITHUB_AUTH_CONF"
  CURL_COMMON+=( -K "$GITHUB_AUTH_CONF" )
  TOKEN=""
fi

curl_api() {
  curl "${CURL_COMMON[@]}" --max-time "$CURL_API_MAX_TIME" "$1"
}

select_release() {
  python3 - "$1" "$CHANNEL" <<'PY'
import json
import re
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
channel = sys.argv[2]
releases = payload if isinstance(payload, list) else [payload]

def score_asset(asset):
    name = str(asset.get("name") or "")
    lower = name.lower()
    url = str(asset.get("browser_download_url") or "")
    if not url or "linux" not in lower:
        return None
    if lower.endswith((".tar.gz", ".tgz")):
        score = 30
    elif lower.endswith(".zip"):
        score = 20
    else:
        return None
    if "gateway" in lower:
        score += 20
    if "helper" in lower:
        score += 10
    if "hermeshub" in lower.replace("-", "") or "hermes-hub" in lower:
        score += 10
    return score

for release in releases:
    if not isinstance(release, dict) or release.get("draft"):
        continue
    if channel == "latest" and release.get("prerelease"):
        continue
    tag = str(release.get("tag_name") or release.get("name") or "").strip()
    version = tag.lstrip("vV")
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){2,3}", version):
        continue
    candidates = []
    for asset in release.get("assets") or []:
        score = score_asset(asset)
        if score is not None:
            candidates.append((score, str(asset.get("name") or ""), asset))
    if not candidates:
        continue
    candidates.sort(key=lambda item: (item[0], item[1]), reverse=True)
    asset = candidates[0][2]
    print(tag)
    print(asset.get("name") or "")
    print(asset.get("browser_download_url") or "")
    print(int(asset.get("size") or 0))
    print(asset.get("digest") or "")
    break
else:
    raise SystemExit(3)
PY
}

release_info=""
if [ "$CHANNEL" = "latest" ]; then
  for ((page = 1; page <= MAX_RELEASE_PAGES; page++)); do
    RELEASE_JSON_FILE="$TMP_DIR/releases-page-$page.json"
    curl_api "https://api.github.com/repos/$REPO/releases?per_page=50&page=$page" > "$RELEASE_JSON_FILE"
    if release_info="$(select_release "$RELEASE_JSON_FILE")"; then
      break
    else
      selector_status=$?
      release_info=""
      if [ "$selector_status" -ne 3 ]; then
        exit "$selector_status"
      fi
    fi
    release_count="$(python3 - "$RELEASE_JSON_FILE" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
print(len(payload) if isinstance(payload, list) else 1)
PY
)"
    if [ "$release_count" -lt 50 ]; then
      break
    fi
  done
else
  RELEASE_JSON_FILE="$TMP_DIR/releases.json"
  curl_api "https://api.github.com/repos/$REPO/releases/tags/$CHANNEL" > "$RELEASE_JSON_FILE"
  if ! release_info="$(select_release "$RELEASE_JSON_FILE")"; then
    echo "ERROR: release '$CHANNEL' has no compatible Linux gateway asset" >&2
    exit 1
  fi
fi

if [ -z "$release_info" ]; then
  echo "ERROR: no compatible Linux gateway asset found in the newest $MAX_RELEASE_PAGES release pages" >&2
  exit 1
fi

LATEST_TAG="$(printf '%s\n' "$release_info" | sed -n '1p')"
ASSET_NAME="$(printf '%s\n' "$release_info" | sed -n '2p')"
ASSET_URL="$(printf '%s\n' "$release_info" | sed -n '3p')"
ASSET_SIZE="$(printf '%s\n' "$release_info" | sed -n '4p')"
ASSET_DIGEST="$(printf '%s\n' "$release_info" | sed -n '5p')"
LATEST_VERSION="${LATEST_TAG#v}"
LATEST_VERSION="${LATEST_VERSION#V}"
ASSET_BASENAME="$(basename -- "$ASSET_NAME")"

if ! is_positive_uint "$ASSET_SIZE"; then
  echo "ERROR: GitHub asset metadata has no valid positive size" >&2
  exit 1
fi
if ! python3 - "$ASSET_SIZE" "$MAX_ASSET_MB" <<'PY'
import sys

size = int(sys.argv[1])
limit = int(sys.argv[2]) * 1024 * 1024
raise SystemExit(0 if size <= limit else 1)
PY
then
  echo "ERROR: Linux gateway asset is larger than configured limit (${MAX_ASSET_MB} MB): $ASSET_SIZE bytes" >&2
  exit 1
fi

LOCAL_VERSION_FILE="$INSTALL_DIR/VERSION"
LOCAL_VERSION=""
if [ -f "$LOCAL_VERSION_FILE" ]; then
  OLD_VERSION_PRESENT=true
  LOCAL_VERSION="$(tr -d '[:space:]' < "$LOCAL_VERSION_FILE")"
  OLD_VERSION="$LOCAL_VERSION"
fi

version_compare() {
  python3 - "$1" "$2" <<'PY'
import sys
a = tuple(int(x) for x in sys.argv[1].split("."))
b = tuple(int(x) for x in sys.argv[2].split("."))
n = max(len(a), len(b))
a += (0,) * (n - len(a))
b += (0,) * (n - len(b))
print((a > b) - (a < b))
PY
}

echo "Repo: $REPO"
echo "Compatible release: $LATEST_TAG"
echo "Asset: $ASSET_NAME"
echo "Local: ${LOCAL_VERSION:-none}"

if [ -n "$LOCAL_VERSION" ] && [[ "$LOCAL_VERSION" =~ ^[0-9]+(\.[0-9]+){2,3}$ ]]; then
  comparison="$(version_compare "$LOCAL_VERSION" "$LATEST_VERSION")"
  if [ "$comparison" -gt 0 ] && [ "$ALLOW_DOWNGRADE" != "true" ]; then
    echo "Local version $LOCAL_VERSION is newer; downgrade refused. Use --allow-downgrade explicitly." >&2
    exit 42
  fi
fi

if [ "$CHECK_ONLY" = "true" ]; then
  exit 0
fi

FAILED_RELEASE_KEY="${LATEST_VERSION}|${ASSET_DIGEST}"
if [ "$FORCE" != "true" ] && [ -f "$FAILED_RELEASE_FILE" ] && [ "$(tr -d '\r\n' < "$FAILED_RELEASE_FILE")" = "$FAILED_RELEASE_KEY" ]; then
  echo "Quarantined failed release: $LATEST_VERSION (use --force to retry)"
  exit 0
fi

if [ "$FORCE" != "true" ] && [ -n "$LOCAL_VERSION" ] && [ "$LOCAL_VERSION" = "$LATEST_VERSION" ]; then
  echo "Already installed: $LATEST_VERSION"
  clear_update_pending
  exit 0
fi

# First busy gate: never download/stage/restart while runs or queued
# media work exist. A restart would wipe in-memory agent runs.
if [ "$FORCE" = "true" ]; then
  echo "WARN: --force bypasses the busy gate; in-flight runs may be lost" >&2
else
  PROBE_API_KEY="$(resolve_probe_key)"
  BUSY_REASON="$(hub_busy_reason "$PROBE_API_KEY")"
  if [ -n "$BUSY_REASON" ]; then
    defer_update "$LATEST_VERSION" "$BUSY_REASON"
  fi
fi

ARCHIVE="$TMP_DIR/$ASSET_BASENAME"
echo "Downloading: $ASSET_URL"
curl "${CURL_COMMON[@]}" --max-time "$CURL_DOWNLOAD_MAX_TIME" -o "$ARCHIVE" "$ASSET_URL"

if [ ! -s "$ARCHIVE" ]; then
  echo "ERROR: downloaded archive is empty" >&2
  exit 1
fi
if [ "$ASSET_SIZE" -gt 0 ] && [ "$(wc -c < "$ARCHIVE" | tr -d '[:space:]')" != "$ASSET_SIZE" ]; then
  echo "ERROR: downloaded archive size does not match GitHub metadata" >&2
  exit 1
fi
if [ -z "${ASSET_DIGEST:-}" ]; then
  echo "ERROR: missing sha256 digest for $ASSET_NAME" >&2
  exit 1
fi
if [[ "$ASSET_DIGEST" == sha256:* ]]; then
  need_cmd sha256sum
  EXPECTED_SHA256="${ASSET_DIGEST#sha256:}"
  if [ -z "$EXPECTED_SHA256" ]; then
    echo "ERROR: empty sha256 digest for $ASSET_NAME" >&2
    exit 1
  fi
  ACTUAL_SHA256="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
  if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
    echo "ERROR: downloaded archive SHA-256 mismatch" >&2
    exit 1
  fi
else
  echo "ERROR: unsupported digest format (require sha256:...) for $ASSET_NAME" >&2
  exit 1
fi

EXTRACT_DIR="$TMP_DIR/extract"
mkdir -p "$EXTRACT_DIR"
case "$ASSET_BASENAME" in
  *.tar.gz|*.tgz)
    need_cmd tar
    tar -xzf "$ARCHIVE" -C "$EXTRACT_DIR"
    ;;
  *.zip)
    need_cmd unzip
    unzip -q "$ARCHIVE" -d "$EXTRACT_DIR"
    ;;
  *)
    echo "ERROR: unsupported asset format: $ASSET_BASENAME" >&2
    exit 1
    ;;
esac

find_one() {
  local name="$1"
  find "$EXTRACT_DIR" -type f -name "$name" -print -quit
}

BUNDLE_VERSION_FILE="$(find_one VERSION)"
if [ -z "$BUNDLE_VERSION_FILE" ]; then
  echo "ERROR: archive missing VERSION manifest" >&2
  exit 1
fi
BUNDLE_VERSION="$(tr -d '[:space:]' < "$BUNDLE_VERSION_FILE")"
if [ "$BUNDLE_VERSION" != "$LATEST_VERSION" ]; then
  echo "ERROR: archive VERSION '$BUNDLE_VERSION' does not match release '$LATEST_VERSION'" >&2
  exit 1
fi

declare -A FILE_MODE=(
  [hermes-hub-linux.sh]=0755
  [patch-hermes-gateway-native.py]=0644
  [hermes-hub-linux-update.sh]=0755
  [hermes-hub-agent-update.sh]=0755
  [install-hermes-hub-linux.sh]=0755
  [hermes-hub-linux.service]=0644
  [hermes-hub-linux-update.service]=0644
  [hermes-hub-linux-update.timer]=0644
  [hermes-hub-agent-update.service]=0644
  [hermes-hub-agent-update.timer]=0644
  [hermes-wait-tailscale.sh]=0755
  [hermes-wait-llama.sh]=0755
  [rehub-patch.sh]=0755
  [hermes-power-monitor.sh]=0755
  [hermes-power-monitor.service]=0644
)
declare -A FOUND_FILE=()
for name in "${!FILE_MODE[@]}"; do
  FOUND_FILE[$name]="$(find_one "$name")"
  if [ -z "${FOUND_FILE[$name]}" ]; then
    echo "ERROR: archive missing required file: $name" >&2
    exit 1
  fi
done

BACKUP_BUNDLE_FILES=(hermes-hub-backup.py hermes-hub-backup.service hermes-hub-backup.timer)
BACKUP_FILE_COUNT=0
for name in "${BACKUP_BUNDLE_FILES[@]}"; do
  FOUND_FILE[$name]="$(find_one "$name")"
  if [ -n "${FOUND_FILE[$name]}" ]; then
    BACKUP_FILE_COUNT=$((BACKUP_FILE_COUNT + 1))
  fi
done
if [ "$BACKUP_FILE_COUNT" -ne 0 ] && [ "$BACKUP_FILE_COUNT" -ne "${#BACKUP_BUNDLE_FILES[@]}" ]; then
  echo "ERROR: incomplete backup bundle; include the backup script, service, and timer together" >&2
  exit 1
fi
if [ "$BACKUP_FILE_COUNT" -eq "${#BACKUP_BUNDLE_FILES[@]}" ]; then
  BACKUP_BUNDLE_PRESENT=true
  FILE_MODE[hermes-hub-backup.py]=0755
  FILE_MODE[hermes-hub-backup.service]=0644
  FILE_MODE[hermes-hub-backup.timer]=0644
  if [ -e "$BIN_DIR/hermes-hub-backup" ] && [ ! -f "$BIN_DIR/hermes-hub-backup" ] && [ ! -L "$BIN_DIR/hermes-hub-backup" ]; then
    echo "ERROR: existing backup helper path is not a file or symlink" >&2
    exit 1
  fi
  mkdir -p "$TMP_DIR/helper-backup"
  if [ -L "$BIN_DIR/hermes-hub-backup" ]; then
    if [ -f "$BIN_DIR/hermes-hub-backup" ]; then
      cp -p "$BIN_DIR/hermes-hub-backup" "$TMP_DIR/helper-backup/hermes-hub-backup"
    else
      : > "$TMP_DIR/helper-backup/hermes-hub-backup.missing"
    fi
  elif [ -f "$BIN_DIR/hermes-hub-backup" ]; then
    cp -p "$BIN_DIR/hermes-hub-backup" "$TMP_DIR/helper-backup/hermes-hub-backup"
  else
    : > "$TMP_DIR/helper-backup/hermes-hub-backup.missing"
  fi
  if command -v systemctl >/dev/null 2>&1 && systemctl --user is-enabled --quiet hermes-hub-backup.timer; then
    BACKUP_TIMER_WAS_ENABLED=true
  fi
fi

STAGED_RELEASE="$TMP_DIR/release"
mkdir -p "$STAGED_RELEASE"
for name in "${!FILE_MODE[@]}"; do
  install -m "${FILE_MODE[$name]}" "${FOUND_FILE[$name]}" "$STAGED_RELEASE/$name"
done
GATEWAY_PACKAGE_SOURCE="$(find "$EXTRACT_DIR" -type d -name hermes_hub_gateway -print -quit)"
if [ -n "$GATEWAY_PACKAGE_SOURCE" ] && [ -f "$GATEWAY_PACKAGE_SOURCE/infrastructure/runtime_store.py" ]; then
  cp -a "$GATEWAY_PACKAGE_SOURCE" "$STAGED_RELEASE/hermes_hub_gateway"
  find "$STAGED_RELEASE/hermes_hub_gateway" -type d -name __pycache__ -prune -exec rm -rf {} +
  find "$STAGED_RELEASE/hermes_hub_gateway" -type f -name '*.pyc' -delete
fi
printf '%s\n' "$LATEST_VERSION" > "$STAGED_RELEASE/VERSION"
python3 -m py_compile "$STAGED_RELEASE/patch-hermes-gateway-native.py"

FINAL_RELEASE_DIR="$INSTALL_DIR/releases/${LATEST_VERSION}-$(date +%Y%m%d%H%M%S)-$$"
mv "$STAGED_RELEASE" "$FINAL_RELEASE_DIR"

# Final busy gate (double-checked locking): work may have started while
# downloading. Abort before touching live symlinks; the staged dir is
# discarded and VERSION stays untouched so the next tick retries cleanly.
if [ "$FORCE" != "true" ]; then
  BUSY_REASON="$(hub_busy_reason "$PROBE_API_KEY")"
  if [ -n "$BUSY_REASON" ]; then
    echo "Update $LATEST_VERSION aborted at final check: $BUSY_REASON. Staged release discarded; will retry." >&2
    case "$FINAL_RELEASE_DIR" in
      "$INSTALL_DIR"/releases/*) rm -rf "$FINAL_RELEASE_DIR" ;;
    esac
    FINAL_RELEASE_DIR=""
    mark_update_pending "$LATEST_VERSION" "$BUSY_REASON"
    exit 0
  fi
fi

if [ -L "$INSTALL_DIR/current" ]; then
  PREVIOUS_TARGET="$(readlink -f "$INSTALL_DIR/current" || true)"
fi
mkdir -p "$TMP_DIR/unit-backup"
for name in hermes-hub.service hermes-hub-linux-update.service hermes-hub-linux-update.timer hermes-hub-agent-update.service hermes-hub-agent-update.timer hermes-hub-backup.service hermes-hub-backup.timer hermes-power-monitor.service; do
  if [ -f "$SERVICE_DIR/$name" ]; then
    cp -p "$SERVICE_DIR/$name" "$TMP_DIR/unit-backup/$name"
  else
    : > "$TMP_DIR/unit-backup/$name.missing"
  fi
done
if ! snapshot_managed_links; then
  exit 1
fi

TRANSACTION_ACTIVE=true
atomic_symlink "$FINAL_RELEASE_DIR" "$INSTALL_DIR/current"
atomic_symlink "$INSTALL_DIR/current/hermes-hub-linux.sh" "$HOME/hermes-hub-linux.sh"
atomic_symlink "$INSTALL_DIR/current/patch-hermes-gateway-native.py" "$HOME/patch-hermes-gateway-native.py"
atomic_symlink "$INSTALL_DIR/current/hermes-hub-linux-update.sh" "$BIN_DIR/hermes-hub-linux-update"
atomic_symlink "$INSTALL_DIR/current/hermes-hub-agent-update.sh" "$BIN_DIR/hermes-hub-agent-update"
if [ "$BACKUP_BUNDLE_PRESENT" = "true" ]; then
  atomic_install "$FINAL_RELEASE_DIR/hermes-hub-backup.py" "$BIN_DIR/hermes-hub-backup" 0755
fi
atomic_symlink "$INSTALL_DIR/current/hermes-wait-tailscale.sh" "$BIN_DIR/hermes-wait-tailscale.sh"
atomic_symlink "$INSTALL_DIR/current/hermes-wait-llama.sh" "$BIN_DIR/hermes-wait-llama.sh"
atomic_symlink "$INSTALL_DIR/current/hermes-wait-tailscale.sh" "$BIN_DIR/hermes-wait-tailscale"
atomic_symlink "$INSTALL_DIR/current/hermes-wait-llama.sh" "$BIN_DIR/hermes-wait-llama"
atomic_symlink "$INSTALL_DIR/current/hermes-power-monitor.sh" "$BIN_DIR/hermes-power-monitor.sh"
atomic_symlink "$INSTALL_DIR/current/hermes-power-monitor.sh" "$BIN_DIR/hermes-power-monitor"

atomic_install "$FINAL_RELEASE_DIR/hermes-hub-linux.service" "$SERVICE_DIR/hermes-hub.service" 0644
atomic_install "$FINAL_RELEASE_DIR/hermes-hub-linux-update.service" "$SERVICE_DIR/hermes-hub-linux-update.service" 0644
atomic_install "$FINAL_RELEASE_DIR/hermes-hub-linux-update.timer" "$SERVICE_DIR/hermes-hub-linux-update.timer" 0644
atomic_install "$FINAL_RELEASE_DIR/hermes-hub-agent-update.service" "$SERVICE_DIR/hermes-hub-agent-update.service" 0644
atomic_install "$FINAL_RELEASE_DIR/hermes-hub-agent-update.timer" "$SERVICE_DIR/hermes-hub-agent-update.timer" 0644
if [ "$BACKUP_BUNDLE_PRESENT" = "true" ]; then
  atomic_install "$FINAL_RELEASE_DIR/hermes-hub-backup.service" "$SERVICE_DIR/hermes-hub-backup.service" 0644
  atomic_install "$FINAL_RELEASE_DIR/hermes-hub-backup.timer" "$SERVICE_DIR/hermes-hub-backup.timer" 0644
fi
atomic_install "$FINAL_RELEASE_DIR/hermes-power-monitor.service" "$SERVICE_DIR/hermes-power-monitor.service" 0644

if [ "$BACKUP_BUNDLE_PRESENT" = "true" ] && [ "$BACKUP_TIMER_WAS_ENABLED" = "true" ]; then
  systemctl --user enable hermes-hub-backup.timer
fi

if [ "$RESTART" = "true" ]; then
  if ! command -v systemctl >/dev/null 2>&1; then
    echo "ERROR: systemctl missing; cannot verify requested restart" >&2
    exit 1
  fi
  systemctl --user daemon-reload
  systemctl --user restart "$SERVICE_NAME"

  PROBE_API_KEY="$(resolve_probe_key)"
  if [ -z "$PROBE_API_KEY" ]; then
    echo "ERROR: gateway API key missing; configure HERMES_API_KEY or $API_SERVER_KEY_FILE" >&2
    exit 1
  fi

  probe_ok=false
  # Chiave via file di config curl (-K), mai in argv (visibile in `ps`).
  PROBE_AUTH_CONF="$(mktemp "$TMP_DIR/gateway-probe-auth.XXXXXX")"
  if ! write_curl_auth_config "$PROBE_API_KEY" "$PROBE_AUTH_CONF"; then
    rm -f "$PROBE_AUTH_CONF"
    echo "ERROR: gateway API key configuration is invalid" >&2
    exit 1
  fi
  PROBE_API_KEY=""
  for _ in $(seq 1 "$PROBE_ATTEMPTS"); do
    if curl --fail --silent --show-error \
      --connect-timeout 2 --max-time 5 \
      -K "$PROBE_AUTH_CONF" \
      "$PROBE_URL" | python3 -c '
import json
import sys

try:
    data = json.load(sys.stdin)
except Exception:
    raise SystemExit(1)

def nonempty_string(value):
    return isinstance(value, str) and bool(value.strip())

auth = data.get("auth") if isinstance(data, dict) else None
runtime = data.get("runtime") if isinstance(data, dict) else None
features = data.get("features") if isinstance(data, dict) else None
endpoints = data.get("endpoints") if isinstance(data, dict) else None
if (
    not isinstance(data, dict)
    or data.get("object") != "hermes.api_server.capabilities"
    or data.get("platform") != "hermes-agent"
    or not nonempty_string(data.get("model"))
    or not isinstance(data.get("jarvis"), dict)
    or not isinstance(auth, dict)
    or not nonempty_string(auth.get("type"))
    or type(auth.get("required")) is not bool
    or not isinstance(runtime, dict)
    or not nonempty_string(runtime.get("mode"))
    or not isinstance(features, dict)
    or any(features.get(name) is not True for name in (
        "chat_completions", "chat_completions_streaming", "hermes_native"
    ))
    or not isinstance(data.get("bot_mode"), dict)
    or not isinstance(endpoints, dict)
):
    raise SystemExit(1)

required_endpoints = {
    "chat_completions": ("POST", "/v1/chat/completions"),
    "responses": ("POST", "/v1/responses"),
    "runs": ("POST", "/v1/runs"),
    "hermes_native": ("POST", "/v1/hermes/native"),
}
for name, (method, path) in required_endpoints.items():
    endpoint = endpoints.get(name)
    if not isinstance(endpoint, dict) or endpoint.get("method") != method or endpoint.get("path") != path:
        raise SystemExit(1)
' >/dev/null 2>&1; then
      probe_ok=true
      break
    fi
    sleep "$PROBE_SLEEP_SECONDS"
  done
  rm -f "$PROBE_AUTH_CONF"
  if [ "$probe_ok" != "true" ]; then
    echo "ERROR: gateway readiness probe failed after restart: $PROBE_URL" >&2
    exit 1
  fi

  if systemctl --user is-active --quiet hermes-hub-linux-update.timer; then
    systemctl --user restart hermes-hub-linux-update.timer
  fi
  if systemctl --user is-active --quiet hermes-hub-agent-update.timer; then
    systemctl --user restart hermes-hub-agent-update.timer
  fi
fi

write_version "$LATEST_VERSION"
if [ -n "$PREVIOUS_TARGET" ] && [ -d "$PREVIOUS_TARGET" ]; then
  atomic_symlink "$PREVIOUS_TARGET" "$INSTALL_DIR/previous"
fi
COMMITTED=true
rm -f "$FAILED_RELEASE_FILE"
clear_update_pending

echo "Installed: $LATEST_VERSION"
echo "Launcher: $HOME/hermes-hub-linux.sh"
echo "Updater: $BIN_DIR/hermes-hub-linux-update"

if [ "$RESTART" = "true" ]; then
  echo "Restarted and verified: $SERVICE_NAME"
  if systemctl --user is-active --quiet hermes-power-monitor.service 2>/dev/null || systemctl --user is-enabled --quiet hermes-power-monitor.service 2>/dev/null; then
    systemctl --user restart hermes-power-monitor.service || true
    echo "Restarted: hermes-power-monitor.service"
  fi
fi
