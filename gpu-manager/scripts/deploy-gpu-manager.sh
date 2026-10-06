#!/usr/bin/env bash
# Deploy hermes-gpu-manager su host remoto (default 192.168.1.6).
# Uso: ./deploy-gpu-manager.sh [HOST] [USER]
#   HOST default 192.168.1.6 (env HOST), USER default matteo (env USER).
#   Richiede HERMES_GPU_MANAGER_KEY in env locale per la verify con chiave giusta
#   (mai stampata: in output solo http code + stato).
# Passi: scp manager.py+display_bridge.py in /tmp, backup datato in
# /opt/hermes/gpu-manager, py_compile col venv, restart servizio, verify
# (curl /status: senza chiave 401, chiave sbagliata 401, chiave giusta 200 +
# stato via python3), rollback automatico da backup se verify fallisce.
# Solo bash+ssh+scp+curl.
set -euo pipefail

HOST="${1:-${HOST:-192.168.1.6}}"
USER="${2:-${USER:-matteo}}"
# Nomi verificati nel repo:
#  - gpu-manager/hermes-gpu-manager.service -> WorkingDirectory=/opt/hermes/gpu-manager,
#    ExecStart=/opt/hermes/gpu-manager/venv/bin/python /opt/hermes/gpu-manager/manager.py
#  - gpu-manager/gpu-manager.yaml -> port: 8643
#  - gpu-manager/manager.py -> GET /status protetto da require_key (401 senza/errata, 200 con Bearer giusta)
SERVICE="hermes-gpu-manager.service"
REMOTE_DIR="/opt/hermes/gpu-manager"
VENV_PY="/opt/hermes/gpu-manager/venv/bin/python"
PORT="8643"
STATUS_URL="http://127.0.0.1:${PORT}/status"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$(dirname "$SCRIPT_DIR")"
TS="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="${REMOTE_DIR}/backup-${TS}"
SSH="ssh ${USER}@${HOST}"

if [ ! -f "${SRC_DIR}/manager.py" ] || [ ! -f "${SRC_DIR}/display_bridge.py" ]; then
  echo "Sorgenti non trovati in ${SRC_DIR} (manager.py/display_bridge.py)." >&2
  exit 1
fi
if [ -z "${HERMES_GPU_MANAGER_KEY:-}" ]; then
  echo "HERMES_GPU_MANAGER_KEY non impostata: serve per la verify con chiave giusta." >&2
  exit 1
fi

echo "== deploy gpu-manager su ${USER}@${HOST} (backup ${BACKUP_DIR}) =="

echo "== scp sorgenti in /tmp =="
scp "${SRC_DIR}/manager.py" "${SRC_DIR}/display_bridge.py" "${USER}@${HOST}:/tmp/"

echo "== backup datato + install + py_compile =="
# shellcheck disable=SC2029
${SSH} "sudo mkdir -p '${BACKUP_DIR}' && sudo cp -a '${REMOTE_DIR}/manager.py' '${BACKUP_DIR}/manager.py' && sudo cp -a '${REMOTE_DIR}/display_bridge.py' '${BACKUP_DIR}/display_bridge.py' && echo \"backup in ${BACKUP_DIR}\""

# shellcheck disable=SC2029
${SSH} "sudo cp -a /tmp/manager.py '${REMOTE_DIR}/manager.py' && sudo cp -a /tmp/display_bridge.py '${REMOTE_DIR}/display_bridge.py' && sudo '${VENV_PY}' -m py_compile '${REMOTE_DIR}/manager.py' '${REMOTE_DIR}/display_bridge.py' && echo compile-ok"

rollback() {
  echo "== ROLLBACK da ${BACKUP_DIR} ==" >&2
  # shellcheck disable=SC2029
  ${SSH} "sudo cp -a '${BACKUP_DIR}/manager.py' '${REMOTE_DIR}/manager.py' && sudo cp -a '${BACKUP_DIR}/display_bridge.py' '${REMOTE_DIR}/display_bridge.py' && sudo systemctl restart '${SERVICE}'" || true
  echo "rollback completato (backup conservato in ${BACKUP_DIR})." >&2
}

echo "== restart ${SERVICE} =="
# shellcheck disable=SC2029
${SSH} "sudo systemctl restart '${SERVICE}'"
sleep 8

echo "== verify ${STATUS_URL} =="
code_no_key="$(${SSH} "curl -s -o /dev/null -w '%{http_code}' --max-time 10 '${STATUS_URL}'" || true)"
echo "no-key http_code: ${code_no_key}"
code_bad_key="$(${SSH} "curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H 'Authorization: Bearer wrong-key-deploy-check' '${STATUS_URL}'" || true)"
echo "bad-key http_code: ${code_bad_key}"

# Chiave giusta: passa via stdin per non esporla in ps/output; stampa solo http code.
code_ok="$(printf '%s' "${HERMES_GPU_MANAGER_KEY}" | ${SSH} 'KEY=$(cat); curl -s -o /tmp/gpu-manager-verify.json -w "%{http_code}" --max-time 10 -H "Authorization: Bearer $KEY" http://127.0.0.1:8643/status' || true)"
echo "good-key http_code: ${code_ok}"

if [ "${code_no_key}" != "401" ] || [ "${code_bad_key}" != "401" ] || [ "${code_ok}" != "200" ]; then
  echo "verify fallita (attesi 401/401/200)." >&2
  rollback
  exit 1
fi

echo "== stato manager (senza segreti) =="
# shellcheck disable=SC2029
if ! ${SSH} "python3 -c \"import json; d=json.load(open('/tmp/gpu-manager-verify.json')); print('desired_mode:', d.get('desired_mode'), '| current_state:', d.get('current_state'), '| llm_online:', d.get('llm_online'), '| media_online:', d.get('media_online'))\""; then
  echo "lettura stato fallita." >&2
  rollback
  exit 1
fi

echo "deploy completato (backup in ${BACKUP_DIR})."
