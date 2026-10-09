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
ENABLE_BACKUP=false

usage() {
  cat <<'EOF'
Usage: ./install-hermes-hub-linux.sh [--enable-service] [--start] [--enable-auto-update] [--enable-power-monitor] [--enable-backup]

Installs:
  ~/hermes-hub-linux.sh
  ~/patch-hermes-gateway-native.py
  ~/.local/bin/hermes-hub-linux-update
  ~/.local/bin/hermes-hub-agent-update
  ~/.local/bin/hermes-wait-tailscale.sh
  ~/.local/bin/hermes-wait-llama.sh
  ~/.local/bin/hermes-power-monitor.sh
  ~/.config/systemd/user/hermes-hub.service

Optional:
  --enable-auto-update installs/enables the user timer that checks every two minutes.
  --enable-power-monitor installs/enables UPS save power monitor service.
  --enable-backup enables the daily opt-in SQLite backup timer.
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
    --enable-backup)
      ENABLE_BACKUP=true
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
require_file hermes-hub-agent-update.sh
require_file hermes-hub-linux.service
require_file hermes-hub-backup.py
require_file hermes-hub-backup.service
require_file hermes-hub-backup.timer
require_file hermes-hub-agent-update.service
require_file hermes-hub-agent-update.timer
require_file hermes-wait-tailscale.sh
require_file hermes-wait-llama.sh
if [ ! -d "$SCRIPT_DIR/hermes_hub_gateway" ]; then
  echo "ERROR: missing modular gateway package: $SCRIPT_DIR/hermes_hub_gateway" >&2
  exit 1
fi

mkdir -p "$RELEASE_DIR" "$BIN_DIR" "$SERVICE_DIR"
install -m 0755 "$SCRIPT_DIR/hermes-hub-linux.sh" "$RELEASE_DIR/hermes-hub-linux.sh"
install -m 0644 "$SCRIPT_DIR/patch-hermes-gateway-native.py" "$RELEASE_DIR/patch-hermes-gateway-native.py"
install -m 0755 "$SCRIPT_DIR/hermes-hub-linux-update.sh" "$RELEASE_DIR/hermes-hub-linux-update.sh"
install -m 0755 "$SCRIPT_DIR/hermes-hub-agent-update.sh" "$RELEASE_DIR/hermes-hub-agent-update.sh"
install -m 0755 "$SCRIPT_DIR/install-hermes-hub-linux.sh" "$RELEASE_DIR/install-hermes-hub-linux.sh"
install -m 0644 "$SCRIPT_DIR/hermes-hub-linux.service" "$RELEASE_DIR/hermes-hub-linux.service"
install -m 0755 "$SCRIPT_DIR/hermes-hub-backup.py" "$RELEASE_DIR/hermes-hub-backup.py"
install -m 0644 "$SCRIPT_DIR/hermes-hub-backup.service" "$RELEASE_DIR/hermes-hub-backup.service"
install -m 0644 "$SCRIPT_DIR/hermes-hub-backup.timer" "$RELEASE_DIR/hermes-hub-backup.timer"
install -m 0755 "$SCRIPT_DIR/hermes-wait-tailscale.sh" "$RELEASE_DIR/hermes-wait-tailscale.sh"
install -m 0755 "$SCRIPT_DIR/hermes-wait-llama.sh" "$RELEASE_DIR/hermes-wait-llama.sh"
if [ -f "$SCRIPT_DIR/rehub-patch.sh" ]; then
  install -m 0755 "$SCRIPT_DIR/rehub-patch.sh" "$RELEASE_DIR/rehub-patch.sh"
fi
mkdir -p "$RELEASE_DIR/hermes_hub_gateway"
cp -a "$SCRIPT_DIR/hermes_hub_gateway/." "$RELEASE_DIR/hermes_hub_gateway/"
find "$RELEASE_DIR/hermes_hub_gateway" -type d -name __pycache__ -prune -exec rm -rf {} +
find "$RELEASE_DIR/hermes_hub_gateway" -type f -name '*.pyc' -delete

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

atomic_symlink() {
  local target="$1" link="$2" tmp_link="${2}.new.$$"
  rm -f "$tmp_link"
  ln -s "$target" "$tmp_link"
  mv -Tf "$tmp_link" "$link"
}

atomic_install() {
  local source="$1" destination="$2" mode="$3" tmp_destination="${2}.new.$$"
  install -m "$mode" "$source" "$tmp_destination"
  mv -f "$tmp_destination" "$destination"
}

atomic_symlink "$RELEASE_DIR" "$INSTALL_DIR/current"
atomic_symlink "$INSTALL_DIR/current/hermes-hub-linux.sh" "$HOME/hermes-hub-linux.sh"
atomic_symlink "$INSTALL_DIR/current/patch-hermes-gateway-native.py" "$HOME/patch-hermes-gateway-native.py"
atomic_symlink "$INSTALL_DIR/current/hermes-hub-linux-update.sh" "$BIN_DIR/hermes-hub-linux-update"
atomic_symlink "$INSTALL_DIR/current/hermes-hub-agent-update.sh" "$BIN_DIR/hermes-hub-agent-update"
atomic_install "$RELEASE_DIR/hermes-hub-backup.py" "$BIN_DIR/hermes-hub-backup" 0755
atomic_symlink "$INSTALL_DIR/current/hermes-wait-tailscale.sh" "$BIN_DIR/hermes-wait-tailscale.sh"
atomic_symlink "$INSTALL_DIR/current/hermes-wait-llama.sh" "$BIN_DIR/hermes-wait-llama.sh"
atomic_symlink "$INSTALL_DIR/current/hermes-wait-tailscale.sh" "$BIN_DIR/hermes-wait-tailscale"
atomic_symlink "$INSTALL_DIR/current/hermes-wait-llama.sh" "$BIN_DIR/hermes-wait-llama"
if [ -f "$INSTALL_DIR/current/hermes-power-monitor.sh" ]; then
  atomic_symlink "$INSTALL_DIR/current/hermes-power-monitor.sh" "$BIN_DIR/hermes-power-monitor.sh"
  atomic_symlink "$INSTALL_DIR/current/hermes-power-monitor.sh" "$BIN_DIR/hermes-power-monitor"
fi
printf '%s\n' "$VERSION" > "$INSTALL_DIR/VERSION.tmp.$$"
mv -f "$INSTALL_DIR/VERSION.tmp.$$" "$INSTALL_DIR/VERSION"

atomic_install "$SCRIPT_DIR/hermes-hub-linux.service" "$SERVICE_DIR/hermes-hub.service" 0644
if [ -f "$SCRIPT_DIR/hermes-hub-linux-update.service" ]; then
  atomic_install "$SCRIPT_DIR/hermes-hub-linux-update.service" "$SERVICE_DIR/hermes-hub-linux-update.service" 0644
fi
if [ -f "$SCRIPT_DIR/hermes-hub-linux-update.timer" ]; then
  atomic_install "$SCRIPT_DIR/hermes-hub-linux-update.timer" "$SERVICE_DIR/hermes-hub-linux-update.timer" 0644
fi
atomic_install "$SCRIPT_DIR/hermes-hub-agent-update.service" "$SERVICE_DIR/hermes-hub-agent-update.service" 0644
atomic_install "$SCRIPT_DIR/hermes-hub-agent-update.timer" "$SERVICE_DIR/hermes-hub-agent-update.timer" 0644
atomic_install "$SCRIPT_DIR/hermes-hub-backup.service" "$SERVICE_DIR/hermes-hub-backup.service" 0644
atomic_install "$SCRIPT_DIR/hermes-hub-backup.timer" "$SERVICE_DIR/hermes-hub-backup.timer" 0644
if [ -f "$SCRIPT_DIR/hermes-power-monitor.service" ]; then
  atomic_install "$SCRIPT_DIR/hermes-power-monitor.service" "$SERVICE_DIR/hermes-power-monitor.service" 0644
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
    systemctl --user enable --now hermes-hub-agent-update.timer
  fi
  if [ "$ENABLE_POWER_MONITOR" = "true" ]; then
    systemctl --user enable --now hermes-power-monitor.service
  fi
  if [ "$ENABLE_BACKUP" = "true" ]; then
    systemctl --user enable --now hermes-hub-backup.timer
  fi
else
  echo "WARN: systemctl missing; service not enabled." >&2
fi
