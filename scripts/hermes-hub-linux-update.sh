#!/usr/bin/env bash
set -euo pipefail

# Update Hermes Hub Linux gateway helper from GitHub Releases.
# Downloads the latest linux gateway/helper asset, installs it atomically, then
# optionally restarts the user systemd service.

REPO="${HERMES_HUB_REPO:-JackoPeru/app-interazione-nemoclaw}"
INSTALL_DIR="${HERMES_HUB_INSTALL_DIR:-$HOME/.local/share/hermes-hub-gateway}"
BIN_DIR="${HERMES_HUB_BIN_DIR:-$HOME/.local/bin}"
SERVICE_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
SERVICE_NAME="${HERMES_HUB_SERVICE:-hermes-hub.service}"
CHANNEL="${HERMES_HUB_CHANNEL:-latest}"

FORCE=false
CHECK_ONLY=false
RESTART=false

usage() {
  cat <<'EOF'
Usage: hermes-hub-linux-update [--check] [--force] [--restart] [--no-restart]

Env:
  HERMES_HUB_REPO=JackoPeru/app-interazione-nemoclaw
  HERMES_HUB_INSTALL_DIR=$HOME/.local/share/hermes-hub-gateway
  HERMES_HUB_SERVICE=hermes-hub.service
  GH_TOKEN or GITHUB_TOKEN for private/rate-limited GitHub API calls
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --check)
      CHECK_ONLY=true
      ;;
    --force)
      FORCE=true
      ;;
    --restart)
      RESTART=true
      ;;
    --no-restart)
      RESTART=false
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

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "ERROR: missing required command: $1" >&2
    exit 1
  fi
}

need_cmd curl
need_cmd python3

API_URL="https://api.github.com/repos/$REPO/releases/$CHANNEL"
TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"

curl_api() {
  if [ -n "$TOKEN" ]; then
    curl -fsSL \
      -H "User-Agent: HermesHub-Linux-Updater" \
      -H "Accept: application/vnd.github+json" \
      -H "Authorization: Bearer $TOKEN" \
      "$1"
  else
    curl -fsSL \
      -H "User-Agent: HermesHub-Linux-Updater" \
      -H "Accept: application/vnd.github+json" \
      "$1"
  fi
}

release_json="$(curl_api "$API_URL")"
release_info="$(RELEASE_JSON="$release_json" python3 - <<'PY'
import json
import os
import sys

payload = json.loads(os.environ["RELEASE_JSON"])
tag = payload.get("tag_name") or payload.get("name") or ""
assets = payload.get("assets") or []

def score_asset(asset):
    name = (asset.get("name") or "").lower()
    url = asset.get("browser_download_url") or ""
    if not url:
        return None
    suffix_score = 0
    if name.endswith(".tar.gz") or name.endswith(".tgz"):
        suffix_score = 30
    elif name.endswith(".zip"):
        suffix_score = 20
    else:
        return None
    if "linux" not in name:
        return None
    score = suffix_score
    if "gateway" in name:
        score += 20
    if "helper" in name:
        score += 10
    if "hermeshub" in name.replace("-", "") or "hermes-hub" in name:
        score += 10
    return score

candidates = []
for asset in assets:
    score = score_asset(asset)
    if score is not None:
        candidates.append((score, asset.get("name") or "", asset.get("browser_download_url") or ""))

if not tag:
    raise SystemExit("GitHub release has no tag_name")
if not candidates:
    raise SystemExit("No linux gateway asset found in latest release")

candidates.sort(reverse=True)
_, name, url = candidates[0]
print(tag)
print(name)
print(url)
PY
)"

LATEST_TAG="$(printf '%s\n' "$release_info" | sed -n '1p')"
ASSET_NAME="$(printf '%s\n' "$release_info" | sed -n '2p')"
ASSET_URL="$(printf '%s\n' "$release_info" | sed -n '3p')"
LATEST_VERSION="${LATEST_TAG#v}"
LATEST_VERSION="${LATEST_VERSION#V}"
LOCAL_VERSION_FILE="$INSTALL_DIR/VERSION"
LOCAL_VERSION=""
if [ -f "$LOCAL_VERSION_FILE" ]; then
  LOCAL_VERSION="$(tr -d '[:space:]' < "$LOCAL_VERSION_FILE")"
fi

echo "Repo: $REPO"
echo "Latest: $LATEST_TAG"
echo "Asset: $ASSET_NAME"
echo "Local: ${LOCAL_VERSION:-none}"

if [ "$CHECK_ONLY" = "true" ]; then
  exit 0
fi

if [ "$FORCE" != "true" ] && [ -n "$LOCAL_VERSION" ] && [ "$LOCAL_VERSION" = "$LATEST_VERSION" ]; then
  echo "Already installed: $LATEST_VERSION"
  exit 0
fi

TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

ARCHIVE="$TMP_DIR/$ASSET_NAME"
echo "Downloading: $ASSET_URL"
if [ -n "$TOKEN" ]; then
  curl -fL --proto '=https' --tlsv1.2 \
    -H "User-Agent: HermesHub-Linux-Updater" \
    -H "Authorization: Bearer $TOKEN" \
    -o "$ARCHIVE" \
    "$ASSET_URL"
else
  curl -fL --proto '=https' --tlsv1.2 \
    -H "User-Agent: HermesHub-Linux-Updater" \
    -o "$ARCHIVE" \
    "$ASSET_URL"
fi

EXTRACT_DIR="$TMP_DIR/extract"
mkdir -p "$EXTRACT_DIR"
case "$ASSET_NAME" in
  *.tar.gz|*.tgz)
    need_cmd tar
    tar -xzf "$ARCHIVE" -C "$EXTRACT_DIR"
    ;;
  *.zip)
    need_cmd unzip
    unzip -q "$ARCHIVE" -d "$EXTRACT_DIR"
    ;;
  *)
    echo "ERROR: unsupported asset format: $ASSET_NAME" >&2
    exit 1
    ;;
esac

find_one() {
  local name="$1"
  find "$EXTRACT_DIR" -type f -name "$name" -print -quit
}

LAUNCHER="$(find_one hermes-hub-linux.sh)"
PATCHER="$(find_one patch-hermes-gateway-native.py)"
UPDATER="$(find_one hermes-hub-linux-update.sh)"
INSTALLER="$(find_one install-hermes-hub-linux.sh || true)"
SERVICE="$(find_one hermes-hub-linux.service || true)"
UPDATE_SERVICE="$(find_one hermes-hub-linux-update.service || true)"
UPDATE_TIMER="$(find_one hermes-hub-linux-update.timer || true)"

if [ -z "$LAUNCHER" ] || [ -z "$PATCHER" ] || [ -z "$UPDATER" ]; then
  echo "ERROR: asset missing required files: hermes-hub-linux.sh, patch-hermes-gateway-native.py, hermes-hub-linux-update.sh" >&2
  exit 1
fi

RELEASE_DIR="$INSTALL_DIR/releases/$LATEST_VERSION"
mkdir -p "$RELEASE_DIR" "$BIN_DIR"
install -m 0755 "$LAUNCHER" "$RELEASE_DIR/hermes-hub-linux.sh"
install -m 0644 "$PATCHER" "$RELEASE_DIR/patch-hermes-gateway-native.py"
install -m 0755 "$UPDATER" "$RELEASE_DIR/hermes-hub-linux-update.sh"

if [ -n "$INSTALLER" ]; then
  install -m 0755 "$INSTALLER" "$RELEASE_DIR/install-hermes-hub-linux.sh"
fi
if [ -n "$SERVICE" ]; then
  install -m 0644 "$SERVICE" "$RELEASE_DIR/hermes-hub-linux.service"
fi
if [ -n "$UPDATE_SERVICE" ]; then
  install -m 0644 "$UPDATE_SERVICE" "$RELEASE_DIR/hermes-hub-linux-update.service"
fi
if [ -n "$UPDATE_TIMER" ]; then
  install -m 0644 "$UPDATE_TIMER" "$RELEASE_DIR/hermes-hub-linux-update.timer"
fi

ln -sfn "$RELEASE_DIR" "$INSTALL_DIR/current"
ln -sfn "$INSTALL_DIR/current/hermes-hub-linux.sh" "$HOME/hermes-hub-linux.sh"
ln -sfn "$INSTALL_DIR/current/patch-hermes-gateway-native.py" "$HOME/patch-hermes-gateway-native.py"
ln -sfn "$INSTALL_DIR/current/hermes-hub-linux-update.sh" "$BIN_DIR/hermes-hub-linux-update"
printf '%s\n' "$LATEST_VERSION" > "$LOCAL_VERSION_FILE"

mkdir -p "$SERVICE_DIR"
if [ -n "$SERVICE" ]; then
  cp "$SERVICE" "$SERVICE_DIR/hermes-hub.service"
fi
if [ -n "$UPDATE_SERVICE" ]; then
  cp "$UPDATE_SERVICE" "$SERVICE_DIR/hermes-hub-linux-update.service"
fi
if [ -n "$UPDATE_TIMER" ]; then
  cp "$UPDATE_TIMER" "$SERVICE_DIR/hermes-hub-linux-update.timer"
fi

echo "Installed: $LATEST_VERSION"
echo "Launcher: $HOME/hermes-hub-linux.sh"
echo "Updater: $BIN_DIR/hermes-hub-linux-update"

if [ "$RESTART" = "true" ]; then
  if command -v systemctl >/dev/null 2>&1; then
    systemctl --user daemon-reload || true
    systemctl --user restart "$SERVICE_NAME"
    echo "Restarted: $SERVICE_NAME"
  else
    echo "WARN: systemctl missing; restart manually." >&2
  fi
fi
