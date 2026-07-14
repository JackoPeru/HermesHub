#!/usr/bin/env bash
set -euo pipefail

attempts="${HERMES_WAIT_TAILSCALE_ATTEMPTS:-120}"
sleep_seconds="${HERMES_WAIT_TAILSCALE_SLEEP_SECONDS:-2}"

if ! [[ "$attempts" =~ ^[1-9][0-9]*$ && "$sleep_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "invalid Tailscale wait attempts/sleep configuration" >&2
  exit 2
fi

for _ in $(seq 1 "$attempts"); do
  if systemctl is-active --quiet tailscaled.service && timeout 5 tailscale status >/dev/null 2>&1; then
    exit 0
  fi
  sleep "$sleep_seconds"
done

timeout_seconds=$((attempts * sleep_seconds))
echo "tailscale not ready after ${timeout_seconds}s" >&2
exit 1
