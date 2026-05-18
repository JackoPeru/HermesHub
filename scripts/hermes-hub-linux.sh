#!/usr/bin/env bash
set -euo pipefail

# Hermes Gateway launcher for Ubuntu/Linux.
# Exposes Hermes Agent API on LAN/Tailscale for Hermes Hub clients and routes
# inference to LM Studio during tests or vLLM on the final headless server.

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
HERMES_ENV="$HERMES_HOME/.env"
HERMES_CONFIG="$HERMES_HOME/config.yaml"
HERMES_INFERENCE_PROVIDER="${HERMES_INFERENCE_PROVIDER:-lm_studio}"
LM_STUDIO_BASE_URL="${LM_STUDIO_BASE_URL:-http://127.0.0.1:1234}"
VLLM_BASE_URL="${VLLM_BASE_URL:-http://127.0.0.1:8000}"
HERMES_API_HOST="${HERMES_API_HOST:-0.0.0.0}"
HERMES_API_PORT="${HERMES_API_PORT:-8642}"
API_SERVER_KEY="${API_SERVER_KEY:-hermes-hub}"
HERMES_MAX_ITERATIONS="${HERMES_MAX_ITERATIONS:-0}"
HERMES_AUXILIARY_LOCAL_ONLY="${HERMES_AUXILIARY_LOCAL_ONLY:-true}"
HERMES_TERMINAL_CWD="${HERMES_TERMINAL_CWD:-$HOME}"
HERMES_VIDEO_LIBRARY_PATH="${HERMES_VIDEO_LIBRARY_PATH:-$HERMES_HOME/media/video}"
HERMES_MEDIA_ROOTS="${HERMES_MEDIA_ROOTS:-$HERMES_TERMINAL_CWD:$HERMES_HOME/cache:$HERMES_HOME/media:$HERMES_VIDEO_LIBRARY_PATH}"

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
    for item in api_models.get("data", []) or api_models.get("models", []):
        loaded = item.get("loaded_instances") or item.get("loadedInstances") or []
        if loaded:
            print(item.get("id") or item.get("name") or loaded[0].get("model_key") or loaded[0].get("id"))
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
elif [ "$HERMES_INFERENCE_PROVIDER" = "lm_studio" ]; then
  MODEL_ID="$(detect_lm_studio_model || true)"
else
  MODEL_ID="$(detect_openai_model || true)"
fi
MODEL_ID="${MODEL_ID:-hermes-agent}"

cat > "$HERMES_ENV" <<EOF
API_SERVER_ENABLED=true
API_SERVER_HOST=$HERMES_API_HOST
API_SERVER_PORT=$HERMES_API_PORT
API_SERVER_KEY=$API_SERVER_KEY
HERMES_MAX_ITERATIONS=$HERMES_MAX_ITERATIONS
HERMES_AUXILIARY_LOCAL_ONLY=$HERMES_AUXILIARY_LOCAL_ONLY
HERMES_INFERENCE_PROVIDER=$HERMES_INFERENCE_PROVIDER
HERMES_INFERENCE_BASE_URL=$HERMES_INFERENCE_BASE_URL
HERMES_INFERENCE_MODEL=$MODEL_ID
GATEWAY_ALLOW_ALL_USERS=true
TERMINAL_CWD=$HERMES_TERMINAL_CWD
HERMES_VIDEO_LIBRARY_PATH=$HERMES_VIDEO_LIBRARY_PATH
HERMES_MEDIA_ROOTS=$HERMES_MEDIA_ROOTS
TIRITH_ENABLED=false
EOF

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

echo "Hermes Gateway API: http://$HERMES_API_HOST:$HERMES_API_PORT/v1"
echo "Provider: $HERMES_INFERENCE_PROVIDER"
echo "Inference: $HERMES_INFERENCE_BASE_URL"
echo "Loaded model: $MODEL_ID"
echo "Max iterations: unlimited"
echo "Auxiliary local-only: $HERMES_AUXILIARY_LOCAL_ONLY"
echo "Video library: $HERMES_VIDEO_LIBRARY_PATH"
echo "Media roots: $HERMES_MEDIA_ROOTS"
echo "Config: $HERMES_CONFIG"

exec hermes gateway run --replace
