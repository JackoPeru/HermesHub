#!/usr/bin/env bash
set -euo pipefail

# Transactional updater for the Hermes Hub Linux gateway helper.

REPO="${HERMES_HUB_REPO:-JackoPeru/HermesHub}"
INSTALL_DIR="${HERMES_HUB_INSTALL_DIR:-$HOME/.local/share/hermes-hub-gateway}"
BIN_DIR="${HERMES_HUB_BIN_DIR:-$HOME/.local/bin}"
SERVICE_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
SERVICE_NAME="${HERMES_HUB_SERVICE:-hermes-hub.service}"
CHANNEL="${HERMES_HUB_CHANNEL:-latest}"
CURL_CONNECT_TIMEOUT="${HERMES_HUB_UPDATE_CONNECT_TIMEOUT:-10}"
CURL_API_MAX_TIME="${HERMES_HUB_UPDATE_API_MAX_TIME:-60}"
CURL_DOWNLOAD_MAX_TIME="${HERMES_HUB_UPDATE_DOWNLOAD_MAX_TIME:-900}"
CURL_RETRIES="${HERMES_HUB_UPDATE_RETRIES:-4}"
MAX_RELEASE_PAGES="${HERMES_HUB_UPDATE_MAX_RELEASE_PAGES:-5}"
MAX_ASSET_MB="${HERMES_HUB_UPDATE_MAX_ASSET_MB:-256}"
PROBE_ATTEMPTS="${HERMES_HUB_UPDATE_PROBE_ATTEMPTS:-30}"
PROBE_SLEEP_SECONDS="${HERMES_HUB_UPDATE_PROBE_SLEEP_SECONDS:-2}"
PROBE_URL="${HERMES_HUB_UPDATE_PROBE_URL:-http://127.0.0.1:${HERMES_API_PORT:-8642}/v1/capabilities}"
API_SERVER_KEY_FILE="${API_SERVER_KEY_FILE:-$HOME/.hermes/api_server.key}"

FORCE=false
CHECK_ONLY=false
RESTART=false
ALLOW_DOWNGRADE=false
TMP_DIR=""
LOCK_DIR=""
TRANSACTION_ACTIVE=false
COMMITTED=false
PREVIOUS_TARGET=""
FINAL_RELEASE_DIR=""
OLD_VERSION_PRESENT=false
OLD_VERSION=""
FAILED_RELEASE_FILE="$INSTALL_DIR/failed-release"

usage() {
  cat <<'EOF'
Usage: hermes-hub-linux-update [--check] [--force] [--allow-downgrade] [--restart] [--no-restart]

Env:
  HERMES_HUB_REPO=JackoPeru/HermesHub
  HERMES_HUB_INSTALL_DIR=$HOME/.local/share/hermes-hub-gateway
  HERMES_HUB_SERVICE=hermes-hub.service
  HERMES_HUB_CHANNEL=latest
  HERMES_HUB_UPDATE_MAX_RELEASE_PAGES=5
  HERMES_HUB_UPDATE_MAX_ASSET_MB=256
  HERMES_HUB_UPDATE_PROBE_URL=http://127.0.0.1:8642/v1/capabilities
  GH_TOKEN or GITHUB_TOKEN for private/rate-limited GitHub API calls
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --check) CHECK_ONLY=true ;;
    --force) FORCE=true ;;
    --allow-downgrade) ALLOW_DOWNGRADE=true ;;
    --restart) RESTART=true ;;
    --no-restart) RESTART=false ;;
    -h|--help) usage; exit 0 ;;
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

is_uint() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

is_positive_uint() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]]
}

for value in "$CURL_CONNECT_TIMEOUT" "$CURL_API_MAX_TIME" "$CURL_DOWNLOAD_MAX_TIME" "$MAX_RELEASE_PAGES" "$MAX_ASSET_MB" "$PROBE_ATTEMPTS" "$PROBE_SLEEP_SECONDS"; do
  if ! is_positive_uint "$value"; then
    echo "ERROR: updater timeout/probe values must be positive integers" >&2
    exit 2
  fi
done
if (( 10#$MAX_RELEASE_PAGES > 10 )); then
  echo "ERROR: updater release page limit cannot exceed 10" >&2
  exit 2
fi
if ! is_uint "$CURL_RETRIES"; then
  echo "ERROR: updater retry count must be a non-negative integer" >&2
  exit 2
fi

need_cmd curl
need_cmd python3
need_cmd find
need_cmd install

mkdir -p "$INSTALL_DIR" "$INSTALL_DIR/releases" "$BIN_DIR" "$SERVICE_DIR"

LOCK_FILE="$INSTALL_DIR/.update.lock"
if command -v flock >/dev/null 2>&1; then
  exec 9>"$LOCK_FILE"
  if ! flock -n 9; then
    echo "ERROR: another Hermes Hub gateway update is already running" >&2
    exit 75
  fi
else
  LOCK_DIR="$LOCK_FILE.d"
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    echo "ERROR: another Hermes Hub gateway update is already running" >&2
    exit 75
  fi
fi

TMP_DIR="$(mktemp -d "$INSTALL_DIR/.update-tmp.XXXXXX")"

atomic_symlink() {
  local target="$1"
  local link="$2"
  local tmp_link="${link}.new.$$"
  rm -f "$tmp_link"
  ln -s "$target" "$tmp_link"
  mv -Tf "$tmp_link" "$link"
}

atomic_install() {
  local source="$1"
  local destination="$2"
  local mode="$3"
  local tmp_destination="${destination}.new.$$"
  install -m "$mode" "$source" "$tmp_destination"
  mv -f "$tmp_destination" "$destination"
}

write_version() {
  local value="$1"
  local tmp_version="$INSTALL_DIR/.VERSION.new.$$"
  printf '%s\n' "$value" > "$tmp_version"
  mv -f "$tmp_version" "$INSTALL_DIR/VERSION"
}

record_failed_release() {
  if [ -z "${LATEST_VERSION:-}" ] || [ -z "${ASSET_DIGEST:-}" ]; then
    return
  fi
  local tmp_failed="$INSTALL_DIR/.failed-release.new.$$"
  printf '%s|%s\n' "$LATEST_VERSION" "$ASSET_DIGEST" > "$tmp_failed"
  mv -f "$tmp_failed" "$FAILED_RELEASE_FILE"
}

restore_units() {
  local name
  for name in hermes-hub.service hermes-hub-linux-update.service hermes-hub-linux-update.timer hermes-power-monitor.service; do
    if [ -f "$TMP_DIR/unit-backup/$name" ]; then
      atomic_install "$TMP_DIR/unit-backup/$name" "$SERVICE_DIR/$name" 0644 || true
    elif [ -f "$TMP_DIR/unit-backup/$name.missing" ]; then
      rm -f "$SERVICE_DIR/$name"
    fi
  done
}

rollback() {
  set +e
  echo "ERROR: update failed; rolling back gateway release" >&2
  if [ -n "$PREVIOUS_TARGET" ] && [ -d "$PREVIOUS_TARGET" ]; then
    atomic_symlink "$PREVIOUS_TARGET" "$INSTALL_DIR/current"
  else
    rm -f "$INSTALL_DIR/current"
  fi
  restore_units
  if [ "$OLD_VERSION_PRESENT" = "true" ]; then
    write_version "$OLD_VERSION"
  else
    rm -f "$INSTALL_DIR/VERSION"
  fi
  if command -v systemctl >/dev/null 2>&1; then
    systemctl --user daemon-reload >/dev/null 2>&1 || true
    if [ -n "$PREVIOUS_TARGET" ]; then
      systemctl --user restart "$SERVICE_NAME" >/dev/null 2>&1 || true
    fi
  fi
  if [ -n "$FINAL_RELEASE_DIR" ] && [ -d "$FINAL_RELEASE_DIR" ]; then
    case "$FINAL_RELEASE_DIR" in
      "$INSTALL_DIR"/releases/*) rm -rf "$FINAL_RELEASE_DIR" ;;
      *) echo "WARN: refusing to remove unsafe rollback path: $FINAL_RELEASE_DIR" >&2 ;;
    esac
  fi
  record_failed_release
}

cleanup() {
  local status=$?
  trap - EXIT
  if [ "$TRANSACTION_ACTIVE" = "true" ] && [ "$COMMITTED" != "true" ]; then
    rollback
  fi
  if [ -n "$TMP_DIR" ] && [ -d "$TMP_DIR" ]; then
    rm -rf "$TMP_DIR"
  fi
  if [ -n "$LOCK_DIR" ]; then
    rmdir "$LOCK_DIR" 2>/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT

TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
CURL_COMMON=(
  --fail --silent --show-error --location
  --connect-timeout "$CURL_CONNECT_TIMEOUT"
  --retry "$CURL_RETRIES" --retry-delay 2 --retry-connrefused
  --proto '=https' --tlsv1.2
  -H "User-Agent: HermesHub-Linux-Updater"
  -H "Accept: application/vnd.github+json"
)
if [ -n "$TOKEN" ]; then
  CURL_COMMON+=( -H "Authorization: Bearer $TOKEN" )
fi

curl_api() {
  curl "${CURL_COMMON[@]}" --max-time "$CURL_API_MAX_TIME" "$1"
}

select_release() {
  python3 - "$1" "$CHANNEL" <<'PY'
import json
import re
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
channel = sys.argv[2]
releases = payload if isinstance(payload, list) else [payload]

def score_asset(asset):
    name = str(asset.get("name") or "")
    lower = name.lower()
    url = str(asset.get("browser_download_url") or "")
    if not url or "linux" not in lower:
        return None
    if lower.endswith((".tar.gz", ".tgz")):
        score = 30
    elif lower.endswith(".zip"):
        score = 20
    else:
        return None
    if "gateway" in lower:
        score += 20
    if "helper" in lower:
        score += 10
    if "hermeshub" in lower.replace("-", "") or "hermes-hub" in lower:
        score += 10
    return score

for release in releases:
    if not isinstance(release, dict) or release.get("draft"):
        continue
    if channel == "latest" and release.get("prerelease"):
        continue
    tag = str(release.get("tag_name") or release.get("name") or "").strip()
    version = tag.lstrip("vV")
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){2,3}", version):
        continue
    candidates = []
    for asset in release.get("assets") or []:
        score = score_asset(asset)
        if score is not None:
            candidates.append((score, str(asset.get("name") or ""), asset))
    if not candidates:
        continue
    candidates.sort(key=lambda item: (item[0], item[1]), reverse=True)
    asset = candidates[0][2]
    print(tag)
    print(asset.get("name") or "")
    print(asset.get("browser_download_url") or "")
    print(int(asset.get("size") or 0))
    print(asset.get("digest") or "")
    break
else:
    raise SystemExit(3)
PY
}

release_info=""
if [ "$CHANNEL" = "latest" ]; then
  for ((page = 1; page <= MAX_RELEASE_PAGES; page++)); do
    RELEASE_JSON_FILE="$TMP_DIR/releases-page-$page.json"
    curl_api "https://api.github.com/repos/$REPO/releases?per_page=50&page=$page" > "$RELEASE_JSON_FILE"
    if release_info="$(select_release "$RELEASE_JSON_FILE")"; then
      break
    else
      selector_status=$?
      release_info=""
      if [ "$selector_status" -ne 3 ]; then
        exit "$selector_status"
      fi
    fi
    release_count="$(python3 - "$RELEASE_JSON_FILE" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
print(len(payload) if isinstance(payload, list) else 1)
PY
)"
    if [ "$release_count" -lt 50 ]; then
      break
    fi
  done
else
  RELEASE_JSON_FILE="$TMP_DIR/releases.json"
  curl_api "https://api.github.com/repos/$REPO/releases/tags/$CHANNEL" > "$RELEASE_JSON_FILE"
  if ! release_info="$(select_release "$RELEASE_JSON_FILE")"; then
    echo "ERROR: release '$CHANNEL' has no compatible Linux gateway asset" >&2
    exit 1
  fi
fi

if [ -z "$release_info" ]; then
  echo "ERROR: no compatible Linux gateway asset found in the newest $MAX_RELEASE_PAGES release pages" >&2
  exit 1
fi

LATEST_TAG="$(printf '%s\n' "$release_info" | sed -n '1p')"
ASSET_NAME="$(printf '%s\n' "$release_info" | sed -n '2p')"
ASSET_URL="$(printf '%s\n' "$release_info" | sed -n '3p')"
ASSET_SIZE="$(printf '%s\n' "$release_info" | sed -n '4p')"
ASSET_DIGEST="$(printf '%s\n' "$release_info" | sed -n '5p')"
LATEST_VERSION="${LATEST_TAG#v}"
LATEST_VERSION="${LATEST_VERSION#V}"
ASSET_BASENAME="$(basename -- "$ASSET_NAME")"

if ! is_positive_uint "$ASSET_SIZE"; then
  echo "ERROR: GitHub asset metadata has no valid positive size" >&2
  exit 1
fi
if ! python3 - "$ASSET_SIZE" "$MAX_ASSET_MB" <<'PY'
import sys

size = int(sys.argv[1])
limit = int(sys.argv[2]) * 1024 * 1024
raise SystemExit(0 if size <= limit else 1)
PY
then
  echo "ERROR: Linux gateway asset is larger than configured limit (${MAX_ASSET_MB} MB): $ASSET_SIZE bytes" >&2
  exit 1
fi

LOCAL_VERSION_FILE="$INSTALL_DIR/VERSION"
LOCAL_VERSION=""
if [ -f "$LOCAL_VERSION_FILE" ]; then
  OLD_VERSION_PRESENT=true
  LOCAL_VERSION="$(tr -d '[:space:]' < "$LOCAL_VERSION_FILE")"
  OLD_VERSION="$LOCAL_VERSION"
fi

version_compare() {
  python3 - "$1" "$2" <<'PY'
import sys
a = tuple(int(x) for x in sys.argv[1].split("."))
b = tuple(int(x) for x in sys.argv[2].split("."))
n = max(len(a), len(b))
a += (0,) * (n - len(a))
b += (0,) * (n - len(b))
print((a > b) - (a < b))
PY
}

echo "Repo: $REPO"
echo "Compatible release: $LATEST_TAG"
echo "Asset: $ASSET_NAME"
echo "Local: ${LOCAL_VERSION:-none}"

if [ -n "$LOCAL_VERSION" ] && [[ "$LOCAL_VERSION" =~ ^[0-9]+(\.[0-9]+){2,3}$ ]]; then
  comparison="$(version_compare "$LOCAL_VERSION" "$LATEST_VERSION")"
  if [ "$comparison" -gt 0 ] && [ "$ALLOW_DOWNGRADE" != "true" ]; then
    echo "Local version $LOCAL_VERSION is newer; downgrade refused. Use --allow-downgrade explicitly." >&2
    exit 0
  fi
fi

if [ "$CHECK_ONLY" = "true" ]; then
  exit 0
fi

FAILED_RELEASE_KEY="${LATEST_VERSION}|${ASSET_DIGEST}"
if [ "$FORCE" != "true" ] && [ -f "$FAILED_RELEASE_FILE" ] && [ "$(tr -d '\r\n' < "$FAILED_RELEASE_FILE")" = "$FAILED_RELEASE_KEY" ]; then
  echo "Quarantined failed release: $LATEST_VERSION (use --force to retry)"
  exit 0
fi

if [ "$FORCE" != "true" ] && [ -n "$LOCAL_VERSION" ] && [ "$LOCAL_VERSION" = "$LATEST_VERSION" ]; then
  echo "Already installed: $LATEST_VERSION"
  exit 0
fi

ARCHIVE="$TMP_DIR/$ASSET_BASENAME"
echo "Downloading: $ASSET_URL"
curl "${CURL_COMMON[@]}" --max-time "$CURL_DOWNLOAD_MAX_TIME" -o "$ARCHIVE" "$ASSET_URL"

if [ ! -s "$ARCHIVE" ]; then
  echo "ERROR: downloaded archive is empty" >&2
  exit 1
fi
if [ "$ASSET_SIZE" -gt 0 ] && [ "$(wc -c < "$ARCHIVE" | tr -d '[:space:]')" != "$ASSET_SIZE" ]; then
  echo "ERROR: downloaded archive size does not match GitHub metadata" >&2
  exit 1
fi
if [[ "$ASSET_DIGEST" == sha256:* ]]; then
  need_cmd sha256sum
  EXPECTED_SHA256="${ASSET_DIGEST#sha256:}"
  ACTUAL_SHA256="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
  if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
    echo "ERROR: downloaded archive SHA-256 mismatch" >&2
    exit 1
  fi
fi

EXTRACT_DIR="$TMP_DIR/extract"
mkdir -p "$EXTRACT_DIR"
case "$ASSET_BASENAME" in
  *.tar.gz|*.tgz)
    need_cmd tar
    tar -xzf "$ARCHIVE" -C "$EXTRACT_DIR"
    ;;
  *.zip)
    need_cmd unzip
    unzip -q "$ARCHIVE" -d "$EXTRACT_DIR"
    ;;
  *)
    echo "ERROR: unsupported asset format: $ASSET_BASENAME" >&2
    exit 1
    ;;
esac

find_one() {
  local name="$1"
  find "$EXTRACT_DIR" -type f -name "$name" -print -quit
}

BUNDLE_VERSION_FILE="$(find_one VERSION)"
if [ -z "$BUNDLE_VERSION_FILE" ]; then
  echo "ERROR: archive missing VERSION manifest" >&2
  exit 1
fi
BUNDLE_VERSION="$(tr -d '[:space:]' < "$BUNDLE_VERSION_FILE")"
if [ "$BUNDLE_VERSION" != "$LATEST_VERSION" ]; then
  echo "ERROR: archive VERSION '$BUNDLE_VERSION' does not match release '$LATEST_VERSION'" >&2
  exit 1
fi

declare -A FILE_MODE=(
  [hermes-hub-linux.sh]=0755
  [patch-hermes-gateway-native.py]=0644
  [hermes-hub-linux-update.sh]=0755
  [install-hermes-hub-linux.sh]=0755
  [hermes-hub-linux.service]=0644
  [hermes-hub-linux-update.service]=0644
  [hermes-hub-linux-update.timer]=0644
  [hermes-wait-tailscale.sh]=0755
  [hermes-wait-llama.sh]=0755
  [hermes-power-monitor.sh]=0755
  [hermes-power-monitor.service]=0644
)
declare -A FOUND_FILE=()
for name in "${!FILE_MODE[@]}"; do
  FOUND_FILE[$name]="$(find_one "$name")"
  if [ -z "${FOUND_FILE[$name]}" ]; then
    echo "ERROR: archive missing required file: $name" >&2
    exit 1
  fi
done

STAGED_RELEASE="$TMP_DIR/release"
mkdir -p "$STAGED_RELEASE"
for name in "${!FILE_MODE[@]}"; do
  install -m "${FILE_MODE[$name]}" "${FOUND_FILE[$name]}" "$STAGED_RELEASE/$name"
done
printf '%s\n' "$LATEST_VERSION" > "$STAGED_RELEASE/VERSION"
python3 -m py_compile "$STAGED_RELEASE/patch-hermes-gateway-native.py"

FINAL_RELEASE_DIR="$INSTALL_DIR/releases/${LATEST_VERSION}-$(date +%Y%m%d%H%M%S)-$$"
mv "$STAGED_RELEASE" "$FINAL_RELEASE_DIR"

if [ -L "$INSTALL_DIR/current" ]; then
  PREVIOUS_TARGET="$(readlink -f "$INSTALL_DIR/current" || true)"
fi
mkdir -p "$TMP_DIR/unit-backup"
for name in hermes-hub.service hermes-hub-linux-update.service hermes-hub-linux-update.timer hermes-power-monitor.service; do
  if [ -f "$SERVICE_DIR/$name" ]; then
    cp -p "$SERVICE_DIR/$name" "$TMP_DIR/unit-backup/$name"
  else
    : > "$TMP_DIR/unit-backup/$name.missing"
  fi
done

TRANSACTION_ACTIVE=true
atomic_symlink "$FINAL_RELEASE_DIR" "$INSTALL_DIR/current"
atomic_symlink "$INSTALL_DIR/current/hermes-hub-linux.sh" "$HOME/hermes-hub-linux.sh"
atomic_symlink "$INSTALL_DIR/current/patch-hermes-gateway-native.py" "$HOME/patch-hermes-gateway-native.py"
atomic_symlink "$INSTALL_DIR/current/hermes-hub-linux-update.sh" "$BIN_DIR/hermes-hub-linux-update"
atomic_symlink "$INSTALL_DIR/current/hermes-wait-tailscale.sh" "$BIN_DIR/hermes-wait-tailscale.sh"
atomic_symlink "$INSTALL_DIR/current/hermes-wait-llama.sh" "$BIN_DIR/hermes-wait-llama.sh"
atomic_symlink "$INSTALL_DIR/current/hermes-wait-tailscale.sh" "$BIN_DIR/hermes-wait-tailscale"
atomic_symlink "$INSTALL_DIR/current/hermes-wait-llama.sh" "$BIN_DIR/hermes-wait-llama"
atomic_symlink "$INSTALL_DIR/current/hermes-power-monitor.sh" "$BIN_DIR/hermes-power-monitor.sh"
atomic_symlink "$INSTALL_DIR/current/hermes-power-monitor.sh" "$BIN_DIR/hermes-power-monitor"

atomic_install "$FINAL_RELEASE_DIR/hermes-hub-linux.service" "$SERVICE_DIR/hermes-hub.service" 0644
atomic_install "$FINAL_RELEASE_DIR/hermes-hub-linux-update.service" "$SERVICE_DIR/hermes-hub-linux-update.service" 0644
atomic_install "$FINAL_RELEASE_DIR/hermes-hub-linux-update.timer" "$SERVICE_DIR/hermes-hub-linux-update.timer" 0644
atomic_install "$FINAL_RELEASE_DIR/hermes-power-monitor.service" "$SERVICE_DIR/hermes-power-monitor.service" 0644

if [ "$RESTART" = "true" ]; then
  if ! command -v systemctl >/dev/null 2>&1; then
    echo "ERROR: systemctl missing; cannot verify requested restart" >&2
    exit 1
  fi
  systemctl --user daemon-reload
  systemctl --user restart "$SERVICE_NAME"

  PROBE_API_KEY="${HERMES_HUB_API_KEY:-${HERMES_API_KEY:-}}"
  if [ -z "$PROBE_API_KEY" ] && [ -s "$API_SERVER_KEY_FILE" ]; then
    PROBE_API_KEY="$(tr -d '[:space:]' < "$API_SERVER_KEY_FILE")"
  fi
  if [ -z "$PROBE_API_KEY" ]; then
    echo "ERROR: gateway API key missing; configure HERMES_API_KEY or $API_SERVER_KEY_FILE" >&2
    exit 1
  fi

  probe_ok=false
  for _ in $(seq 1 "$PROBE_ATTEMPTS"); do
    if curl --fail --silent --show-error \
      --connect-timeout 2 --max-time 5 \
      -H "Authorization: Bearer $PROBE_API_KEY" \
      "$PROBE_URL" | python3 -c 'import json,sys; data=json.load(sys.stdin); assert isinstance(data, dict)' >/dev/null 2>&1; then
      probe_ok=true
      break
    fi
    sleep "$PROBE_SLEEP_SECONDS"
  done
  if [ "$probe_ok" != "true" ]; then
    echo "ERROR: gateway readiness probe failed after restart: $PROBE_URL" >&2
    exit 1
  fi
fi

write_version "$LATEST_VERSION"
if [ -n "$PREVIOUS_TARGET" ] && [ -d "$PREVIOUS_TARGET" ]; then
  atomic_symlink "$PREVIOUS_TARGET" "$INSTALL_DIR/previous"
fi
COMMITTED=true
rm -f "$FAILED_RELEASE_FILE"

echo "Installed: $LATEST_VERSION"
echo "Launcher: $HOME/hermes-hub-linux.sh"
echo "Updater: $BIN_DIR/hermes-hub-linux-update"

if [ "$RESTART" = "true" ]; then
  echo "Restarted and verified: $SERVICE_NAME"
  if systemctl --user is-active --quiet hermes-power-monitor.service 2>/dev/null || systemctl --user is-enabled --quiet hermes-power-monitor.service 2>/dev/null; then
    systemctl --user restart hermes-power-monitor.service || true
    echo "Restarted: hermes-power-monitor.service"
  fi
fi
