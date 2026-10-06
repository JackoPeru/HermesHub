#!/usr/bin/env bash
# Rollback hermes-gpu-manager: restore pure LLM operation like before.
# Usage: ./rollback-gpu-manager.sh [--purge]
set -euo pipefail
echo "== stopping GPU manager stack =="
sudo systemctl disable --now hermes-gpu-manager.service || true
sudo systemctl disable --now hermes-comfyui.service || true
if [ "${1:-}" = "--purge" ]; then
  echo "== backing up config and state before purge =="
  TS="$(date +%Y%m%d-%H%M%S)"
  sudo mkdir -p /var/backups
  if [ -f /etc/hermes/gpu-manager.yaml ]; then
    sudo cp -a /etc/hermes/gpu-manager.yaml "/var/backups/gpu-manager.yaml.${TS}.bak"
    echo "backup config: /var/backups/gpu-manager.yaml.${TS}.bak"
  else
    echo "no config file at /etc/hermes/gpu-manager.yaml, skipping config backup"
  fi
  if [ -f /var/lib/hermes-gpu-manager/state.db ]; then
    sudo cp -a /var/lib/hermes-gpu-manager/state.db "/var/backups/hermes-gpu-manager-state.db.${TS}.bak"
    echo "backup db: /var/backups/hermes-gpu-manager-state.db.${TS}.bak"
  elif [ -d /var/lib/hermes-gpu-manager ]; then
    sudo tar -czf "/var/backups/hermes-gpu-manager.${TS}.tar.gz" -C /var/lib hermes-gpu-manager
    echo "backup state dir: /var/backups/hermes-gpu-manager.${TS}.tar.gz"
  else
    echo "no state at /var/lib/hermes-gpu-manager, skipping db backup"
  fi
  echo "== purging units, config and state =="
  sudo rm -f /etc/systemd/system/hermes-gpu-manager.service /etc/systemd/system/hermes-comfyui.service
  sudo rm -f /etc/hermes/gpu-manager.yaml
  sudo rm -rf /var/lib/hermes-gpu-manager
  sudo systemctl daemon-reload
fi
echo "== restoring LLM backend =="
sudo systemctl enable --now hermes-tabby.service
sleep 5
systemctl is-active hermes-tabby.service
echo "== verifying inference =="
curl -fsS http://127.0.0.1:8000/v1/models | head -c 200
echo
echo "rollback complete: Hermes works as before (LLM only)."
