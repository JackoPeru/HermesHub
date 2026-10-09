#!/usr/bin/env bash
# Deploy hermes-gpu-manager su host remoto (default 192.168.1.6).
# Uso: ./deploy-gpu-manager.sh [HOST] [USER].
# HOST/USER legacy restano fallback; gli argomenti espliciti hanno priorità.
# La chiave resta su stdin e in memoria; output contiene solo status e stato sicuro.
set -euo pipefail

REMOTE_DIR="/opt/hermes/gpu-manager"
VENV_PY="/opt/hermes/gpu-manager/venv/bin/python"
SERVICE="hermes-gpu-manager.service"
REMOTE_LOCK_DIR="/tmp/hermes-gpu-manager-deploy.lock"

REMOTE_STAGE=''
REMOTE_LOCK_HELD=false
BACKUP_DIR=''
BACKUP_COMPLETE=false
LIVE_REPLACEMENT_STARTED=false
SSH_CMD=()
SCP_CMD=(scp)

remote_root_script() {
  local script="$1"
  "${SSH_CMD[@]}" 'sudo bash -s' <<< "$script"
}

acquire_deploy_staging() {
  REMOTE_STAGE=''
  REMOTE_LOCK_HELD=false
  if ! "${SSH_CMD[@]}" "mkdir -m 700 '$REMOTE_LOCK_DIR'"; then
    echo "deploy già in corso o lock remoto non disponibile: $REMOTE_LOCK_DIR" >&2
    return 1
  fi
  REMOTE_LOCK_HELD=true
  REMOTE_STAGE="$("${SSH_CMD[@]}" 'mktemp -d /tmp/hermes-gpu-manager.XXXXXXXX')" || return 1
  [[ "$REMOTE_STAGE" =~ ^/tmp/hermes-gpu-manager\.[[:alnum:]]+$ ]] || {
    echo 'staging remoto non valido' >&2
    return 1
  }
  "${SSH_CMD[@]}" "chmod 700 '$REMOTE_STAGE'"
}

release_deploy_staging() {
  local failed=false
  if [[ -n "$REMOTE_STAGE" ]]; then
    if [[ "$REMOTE_STAGE" =~ ^/tmp/hermes-gpu-manager\.[[:alnum:]]+$ ]]; then
      "${SSH_CMD[@]}" "rm -rf -- '$REMOTE_STAGE'" >/dev/null 2>&1 || failed=true
    else
      failed=true
    fi
  fi
  if [[ "$REMOTE_LOCK_HELD" == true ]]; then
    "${SSH_CMD[@]}" "rmdir -- '$REMOTE_LOCK_DIR'" >/dev/null 2>&1 || failed=true
  fi
  REMOTE_STAGE=''
  REMOTE_LOCK_HELD=false
  [[ "$failed" == false ]]
}

deploy_exit_cleanup() {
  local exit_code="$?"
  trap - EXIT ERR INT TERM
  set +e
  if ! release_deploy_staging; then
    echo 'cleanup staging/lock remoto non verificato' >&2
    [[ "$exit_code" -ne 0 ]] || exit_code=1
  fi
  exit "$exit_code"
}

deploy_error_trap() {
  local exit_code="$?"
  trap - ERR
  if [[ "$LIVE_REPLACEMENT_STARTED" == true ]]; then
    if ! rollback; then
      echo "rollback fallito; backup conservato in $BACKUP_DIR" >&2
    fi
  fi
  exit "$exit_code"
}

backup_live() {
  local script
  script=$(cat <<EOF
# HGM_OP=backup
set -euo pipefail
mkdir '$BACKUP_DIR'
cp -a '$REMOTE_DIR/manager.py' '$BACKUP_DIR/manager.py'
cp -a '$REMOTE_DIR/display_bridge.py' '$BACKUP_DIR/display_bridge.py'
if [[ -e '$REMOTE_DIR/character_id' || -L '$REMOTE_DIR/character_id' ]]; then
  cp -a '$REMOTE_DIR/character_id' '$BACKUP_DIR/character_id'
  [[ -e '$BACKUP_DIR/character_id' || -L '$BACKUP_DIR/character_id' ]]
  printf '%s\n' present > '$BACKUP_DIR/character_id.state'
else
  printf '%s\n' absent > '$BACKUP_DIR/character_id.state'
fi
EOF
)
  remote_root_script "$script" || return 1
  BACKUP_COMPLETE=true
}

install_live() {
  local script
  script=$(cat <<EOF
# HGM_OP=install
set -euo pipefail
cp -a '$REMOTE_STAGE/manager.py' '$REMOTE_DIR/manager.py'
cp -a '$REMOTE_STAGE/display_bridge.py' '$REMOTE_DIR/display_bridge.py'
rm -rf -- '$REMOTE_DIR/character_id'
cp -a '$REMOTE_STAGE/character_id' '$REMOTE_DIR/character_id'
'$VENV_PY' -m py_compile '$REMOTE_DIR/manager.py' '$REMOTE_DIR/display_bridge.py'
'$VENV_PY' -m compileall -q '$REMOTE_DIR/character_id'
EOF
)
  remote_root_script "$script"
}

rollback() {
  [[ "$BACKUP_COMPLETE" == true && "$LIVE_REPLACEMENT_STARTED" == true ]] || return 0
  local script
  script=$(cat <<EOF
# HGM_OP=rollback
set -euo pipefail
cp -a '$BACKUP_DIR/manager.py' '$REMOTE_DIR/manager.py'
cp -a '$BACKUP_DIR/display_bridge.py' '$REMOTE_DIR/display_bridge.py'
rm -rf -- '$REMOTE_DIR/character_id'
case "\$(cat '$BACKUP_DIR/character_id.state')" in
  present)
    [[ -e '$BACKUP_DIR/character_id' || -L '$BACKUP_DIR/character_id' ]]
    cp -a '$BACKUP_DIR/character_id' '$REMOTE_DIR/character_id'
    ;;
  absent)
    [[ ! -e '$REMOTE_DIR/character_id' && ! -L '$REMOTE_DIR/character_id' ]]
    ;;
  *) echo 'stato backup character_id non valido' >&2; exit 1 ;;
esac
systemctl restart '$SERVICE'
EOF
)
  if remote_root_script "$script"; then
    LIVE_REPLACEMENT_STARTED=false
    echo "rollback completato da $BACKUP_DIR" >&2
    return 0
  fi
  echo "rollback fallito; backup conservato in $BACKUP_DIR" >&2
  return 1
}

install_with_rollback() {
  if ! backup_live; then
    echo 'backup remoto incompleto; installazione non avviata' >&2
    return 1
  fi
  LIVE_REPLACEMENT_STARTED=true
  if ! install_live; then
    echo 'installazione o compilazione fallita; ripristino backup' >&2
    rollback || return 2
    return 1
  fi
}

remote_verify_program() {
  cat <<'PY'
import json, sys, urllib.error, urllib.request
key = sys.stdin.read()
if not key:
    print("verification key unavailable", file=sys.stderr)
    raise SystemExit(2)

def request(path, token=None, method="GET"):
    headers = {}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    data = b"" if method == "POST" else None
    req = urllib.request.Request(
        "http://127.0.0.1:8643" + path,
        data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()
    except Exception:
        print("HTTP verification transport failed", file=sys.stderr)
        raise SystemExit(2)

no_key, _ = request("/status")
print("no-key http_code:", no_key)
if no_key != 401:
    raise SystemExit(1)
bad_key, _ = request("/status", "wrong-key-deploy-check")
print("bad-key http_code:", bad_key)
if bad_key != 401:
    raise SystemExit(1)
good_key, body = request("/status", key)
print("good-key http_code:", good_key)
if good_key != 200:
    raise SystemExit(1)
try:
    status = json.loads(body)
except Exception:
    raise SystemExit(1)
if not isinstance(status, dict):
    raise SystemExit(1)
print("desired_mode:", status.get("desired_mode"), "| current_state:", status.get("current_state"),
      "| llm_online:", status.get("llm_online"), "| media_online:", status.get("media_online"))
if status.get("llm_serving") is not True:
    raise SystemExit(1)
if "character_training" not in status:
    raise SystemExit(1)
mode_code, _ = request("/mode/media", key, "POST")
print("mode/media-from-localhost http_code:", mode_code, "(atteso 403/409)")
if mode_code not in (403, 409):
    raise SystemExit(1)
characters_code, _ = request("/characters", key)
print("characters http_code:", characters_code, "(atteso 200)")
if characters_code != 200:
    raise SystemExit(1)
PY
}

verify_remote() {
  local program output
  program="$(remote_verify_program)"
  if ! output="$(printf '%s' "$HERMES_GPU_MANAGER_KEY" |
      "${SSH_CMD[@]}" "python3 -c '$program'")"; then
    printf '%s\n' "$output"
    return 1
  fi
  printf '%s\n' "$output"
}

deploy_main() {
  set -Eeuo pipefail
  local DEPLOY_HOST="${1:-${HOST:-192.168.1.6}}"
  local DEPLOY_USER="${2:-${USER:-matteo}}"
  local SCRIPT_DIR SRC_DIR TS
  local REMOTE_STAGE=''
  local REMOTE_LOCK_HELD=false
  local BACKUP_DIR=''
  local BACKUP_COMPLETE=false
  local LIVE_REPLACEMENT_STARTED=false
  local -a SSH_CMD=(ssh "${DEPLOY_USER}@${DEPLOY_HOST}")
  local -a SCP_CMD=(scp)

  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  SRC_DIR="$(dirname "$SCRIPT_DIR")"
  TS="$(date +%Y%m%d-%H%M%S)-$$-$RANDOM"
  BACKUP_DIR="${REMOTE_DIR}/backup-${TS}"

  if [[ ! -f "${SRC_DIR}/manager.py" || ! -f "${SRC_DIR}/display_bridge.py" ]]; then
    echo "Sorgenti non trovati in ${SRC_DIR} (manager.py/display_bridge.py)." >&2
    return 1
  fi
  if [[ ! -d "${SRC_DIR}/character_id" ]]; then
    echo "Pacchetto character_id assente in ${SRC_DIR}." >&2
    return 1
  fi
  if [[ -z "${HERMES_GPU_MANAGER_KEY:-}" ]]; then
    echo 'HERMES_GPU_MANAGER_KEY non impostata: serve per la verify con chiave giusta.' >&2
    return 1
  fi

  echo "== deploy gpu-manager su ${DEPLOY_USER}@${DEPLOY_HOST} (backup ${BACKUP_DIR}) =="
  trap 'deploy_exit_cleanup' EXIT
  trap 'deploy_error_trap' ERR
  trap 'exit 130' INT
  trap 'exit 143' TERM

  echo '== acquisizione lock e staging remoto privato =='
  if ! acquire_deploy_staging; then
    return 1
  fi
  echo '== scp sorgenti nello staging =='
  if ! "${SCP_CMD[@]}" "${SRC_DIR}/manager.py" "${SRC_DIR}/display_bridge.py" \
      "${DEPLOY_USER}@${DEPLOY_HOST}:${REMOTE_STAGE}/"; then
    echo 'copia sorgenti manager fallita' >&2
    return 1
  fi
  if ! "${SCP_CMD[@]}" -r "${SRC_DIR}/character_id" \
      "${DEPLOY_USER}@${DEPLOY_HOST}:${REMOTE_STAGE}/"; then
    echo 'copia pacchetto character_id fallita' >&2
    return 1
  fi

  echo '== backup completo + install + py_compile =='
  if ! install_with_rollback; then
    if [[ "$LIVE_REPLACEMENT_STARTED" == true ]]; then
      echo 'installazione fallita; controllare stato rollback e backup' >&2
    fi
    return 1
  fi

  echo "== restart ${SERVICE} =="
  if ! "${SSH_CMD[@]}" "sudo systemctl restart '${SERVICE}'"; then
    echo 'restart fallito; ripristino backup' >&2
    rollback || return 1
    return 1
  fi
  sleep 8

  echo '== verify HTTP/auth/media/character-id =='
  if ! verify_remote; then
    echo 'verify fallita; ripristino backup' >&2
    rollback || return 1
    return 1
  fi

  LIVE_REPLACEMENT_STARTED=false
  echo "deploy completato (backup conservato in ${BACKUP_DIR})."
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  deploy_main "$@"
fi