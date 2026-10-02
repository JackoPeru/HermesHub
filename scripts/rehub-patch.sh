#!/usr/bin/env bash
# rehub-patch: re-apply the Hermes Hub gateway patch after an upstream agent update.
# Idempotent and safe: checks first, restarts the gateway only when files changed,
# then probes the Hub endpoints. Run after every `git pull` in ~/.hermes/hermes-agent.
set -euo pipefail
PATCHER_DIR="${HERMES_HUB_PATCHER_DIR:-$HOME/.hermes/hh187-patcher}"
AGENT_ROOT="${HERMES_HUB_AGENT_ROOT:-$HOME/.hermes/hermes-agent}"
TARGET="$AGENT_ROOT/gateway/platforms/api_server.py"
SERVICE="${HERMES_HUB_SERVICE:-hermes-hub.service}"
HUB_PORT="${HERMES_API_PORT:-8642}"

if [ ! -f "$PATCHER_DIR/patch-hermes-gateway-native.py" ]; then
  echo "ERROR: patcher not found in $PATCHER_DIR" >&2
  exit 1
fi
if [ ! -f "$TARGET" ]; then
  echo "ERROR: target not found: $TARGET" >&2
  exit 1
fi

echo "== patch check =="
set +e
(cd "$PATCHER_DIR" && python3 patch-hermes-gateway-native.py --target "$TARGET" --check)
check_rc=$?
set -e
if [ "$check_rc" -eq 0 ]; then
  echo "already patched; no restart."
  check_only=1
elif [ "$check_rc" -eq 1 ]; then
  echo "== applying patch =="
  (cd "$PATCHER_DIR" && python3 patch-hermes-gateway-native.py --target "$TARGET")
  check_only=0
else
  echo "ERROR: patch check failed (exit $check_rc)" >&2
  exit 1
fi

markers=$(grep -c HERMES_HUB_ "$TARGET" || true)
echo "markers: $markers"
for method in _handle_news_library _handle_hub_media _handle_hub_media_upload; do
  if ! grep -q "async def $method" "$TARGET"; then
    echo "ERROR: expected Hub method missing after patch: $method" >&2
    exit 1
  fi
done
echo "hub methods present."

if [ "$check_only" = "0" ]; then
  echo "== restarting $SERVICE =="
  systemctl --user restart "$SERVICE"
  sleep 10
  systemctl --user is-active "$SERVICE"
fi

echo "== probing hub endpoints (key from HERMES_API_KEY env or key file) =="
key="${HERMES_HUB_API_KEY:-}"
if [ -z "$key" ] && [ -f "$HOME/.hermes/api_server.key" ]; then
  key="$(tr -d '[:space:]' < "$HOME/.hermes/api_server.key")"
fi
if [ -z "$key" ]; then
  key="$(grep ^HERMES_API_KEY= "$HOME/.hermes/.env" 2>/dev/null | cut -d= -f2- | tr -d '[:space:]')"
fi
fail=0
auth_conf="$(mktemp "${TMPDIR:-/tmp}/rehub-auth.XXXXXX")"
chmod 600 "$auth_conf"
printf 'header = "Authorization: Bearer %s"\n' "$key" > "$auth_conf"
trap 'rm -f "$auth_conf"' EXIT
for path in /v1/capabilities /v1/hub/runtime /v1/hub/hardware /v1/hub/conversations; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 2 --max-time 10 -K "$auth_conf" "http://127.0.0.1:$HUB_PORT$path" || true)
  echo "$path -> $code"
  [ "$code" = "200" ] || fail=1
done
if [ "$fail" -ne 0 ]; then
  echo "ERROR: one or more hub endpoints unhealthy; check journalctl --user -u $SERVICE" >&2
  exit 1
fi
echo "rehub-patch OK: gateway patched and healthy."
