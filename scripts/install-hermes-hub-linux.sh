#!/usr/bin/env bash
set -euo pipefail

# First-time installer for Hermes Hub Linux gateway helper.
# Run from the transferred repo/scripts folder on the Linux server.

INSTALL_DIR="${HERMES_HUB_INSTALL_DIR:-$HOME/.local/share/hermes-hub-gateway}"
BIN_DIR="${HERMES_HUB_BIN_DIR:-$HOME/.local/bin}"
SERVICE_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
VERSION="${HERMES_HUB_BUNDLE_VERSION:-local}"

ENABLE_SERVICE=false
START_SERVICE=false
ENABLE_AUTO_UPDATE=false
ENABLE_POWER_MONITOR=false

usage() {
  cat <<'EOF'
Usage: ./install-hermes-hub-linux.sh [--enable-service] [--start] [--enable-auto-update] [--enable-power-monitor]

Installs:
  ~/hermes-hub-linux.sh
  ~/patch-hermes-gateway-native.py
  ~/.local/bin/hermes-hub-linux-update
  ~/.local/bin/hermes-wait-tailscale.sh
  ~/.local/bin/hermes-wait-llama.sh
  ~/.local/bin/hermes-power-monitor.sh
  ~/.config/systemd/user/hermes-hub.service

Optional:
  --enable-auto-update installs/enables the hourly user timer.
  --enable-power-monitor installs/enables UPS save power monitor service.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --enable-service)
      ENABLE_SERVICE=true
      START_SERVICE=true
      ;;
    --start)
      START_SERVICE=true
      ;;
    --enable-auto-update)
      ENABLE_AUTO_UPDATE=true
      ;;
    --enable-power-monitor)
      ENABLE_POWER_MONITOR=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [ "$VERSION" = "local" ] && [ -f "$SCRIPT_DIR/../VERSION" ]; then
  VERSION="$(tr -d '[:space:]' < "$SCRIPT_DIR/../VERSION")"
elif [ "$VERSION" = "local" ] && [ -f "$SCRIPT_DIR/VERSION" ]; then
  VERSION="$(tr -d '[:space:]' < "$SCRIPT_DIR/VERSION")"
fi
RELEASE_DIR="$INSTALL_DIR/releases/$VERSION"

require_file() {
  if [ ! -f "$SCRIPT_DIR/$1" ]; then
    echo "ERROR: missing $SCRIPT_DIR/$1" >&2
    exit 1
  fi
}

require_file hermes-hub-linux.sh
require_file patch-hermes-gateway-native.py
require_file hermes-hub-linux-update.sh
require_file hermes-hub-linux.service
require_file hermes-wait-tailscale.sh
require_file hermes-wait-llama.sh

mkdir -p "$RELEASE_DIR" "$BIN_DIR" "$SERVICE_DIR"
install -m 0755 "$SCRIPT_DIR/hermes-hub-linux.sh" "$RELEASE_DIR/hermes-hub-linux.sh"
install -m 0644 "$SCRIPT_DIR/patch-hermes-gateway-native.py" "$RELEASE_DIR/patch-hermes-gateway-native.py"
install -m 0755 "$SCRIPT_DIR/hermes-hub-linux-update.sh" "$RELEASE_DIR/hermes-hub-linux-update.sh"
install -m 0755 "$SCRIPT_DIR/install-hermes-hub-linux.sh" "$RELEASE_DIR/install-hermes-hub-linux.sh"
install -m 0644 "$SCRIPT_DIR/hermes-hub-linux.service" "$RELEASE_DIR/hermes-hub-linux.service"
install -m 0755 "$SCRIPT_DIR/hermes-wait-tailscale.sh" "$RELEASE_DIR/hermes-wait-tailscale.sh"
install -m 0755 "$SCRIPT_DIR/hermes-wait-llama.sh" "$RELEASE_DIR/hermes-wait-llama.sh"

if [ -f "$SCRIPT_DIR/hermes-hub-linux-update.service" ]; then
  install -m 0644 "$SCRIPT_DIR/hermes-hub-linux-update.service" "$RELEASE_DIR/hermes-hub-linux-update.service"
fi
if [ -f "$SCRIPT_DIR/hermes-hub-linux-update.timer" ]; then
  install -m 0644 "$SCRIPT_DIR/hermes-hub-linux-update.timer" "$RELEASE_DIR/hermes-hub-linux-update.timer"
fi
if [ -f "$SCRIPT_DIR/hermes-power-monitor.sh" ]; then
  install -m 0755 "$SCRIPT_DIR/hermes-power-monitor.sh" "$RELEASE_DIR/hermes-power-monitor.sh"
fi
if [ -f "$SCRIPT_DIR/hermes-power-monitor.service" ]; then
  install -m 0644 "$SCRIPT_DIR/hermes-power-monitor.service" "$RELEASE_DIR/hermes-power-monitor.service"
fi

ln -sfn "$RELEASE_DIR" "$INSTALL_DIR/current"
ln -sfn "$INSTALL_DIR/current/hermes-hub-linux.sh" "$HOME/hermes-hub-linux.sh"
ln -sfn "$INSTALL_DIR/current/patch-hermes-gateway-native.py" "$HOME/patch-hermes-gateway-native.py"
ln -sfn "$INSTALL_DIR/current/hermes-hub-linux-update.sh" "$BIN_DIR/hermes-hub-linux-update"
ln -sfn "$INSTALL_DIR/current/hermes-wait-tailscale.sh" "$BIN_DIR/hermes-wait-tailscale.sh"
ln -sfn "$INSTALL_DIR/current/hermes-wait-llama.sh" "$BIN_DIR/hermes-wait-llama.sh"
ln -sfn "$INSTALL_DIR/current/hermes-wait-tailscale.sh" "$BIN_DIR/hermes-wait-tailscale"
ln -sfn "$INSTALL_DIR/current/hermes-wait-llama.sh" "$BIN_DIR/hermes-wait-llama"
if [ -f "$INSTALL_DIR/current/hermes-power-monitor.sh" ]; then
  ln -sfn "$INSTALL_DIR/current/hermes-power-monitor.sh" "$BIN_DIR/hermes-power-monitor.sh"
  ln -sfn "$INSTALL_DIR/current/hermes-power-monitor.sh" "$BIN_DIR/hermes-power-monitor"
fi
printf '%s\n' "$VERSION" > "$INSTALL_DIR/VERSION"

cp "$SCRIPT_DIR/hermes-hub-linux.service" "$SERVICE_DIR/hermes-hub.service"
if [ -f "$SCRIPT_DIR/hermes-hub-linux-update.service" ]; then
  cp "$SCRIPT_DIR/hermes-hub-linux-update.service" "$SERVICE_DIR/hermes-hub-linux-update.service"
fi
if [ -f "$SCRIPT_DIR/hermes-hub-linux-update.timer" ]; then
  cp "$SCRIPT_DIR/hermes-hub-linux-update.timer" "$SERVICE_DIR/hermes-hub-linux-update.timer"
fi
if [ -f "$SCRIPT_DIR/hermes-power-monitor.service" ]; then
  cp "$SCRIPT_DIR/hermes-power-monitor.service" "$SERVICE_DIR/hermes-power-monitor.service"
fi

echo "Installed Hermes Gateway helper: $INSTALL_DIR/current"
echo "Command: $BIN_DIR/hermes-hub-linux-update --restart"
echo "Launcher: $HOME/hermes-hub-linux.sh"

if command -v systemctl >/dev/null 2>&1; then
  systemctl --user daemon-reload || true
  if [ "$ENABLE_SERVICE" = "true" ]; then
    systemctl --user enable hermes-hub.service
  fi
  if [ "$START_SERVICE" = "true" ]; then
    systemctl --user restart hermes-hub.service
  fi
  if [ "$ENABLE_AUTO_UPDATE" = "true" ]; then
    systemctl --user enable --now hermes-hub-linux-update.timer
  fi
  if [ "$ENABLE_POWER_MONITOR" = "true" ]; then
    systemctl --user enable --now hermes-power-monitor.service
  fi
else
  echo "WARN: systemctl missing; service not enabled." >&2
fi
