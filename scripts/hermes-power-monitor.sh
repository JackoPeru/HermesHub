#!/usr/bin/env bash
set -euo pipefail

# UPS safeguard. A reachable local router is sufficient to prove the protected
# network still has power; public targets avoid one ICMP-only false positive.

MAX_FAILURES="${HERMES_POWER_MAX_FAILURES:-3}"
CHECK_INTERVAL_SEC="${HERMES_POWER_CHECK_INTERVAL_SEC:-300}"
PING_TIMEOUT_SEC="${HERMES_POWER_PING_TIMEOUT_SEC:-5}"
NOTIFICATION_URL="${HERMES_POWER_NOTIFICATION_URL:-http://127.0.0.1:8642/v1/hub/notifications}"

is_positive_uint() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]]
}

for value in "$MAX_FAILURES" "$CHECK_INTERVAL_SEC" "$PING_TIMEOUT_SEC"; do
  if ! is_positive_uint "$value"; then
    echo "ERROR: power monitor numeric settings must be positive integers" >&2
    exit 2
  fi
done

if ! command -v ping >/dev/null 2>&1; then
  echo "ERROR: ping is required by Hermes Power Monitor" >&2
  exit 1
fi

GATEWAY_IP="$(ip route show default 2>/dev/null | awk '/default/ {print $3; exit}' || true)"
DEFAULT_TARGETS="${GATEWAY_IP:+$GATEWAY_IP,}1.1.1.1,8.8.8.8"
TARGETS_RAW="${HERMES_POWER_MONITOR_TARGETS:-$DEFAULT_TARGETS}"
IFS=',' read -r -a TARGETS <<< "$TARGETS_RAW"
if [ "${#TARGETS[@]}" -eq 0 ]; then
  echo "ERROR: no power-monitor targets configured" >&2
  exit 2
fi

read_env_value() {
  local file="$1"
  local key="$2"
  local value first last
  value="$(awk -v key="$key" 'index($0, key "=") == 1 {sub(/^[^=]*=/, ""); print; exit}' "$file" 2>/dev/null || true)"
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

HERMES_API_KEY="${HERMES_API_KEY:-}"
if [ -f "$HOME/.hermes/.env" ]; then
  ENV_KEY="$(read_env_value "$HOME/.hermes/.env" HERMES_API_KEY)"
  if [ -n "$ENV_KEY" ]; then
    HERMES_API_KEY="$ENV_KEY"
  fi
fi
if [ -z "$HERMES_API_KEY" ] && [ -s "$HOME/.hermes/api_server.key" ]; then
  HERMES_API_KEY="$(tr -d '[:space:]' < "$HOME/.hermes/api_server.key")"
fi
if [ -z "$HERMES_API_KEY" ]; then
  echo "ERROR: Hermes API key missing" >&2
  exit 1
fi

network_reachable() {
  local target
  for target in "${TARGETS[@]}"; do
    target="${target//[[:space:]]/}"
    [ -n "$target" ] || continue
    if ping -c 1 -W "$PING_TIMEOUT_SEC" "$target" >/dev/null 2>&1; then
      return 0
    fi
  done
  return 1
}

notify_shutdown() {
  command -v curl >/dev/null 2>&1 || return 0
  curl --fail --silent --show-error \
    --connect-timeout 2 --max-time 5 \
    -X POST "$NOTIFICATION_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $HERMES_API_KEY" \
    -d '{
          "title": "Avviso Spegnimento (UPS)",
          "message": "Spegnimento automatico del server per assenza prolungata della rete protetta.",
          "severity": "critical",
          "source": "hermes-power-monitor"
        }' >/dev/null || true
}

request_shutdown() {
  if ! command -v sudo >/dev/null 2>&1; then
    echo "ERROR: sudo missing; configure a privileged shutdown path for Hermes Power Monitor" >&2
    return 1
  fi
  if ! sudo -n shutdown -h now; then
    echo "ERROR: non-interactive shutdown denied; configure sudoers/polkit before enabling this service" >&2
    return 1
  fi
}

fail_count=0
echo "Avvio Hermes Power Monitor..."
echo "Target: $TARGETS_RAW, intervallo: ${CHECK_INTERVAL_SEC}s, max fallimenti: $MAX_FAILURES"

while true; do
  if network_reachable; then
    if [ "$fail_count" -gt 0 ]; then
      echo "$(date --iso-8601=seconds): connessione ripristinata; contatore azzerato"
    fi
    fail_count=0
  else
    fail_count=$((fail_count + 1))
    echo "$(date --iso-8601=seconds): tutti i probe falliti ($fail_count/$MAX_FAILURES)" >&2
    if [ "$fail_count" -ge "$MAX_FAILURES" ]; then
      echo "$(date --iso-8601=seconds): assenza rete prolungata; richiesta shutdown" >&2
      notify_shutdown
      if request_shutdown; then
        exit 0
      fi
      # Do not exit and let systemd restart in a tight loop. Retry after one
      # normal interval while keeping the alert threshold reached.
      fail_count=$MAX_FAILURES
    fi
  fi
  sleep "$CHECK_INTERVAL_SEC"
done
