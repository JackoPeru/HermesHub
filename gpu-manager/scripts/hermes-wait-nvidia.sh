#!/usr/bin/env bash
# hermes-wait-nvidia.sh — attesa driver Nvidia prima di avviare ComfyUI.
# Deploy: copiare in /home/matteo/.local/bin/hermes-wait-nvidia.sh (vedi ExecStartPre
# di hermes-comfyui.service / hermes-comfyui-direct.service) e rendere eseguibile.
set -u
MAX_WAIT="${HERMES_NVIDIA_WAIT_S:-60}"
if ! [[ "$MAX_WAIT" =~ ^[0-9]+$ ]]; then
    echo "hermes-wait-nvidia: HERMES_NVIDIA_WAIT_S non numerico ('$MAX_WAIT'), uso default 60" >&2
    MAX_WAIT=60
fi
if ! command -v nvidia-smi >/dev/null 2>&1; then
    echo "hermes-wait-nvidia: nvidia-smi non trovato" >&2
    exit 1
fi
DEADLINE=$(( $(date +%s) + MAX_WAIT ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    # --query-gpu fallisce con codice != 0 se il driver non e pronto;
    # retry ogni 2s invece di fallire una tantum al boot.
    if nvidia-smi --query-gpu=index --format=csv,noheader 2>/dev/null | grep -q .; then
        exit 0
    fi
    sleep 2
done
echo "hermes-wait-nvidia: driver Nvidia non pronto dopo ${MAX_WAIT}s" >&2
exit 1
