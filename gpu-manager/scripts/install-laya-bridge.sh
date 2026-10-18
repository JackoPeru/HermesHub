#!/usr/bin/env bash
# Installa/aggiorna il bridge laya (hermes-laya-bridge) su host remoto.
# Uso: ./install-laya-bridge.sh [HOST] [USER].
# HOST/USER legacy restano fallback; gli argomenti espliciti hanno priorità.
# Serve: gpu-manager/laya_bridge.py + hermes-laya-bridge.service.
# Idempotente e indipendente da deploy-gpu-manager.sh (non tocca il manager).
# La chiave resta su stdin e in memoria; output contiene solo status sicuro.
set -euo pipefail

REMOTE_DIR="/opt/hermes/gpu-manager"
SERVICE="hermes-laya-bridge.service"
# 8644 e' occupata dal gateway Hermes Agent: il bridge usa 8645.
BRIDGE_PORT=8645

# EXIT trap runs after install_main returns: these must outlive the function.
REMOTE_STAGE=''
SSH_CMD=()

install_main() {
  set -Eeuo pipefail
  local DEPLOY_HOST="${1:-${HOST:-192.168.1.6}}"
  local DEPLOY_USER="${2:-${USER:-matteo}}"
  local SCRIPT_DIR SRC_DIR
  REMOTE_STAGE=''
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  SRC_DIR="$(dirname "$SCRIPT_DIR")"
  SSH_CMD=(ssh "${DEPLOY_USER}@${DEPLOY_HOST}")

  if [[ ! -f "${SRC_DIR}/laya_bridge.py" || ! -f "${SRC_DIR}/${SERVICE}" ]]; then
    echo "Sorgenti bridge assenti in ${SRC_DIR} (laya_bridge.py, ${SERVICE})." >&2
    return 1
  fi

  cleanup_staging() {
    local exit_code="$?"
    trap - EXIT INT TERM
    set +e
    if [[ "$REMOTE_STAGE" =~ ^/tmp/hermes-laya-bridge\.[[:alnum:]]+$ ]]; then
      "${SSH_CMD[@]}" "rm -rf -- '$REMOTE_STAGE'" >/dev/null 2>&1 || true
    fi
    exit "$exit_code"
  }
  trap 'cleanup_staging' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  echo "== install bridge laya su ${DEPLOY_USER}@${DEPLOY_HOST} =="
  if ! REMOTE_STAGE="$("${SSH_CMD[@]}" 'mktemp -d /tmp/hermes-laya-bridge.XXXXXXXX')"; then
    echo 'staging remoto non disponibile' >&2
    return 1
  fi
  [[ "$REMOTE_STAGE" =~ ^/tmp/hermes-laya-bridge\.[[:alnum:]]+$ ]] || {
    echo 'staging remoto non valido' >&2
    return 1
  }
  "${SSH_CMD[@]}" "chmod 700 '$REMOTE_STAGE'"

  if ! scp "${SRC_DIR}/laya_bridge.py" "${SRC_DIR}/${SERVICE}" \
      "${DEPLOY_USER}@${DEPLOY_HOST}:${REMOTE_STAGE}/"; then
    echo 'copia sorgenti bridge fallita' >&2
    return 1
  fi

  local script
  script=$(cat <<EOF
set -euo pipefail
install -o matteo -g matteo -m 0640 '$REMOTE_STAGE/laya_bridge.py' '$REMOTE_DIR/laya_bridge.py'
/usr/bin/python3 -m py_compile '$REMOTE_DIR/laya_bridge.py'
install -o root -g root -m 0644 '$REMOTE_STAGE/${SERVICE}' '/etc/systemd/system/${SERVICE}'
systemctl daemon-reload
systemctl enable '${SERVICE}'
systemctl restart '${SERVICE}'
if command -v ufw >/dev/null 2>&1; then
  ufw allow from 100.64.0.0/10 to any port ${BRIDGE_PORT} proto tcp >/dev/null 2>&1 || true
fi
rm -rf -- '$REMOTE_STAGE'
EOF
)
  if ! "${SSH_CMD[@]}" 'sudo bash -s' <<<"$script"; then
    echo 'installazione remota fallita' >&2
    return 1
  fi
  REMOTE_STAGE=''

  sleep 3
  echo "== verify http://127.0.0.1:${BRIDGE_PORT} (/health + auth) =="
  local health
  health="$("${SSH_CMD[@]}" "curl -s -m 5 http://127.0.0.1:${BRIDGE_PORT}/health")"
  echo "health: $health"
  # Sull'identita' del servizio, non solo il codice: un altro processo
  # (es. il gateway) sulla stessa porta risponderebbe 200 a /health.
  if [[ "$health" != *"hermes-laya-bridge"* ]]; then
    echo 'bridge non risponde su /health (porta occupata da un altro servizio?)' >&2
    return 1
  fi

  local no_key
  no_key="$("${SSH_CMD[@]}" "curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:${BRIDGE_PORT}/laya -H 'Content-Type: application/json' -d '{\"text\":\"stato\"}'")"
  echo "/laya senza chiave: $no_key (atteso 401/500)"
  if [[ "$no_key" != "401" && "$no_key" != "500" ]]; then
    echo 'auth bridge inattesa (dovrebbe rifiutare senza chiave)' >&2
    return 1
  fi

  if [[ -n "${HERMES_GPU_MANAGER_KEY:-}" ]]; then
    local program with_key
    program=$(cat <<'PY'
import json, sys, urllib.error, urllib.request
key = sys.stdin.read().strip()
if not key:
    print(0)
    raise SystemExit(0)
req = urllib.request.Request(
    "http://127.0.0.1:8645/laya",
    data=json.dumps({"text": "stato"}).encode(),
    headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
    method="POST")
try:
    with urllib.request.urlopen(req, timeout=20) as response:
        print(response.status)
except urllib.error.HTTPError as error:
    print(error.code)
except Exception:
    print(0)
PY
)
    with_key="$(printf '%s' "$HERMES_GPU_MANAGER_KEY" | \
      "${SSH_CMD[@]}" "python3 -c '$program'")"
    echo "/laya con chiave: $with_key (atteso 200)"
    [[ "$with_key" == "200" ]] || { echo 'verify con chiave fallita' >&2; return 1; }
  else
    echo 'HERMES_GPU_MANAGER_KEY non impostata: salto la verify autenticata.'
  fi

  echo "bridge laya pronto (porta ${BRIDGE_PORT})."
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  install_main "$@"
fi
