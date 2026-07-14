#!/usr/bin/env bash
set -euo pipefail

url="${HERMES_WAIT_LLAMA_URL:-http://127.0.0.1:8000/v1/models}"
attempts="${HERMES_WAIT_LLAMA_ATTEMPTS:-450}"
sleep_seconds="${HERMES_WAIT_LLAMA_SLEEP_SECONDS:-2}"

if ! [[ "$attempts" =~ ^[1-9][0-9]*$ && "$sleep_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "invalid llama wait attempts/sleep configuration" >&2
  exit 2
fi

for _ in $(seq 1 "$attempts"); do
  if curl -fsS --connect-timeout 2 --max-time 5 "$url" >/dev/null 2>&1; then
    exit 0
  fi
  sleep "$sleep_seconds"
done

timeout_seconds=$((attempts * sleep_seconds))
echo "llama.cpp API not ready after ${timeout_seconds}s" >&2
exit 1
