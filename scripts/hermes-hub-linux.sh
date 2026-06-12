#!/usr/bin/env bash
set -euo pipefail

# Hermes Gateway launcher for Ubuntu/Linux.
# Exposes Hermes Agent API on LAN/Tailscale for Hermes Hub clients and routes
# inference to LM Studio during tests or vLLM on the final headless server.

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
HERMES_ENV="$HERMES_HOME/.env"
HERMES_CONFIG="$HERMES_HOME/config.yaml"
HERMES_INFERENCE_PROVIDER="${HERMES_INFERENCE_PROVIDER:-lmstudio}"
LM_STUDIO_BASE_URL="${LM_STUDIO_BASE_URL:-http://127.0.0.1:1234}"
VLLM_BASE_URL="${VLLM_BASE_URL:-http://127.0.0.1:8000}"
API_SERVER_HOST="${API_SERVER_HOST:-${HERMES_API_HOST:-0.0.0.0}}"
API_SERVER_PORT="${API_SERVER_PORT:-${HERMES_API_PORT:-8642}}"
HERMES_API_HOST="$API_SERVER_HOST"
HERMES_API_PORT="$API_SERVER_PORT"
API_SERVER_KEY="${API_SERVER_KEY:-hermes-hub}"
HERMES_MAX_ITERATIONS="${HERMES_MAX_ITERATIONS:-0}"
HERMES_AUXILIARY_LOCAL_ONLY="${HERMES_AUXILIARY_LOCAL_ONLY:-true}"
HERMES_NATIVE_EVENTS="${HERMES_NATIVE_EVENTS:-true}"
HERMES_RAW_EVENT_PASSTHROUGH="${HERMES_RAW_EVENT_PASSTHROUGH:-true}"
HERMES_NATIVE_GATEWAY_PATCH="${HERMES_NATIVE_GATEWAY_PATCH:-true}"
HERMES_TERMINAL_CWD="${HERMES_TERMINAL_CWD:-$HOME}"
HERMES_VIDEO_LIBRARY_PATH="${HERMES_VIDEO_LIBRARY_PATH:-$HERMES_HOME/media/video}"
HERMES_MEDIA_ROOTS="${HERMES_MEDIA_ROOTS:-$HERMES_TERMINAL_CWD:$HERMES_HOME/cache:$HERMES_HOME/media:$HERMES_VIDEO_LIBRARY_PATH}"
HERMES_HUB_STATE_PATH="${HERMES_HUB_STATE_PATH:-$HERMES_HOME/hub_state.json}"
HERMES_HUB_MEMORY_PATH="${HERMES_HUB_MEMORY_PATH:-$HERMES_HOME/hub_memory.json}"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

mkdir -p "$HERMES_HOME" "$HERMES_VIDEO_LIBRARY_PATH"

default_inference_base_url() {
  case "$HERMES_INFERENCE_PROVIDER" in
    vllm)
      printf '%s/v1\n' "${VLLM_BASE_URL%/}"
      ;;
    *)
      printf '%s/v1\n' "${LM_STUDIO_BASE_URL%/}"
      ;;
  esac
}

detect_lm_studio_model() {
  python3 - "$LM_STUDIO_BASE_URL" <<'PY'
import json
import sys
import urllib.request

base = sys.argv[1].rstrip("/")

def read(path):
    try:
        with urllib.request.urlopen(base + path, timeout=2) as response:
            return json.loads(response.read().decode("utf-8", "replace"))
    except Exception:
        return None

api_models = read("/api/v1/models")
if isinstance(api_models, dict):
    candidates = []
    for item in api_models.get("data", []) or api_models.get("models", []):
        loaded = item.get("loaded_instances") or item.get("loadedInstances") or []
        for instance in loaded:
            config = instance.get("config") or {}
            context = int(config.get("context_length") or instance.get("context_length") or 0)
            model = (
                instance.get("model_key")
                or instance.get("id")
                or item.get("key")
                or item.get("id")
                or item.get("name")
            )
            if model:
                candidates.append((context, str(model)))
    if candidates:
        candidates.sort(reverse=True)
        print(candidates[0][1])
        raise SystemExit(0)

v1_models = read("/v1/models")
if isinstance(v1_models, dict) and v1_models.get("data"):
    first = v1_models["data"][0]
    print(first.get("id") or first.get("name") or "")
PY
}

detect_openai_model() {
  python3 - "$HERMES_INFERENCE_BASE_URL" <<'PY'
import json
import sys
import urllib.request

base = sys.argv[1].rstrip("/")
try:
    with urllib.request.urlopen(base + "/models", timeout=2) as response:
        payload = json.loads(response.read().decode("utf-8", "replace"))
except Exception:
    raise SystemExit(0)

for item in payload.get("data", []) if isinstance(payload, dict) else []:
    model = item.get("id") or item.get("name")
    if model:
        print(model)
        raise SystemExit(0)
PY
}

HERMES_INFERENCE_BASE_URL="${HERMES_INFERENCE_BASE_URL:-$(default_inference_base_url)}"
if [ -n "${HERMES_INFERENCE_MODEL:-}" ]; then
  MODEL_ID="$HERMES_INFERENCE_MODEL"
elif [ "$HERMES_INFERENCE_PROVIDER" = "lmstudio" ] || [ "$HERMES_INFERENCE_PROVIDER" = "lm_studio" ] || [ "$HERMES_INFERENCE_PROVIDER" = "lm-studio" ]; then
  MODEL_ID="$(detect_lm_studio_model || true)"
else
  MODEL_ID="$(detect_openai_model || true)"
fi
MODEL_ID="${MODEL_ID:-hermes-agent}"

cat > "$HERMES_ENV" <<EOF
API_SERVER_ENABLED=true
API_SERVER_HOST=$API_SERVER_HOST
API_SERVER_PORT=$API_SERVER_PORT
API_SERVER_KEY=$API_SERVER_KEY
HERMES_MAX_ITERATIONS=$HERMES_MAX_ITERATIONS
HERMES_AUXILIARY_LOCAL_ONLY=$HERMES_AUXILIARY_LOCAL_ONLY
HERMES_NATIVE_EVENTS=$HERMES_NATIVE_EVENTS
HERMES_RAW_EVENT_PASSTHROUGH=$HERMES_RAW_EVENT_PASSTHROUGH
HERMES_NATIVE_GATEWAY_PATCH=$HERMES_NATIVE_GATEWAY_PATCH
HERMES_INFERENCE_PROVIDER=$HERMES_INFERENCE_PROVIDER
HERMES_INFERENCE_BASE_URL=$HERMES_INFERENCE_BASE_URL
HERMES_INFERENCE_MODEL=$MODEL_ID
GATEWAY_ALLOW_ALL_USERS=true
TERMINAL_CWD=$HERMES_TERMINAL_CWD
HERMES_VIDEO_LIBRARY_PATH=$HERMES_VIDEO_LIBRARY_PATH
HERMES_MEDIA_ROOTS=$HERMES_MEDIA_ROOTS
HERMES_HUB_STATE_PATH=$HERMES_HUB_STATE_PATH
HERMES_HUB_MEMORY_PATH=$HERMES_HUB_MEMORY_PATH
TIRITH_ENABLED=false
EOF

export API_SERVER_ENABLED=true
export API_SERVER_HOST
export API_SERVER_PORT
export API_SERVER_KEY
export HERMES_MAX_ITERATIONS
export HERMES_AUXILIARY_LOCAL_ONLY
export HERMES_NATIVE_EVENTS
export HERMES_RAW_EVENT_PASSTHROUGH
export HERMES_NATIVE_GATEWAY_PATCH
export HERMES_INFERENCE_PROVIDER
export HERMES_INFERENCE_BASE_URL
export HERMES_INFERENCE_MODEL="$MODEL_ID"
export GATEWAY_ALLOW_ALL_USERS=true
export TERMINAL_CWD="$HERMES_TERMINAL_CWD"
export HERMES_VIDEO_LIBRARY_PATH
export HERMES_MEDIA_ROOTS
export HERMES_HUB_STATE_PATH
export HERMES_HUB_MEMORY_PATH
export TIRITH_ENABLED=false

python3 - "$HERMES_CONFIG" "$MODEL_ID" "$HERMES_INFERENCE_PROVIDER" "$HERMES_INFERENCE_BASE_URL" "$HERMES_TERMINAL_CWD" <<'PY' || true
import sys
from pathlib import Path

path = Path(sys.argv[1])
model = sys.argv[2]
provider = sys.argv[3]
base = sys.argv[4].rstrip("/")
cwd = sys.argv[5]

try:
    import yaml
except Exception:
    raise SystemExit(0)

data = {}
if path.exists():
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}

data.setdefault("model", {})
data["model"]["default"] = model
data["model"]["provider"] = provider
data["model"]["base_url"] = base
data.setdefault("terminal", {})
data["terminal"]["cwd"] = cwd
data.setdefault("security", {})
data["security"]["tirith_enabled"] = False

path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
PY

if [ "$HERMES_NATIVE_GATEWAY_PATCH" = "true" ]; then
  PATCHER="$SCRIPT_DIR/patch-hermes-gateway-native.py"
  if [ ! -f "$PATCHER" ]; then
    echo "ERROR: missing native gateway patcher: $PATCHER" >&2
    echo "Copy scripts/patch-hermes-gateway-native.py next to hermes-hub-linux.sh." >&2
    exit 1
  fi
  python3 "$PATCHER"
fi

echo "Hermes Gateway API: http://$HERMES_API_HOST:$HERMES_API_PORT/v1"
echo "Provider: $HERMES_INFERENCE_PROVIDER"
echo "Inference: $HERMES_INFERENCE_BASE_URL"
echo "Loaded model: $MODEL_ID"
echo "Max iterations: unlimited"
echo "Auxiliary local-only: $HERMES_AUXILIARY_LOCAL_ONLY"
echo "Native events: $HERMES_NATIVE_EVENTS"
echo "Raw event passthrough: $HERMES_RAW_EVENT_PASSTHROUGH"
echo "Native gateway patch: $HERMES_NATIVE_GATEWAY_PATCH"
echo "Video library: $HERMES_VIDEO_LIBRARY_PATH"
echo "Media roots: $HERMES_MEDIA_ROOTS"
echo "Hub state: $HERMES_HUB_STATE_PATH"
echo "Hub memory: $HERMES_HUB_MEMORY_PATH"
echo "Config: $HERMES_CONFIG"

exec hermes gateway run --replace
