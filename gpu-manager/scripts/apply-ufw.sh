#!/usr/bin/env bash
# apply-ufw.sh — applica le regole firewall versionate di HermesHub.
# Uso: sudo ./apply-ufw.sh   (idempotente: resetta e riapplica da zero)
set -euo pipefail
if [ "$(id -u)" -ne 0 ]; then echo "Esegui come root (sudo)." >&2; exit 1; fi

ufw --force reset
ufw default deny incoming
ufw default allow outgoing

# SSH: solo dalla tailnet (niente esposizione pubblica).
ufw allow in on tailscale0 to any port 22 proto tcp comment 'ssh tailnet'

# Hermes hub + manager: solo tailnet.
ufw allow in on tailscale0 to any port 8642 proto tcp comment 'hermes-hub'
ufw allow in on tailscale0 to any port 8643 proto tcp comment 'gpu-manager'

# Tabby LLM: solo tailnet.
ufw allow in on tailscale0 to any port 8000 proto tcp comment 'tabby'

# ComfyUI DIRECT: solo tailnet (il servizio normale resta su loopback).
ufw allow in on tailscale0 to any port 8188 proto tcp comment 'comfyui-direct'

# Kokoro TTS / STT: solo tailnet.
ufw allow in on tailscale0 to any port 8020 proto tcp comment 'kokoro-tts'
ufw allow in on tailscale0 to any port 8010 proto tcp comment 'uninote-stt'

# Laya (orchestratore bot): solo tailnet.
ufw allow in on tailscale0 to any port 11435 proto tcp comment 'laya'

ufw --force enable
ufw status numbered
