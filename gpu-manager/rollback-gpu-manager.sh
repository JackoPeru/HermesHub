#!/usr/bin/env bash
# Rollback hermes-gpu-manager: restore pure LLM operation like before.
# Usage: ./rollback-gpu-manager.sh [--purge]
set -euo pipefail
echo "== stopping GPU manager stack =="
sudo systemctl disable --now hermes-gpu-manager.service || true
sudo systemctl disable --now hermes-comfyui.service || true
if [ "${1:-}" = "--purge" ]; then
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
