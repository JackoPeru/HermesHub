import hashlib
import importlib.util
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import textwrap
import time
import types
import unittest
from pathlib import Path
from typing import Any, Dict, List, Optional
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
PATCHER_PATH = SCRIPTS / "patch-hermes-gateway-native.py"
GATEWAY_PACKAGE_PATH = SCRIPTS / "hermes_hub_gateway"
UPSTREAM_GATEWAY_FIXTURE = ROOT / "tests" / "fixtures" / "hermes-agent-v2026.7.7.2-api_server.py"
CURRENT_UPSTREAM_GATEWAY_FIXTURE = (
    ROOT / "tests" / "fixtures" / "hermes-agent-0a62610f1-api_server.py"
)
VALID_GATEWAY_CAPABILITIES_RESPONSE = json.dumps({
    "object": "hermes.api_server.capabilities",
    "platform": "hermes-agent",
    "model": "test-model",
    "jarvis": {},
    "auth": {"type": "bearer", "required": True},
    "runtime": {"mode": "server_agent"},
    "features": {
        "chat_completions": True,
        "chat_completions_streaming": True,
        "hermes_native": True,
    },
    "bot_mode": {},
    "endpoints": {
        "chat_completions": {"method": "POST", "path": "/v1/chat/completions"},
        "responses": {"method": "POST", "path": "/v1/responses"},
        "runs": {"method": "POST", "path": "/v1/runs"},
        "hermes_native": {"method": "POST", "path": "/v1/hermes/native"},
    },
})
UPDATER_REQUIRED_FILES = (
    "hermes-hub-linux.sh",
    "patch-hermes-gateway-native.py",
    "hermes-hub-linux-update.sh",
    "hermes-hub-agent-update.sh",
    "install-hermes-hub-linux.sh",
    "hermes-hub-backup.py",
    "hermes-hub-linux.service",
    "hermes-hub-linux-update.service",
    "hermes-hub-linux-update.timer",
    "hermes-hub-agent-update.service",
    "hermes-hub-agent-update.timer",
    "hermes-hub-backup.service",
    "hermes-hub-backup.timer",
    "hermes-wait-tailscale.sh",
    "hermes-wait-llama.sh",
    "rehub-patch.sh",
    "hermes-power-monitor.sh",
    "hermes-power-monitor.service",
)
BACKUP_BUNDLE_FILES = (
    "hermes-hub-backup.py",
    "hermes-hub-backup.service",
    "hermes-hub-backup.timer",
)


def find_bash() -> str | None:
    bash = shutil.which("bash")
    if bash:
        return bash
    if os.name == "nt":
        for candidate in (r"C:\Program Files\Git\bin\bash.exe", r"C:\Program Files\Git\usr\bin\bash.exe"):
            if Path(candidate).is_file():
                return candidate
    return None


def find_powershell() -> str | None:
    for name in ("pwsh", "powershell"):
        executable = shutil.which(name)
        if executable:
            return executable
    if os.name == "nt":
        candidate = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe"
        if candidate.is_file():
            return str(candidate)
    return None


def bash_path(bash: str, path: Path) -> str:
    del bash
    if os.name != "nt":
        return str(path)
    resolved = path.resolve()
    drive = resolved.drive
    if len(drive) != 2 or drive[1] != ":":
        raise ValueError(f"Git Bash fixture requires a drive-qualified path: {resolved}")
    return f"/{drive[0].lower()}{resolved.as_posix()[2:]}"


def bash_path_preserving_symlink(bash: str, path: Path) -> str:
    """Convert an absolute Windows path for Git Bash without resolving symlinks."""
    del bash
    absolute = path.absolute()
    if os.name != "nt":
        return str(absolute)
    drive = absolute.drive
    if len(drive) != 2 or drive[1] != ":":
        raise ValueError(f"Git Bash fixture requires a drive-qualified path: {absolute}")
    return f"/{drive[0].lower()}{absolute.as_posix()[2:]}"


def load_patcher():
    spec = importlib.util.spec_from_file_location("hermes_gateway_patcher", PATCHER_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def heredoc_between(source: str, start: str, end: str) -> str:
    start_index = source.index(start) + len(start)
    end_index = source.index(end, start_index)
    return source[start_index:end_index]


class GatewayScriptTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.patcher = load_patcher()


    def test_auto_update_timer_runs_every_two_minutes_and_restarts_gateway(self):
        timer = (SCRIPTS / "hermes-hub-linux-update.timer").read_text(encoding="utf-8")
        service = (SCRIPTS / "hermes-hub-linux-update.service").read_text(encoding="utf-8")
        updater = (SCRIPTS / "hermes-hub-linux-update.sh").read_text(encoding="utf-8")

        self.assertIn("OnBootSec=2min", timer)
        self.assertIn("OnUnitActiveSec=2min", timer)
        self.assertIn("AccuracySec=1s", timer)
        self.assertIn("ExecStart=%h/.local/bin/hermes-hub-linux-update --restart", service)
        self.assertIn("systemctl --user restart hermes-hub-linux-update.timer", updater)

    def _prepare_updater_fixture(
        self,
        root: Path,
        bash: str,
        *,
        probe_ok: bool,
        gateway_page: int = 1,
        asset_size_override: int | None = None,
        manager_state: str = "idle",
        comfy_state: str = "idle",
        manager_flip: bool = False,
        backup_bundle_files: tuple[str, ...] | None = None,
        preexisting_backup_helper: bytes | None = None,
    ) -> dict[str, object]:
        version = "9.8.7"
        home = root / "home"
        install_dir = root / "install"
        bin_dir = root / "bin"
        config_dir = root / "config"
        for directory in (home, install_dir, bin_dir, config_dir):
            directory.mkdir(parents=True, exist_ok=True)
        if preexisting_backup_helper is not None:
            (bin_dir / "hermes-hub-backup").write_bytes(preexisting_backup_helper)

        archive_path = root / f"HermesHub-{version}-linux-gateway.tar.gz"
        archive_files = tuple(name for name in UPDATER_REQUIRED_FILES if name not in BACKUP_BUNDLE_FILES)
        archive_files += BACKUP_BUNDLE_FILES if backup_bundle_files is None else backup_bundle_files
        with tarfile.open(archive_path, "w:gz") as archive:
            for name in archive_files:
                archive.add(SCRIPTS / name, arcname=f"bundle/{name}")
            version_bytes = f"{version}\n".encode()
            version_info = tarfile.TarInfo("bundle/VERSION")
            version_info.size = len(version_bytes)
            version_info.mode = 0o644
            archive.addfile(version_info, io.BytesIO(version_bytes))

        asset_digest = f"sha256:{hashlib.sha256(archive_path.read_bytes()).hexdigest()}"
        release_json = root / "release.json"
        release_json.write_text(
            json.dumps(
                [
                    {
                        "tag_name": f"v{version}",
                        "draft": False,
                        "prerelease": False,
                        "assets": [
                            {
                                "name": archive_path.name,
                                "browser_download_url": f"https://assets.invalid/{archive_path.name}",
                                "size": asset_size_override or archive_path.stat().st_size,
                                "digest": asset_digest,
                            }
                        ],
                    }
                ]
            ),
            encoding="utf-8",
            newline="\n",
        )
        first_page_json = root / "release-page-one.json"
        first_page_json.write_text(
            json.dumps(
                [
                    {
                        "tag_name": f"v99.0.{50 - index}",
                        "draft": False,
                        "prerelease": False,
                        "assets": [
                            {
                                "name": f"HermesHub-99.0.{50 - index}-android.apk",
                                "browser_download_url": f"https://assets.invalid/app-{index}.apk",
                                "size": 1024,
                            }
                        ],
                    }
                    for index in range(50)
                ]
            ),
            encoding="utf-8",
            newline="\n",
        )

        curl_log = root / "curl.log"
        curl_auth_log = root / "curl-auth.log"
        curl_argv_secret_log = root / "curl-argv-secret.log"
        curl_config_log = root / "curl-config.log"
        chmod_log = root / "chmod.log"
        systemctl_log = root / "systemctl.log"
        bash_env = root / "bash-env.sh"
        curl_log.touch()
        curl_auth_log.touch()
        curl_argv_secret_log.touch()
        curl_config_log.touch()
        chmod_log.touch()
        systemctl_log.touch()
        bash_env.write_text(
            textwrap.dedent(
                r'''
                curl() {
                  local output="" url="" auth_config="" write_out="" fail_on_http=false
                  local raw_arg arg auth="" auth_kind="none" config_mode="none" config_safe="none" secret_arg=false
                  local response_body="" manager_status="${FAKE_MANAGER_HTTP_STATUS:-200}"
                  local gateway_status="${FAKE_GATEWAY_HTTP_STATUS:-200}"
                  local -a raw_args=("$@")
                  while [ "$#" -gt 0 ]; do
                    case "$1" in
                      -o|--output)
                        shift
                        output="${1:-}"
                        ;;
                      -K|--config)
                        shift
                        auth_config="${1:-}"
                        ;;
                      -w|--write-out)
                        shift
                        write_out="${1:-}"
                        ;;
                      --fail|-f) fail_on_http=true ;;
                      http://*|https://*) url="$1" ;;
                    esac
                    shift
                  done
                  printf '%s\n' "$url" >> "$FAKE_CURL_LOG"
                  for raw_arg in "${raw_args[@]}"; do
                    if { [ -n "${FAKE_MANAGER_EXPECTED_KEY:-}" ] && [[ "$raw_arg" == *"$FAKE_MANAGER_EXPECTED_KEY"* ]]; } ||
                       { [ -n "${FAKE_GATEWAY_EXPECTED_KEY:-}" ] && [[ "$raw_arg" == *"$FAKE_GATEWAY_EXPECTED_KEY"* ]]; }; then
                      secret_arg=true
                    fi
                  done
                  printf '%s\n' "$secret_arg" >> "$FAKE_CURL_ARGV_SECRET_LOG"
                  if [ -n "$auth_config" ] && [ -f "$auth_config" ]; then
                    auth="$(sed -n 's/^header = "Authorization: Bearer \(.*\)"$/\1/p' "$auth_config" | head -n 1)"
                    config_mode="$(stat -c '%a' "$auth_config" 2>/dev/null || echo unknown)"
                    if [ "$(cat "$auth_config")" = "header = \"Authorization: Bearer $auth\"" ] && [ "$(wc -l < "$auth_config")" -eq 1 ]; then
                      config_safe=true
                    else
                      config_safe=false
                    fi
                    printf '%s\n' "$auth_config" >> "$FAKE_CURL_CONFIG_LOG"
                  fi
                  if [ -n "$auth" ] && [ "$auth" = "${FAKE_MANAGER_EXPECTED_KEY:-}" ]; then
                    auth_kind=manager
                  elif [ -n "$auth" ] && [ "$auth" = "${FAKE_GATEWAY_EXPECTED_KEY:-}" ]; then
                    auth_kind=gateway
                  elif [ -n "$auth" ]; then
                    auth_kind=other
                  fi
                  if [ -n "$auth_config" ]; then
                    printf '%s|%s|%s|%s\n' "$url" "$auth_kind" "$config_mode" "$config_safe" >> "$FAKE_CURL_AUTH_LOG"
                  fi
                  if [ -n "$output" ] && [[ "$url" == https://assets.invalid/* ]]; then
                    cp "$FAKE_ARCHIVE" "$output"
                  elif [[ "$url" == https://api.github.com/* ]]; then
                    if [ "$FAKE_GATEWAY_PAGE" = "2" ] && [[ "$url" == *"page=1"* ]]; then
                      cat "$FAKE_RELEASE_PAGE_ONE"
                    else
                      cat "$FAKE_RELEASE_JSON"
                    fi
                  elif [ "$url" = "$HERMES_HUB_UPDATE_PROBE_URL" ] && [ "$FAKE_PROBE_OK" = "1" ]; then
                    printf '%s\n' "$FAKE_PROBE_RESPONSE"
                  elif [[ "$url" == */health/detailed ]]; then
                    if [ -n "${FAKE_GATEWAY_RESPONSE:-}" ]; then
                      response_body="$FAKE_GATEWAY_RESPONSE"
                    elif [ "${FAKE_GATEWAY_FLIP:-0}" = "1" ]; then
                      calls="$(cat "$FAKE_GATEWAY_CALLS" 2>/dev/null || echo 0)"
                      printf '%s' "$((calls + 1))" > "$FAKE_GATEWAY_CALLS"
                      if [ "$calls" = "0" ]; then
                        response_body='{"status":"ok","gateway_state":"running","active_agents":0,"gateway_busy":false,"gateway_drainable":true,"api_server":{"active_runs":0},"readiness":{"status":"ok"}}'
                      else
                        response_body='{"status":"ok","gateway_state":"running","active_agents":0,"gateway_busy":false,"gateway_drainable":true,"api_server":{"active_runs":1},"readiness":{"status":"ok"}}'
                      fi
                    elif [ "$FAKE_GATEWAY_STATE" = "active_agents" ]; then
                      response_body='{"status":"ok","gateway_state":"running","active_agents":1,"gateway_busy":false,"gateway_drainable":true,"api_server":{"active_runs":0},"readiness":{"status":"ok"}}'
                    elif [ "$FAKE_GATEWAY_STATE" = "active_runs" ]; then
                      response_body='{"status":"ok","gateway_state":"running","active_agents":0,"gateway_busy":false,"gateway_drainable":true,"api_server":{"active_runs":2},"readiness":{"status":"ok"}}'
                    elif [ "$FAKE_GATEWAY_STATE" = "busy" ]; then
                      response_body='{"status":"ok","gateway_state":"running","active_agents":0,"gateway_busy":true,"gateway_drainable":true,"api_server":{"active_runs":0},"readiness":{"status":"ok"}}'
                    elif [ "$FAKE_GATEWAY_STATE" = "not_drainable" ]; then
                      response_body='{"status":"ok","gateway_state":"running","active_agents":0,"gateway_busy":false,"gateway_drainable":false,"api_server":{"active_runs":0},"readiness":{"status":"ok"}}'
                    elif [ "$FAKE_GATEWAY_STATE" = "wrong_type" ]; then
                      response_body='{"status":"ok","gateway_state":"running","active_agents":false,"gateway_busy":false,"gateway_drainable":true,"api_server":{"active_runs":0},"readiness":{"status":"ok"}}'
                    elif [ "$FAKE_GATEWAY_STATE" = "missing" ]; then
                      response_body='{"status":"ok","gateway_state":"running","active_agents":0,"gateway_busy":false,"gateway_drainable":true,"api_server":{"active_runs":0}}'
                    elif [ "$FAKE_GATEWAY_STATE" = "invalid_json" ]; then
                      response_body='{"status":"ok"'
                    elif [ "$FAKE_GATEWAY_STATE" = "not_running" ]; then
                      response_body='{"status":"ok","gateway_state":"stopped","active_agents":0,"gateway_busy":false,"gateway_drainable":true,"api_server":{"active_runs":0},"readiness":{"status":"ok"}}'
                    else
                      response_body='{"status":"ok","gateway_state":"running","active_agents":0,"gateway_busy":false,"gateway_drainable":true,"api_server":{"active_runs":0},"readiness":{"status":"ok"}}'
                    fi
                    if [ -n "$output" ]; then printf '%s\n' "$response_body" > "$output"; fi
                    [ -n "$write_out" ] && printf '%s' "$gateway_status"
                    if [ "$fail_on_http" = "true" ] && [ "$gateway_status" != "200" ]; then return 22; fi
                  elif [[ "$url" == */status && "$url" != "$HERMES_HUB_UPDATE_PROBE_URL" ]]; then
                    if [ "$FAKE_MANAGER_STATE" = "down" ]; then
                      [ -n "$write_out" ] && printf '000'
                      return 22
                    fi
                    if [ "$manager_status" != "200" ]; then
                      response_body='{"detail":"manager status unavailable"}'
                      if [ "$fail_on_http" != "true" ]; then
                        if [ -n "$output" ]; then printf '%s\n' "$response_body" > "$output"; else printf '%s\n' "$response_body"; fi
                      fi
                      [ -n "$write_out" ] && printf '%s' "$manager_status"
                      if [ "$fail_on_http" = "true" ]; then return 22; fi
                      return 0
                    fi
                    if [ "$FAKE_MANAGER_FLIP" = "1" ]; then
                      calls="$(cat "$FAKE_MANAGER_CALLS" 2>/dev/null || echo 0)"
                      printf '%s' "$((calls + 1))" > "$FAKE_MANAGER_CALLS"
                      if [ "$calls" = "0" ]; then
                        response_body='{"desired_mode":"AUTO","current_state":"LLM_READY","queue_length":0,"current_job":null}'
                      else
                        response_body='{"desired_mode":"AUTO","current_state":"MEDIA_BUSY","queue_length":1,"current_job":"flip123"}'
                      fi
                    elif [ "$FAKE_MANAGER_STATE" = "queue" ]; then
                      response_body='{"desired_mode":"AUTO","current_state":"MEDIA_BUSY","queue_length":2,"current_job":"abc123"}'
                    elif [ "$FAKE_MANAGER_STATE" = "media_idle" ]; then
                      response_body='{"desired_mode":"AUTO","current_state":"MEDIA_READY","queue_length":0,"current_job":null}'
                    elif [ "$FAKE_MANAGER_STATE" = "invalid_schema" ]; then
                      response_body='{"desired_mode":"AUTO","current_state":"LLM_READY","queue_length":"bad","current_job":null}'
                    elif [ "$FAKE_MANAGER_STATE" = "unknown" ]; then
                      response_body='{"desired_mode":"AUTO","current_state":"FUTURE_STATE","queue_length":0,"current_job":null}'
                    elif [ "$FAKE_MANAGER_STATE" = "malformed_json" ]; then
                      response_body='{"current_state":"LLM_READY"'
                    else
                      response_body='{"desired_mode":"AUTO","current_state":"LLM_READY","queue_length":0,"current_job":null}'
                    fi
                    if [ -n "$output" ]; then
                      printf '%s\n' "$response_body" > "$output"
                    else
                      printf '%s\n' "$response_body"
                    fi
                    [ -n "$write_out" ] && printf '%s' "$manager_status"
                  elif [[ "$url" == */queue ]]; then
                    if [ "$FAKE_COMFY_STATE" = "busy" ]; then
                      printf '{"queue_running":{"1":{}},"queue_pending":[]}\n'
                    else
                      printf '{"queue_running":{},"queue_pending":[]}\n'
                    fi
                  else
                    return 22
                  fi
                }

                chmod() {
                  printf '%s|%s\n' "${1:-}" "${2:-}" >> "$FAKE_CHMOD_LOG"
                  command chmod "$@"
                }

                stat() {
                  local stat_path="${3:-}"
                  [ "$stat_path" = "--" ] && stat_path="${4:-}"
                  if [ "${1:-}" = "-c" ] && [ "${2:-}" = "%a %u" ] &&
                     [ -n "${FAKE_MANAGER_KEY_FILE:-}" ] && [ "$stat_path" = "$FAKE_MANAGER_KEY_FILE" ]; then
                    local actual_mode="$(command stat -c '%a' -- "$stat_path")"
                    printf '%s %s\n' "$actual_mode" "${FAKE_MANAGER_KEY_FILE_OWNER:-$(command id -u)}"
                  else
                    command stat "$@"
                  fi
                }

                systemctl() {
                  printf '%s\n' "$*" >> "$FAKE_SYSTEMCTL_LOG"
                  case " $* " in
                    *" is-active "*) return 1 ;;
                    *" is-enabled --quiet hermes-hub-backup.timer "*) [ "${FAKE_BACKUP_TIMER_ENABLED:-0}" = "1" ] ;;
                    *" is-enabled "*) return 1 ;;
                    *) return 0 ;;
                  esac
                }

                sleep() {
                  return 0
                }

                python3() {
                  "$FAKE_REAL_PYTHON" "$@"
                }
                '''
            ).lstrip(),
            encoding="utf-8",
            newline="\n",
        )

        environment = os.environ.copy()
        environment.update(
            {
                "PATH": "/usr/local/bin:/usr/bin:/bin",
                "BASH_ENV": bash_path(bash, bash_env),
                "HOME": bash_path(bash, home),
                "XDG_CONFIG_HOME": bash_path(bash, config_dir),
                "HERMES_HUB_INSTALL_DIR": bash_path(bash, install_dir),
                "HERMES_HUB_BIN_DIR": bash_path(bash, bin_dir),
                "HERMES_HUB_REPO": "example/hermes-hub",
                "HERMES_HUB_CHANNEL": "latest",
                "HERMES_HUB_SERVICE": "hermes-hub.service",
                "HERMES_HUB_UPDATE_CONNECT_TIMEOUT": "1",
                "HERMES_HUB_UPDATE_API_MAX_TIME": "5",
                "HERMES_HUB_UPDATE_DOWNLOAD_MAX_TIME": "5",
                "HERMES_HUB_UPDATE_RETRIES": "0",
                "HERMES_HUB_UPDATE_PROBE_ATTEMPTS": "1",
                "HERMES_HUB_UPDATE_PROBE_SLEEP_SECONDS": "1",
                "HERMES_HUB_UPDATE_PROBE_URL": "https://probe.invalid/v1/capabilities",
                "HERMES_HUB_API_KEY": "integration-test-key",
                "FAKE_GATEWAY_EXPECTED_KEY": "integration-test-key",
                "FAKE_MANAGER_EXPECTED_KEY": "manager-test-key",
                "FAKE_MANAGER_HTTP_STATUS": "200",
                "FAKE_ARCHIVE": bash_path(bash, archive_path),
                "FAKE_RELEASE_JSON": bash_path(bash, release_json),
                "FAKE_RELEASE_PAGE_ONE": bash_path(bash, first_page_json),
                "FAKE_GATEWAY_PAGE": str(gateway_page),
                "FAKE_GATEWAY_STATE": "idle",
                "FAKE_GATEWAY_HTTP_STATUS": "200",
                "FAKE_GATEWAY_FLIP": "0",
                "FAKE_CURL_LOG": bash_path(bash, curl_log),
                "FAKE_CURL_AUTH_LOG": bash_path(bash, curl_auth_log),
                "FAKE_CURL_ARGV_SECRET_LOG": bash_path(bash, curl_argv_secret_log),
                "FAKE_CURL_CONFIG_LOG": bash_path(bash, curl_config_log),
                "FAKE_CHMOD_LOG": bash_path(bash, chmod_log),
                "FAKE_SYSTEMCTL_LOG": bash_path(bash, systemctl_log),
                "FAKE_BACKUP_TIMER_ENABLED": "0",
                "FAKE_REAL_PYTHON": bash_path(bash, Path(sys.executable)),
                "FAKE_PROBE_OK": "1" if probe_ok else "0",
                "FAKE_PROBE_RESPONSE": VALID_GATEWAY_CAPABILITIES_RESPONSE,
                "FAKE_MANAGER_STATE": manager_state,
                "FAKE_COMFY_STATE": comfy_state,
                "FAKE_MANAGER_FLIP": "1" if manager_flip else "0",
                "FAKE_MANAGER_CALLS": bash_path(bash, root / "manager_calls"),
                "FAKE_GATEWAY_CALLS": bash_path(bash, root / "gateway_calls"),
                "MSYS": "winsymlinks:sys",
            }
        )
        for key in ("HERMES_HUB_MANAGER_API_KEY", "HERMES_GPU_MANAGER_KEY", "HERMES_HUB_MANAGER_KEY_FILE"):
            environment.pop(key, None)
        environment.pop("GH_TOKEN", None)
        environment.pop("GITHUB_TOKEN", None)
        return {
            "version": version,
            "asset_digest": asset_digest,
            "home": home,
            "install_dir": install_dir,
            "bin_dir": bin_dir,
            "service_dir": config_dir / "systemd" / "user",
            "curl_log": curl_log,
            "curl_auth_log": curl_auth_log,
            "curl_argv_secret_log": curl_argv_secret_log,
            "curl_config_log": curl_config_log,
            "chmod_log": chmod_log,
            "systemctl_log": systemctl_log,
            "environment": environment,
        }

    def _run_updater(
        self,
        bash: str,
        fixture: dict[str, object],
        *arguments: str,
    ) -> subprocess.CompletedProcess[str]:
        updater_arguments = arguments or ("--restart",)
        return subprocess.run(
            [bash, bash_path(bash, SCRIPTS / "hermes-hub-linux-update.sh"), *updater_arguments],
            env=fixture["environment"],
            text=True,
            capture_output=True,
            check=False,
        )

    def _readlink(self, bash: str, path: Path, environment: object) -> str:
        result = subprocess.run(
            [bash, "-c", 'readlink -f "$1"', "_", bash_path(bash, path)],
            env=environment,
            text=True,
            capture_output=True,
            check=True,
        )
        return result.stdout.strip()

    def _seed_previous_updater_release(self, bash, fixture) -> Path:
        install_dir = Path(fixture["install_dir"])
        old_version = "1.0.0"
        old_release = install_dir / "releases" / f"{old_version}-existing"
        old_release.mkdir(parents=True)
        for name in UPDATER_REQUIRED_FILES:
            shutil.copy2(SCRIPTS / name, old_release / name)
        (old_release / "VERSION").write_text(f"{old_version}\n", encoding="utf-8", newline="\n")
        (install_dir / "VERSION").write_text(f"{old_version}\n", encoding="utf-8", newline="\n")
        subprocess.run(
            [
                bash,
                "-c",
                'ln -s "$1" "$2" && test -L "$2"',
                "_",
                bash_path(bash, old_release),
                bash_path(bash, install_dir / "current"),
            ],
            env=fixture["environment"],
            text=True,
            capture_output=True,
            check=True,
        )
        return old_release

    @staticmethod
    def _base_gateway_health_payload() -> dict[str, object]:
        return {
            "status": "ok",
            "gateway_state": "running",
            "active_agents": 0,
            "gateway_busy": False,
            "gateway_drainable": True,
        }

    def test_release_selector_skips_app_only_latest_release(self):
        script = (SCRIPTS / "hermes-hub-linux-update.sh").read_text(encoding="utf-8")
        selector = heredoc_between(
            script,
            'select_release() {\n  python3 - "$1" "$CHANNEL" <<\'PY\'\n',
            "\nPY\n}",
        )
        releases = [
            {
                "tag_name": "v9.9.9",
                "draft": False,
                "prerelease": False,
                "assets": [{"name": "HermesHub-9.9.9-android.apk", "browser_download_url": "https://example.invalid/app"}],
            },
            {
                "tag_name": "v9.9.8",
                "draft": False,
                "prerelease": False,
                "assets": [
                    {
                        "name": "HermesHub-9.9.8-linux-gateway.tar.gz",
                        "browser_download_url": "https://example.invalid/gateway",
                        "size": 123,
                        "digest": "sha256:abc",
                    }
                ],
            },
        ]
        with tempfile.TemporaryDirectory() as temporary:
            payload = Path(temporary) / "releases.json"
            payload.write_text(json.dumps(releases), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, "-c", selector, str(payload), "latest"],
                text=True,
                capture_output=True,
                check=True,
            )
        lines = result.stdout.splitlines()
        self.assertEqual("v9.9.8", lines[0])
        self.assertEqual("HermesHub-9.9.8-linux-gateway.tar.gz", lines[1])
        self.assertEqual("sha256:abc", lines[4])

    def test_official_upstream_gateway_patch_is_stable_for_three_passes(self):
        fixture_bytes = UPSTREAM_GATEWAY_FIXTURE.read_bytes()
        self.assertEqual(
            "d819f04f4f3a7d2c7f2d3b5befb13aa50dc0df7ca8416909f65f6d214e8c7b66",
            hashlib.sha256(fixture_bytes).hexdigest(),
        )
        patched = fixture_bytes.decode("utf-8")
        digests = []
        for pass_index in range(3):
            patched, changes = self.patcher._patch_text(patched)
            compile(patched, f"<official-upstream-pass-{pass_index + 1}>", "exec")
            digests.append(hashlib.sha256(patched.encode()).hexdigest())
            if pass_index == 0:
                self.assertTrue(changes)
                self.assertIn("async def _handle_responses", patched)
                self.assertIn("hermes.native.protocol", patched)
                self.assertIn(":compatv3", patched)
                self.assertIn('"""Pass through Hermes-native tool/progress metadata including reasoning."""', patched)
                self.assertIn('"""Forward Responses progress metadata and reasoning."""', patched)
                self.assertIn('"type": "hermes.reasoning.available" if is_reasoning else event_name', patched)
                self.assertIn('"reasoning": (preview or "") if is_reasoning else None', patched)
                self.assertIn(self.patcher._HARDWARE_DISK_FILTER_MARKER, patched)
                self.assertIn(self.patcher._MODEL_ROUTE_TOOLSETS_MARKER, patched)
                self.assertIn(self.patcher._MODEL_ROUTE_LIMITS_MARKER, patched)
                self.assertIn(self.patcher._MODEL_ROUTE_MAX_TOKENS_MARKER, patched)
                self.assertIn(self.patcher._MODEL_ROUTE_MAX_TOKENS_FIX_MARKER, patched)
                self.assertIn('route["toolsets"] = sorted(', patched)
                self.assertIn('route_toolsets = route.get("toolsets")', patched)
                self.assertIn('route["max_iterations"] = max(1, min(120, int(configured_max_iterations)))', patched)
                self.assertIn('route["max_tokens"] = max(64, min(4096, int(configured_max_tokens)))', patched)
                self.assertIn('runtime_kwargs["max_tokens"] = int(route["max_tokens"])', patched)
                self.assertIn('HERMES_GATEWAY_MAX_REQUEST_MB", "0"', patched)
                self.assertIn("0 maps to a 100GB practical ceiling for aiohttp", patched)
                self.assertIn('HERMES_HUB_MAX_UPLOAD_MB", "0"', patched)
                self.assertIn("if not encoded or estimated > max_bytes + 3:", patched)
                self.assertIn("for offset in range(0, len(encoded), chunk_chars):", patched)
                self.assertIn('"max_upload_mb": int(os.environ.get("HERMES_HUB_MAX_UPLOAD_MB", "0"))', patched)
                self.assertIn('self._app.router.add_get("/v1/hub/runtime", self._handle_hub_runtime)', patched)
                self.assertIn("def _hermes_hub_runtime_payload()", patched)
                self.assertIn("async def _handle_hub_runtime", patched)
                self.assertIn('"squashfs"', patched)
                self.assertIn("device.startswith('/dev/loop')", patched)
            else:
                self.assertEqual([], changes)
        self.assertEqual(digests[0], digests[1])
        self.assertEqual(digests[1], digests[2])

    def test_current_upstream_gateway_patch_is_compilable_and_idempotent(self):
        fixture_bytes = CURRENT_UPSTREAM_GATEWAY_FIXTURE.read_bytes()
        self.assertEqual(
            "1299cbb9019d1cf9e4bcaf931162956a56f0fb4656f2af6efd6da641b2b37966",
            hashlib.sha256(fixture_bytes).hexdigest(),
        )
        patched, changes = self.patcher._patch_text(fixture_bytes.decode("utf-8"))
        compile(patched, "<current-upstream-pass-1>", "exec")
        self.assertTrue(changes)
        self.assertIn(self.patcher._MODEL_ROUTE_MAX_TOKENS_MARKER, patched)
        self.assertIn(self.patcher._MODEL_ROUTE_MAX_TOKENS_FIX_MARKER, patched)
        self.assertIn("# HERMES_HUB_AUTH_KEY_ALIASES_V2", patched)
        self.assertIn('"max_iterations": max_iterations', patched)
        self.assertIn('runtime_kwargs["max_tokens"] = int(route["max_tokens"])', patched)

        second, second_changes = self.patcher._patch_text(patched)
        compile(second, "<current-upstream-pass-2>", "exec")
        self.assertEqual([], second_changes)
        self.assertEqual(patched, second)

    def test_bot_profile_routes_are_compilable_profile_scoped_and_idempotent(self):
        for fixture_path in (UPSTREAM_GATEWAY_FIXTURE, CURRENT_UPSTREAM_GATEWAY_FIXTURE):
            with self.subTest(fixture=fixture_path.name):
                patched, changes = self.patcher._patch_text(
                    fixture_path.read_text(encoding="utf-8")
                )
                compile(patched, f"<{fixture_path.name}-bot-profile>", "exec")
                self.assertIn("HERMES_HUB_BOTS_HANDLERS_BEGIN", patched)
                self.assertIn("HERMES_HUB_BOTS_CAPABILITIES_BEGIN", patched)
                self.assertIn("HERMES_HUB_BOTS_ENDPOINTS_BEGIN", patched)
                self.assertTrue(
                    "HERMES_HUB_BOTS_DYNAMIC_ROUTES_BEGIN" in patched
                    or "HERMES_HUB_BOTS_LEGACY_ROUTES_BEGIN" in patched
                )
                self.assertIn('"/v1/hub/bots"', patched)
                self.assertIn('"/v1/hub/bots/{bot_name}/chat"', patched)
                self.assertIn('"/v1/hub/bots/{bot_name}"', patched)
                self.assertIn('confirm_name', patched)
                self.assertIn('display_name=body.get("display_name")', patched)
                self.assertIn('profile_multiplexing_disabled', patched)
                self.assertIn("profile_multiplexing_disabled", patched)
                self.assertIn("/p/{encoded}/v1", patched)
                self.assertTrue(any("bot" in change.lower() for change in changes))

                second, second_changes = self.patcher._patch_text(patched)
                compile(second, f"<{fixture_path.name}-bot-profile-second>", "exec")
                self.assertEqual([], second_changes)
                self.assertEqual(patched, second)

    def test_generated_gateway_delegates_hub_state_and_sync_to_sqlite_runtime(self):
        source = UPSTREAM_GATEWAY_FIXTURE.read_text(encoding="utf-8")
        patched, _ = self.patcher._patch_text(source)
        self.assertIn("# HERMES_HUB_SQLITE_SYNC_V1", patched)
        self.assertIn("HubRuntimeStore.from_environment()", patched)
        self.assertIn('self._app.router.add_get("/v1/hub/sync", self._handle_get_hub_sync)', patched)
        self.assertIn("async def _handle_get_hub_sync", patched)
        self.assertIn('"hub_sync": {"method": "GET", "path": "/v1/hub/sync?since=revision"}', patched)

        runtime_block = patched[patched.index("# HERMES_HUB_SQLITE_SYNC_V1"):]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legacy = root / "hub_conversations.json"
            now_ms = int(time.time() * 1000)
            legacy.write_text(
                json.dumps(
                    {
                        "items": [
                            {"id": "legacy", "title": "Legacy", "updatedAt": now_ms},
                            {"id": "deleted", "title": "Deleted", "updatedAt": now_ms, "deletedAt": now_ms},
                        ]
                    }
                ),
                encoding="utf-8",
            )
            namespace = {
                "__file__": str(root / "api_server.py"),
                "os": os,
                "_hermes_hub_storage_path": lambda name, default: legacy if name == "HERMES_HUB_CONVERSATIONS_PATH" else root / default,
                "_hermes_hub_conversations_payload": lambda: {"items": []},
                "_hermes_hub_merge_conversations": lambda items: {"items": items},
                "_hermes_hub_delete_conversation": lambda conversation_id: {"id": conversation_id},
                "_hermes_hub_state_payload": lambda: {"items": []},
                "_hermes_hub_add_state": lambda item: item,
                "_hermes_hub_delete_state": lambda state_id: {"id": state_id},
                "_hermes_hub_conversation_event_payload": lambda reason, result=None: {"reason": reason},
            }
            with mock.patch.dict(
                os.environ,
                {
                    "HERMES_HUB_GATEWAY_PACKAGE": str(SCRIPTS),
                    "HERMES_HOME": str(root / "home"),
                    "HERMES_HUB_CONVERSATIONS_PATH": str(legacy),
                },
                clear=False,
            ):
                exec(compile(runtime_block, "<generated-runtime>", "exec"), namespace)
                payload = namespace["_hermes_hub_conversations_payload"]()
                merged = namespace["_hermes_hub_merge_conversations"](
                    [{"id": "new", "title": "New", "updatedAt": now_ms + 1}]
                )
                sync = namespace["_hermes_hub_sync_payload"](0, 500)

            self.assertEqual("sqlite", payload["storage"])
            self.assertEqual(2, payload["migration"]["imported_count"])
            self.assertEqual(1, payload["migration"]["tombstone_count"])
            self.assertEqual(3, merged["revision"])
            self.assertEqual(3, len(sync["changes"]))
            self.assertEqual("legacy", json.loads(legacy.read_text(encoding="utf-8"))["items"][0]["id"])

    def test_generated_runtime_falls_back_only_when_modular_package_is_absent(self):
        source = UPSTREAM_GATEWAY_FIXTURE.read_text(encoding="utf-8")
        patched, _ = self.patcher._patch_text(source)
        runtime_block = patched[patched.index("# HERMES_HUB_SQLITE_SYNC_V1"):]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assertFalse(
                (root / ".local/share/hermes-hub-gateway/current").exists()
            )
            namespace = {
                "__file__": str(root / "api_server.py"),
                "os": os,
                "_hermes_hub_storage_path": lambda name, default: root / default,
                "_hermes_hub_conversations_payload": lambda: {"items": [{"id": "legacy"}]},
                "_hermes_hub_merge_conversations": lambda items: {"items": items},
                "_hermes_hub_delete_conversation": lambda conversation_id: {"id": conversation_id},
                "_hermes_hub_state_payload": lambda: {"items": []},
                "_hermes_hub_add_state": lambda item: item,
                "_hermes_hub_delete_state": lambda state_id: {"id": state_id},
                "_hermes_hub_conversation_event_payload": lambda reason, result=None: {"reason": reason},
            }
            with mock.patch.dict(
                os.environ,
                {"HERMES_HUB_GATEWAY_PACKAGE": str(root / "missing-package")},
                clear=False,
            ), mock.patch("pathlib.Path.home", return_value=root), mock.patch(
                "importlib.util.find_spec", return_value=None
            ):
                exec(compile(runtime_block, "<generated-runtime>", "exec"), namespace)
                self.assertIsNone(namespace["_hermes_hub_gateway_runtime_store"]())
                self.assertEqual(
                    {"items": [{"id": "legacy"}]},
                    namespace["_hermes_hub_conversations_payload"](),
                )

    def test_generated_runtime_propagates_sqlite_initialization_failure(self):
        source = UPSTREAM_GATEWAY_FIXTURE.read_text(encoding="utf-8")
        patched, _ = self.patcher._patch_text(source)
        runtime_block = patched[patched.index("# HERMES_HUB_SQLITE_SYNC_V1"):]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database_directory = root / "sqlite-directory"
            database_directory.mkdir()
            namespace = {
                "__file__": str(root / "api_server.py"),
                "os": os,
                "_hermes_hub_storage_path": lambda name, default: root / default,
                "_hermes_hub_conversations_payload": lambda: {"items": []},
                "_hermes_hub_merge_conversations": lambda items: {"items": items},
                "_hermes_hub_delete_conversation": lambda conversation_id: {"id": conversation_id},
                "_hermes_hub_state_payload": lambda: {"items": []},
                "_hermes_hub_add_state": lambda item: item,
                "_hermes_hub_delete_state": lambda state_id: {"id": state_id},
                "_hermes_hub_conversation_event_payload": lambda reason, result=None: {"reason": reason},
            }
            with mock.patch.dict(
                os.environ,
                {
                    "HERMES_HUB_GATEWAY_PACKAGE": str(SCRIPTS),
                    "HERMES_HUB_SQLITE_PATH": str(database_directory),
                },
                clear=False,
            ):
                exec(compile(runtime_block, "<generated-runtime>", "exec"), namespace)
                with self.assertRaises(Exception):
                    namespace["_hermes_hub_gateway_runtime_store"]()

    def test_generated_gateway_composes_agent_runtime_and_correlation_for_all_agent_surfaces(self):
        source = CURRENT_UPSTREAM_GATEWAY_FIXTURE.read_text(encoding="utf-8")
        patched, changes = self.patcher._patch_text(source)
        compile(patched, "<agent-runtime-correlation-gateway>", "exec")
        self.assertIn("# HERMES_HUB_AGENT_RUNTIME_ADAPTER_V1", patched)
        self.assertIn("async def _hermes_hub_legacy_run_agent", patched)
        self.assertIn("from hermes_hub_gateway.adapters.hermes.agent_runtime import AgentRunRequest", patched)
        self.assertIn("# HERMES_HUB_CORRELATION_RUNTIME_V1", patched)
        self.assertIn("mws.append(web.middleware(_hermes_hub_correlation_middleware))", patched)
        self.assertIn("_hermes_hub_install_correlation_runtime()", patched)
        self.assertIn('"/v1/chat/completions"', patched)
        self.assertIn('"/v1/responses"', patched)
        self.assertIn('"/v1/runs"', patched)
        self.assertIn('"/v1/runs/{run_id}/events"', patched)
        self.assertIn("_hermes_hub_sse_sequence", (SCRIPTS / "hermes_hub_gateway" / "adapters" / "hermes" / "correlation_runtime.py").read_text(encoding="utf-8"))
        self.assertIn("AgentRuntime adapter composition", " ".join(changes))

    def test_gateway_patch_reverts_06174_finite_transfer_defaults(self):
        patched, _ = self.patcher._patch_text(
            UPSTREAM_GATEWAY_FIXTURE.read_text(encoding="utf-8")
        )
        unlimited_request = (
            '_HERMES_GATEWAY_MAX_REQUEST_MB = int(os.environ.get("HERMES_GATEWAY_MAX_REQUEST_MB", "0"))\n'
            'MAX_REQUEST_BYTES = (_HERMES_GATEWAY_MAX_REQUEST_MB if _HERMES_GATEWAY_MAX_REQUEST_MB > 0 else 102400) '
            '* 1024 * 1024  # 0 maps to a 100GB practical ceiling for aiohttp'
        )
        finite_request = (
            '_HERMES_GATEWAY_MAX_REQUEST_MB = int(os.environ.get("HERMES_GATEWAY_MAX_REQUEST_MB", "256"))\n'
            'if _HERMES_GATEWAY_MAX_REQUEST_MB <= 0:\n'
            '    _HERMES_GATEWAY_MAX_REQUEST_MB = 256\n'
            'MAX_REQUEST_BYTES = min(_HERMES_GATEWAY_MAX_REQUEST_MB, 4096) * 1024 * 1024  '
            '# finite configurable gateway body limit'
        )
        unlimited_capability = (
            '"max_upload_mb": int(os.environ.get("HERMES_HUB_MAX_UPLOAD_MB", "0")),'
        )
        finite_capability = (
            '"max_upload_mb": _hermes_hub_env_int("HERMES_HUB_MAX_UPLOAD_MB", 150, 1, 4096),'
        )
        finite = patched.replace(unlimited_request, finite_request, 1).replace(
            unlimited_capability,
            finite_capability,
            1,
        )
        self.assertNotEqual(patched, finite)

        reverted, changes = self.patcher._patch_text(finite)

        self.assertIn(unlimited_request, reverted)
        self.assertIn(unlimited_capability, reverted)
        self.assertNotIn(finite_request, reverted)
        self.assertNotIn(finite_capability, reverted)
        self.assertIn("unlimited gateway request default", changes)
        self.assertIn("capabilities unlimited upload default", changes)

    def test_gateway_patch_upgrades_legacy_responses_progress_callback(self):
        patched, _ = self.patcher._patch_text(UPSTREAM_GATEWAY_FIXTURE.read_text(encoding="utf-8"))
        current_callback = textwrap.indent(textwrap.dedent(
            '''
            def _on_tool_progress(event_type, name, preview, args, **kwargs):
                """Forward Responses progress metadata and reasoning."""
                event_name = str(event_type or "hermes.tool.progress")
                is_reasoning = "reasoning" in event_name.lower()
                if str(name).startswith("_") and not is_reasoning:
                    return
                payload = {
                    "type": "hermes.reasoning.available" if is_reasoning else event_name,
                    "event": "reasoning.available" if is_reasoning else event_name,
                    "tool": name,
                    "label": preview,
                    "reasoning": (preview or "") if is_reasoning else None,
                    "arguments": args or {},
                }
                payload.update(kwargs or {})
                _stream_q.put(("__hermes_raw_event__", payload))
            '''
        ).strip(), "            ")
        legacy_callback = textwrap.indent(textwrap.dedent(
            '''
            def _on_tool_progress(event_type, name, preview, args, **kwargs):
                """Pass through Hermes-native tool/progress metadata."""
                if str(name).startswith("_"):
                    return
                payload = {
                    "type": str(event_type or "hermes.tool.progress"),
                    "event": str(event_type or "hermes.tool.progress"),
                    "tool": name,
                    "label": preview,
                    "arguments": args or {},
                }
                payload.update(kwargs or {})
                _stream_q.put(("__hermes_raw_event__", payload))
            '''
        ).strip(), "            ")
        self.assertEqual(1, patched.count(current_callback))
        legacy_patched = patched.replace(current_callback, legacy_callback, 1)

        upgraded, changes = self.patcher._patch_text(legacy_patched)

        compile(upgraded, "<legacy-responses-progress-upgrade>", "exec")
        self.assertIn("responses reasoning passthrough callback", changes)
        self.assertEqual(1, upgraded.count(current_callback))
        self.assertNotIn(legacy_callback, upgraded)

    def test_agent_helper_forwards_only_real_prompt_progress_and_reasoning(self):
        helper = textwrap.dedent(
            '''
            def interruptible_streaming_api_call(agent, api_kwargs: dict, *, on_first_delta=None):
                for chunk in []:
                    if chunk is not None:
                        agent._touch_activity("receiving stream response")

                        # Update per-attempt diagnostic counters.
                        pass
            '''
        )
        patched, changes = self.patcher._patch_agent_chat_completion_helpers(helper)
        self.assertIn("agent request real llama prompt progress and timings", changes)
        self.assertIn("agent emit real llama prompt progress and timings", changes)
        namespace = {}
        exec(compile(patched, "<patched-agent-helper>", "exec"), namespace)
        events = []
        agent = types.SimpleNamespace(
            tool_progress_callback=lambda event_type, name, preview, args, **kwargs: events.append(
                (event_type, name, preview, kwargs)
            )
        )
        chunk = types.SimpleNamespace(
            reasoning_content="Controllo dati.",
            prompt_progress={"processed": 25, "total": 100, "cache": 5, "time_ms": 1200},
            timings=None,
        )
        namespace["_hermes_hub_emit_llama_stream_metadata"](agent, chunk)
        self.assertEqual("reasoning.available", events[0][0])
        self.assertEqual("Controllo dati.", events[0][2])
        self.assertEqual("hermes.processing.progress", events[1][0])
        self.assertEqual(25, events[1][3]["percent"])
        self.assertFalse(events[1][3]["estimated"])
        second_pass, second_changes = self.patcher._patch_agent_chat_completion_helpers(patched)
        self.assertEqual([], second_changes)
        self.assertEqual(patched, second_pass)

    def test_hardware_disk_filter_upgrades_old_patched_gateway_and_filters_runtime(self):
        fixture = UPSTREAM_GATEWAY_FIXTURE.read_text(encoding="utf-8")
        freshly_patched, _ = self.patcher._patch_text(fixture)
        self.assertIn(self.patcher._HARDWARE_DISK_BLOCK_V1, freshly_patched)

        old_disk_block = '''    disks: List[Dict[str, Any]] = []
    for part in psutil.disk_partitions(all=False):
        try:
            usage = psutil.disk_usage(part.mountpoint)
        except Exception:
            continue
        disks.append({
            "device": part.device,
            "mountpoint": part.mountpoint,
            "fstype": part.fstype,
            "total_bytes": int(usage.total),
            "used_bytes": int(usage.used),
            "free_bytes": int(usage.free),
            "percent": float(usage.percent),
        })
    snapshot["disks"] = disks
'''
        old_patched = freshly_patched.replace(
            self.patcher._HARDWARE_DISK_BLOCK_V1,
            old_disk_block,
            1,
        )
        self.assertNotIn(self.patcher._HARDWARE_DISK_FILTER_MARKER, old_patched)

        upgraded, changes = self.patcher._patch_text(old_patched)
        compile(upgraded, "<old-patched-to-current>", "exec")
        self.assertIn("hardware disk filter v1", changes)
        self.assertIn(self.patcher._HARDWARE_DISK_BLOCK_V1, upgraded)

        stable, second_changes = self.patcher._patch_text(upgraded)
        self.assertEqual([], second_changes)
        self.assertEqual(upgraded, stable)

        collector_match = re.search(
            r"(?ms)^def _collect_hardware_snapshot\([^\n]*\)[^\n]*:\n.*?(?=^def |\Z)",
            upgraded,
        )
        self.assertIsNotNone(collector_match)
        collector_namespace = {
            "Any": Any,
            "Dict": Dict,
            "List": List,
            "Optional": Optional,
            "os": os,
            "time": time,
        }
        exec(compile(collector_match.group(0), "<hardware-collector>", "exec"), collector_namespace)

        fake_psutil = types.ModuleType("psutil")
        fake_psutil.boot_time = lambda: 0.0
        fake_psutil.cpu_freq = lambda: None
        fake_psutil.cpu_percent = lambda interval=None, percpu=False: [1.0] if percpu else 1.0
        fake_psutil.cpu_count = lambda logical=True: 2
        fake_psutil.virtual_memory = lambda: types.SimpleNamespace(
            total=1000, available=700, used=300, free=600, percent=30.0
        )
        fake_psutil.swap_memory = lambda: types.SimpleNamespace(
            total=100, used=10, free=90, percent=10.0
        )
        fake_psutil.disk_partitions = lambda all=False: [
            types.SimpleNamespace(device="/dev/loop7", mountpoint="/snap/test", fstype="squashfs"),
            types.SimpleNamespace(device="overlay", mountpoint="/container", fstype="overlay"),
            types.SimpleNamespace(device="tmpfs", mountpoint="/run", fstype="tmpfs"),
            types.SimpleNamespace(device="/dev/nvme0n1p2", mountpoint="/", fstype="ext4"),
        ]
        fake_psutil.disk_usage = lambda mountpoint: types.SimpleNamespace(
            total=1000, used=400, free=600, percent=40.0
        )
        fake_psutil.net_io_counters = lambda: types.SimpleNamespace(
            bytes_sent=1, bytes_recv=2, packets_sent=3, packets_recv=4
        )
        fake_psutil.sensors_temperatures = lambda fahrenheit=False: {}
        fake_psutil.pids = lambda: []

        with (
            mock.patch.dict(sys.modules, {"psutil": fake_psutil}),
            mock.patch("subprocess.run", side_effect=FileNotFoundError),
        ):
            snapshot = collector_namespace["_collect_hardware_snapshot"]()

        self.assertEqual(["/dev/nvme0n1p2"], [item["device"] for item in snapshot["disks"]])

    def test_updater_service_timeout_covers_download_restart_and_margin(self):
        updater = (SCRIPTS / "hermes-hub-linux-update.sh").read_text(encoding="utf-8")
        updater_service = (SCRIPTS / "hermes-hub-linux-update.service").read_text(encoding="utf-8")
        gateway_service = (SCRIPTS / "hermes-hub-linux.service").read_text(encoding="utf-8")
        download_match = re.search(r'HERMES_HUB_UPDATE_DOWNLOAD_MAX_TIME:-([0-9]+)', updater)
        api_match = re.search(r'HERMES_HUB_UPDATE_API_MAX_TIME:-([0-9]+)', updater)
        pages_match = re.search(r'HERMES_HUB_UPDATE_MAX_RELEASE_PAGES:-([0-9]+)', updater)
        probe_attempts_match = re.search(r'HERMES_HUB_UPDATE_PROBE_ATTEMPTS:-([0-9]+)', updater)
        probe_sleep_match = re.search(r'HERMES_HUB_UPDATE_PROBE_SLEEP_SECONDS:-([0-9]+)', updater)
        update_timeout_match = re.search(r"^TimeoutStartSec=([0-9]+)$", updater_service, re.MULTILINE)
        gateway_timeout_match = re.search(r"^TimeoutStartSec=([0-9]+)$", gateway_service, re.MULTILINE)
        self.assertIsNotNone(download_match)
        self.assertIsNotNone(api_match)
        self.assertIsNotNone(pages_match)
        self.assertIsNotNone(probe_attempts_match)
        self.assertIsNotNone(probe_sleep_match)
        self.assertIsNotNone(update_timeout_match)
        self.assertIsNotNone(gateway_timeout_match)
        download_timeout = int(download_match.group(1))
        paginated_api_timeout = int(api_match.group(1)) * int(pages_match.group(1))
        probe_timeout = int(probe_attempts_match.group(1)) * int(probe_sleep_match.group(1))
        update_timeout = int(update_timeout_match.group(1))
        gateway_timeout = int(gateway_timeout_match.group(1))
        self.assertGreaterEqual(
            update_timeout,
            paginated_api_timeout + download_timeout + gateway_timeout + probe_timeout + 300,
        )

    def test_linux_packager_rejects_unsafe_version_and_emits_exact_manifest(self):
        powershell = find_powershell()
        if not powershell:
            self.skipTest("PowerShell unavailable")
        script = SCRIPTS / "package-linux-gateway.ps1"
        with tempfile.TemporaryDirectory(dir=ROOT / "tests") as temporary:
            output_dir = Path(temporary)
            relative_output = output_dir.relative_to(ROOT)
            command = [
                powershell,
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(script),
            ]
            invalid = subprocess.run(
                [*command, "-Version", "../escape", "-OutputDirectory", str(relative_output)],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(0, invalid.returncode, invalid.stdout + invalid.stderr)
            self.assertIn("Invalid version", invalid.stdout + invalid.stderr)

            packaged = subprocess.run(
                [*command, "-Version", "1.2.3", "-OutputDirectory", str(relative_output)],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, packaged.returncode, packaged.stdout + packaged.stderr)
            archive_path = output_dir / "HermesHub-1.2.3-linux-gateway.tar.gz"
            first_digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
            with tarfile.open(archive_path, "r:gz") as archive:
                actual = set(archive.getnames())
                members = {member.name: member for member in archive.getmembers()}
            expected = {".", "./VERSION", "./scripts"}
            expected.update(f"./scripts/{name}" for name in UPDATER_REQUIRED_FILES)
            expected.add("./scripts/hermes_hub_gateway")
            expected.update(
                f"./scripts/{path.relative_to(SCRIPTS).as_posix()}"
                for path in GATEWAY_PACKAGE_PATH.rglob("*")
                if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc"
            )
            expected.update(
                f"./scripts/{path.relative_to(SCRIPTS).as_posix()}"
                for path in GATEWAY_PACKAGE_PATH.rglob("*")
                if path.is_dir() and "__pycache__" not in path.parts
            )
            self.assertEqual(expected, actual)
            self.assertEqual(0o755, members["."].mode)
            self.assertEqual(0o755, members["./scripts"].mode)
            self.assertEqual(0o644, members["./VERSION"].mode)
            for name in UPDATER_REQUIRED_FILES:
                expected_mode = 0o755 if name.endswith(".sh") else 0o644
                self.assertEqual(expected_mode, members[f"./scripts/{name}"].mode, name)
            packaged_again = subprocess.run(
                [*command, "-Version", "1.2.3", "-OutputDirectory", str(relative_output)],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, packaged_again.returncode, packaged_again.stdout + packaged_again.stderr)
            self.assertEqual(first_digest, hashlib.sha256(archive_path.read_bytes()).hexdigest())

    def test_backup_installer_is_opt_in_and_updater_preserves_timer_state(self):
        installer = (SCRIPTS / "install-hermes-hub-linux.sh").read_text(encoding="utf-8")
        updater = (SCRIPTS / "hermes-hub-linux-update.sh").read_text(encoding="utf-8")
        packager = (SCRIPTS / "package-linux-gateway.ps1").read_text(encoding="utf-8")

        self.assertIn("--enable-backup", installer)
        self.assertIn("ENABLE_BACKUP=false", installer)
        self.assertIn("require_file hermes-hub-backup.py", installer)
        self.assertLess(installer.index("require_file hermes-hub-backup.timer"), installer.index('mkdir -p "$RELEASE_DIR"'))
        for name in ("hermes-hub-backup.py", "hermes-hub-backup.service", "hermes-hub-backup.timer"):
            self.assertIn(f'"$SCRIPT_DIR/{name}"', installer)
            self.assertIn(f'"{name}"', packager)
            self.assertIn(name, updater)
        self.assertIn('if [ "$ENABLE_BACKUP" = "true" ]; then', installer)
        self.assertIn("systemctl --user enable --now hermes-hub-backup.timer", installer)
        self.assertIn('atomic_install "$RELEASE_DIR/hermes-hub-backup.py" "$BIN_DIR/hermes-hub-backup" 0755', installer)
        self.assertNotIn('atomic_symlink "$INSTALL_DIR/current/hermes-hub-backup.py" "$BIN_DIR/hermes-hub-backup"', installer)

        self.assertIn("[hermes-hub-backup.py]=0755", updater)
        self.assertIn('atomic_install "$FINAL_RELEASE_DIR/hermes-hub-backup.py" "$BIN_DIR/hermes-hub-backup" 0755', updater)
        self.assertNotIn('atomic_symlink "$INSTALL_DIR/current/hermes-hub-backup.py" "$BIN_DIR/hermes-hub-backup"', updater)
        self.assertIn('atomic_install "$FINAL_RELEASE_DIR/hermes-hub-backup.service" "$SERVICE_DIR/hermes-hub-backup.service" 0644', updater)
        self.assertIn('atomic_install "$FINAL_RELEASE_DIR/hermes-hub-backup.timer" "$SERVICE_DIR/hermes-hub-backup.timer" 0644', updater)
        self.assertIn('"$BIN_DIR/hermes-hub-backup"', updater)
        self.assertIn("BACKUP_TIMER_WAS_ENABLED=false", updater)
        self.assertIn("systemctl --user is-enabled --quiet hermes-hub-backup.timer", updater)
        self.assertIn("systemctl --user enable hermes-hub-backup.timer", updater)
        self.assertNotIn("systemctl --user enable --now hermes-hub-backup.timer", updater)
        self.assertLess(updater.index("is-enabled --quiet hermes-hub-backup.timer"), updater.index("TRANSACTION_ACTIVE=true"))
        self.assertIn("BACKUP_BUNDLE_FILES", updater)
        self.assertIn("incomplete backup bundle", updater.lower())

    def test_updater_keeps_preexisting_backup_files_when_release_is_legacy(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        helper_contents = b"local backup helper\n"
        service_contents = "local backup service\n"
        timer_contents = "local backup timer\n"
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(
                Path(temporary),
                bash,
                probe_ok=True,
                backup_bundle_files=(),
                preexisting_backup_helper=helper_contents,
            )
            helper = Path(fixture["bin_dir"]) / "hermes-hub-backup"
            service = Path(fixture["service_dir"]) / "hermes-hub-backup.service"
            timer = Path(fixture["service_dir"]) / "hermes-hub-backup.timer"
            service.parent.mkdir(parents=True, exist_ok=True)
            service.write_text(service_contents, encoding="utf-8")
            timer.write_text(timer_contents, encoding="utf-8")

            result = self._run_updater(bash, fixture, "--no-restart")

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertTrue(helper.is_file())
            self.assertFalse(helper.is_symlink())
            self.assertEqual(helper_contents, helper.read_bytes())
            self.assertEqual(service_contents, service.read_text(encoding="utf-8"))
            self.assertEqual(timer_contents, timer.read_text(encoding="utf-8"))
            systemctl_log = Path(fixture["systemctl_log"]).read_text(encoding="utf-8")
            self.assertNotIn("enable hermes-hub-backup.timer", systemctl_log)

    def test_updater_rejects_partial_backup_bundle(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        helper_contents = b"preexisting helper\n"
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(
                Path(temporary),
                bash,
                probe_ok=True,
                backup_bundle_files=("hermes-hub-backup.py",),
                preexisting_backup_helper=helper_contents,
            )
            helper = Path(fixture["bin_dir"]) / "hermes-hub-backup"

            result = self._run_updater(bash, fixture, "--no-restart")

            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("incomplete backup bundle", result.stdout.lower() + result.stderr.lower())
            self.assertEqual(helper_contents, helper.read_bytes())
            self.assertFalse((Path(fixture["install_dir"]) / "VERSION").exists())

    def test_windows_packager_refuses_version_drift_before_build(self):
        powershell = find_powershell()
        if not powershell:
            self.skipTest("PowerShell unavailable")
        result = subprocess.run(
            [
                powershell,
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(SCRIPTS / "package-windows-msix.ps1"),
                "-Version",
                "9.9.9",
                "-SkipSigning",
            ],
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("diversa dal progetto", result.stdout + result.stderr)

    def test_updater_paginates_past_fifty_app_only_releases(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True, gateway_page=2)
            result = self._run_updater(bash, fixture, "--check")
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            curl_urls = Path(fixture["curl_log"]).read_text(encoding="utf-8").splitlines()
            self.assertTrue(any(url.endswith("per_page=50&page=1") for url in curl_urls), curl_urls)
            self.assertTrue(any(url.endswith("per_page=50&page=2") for url in curl_urls), curl_urls)
            self.assertFalse(any(url.startswith("https://assets.invalid/") for url in curl_urls), curl_urls)
            self.assertIn(f'Compatible release: v{fixture["version"]}', result.stdout)

    def test_updater_rejects_oversized_asset_before_download(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(
                Path(temporary),
                bash,
                probe_ok=True,
                asset_size_override=257 * 1024 * 1024,
            )
            result = self._run_updater(bash, fixture)
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("larger than configured limit (256 MB)", result.stderr)
            curl_urls = Path(fixture["curl_log"]).read_text(encoding="utf-8").splitlines()
            self.assertFalse(any(url.startswith("https://assets.invalid/") for url in curl_urls), curl_urls)

    def test_updater_installs_commits_restarts_and_probes_with_local_fakes(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            fixture["environment"]["FAKE_BACKUP_TIMER_ENABLED"] = "1"
            result = self._run_updater(bash, fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

            version = str(fixture["version"])
            install_dir = Path(fixture["install_dir"])
            service_dir = Path(fixture["service_dir"])
            environment = fixture["environment"]
            self.assertEqual(version, (install_dir / "VERSION").read_text(encoding="utf-8").strip())
            current_target = self._readlink(bash, install_dir / "current", environment)
            self.assertIn(f"/releases/{version}-", current_target.replace("\\", "/"))
            self.assertEqual(
                (SCRIPTS / "hermes-hub-linux.service").read_text(encoding="utf-8"),
                (service_dir / "hermes-hub.service").read_text(encoding="utf-8"),
            )
            backup_helper = Path(fixture["bin_dir"]) / "hermes-hub-backup"
            self.assertTrue(backup_helper.is_file())
            self.assertFalse(backup_helper.is_symlink())
            self.assertEqual((SCRIPTS / "hermes-hub-backup.py").read_bytes(), backup_helper.read_bytes())
            for unit in BACKUP_BUNDLE_FILES[1:]:
                self.assertEqual((SCRIPTS / unit).read_bytes(), (service_dir / unit).read_bytes())
            launcher_target = self._readlink(bash, Path(fixture["home"]) / "hermes-hub-linux.sh", environment)
            self.assertEqual(f"{current_target}/hermes-hub-linux.sh", launcher_target)
            self.assertIn("https://probe.invalid/v1/capabilities", Path(fixture["curl_log"]).read_text(encoding="utf-8"))
            systemctl_log = Path(fixture["systemctl_log"]).read_text(encoding="utf-8")
            self.assertIn("--user daemon-reload", systemctl_log)
            self.assertIn("--user restart hermes-hub.service", systemctl_log)
            self.assertIn("--user enable hermes-hub-backup.timer", systemctl_log)
            self.assertNotIn("--user enable --now hermes-hub-backup.timer", systemctl_log)
            self.assertNotIn("--user start hermes-hub-backup.timer", systemctl_log)
            self.assertIn(f"Installed: {version}", result.stdout)
            self.assertIn("Restarted and verified: hermes-hub.service", result.stdout)

    def test_updater_rejects_invalid_capabilities_and_rolls_back_release(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        valid = json.loads(VALID_GATEWAY_CAPABILITIES_RESPONSE)
        wrong_object = {**valid, "object": "unexpected"}
        wrong_auth = {**valid, "auth": {"type": [], "required": "true"}}
        wrong_features = {**valid, "features": {**valid["features"], "hermes_native": "true"}}
        wrong_endpoints = {**valid, "endpoints": {**valid["endpoints"], "chat_completions": []}}
        cases = (
            ("empty object", {}),
            ("wrong object marker", wrong_object),
            ("wrong auth types", wrong_auth),
            ("wrong feature type", wrong_features),
            ("wrong endpoint type", wrong_endpoints),
        )

        for label, payload in cases:
            with self.subTest(schema=label), tempfile.TemporaryDirectory() as temporary:
                fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
                fixture["environment"]["FAKE_PROBE_RESPONSE"] = json.dumps(payload)
                old_release = self._seed_previous_updater_release(bash, fixture)
                install_dir = Path(fixture["install_dir"])
                service_dir = Path(fixture["service_dir"])
                service_dir.mkdir(parents=True, exist_ok=True)
                old_unit = "old gateway unit sentinel\n"
                (service_dir / "hermes-hub.service").write_text(old_unit, encoding="utf-8")

                result = self._run_updater(bash, fixture)

                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("gateway readiness probe failed", result.stderr)
                self.assertEqual("1.0.0", (install_dir / "VERSION").read_text(encoding="utf-8").strip())
                self.assertEqual(old_release.resolve(), Path(self._readlink(
                    bash, install_dir / "current", fixture["environment"]
                )))
                self.assertEqual(old_unit, (service_dir / "hermes-hub.service").read_text(encoding="utf-8"))
                self.assertEqual(
                    f'{fixture["version"]}|{fixture["asset_digest"]}',
                    (install_dir / "failed-release").read_text(encoding="utf-8").strip(),
                )
                restarts = Path(fixture["systemctl_log"]).read_text(encoding="utf-8").count(
                    "--user restart hermes-hub.service"
                )
                self.assertGreaterEqual(restarts, 2)

    def _write_busy_lease(
        self, home: Path, *, expired: bool = False, expires_in: float = 3600
    ) -> Path:
        lease_dir = Path(home) / ".hermes"
        lease_dir.mkdir(parents=True, exist_ok=True)
        lease = lease_dir / "hub_busy.lock"
        lease.write_text(
            json.dumps(
                {
                    "owner": "test-agent",
                    "task": "long journey task",
                    "expires_at": time.time() - 60 if expired else time.time() + expires_in,
                }
            ),
            encoding="utf-8",
            newline="\n",
        )
        return lease

    def _assert_update_deferred(self, bash, fixture, result, *, reason_fragment: str) -> None:
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("deferred", result.stderr.lower())
        self.assertIn(reason_fragment, result.stderr)
        systemctl_log = Path(fixture["systemctl_log"]).read_text(encoding="utf-8")
        self.assertNotIn("restart hermes-hub.service", systemctl_log)
        pending = Path(fixture["install_dir"]) / ".pending-update"
        self.assertTrue(pending.is_file())
        self.assertTrue(pending.read_text(encoding="utf-8").startswith(f'{fixture["version"]}|'))
        self.assertFalse((Path(fixture["install_dir"]) / "VERSION").exists())
        releases = Path(fixture["install_dir"]) / "releases"
        if releases.is_dir():
            self.assertFalse(any(path.is_dir() for path in releases.iterdir()))

    def _curl_auth_records(self, fixture):
        return [
            line.split("|")
            for line in Path(fixture["curl_auth_log"]).read_text(encoding="utf-8").splitlines()
            if line
        ]

    def _assert_paths_absent_in_bash(self, bash, fixture, paths):
        result = subprocess.run(
            [bash, "-c", 'for path in "$@"; do [ ! -e "$path" ] || exit 1; done', "_", *paths],
            env=fixture["environment"],
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def _require_owner_only_key_file_mode(self, bash, fixture, key_file):
        result = subprocess.run(
            [bash, "-c", 'stat -c %a -- "$1"', "_", bash_path(bash, key_file)],
            env=fixture["environment"],
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        if result.stdout.strip() not in {"400", "600"}:
            self.skipTest("filesystem does not preserve POSIX owner-only mode 0400/0600")

    def test_updater_defers_on_manager_auth_rejection_without_fallback(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        for status in ("401", "403"):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temporary:
                fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
                environment = fixture["environment"]
                environment["HERMES_HUB_MANAGER_API_KEY"] = "manager-test-key"
                environment["FAKE_MANAGER_HTTP_STATUS"] = status
                result = self._run_updater(bash, fixture)

                self._assert_update_deferred(
                    bash,
                    fixture,
                    result,
                    reason_fragment=f"manager authentication rejected (HTTP {status})",
                )
                records = [
                    row
                    for row in self._curl_auth_records(fixture)
                    if row[0].endswith("/status")
                ]
                self.assertEqual(1, len(records))
                self.assertEqual("manager", records[0][1])
                self.assertNotIn("integration-test-key", result.stdout + result.stderr)
                self.assertNotIn("manager-test-key", result.stdout + result.stderr)

    def test_updater_uses_distinct_manager_and_gateway_keys_for_both_gates_and_probe(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            environment = fixture["environment"]
            environment["HERMES_HUB_MANAGER_API_KEY"] = "manager-test-key"
            result = self._run_updater(bash, fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

            records = self._curl_auth_records(fixture)
            manager_records = [row for row in records if row[0].endswith("/status")]
            probe_records = [row for row in records if row[0] == environment["HERMES_HUB_UPDATE_PROBE_URL"]]
            self.assertEqual(2, len(manager_records), records)
            self.assertTrue(all(row[1] == "manager" and row[3] == "true" for row in manager_records), records)
            self.assertEqual(1, len(probe_records), records)
            self.assertEqual("gateway", probe_records[0][1])
            self.assertEqual("true", probe_records[0][3])
            self.assertTrue(
                all(line == "false" for line in Path(fixture["curl_argv_secret_log"]).read_text().splitlines())
            )
            outputs = result.stdout + result.stderr
            all_logs = "\n".join(
                Path(fixture[name]).read_text(encoding="utf-8")
                for name in ("curl_log", "curl_auth_log", "curl_argv_secret_log", "curl_config_log")
            )
            for secret in ("integration-test-key", "manager-test-key"):
                self.assertNotIn(secret, outputs + all_logs)
            config_paths = Path(fixture["curl_config_log"]).read_text().splitlines()
            chmod_calls = Path(fixture["chmod_log"]).read_text().splitlines()
            for config_path in config_paths:
                self.assertIn(f"600|{config_path}", chmod_calls)
            self._assert_paths_absent_in_bash(bash, fixture, config_paths)

    def test_updater_reads_manager_key_from_env_file_before_legacy_gateway_fallback(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            hermes_home = Path(fixture["home"]) / ".hermes"
            hermes_home.mkdir()
            (hermes_home / ".env").write_text(
                "HERMES_GPU_MANAGER_KEY=manager-test-key\n",
                encoding="utf-8",
                newline="\n",
            )
            result = self._run_updater(bash, fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            records = self._curl_auth_records(fixture)
            manager_records = [row for row in records if row[0].endswith("/status")]
            self.assertEqual(2, len(manager_records), records)
            self.assertTrue(all(row[1] == "manager" for row in manager_records), records)

    def test_updater_uses_gateway_key_only_when_manager_key_is_unconfigured(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            result = self._run_updater(bash, fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            manager_records = [row for row in self._curl_auth_records(fixture) if row[0].endswith("/status")]
            self.assertEqual(2, len(manager_records), manager_records)
            self.assertTrue(all(row[1] == "gateway" for row in manager_records), manager_records)

    def test_updater_defers_when_manager_and_gateway_keys_are_missing(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            environment = fixture["environment"]
            for key in (
                "HERMES_HUB_API_KEY",
                "HERMES_API_KEY",
                "HERMES_GATEWAY_API_KEY",
                "API_SERVER_KEY",
                "HERMESAPIKEY",
            ):
                environment.pop(key, None)
            result = self._run_updater(bash, fixture)
            self._assert_update_deferred(
                bash,
                fixture,
                result,
                reason_fragment="no API key available for gateway health check",
            )
            self.assertFalse(self._curl_auth_records(fixture))

    def test_updater_rejects_malformed_explicit_manager_keys_without_fallback(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        malformed_keys = (
            'manager"key\nurl = https://evil.invalid/status',
            "manager-key\r\nurl = https://evil.invalid/status",
            "manager\\key",
        )
        for malformed_key in malformed_keys:
            with self.subTest(malformed_key=repr(malformed_key)), tempfile.TemporaryDirectory() as temporary:
                fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
                fixture["environment"]["HERMES_HUB_MANAGER_API_KEY"] = malformed_key
                result = self._run_updater(bash, fixture)
                self._assert_update_deferred(
                    bash,
                    fixture,
                    result,
                    reason_fragment="manager API key configuration invalid",
                )
                records = self._curl_auth_records(fixture)
                self.assertFalse([row for row in records if row[0].endswith("/status")])
                self.assertTrue(all(row[1] == "gateway" for row in records), records)
                logs = Path(fixture["curl_log"]).read_text(encoding="utf-8")
                self.assertNotIn("evil.invalid", logs)
                self.assertNotIn(malformed_key, result.stdout + result.stderr)
                self.assertNotIn("integration-test-key", result.stdout + result.stderr)

    def test_updater_accepts_owner_only_manager_key_file_and_cleans_curl_config(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            key_file = Path(temporary) / "manager.key"
            key_file.write_text("manager-test-key", encoding="ascii", newline="")
            os.chmod(key_file, 0o600)
            self._require_owner_only_key_file_mode(bash, fixture, key_file)
            key_file_bash_path = bash_path(bash, key_file)
            fixture["environment"]["HERMES_HUB_MANAGER_KEY_FILE"] = key_file_bash_path
            result = self._run_updater(bash, fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            manager_records = [row for row in self._curl_auth_records(fixture) if row[0].endswith("/status")]
            self.assertEqual(2, len(manager_records), manager_records)
            self.assertTrue(all(row[1] == "manager" and row[3] == "true" for row in manager_records), manager_records)
            config_paths = Path(fixture["curl_config_log"]).read_text().splitlines()
            chmod_calls = Path(fixture["chmod_log"]).read_text().splitlines()
            for config_path in config_paths:
                self.assertIn(f"600|{config_path}", chmod_calls)
            self._assert_paths_absent_in_bash(bash, fixture, config_paths)

    def test_updater_rejects_unsafe_manager_key_files(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            key_file = Path(temporary) / "manager.key"
            key_file.write_text("manager-test-key", encoding="ascii", newline="")
            key_file_bash_path = bash_path(bash, key_file)
            fixture["environment"]["HERMES_HUB_MANAGER_KEY_FILE"] = key_file_bash_path
            mode_result = subprocess.run(
                [bash, "-c", 'stat -c %a -- "$1"', "_", key_file_bash_path],
                env=fixture["environment"],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, mode_result.returncode, mode_result.stdout + mode_result.stderr)
            if mode_result.stdout.strip() != "644":
                self.skipTest("filesystem does not preserve POSIX mode 0644 for unsafe-file test")
            result = self._run_updater(bash, fixture)
            self._assert_update_deferred(
                bash,
                fixture,
                result,
                reason_fragment="manager API key configuration invalid",
            )
            records = self._curl_auth_records(fixture)
            self.assertFalse([row for row in records if row[0].endswith("/status")])
            self.assertTrue(all(row[1] == "gateway" for row in records), records)

        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            missing_key_file = bash_path(bash, Path(temporary) / "missing-manager.key")
            fixture["environment"]["HERMES_HUB_MANAGER_KEY_FILE"] = missing_key_file
            fixture["environment"]["FAKE_MANAGER_KEY_FILE"] = missing_key_file
            result = self._run_updater(bash, fixture)
            self._assert_update_deferred(
                bash,
                fixture,
                result,
                reason_fragment="manager API key configuration invalid",
            )
            records = self._curl_auth_records(fixture)
            self.assertFalse([row for row in records if row[0].endswith("/status")])
            self.assertTrue(all(row[1] == "gateway" for row in records), records)

    def test_updater_rejects_symlink_manager_key_file(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            target = Path(temporary) / "manager-real.key"
            target.write_text("manager-test-key", encoding="ascii", newline="")
            os.chmod(target, 0o600)
            self._require_owner_only_key_file_mode(bash, fixture, target)
            link = Path(temporary) / "manager-link.key"
            try:
                link.symlink_to(target)
            except OSError as error:
                self.skipTest(f"symlink unavailable: {error}")
            link_bash_path = bash_path(bash, link)
            fixture["environment"]["HERMES_HUB_MANAGER_KEY_FILE"] = link_bash_path
            result = self._run_updater(bash, fixture)
            self._assert_update_deferred(
                bash,
                fixture,
                result,
                reason_fragment="manager API key configuration invalid",
            )
            records = self._curl_auth_records(fixture)
            self.assertFalse([row for row in records if row[0].endswith("/status")])
            self.assertTrue(all(row[1] == "gateway" for row in records), records)

    def test_updater_rejects_manager_key_file_owned_by_another_user(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        owner_result = subprocess.run([bash, "-c", "id -u"], text=True, capture_output=True, check=True)
        foreign_owner = "1" if owner_result.stdout.strip() == "0" else "0"
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            key_file = Path(temporary) / "manager.key"
            key_file.write_text("manager-test-key", encoding="ascii", newline="")
            os.chmod(key_file, 0o600)
            self._require_owner_only_key_file_mode(bash, fixture, key_file)
            key_file_bash_path = bash_path(bash, key_file)
            fixture["environment"]["HERMES_HUB_MANAGER_KEY_FILE"] = key_file_bash_path
            fixture["environment"]["FAKE_MANAGER_KEY_FILE"] = key_file_bash_path
            fixture["environment"]["FAKE_MANAGER_KEY_FILE_OWNER"] = foreign_owner
            result = self._run_updater(bash, fixture)
            self._assert_update_deferred(
                bash,
                fixture,
                result,
                reason_fragment="manager API key configuration invalid",
            )
            records = self._curl_auth_records(fixture)
            self.assertFalse([row for row in records if row[0].endswith("/status")])
            self.assertTrue(all(row[1] == "gateway" for row in records), records)

    def test_updater_defers_on_invalid_manager_status_schema(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        for manager_state in ("invalid_schema", "unknown", "malformed_json"):
            with self.subTest(manager_state=manager_state), tempfile.TemporaryDirectory() as temporary:
                fixture = self._prepare_updater_fixture(
                    Path(temporary),
                    bash,
                    probe_ok=True,
                    manager_state=manager_state,
                )
                fixture["environment"]["HERMES_HUB_MANAGER_API_KEY"] = "manager-test-key"
                result = self._run_updater(bash, fixture)
                self._assert_update_deferred(
                    bash,
                    fixture,
                    result,
                    reason_fragment="manager status unreadable",
                )

    def test_updater_keeps_manager_network_errors_distinct_from_auth_rejection(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True, manager_state="down")
            fixture["environment"]["HERMES_HUB_MANAGER_API_KEY"] = "manager-test-key"
            result = self._run_updater(bash, fixture)
            self._assert_update_deferred(bash, fixture, result, reason_fragment="manager unreachable")
            records = [
                row
                for row in self._curl_auth_records(fixture)
                if row[0].endswith("/status")
            ]
            self.assertEqual(1, len(records))
            self.assertEqual("manager", records[0][1])

    def test_updater_defers_restart_while_busy_lease_active(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            self._write_busy_lease(Path(fixture["home"]))
            result = self._run_updater(bash, fixture)
            self._assert_update_deferred(bash, fixture, result, reason_fragment="test-agent")

    def test_updater_keeps_a_lease_that_expires_in_fifteen_seconds_busy(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            self._write_busy_lease(Path(fixture["home"]), expires_in=15)
            result = self._run_updater(bash, fixture)
            self._assert_update_deferred(bash, fixture, result, reason_fragment="test-agent")

    def test_updater_uses_distinct_gateway_and_manager_credentials_for_both_gates(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            fixture["environment"]["HERMES_HUB_MANAGER_API_KEY"] = "manager-test-key"
            result = self._run_updater(bash, fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            records = self._curl_auth_records(fixture)
            gateway = [row for row in records if row[0].endswith("/health/detailed")]
            manager = [row for row in records if row[0].endswith("/status")]
            self.assertEqual(2, len(gateway), records)
            self.assertTrue(all(row[1] == "gateway" for row in gateway), gateway)
            self.assertEqual(2, len(manager), records)
            self.assertTrue(all(row[1] == "manager" for row in manager), manager)
            urls = Path(fixture["curl_log"]).read_text(encoding="utf-8").splitlines()
            self.assertEqual(2, sum(url.endswith("/health/detailed") for url in urls), urls)
            self.assertFalse(any(url.endswith("/health") for url in urls), urls)

    def test_updater_defers_for_active_or_unknown_gateway_health(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        cases = (
            ("active_agents", "active agent", "200"),
            ("active_runs", "active run", "200"),
            ("busy", "busy", "200"),
            ("not_drainable", "drainable", "200"),
            ("wrong_type", "gateway health unreadable", "200"),
            ("missing", "gateway health unreadable", "200"),
            ("invalid_json", "gateway health unreadable", "200"),
            ("not_running", "gateway state", "200"),
            ("idle", "gateway health unavailable (HTTP 404)", "404"),
            ("idle", "gateway authentication rejected (HTTP 401)", "401"),
        )
        for gateway_state, reason, http_status in cases:
            with self.subTest(gateway_state=gateway_state, http_status=http_status), tempfile.TemporaryDirectory() as temporary:
                fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
                fixture["environment"]["FAKE_GATEWAY_STATE"] = gateway_state
                fixture["environment"]["FAKE_GATEWAY_HTTP_STATUS"] = http_status
                result = self._run_updater(bash, fixture)
                self._assert_update_deferred(bash, fixture, result, reason_fragment=reason)

    def test_updater_accepts_legacy_0a62610f1_idle_gateway_health(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            health = self._base_gateway_health_payload()
            health["readiness"] = {
                "status": "ok",
                "checks": {
                    "background_queues": {
                        "active_api_runs": 0,
                        "process_completions": 0,
                        "active_delegations": 0,
                    }
                },
            }
            fixture["environment"]["FAKE_GATEWAY_RESPONSE"] = json.dumps(health)

            result = self._run_updater(bash, fixture)

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn(f"Installed: {fixture['version']}", result.stdout)
            self.assertIn("--user restart hermes-hub.service", Path(fixture["systemctl_log"]).read_text(encoding="utf-8"))

    def test_updater_accepts_modern_idle_gateway_health_without_optional_queues(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            health = self._base_gateway_health_payload()
            health["api_server"] = {"active_runs": 0}
            health["readiness"] = {"status": "ok"}
            fixture["environment"]["FAKE_GATEWAY_RESPONSE"] = json.dumps(health)

            result = self._run_updater(bash, fixture)

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn(f"Installed: {fixture['version']}", result.stdout)

    def test_updater_defers_for_legacy_missing_partial_or_unready_health(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        cases = (
            ("no_readiness", None, "gateway health unreadable"),
            ("no_readiness_status", {"checks": {}}, "gateway health unreadable"),
            (
                "partial_background_queues",
                {
                    "status": "ok",
                    "checks": {"background_queues": {"active_api_runs": 0, "process_completions": 0}},
                },
                "gateway health unreadable",
            ),
            ("no_background_queues", {"status": "ok", "checks": {}}, "gateway health unreadable"),
            (
                "readiness_not_ok",
                {
                    "status": "starting",
                    "checks": {
                        "background_queues": {
                            "active_api_runs": 0,
                            "process_completions": 0,
                            "active_delegations": 0,
                        }
                    },
                },
                "gateway readiness",
            ),
        )
        for case, readiness, reason in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
                health = self._base_gateway_health_payload()
                if readiness is not None:
                    health["readiness"] = readiness
                if case == "readiness_not_ok":
                    health["api_server"] = {"active_runs": 0}
                fixture["environment"]["FAKE_GATEWAY_RESPONSE"] = json.dumps(health)
                result = self._run_updater(bash, fixture)
                self._assert_update_deferred(bash, fixture, result, reason_fragment=reason)

    def test_updater_defers_for_legacy_nonzero_background_queue_counters(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            health = self._base_gateway_health_payload()
            health["readiness"] = {
                "status": "ok",
                "checks": {
                    "background_queues": {
                        "active_api_runs": 0,
                        "process_completions": 1,
                        "active_delegations": 0,
                    }
                },
            }
            fixture["environment"]["FAKE_GATEWAY_RESPONSE"] = json.dumps(health)

            result = self._run_updater(bash, fixture)

            self._assert_update_deferred(bash, fixture, result, reason_fragment="background")

    def test_updater_defers_for_malformed_optional_background_queue_counters(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        queue_cases = (
            ("wrong_type", []),
            ("missing_counter", {"active_api_runs": 0, "process_completions": 0}),
            ("invalid_counter", {"active_api_runs": 0, "process_completions": "0", "active_delegations": 0}),
            ("negative_counter", {"active_api_runs": 0, "process_completions": 0, "active_delegations": -1}),
            ("nonzero_counter", {"active_api_runs": 0, "process_completions": 0, "active_delegations": 1}),
        )
        for schema in ("legacy", "modern"):
            for case, queues in queue_cases:
                with self.subTest(schema=schema, case=case), tempfile.TemporaryDirectory() as temporary:
                    fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
                    health = self._base_gateway_health_payload()
                    if schema == "modern":
                        health["api_server"] = {"active_runs": 0}
                    health["readiness"] = {"status": "ok", "checks": {"background_queues": queues}}
                    fixture["environment"]["FAKE_GATEWAY_RESPONSE"] = json.dumps(health)
                    result = self._run_updater(bash, fixture)
                    reason = "background" if case == "nonzero_counter" else "gateway health unreadable"
                    self._assert_update_deferred(bash, fixture, result, reason_fragment=reason)

    def test_updater_defers_for_invalid_modern_active_run_counters(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        cases = (
            ("missing", {}),
            ("bool", {"active_runs": False}),
            ("negative", {"active_runs": -1}),
        )
        for case, api_server in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
                health = self._base_gateway_health_payload()
                health["api_server"] = api_server
                health["readiness"] = {"status": "ok"}
                fixture["environment"]["FAKE_GATEWAY_RESPONSE"] = json.dumps(health)
                result = self._run_updater(bash, fixture)
                self._assert_update_deferred(bash, fixture, result, reason_fragment="gateway health unreadable")

    def test_updater_final_gate_rechecks_gateway_api_active_runs(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            fixture["environment"]["FAKE_GATEWAY_FLIP"] = "1"
            result = self._run_updater(bash, fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("aborted at final check", result.stderr)
            self.assertIn("active run", result.stderr)
            self.assertEqual("2", Path(temporary, "gateway_calls").read_text(encoding="utf-8"))
            self.assertNotIn("restart hermes-hub.service", Path(fixture["systemctl_log"]).read_text(encoding="utf-8"))

    def test_updater_defers_restart_while_manager_queue_busy(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True, manager_state="queue")
            result = self._run_updater(bash, fixture)
            self._assert_update_deferred(bash, fixture, result, reason_fragment="manager queue")

    def test_updater_defers_restart_while_media_warm_without_queue(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True, manager_state="media_idle")
            result = self._run_updater(bash, fixture)
            self._assert_update_deferred(bash, fixture, result, reason_fragment="MEDIA_READY")

    def test_updater_defers_restart_while_comfy_holds_prompts(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True, comfy_state="busy")
            result = self._run_updater(bash, fixture)
            self._assert_update_deferred(bash, fixture, result, reason_fragment="ComfyUI")

    def test_updater_ignores_stale_lease_and_installs(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            lease = self._write_busy_lease(Path(fixture["home"]), expired=True)
            result = self._run_updater(bash, fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn(f"Installed: {fixture['version']}", result.stdout)
            self.assertIn("--user restart hermes-hub.service", Path(fixture["systemctl_log"]).read_text(encoding="utf-8"))
            self.assertFalse(lease.exists())

    def test_updater_final_gate_aborts_when_busy_midway(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True, manager_flip=True)
            result = self._run_updater(bash, fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("aborted at final check", result.stderr)
            self.assertIn("manager queue holds 1 job(s)", result.stderr)
            systemctl_log = Path(fixture["systemctl_log"]).read_text(encoding="utf-8")
            self.assertNotIn("restart hermes-hub.service", systemctl_log)
            pending = Path(fixture["install_dir"]) / ".pending-update"
            self.assertTrue(pending.is_file())
            self.assertTrue(pending.read_text(encoding="utf-8").startswith(f'{fixture["version"]}|'))
            self.assertFalse((Path(fixture["install_dir"]) / "VERSION").exists())
            releases = Path(fixture["install_dir"]) / "releases"
            if releases.is_dir():
                self.assertFalse(any(path.is_dir() for path in releases.iterdir()))

    def test_updater_clears_pending_after_successful_install(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            pending = Path(fixture["install_dir"]) / ".pending-update"
            pending.write_text(f'{fixture["version"]}|2026-10-03T00:00:00Z|stale\n', encoding="utf-8", newline="\n")
            result = self._run_updater(bash, fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn(f"Installed: {fixture['version']}", result.stdout)
            self.assertFalse(pending.exists())

    def test_updater_probe_failure_restores_preexisting_regular_managed_links(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        targets = (
            ("launcher", "home", "hermes-hub-linux.sh"),
            ("patcher", "home", "patch-hermes-gateway-native.py"),
            ("updater-helper", "bin_dir", "hermes-hub-linux-update"),
            ("agent-updater-helper", "bin_dir", "hermes-hub-agent-update"),
            ("backup-helper", "bin_dir", "hermes-hub-backup"),
            ("tailscale-wait-helper", "bin_dir", "hermes-wait-tailscale.sh"),
            ("llama-wait-helper", "bin_dir", "hermes-wait-llama.sh"),
            ("tailscale-wait-alias", "bin_dir", "hermes-wait-tailscale"),
            ("llama-wait-alias", "bin_dir", "hermes-wait-llama"),
            ("power-monitor-helper", "bin_dir", "hermes-power-monitor.sh"),
            ("power-monitor-alias", "bin_dir", "hermes-power-monitor"),
        )
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=False)
            old_release = self._seed_previous_updater_release(bash, fixture)
            originals = {}
            for label, directory_key, filename in targets:
                target = Path(fixture[directory_key]) / filename
                original = f"pre-existing {label} bytes\n".encode("utf-8")
                target.write_bytes(original)
                subprocess.run(
                    [bash, "-c", 'chmod 751 -- "$1"', "_", bash_path(bash, target)],
                    env=fixture["environment"],
                    text=True,
                    capture_output=True,
                    check=True,
                )
                original_mode = None
                if os.name != "nt":
                    original_mode = subprocess.run(
                        [bash, "-c", 'stat -c "%a" -- "$1"', "_", bash_path(bash, target)],
                        env=fixture["environment"],
                        text=True,
                        capture_output=True,
                        check=True,
                    ).stdout.strip()
                    self.assertEqual("751", original_mode)
                originals[label] = (target, hashlib.sha256(original).hexdigest(), original_mode)

            result = self._run_updater(bash, fixture)

            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("gateway readiness probe failed", result.stderr)
            for label, (target, expected_digest, original_mode) in originals.items():
                with self.subTest(target=label):
                    regular_file = subprocess.run(
                        [bash, "-c", 'test -f "$1" && test ! -L "$1"', "_", bash_path(bash, target)],
                        env=fixture["environment"],
                        text=True,
                        capture_output=True,
                        check=False,
                    )
                    self.assertEqual(0, regular_file.returncode, regular_file.stdout + regular_file.stderr)
                    self.assertEqual(expected_digest, hashlib.sha256(target.read_bytes()).hexdigest())
                    if original_mode is not None:
                        mode = subprocess.run(
                            [bash, "-c", 'stat -c "%a" -- "$1"', "_", bash_path(bash, target)],
                            env=fixture["environment"],
                            text=True,
                            capture_output=True,
                            check=True,
                        ).stdout.strip()
                        self.assertEqual(original_mode, mode)
            self.assertEqual(
                bash_path(bash, old_release),
                self._readlink(bash, Path(fixture["install_dir"]) / "current", fixture["environment"]),
            )

    def test_updater_probe_failure_restores_old_relative_symlink_target(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=False)
            old_release = self._seed_previous_updater_release(bash, fixture)
            home = Path(fixture["home"])
            link_path = home / "hermes-hub-linux.sh"
            (home / "previous-launcher-target").write_text("old launcher", encoding="utf-8")
            subprocess.run(
                [bash, "-c", 'ln -s "$1" "$2"', "_", "previous-launcher-target", bash_path(bash, link_path)],
                env=fixture["environment"],
                text=True,
                capture_output=True,
                check=True,
            )

            result = self._run_updater(bash, fixture)

            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("gateway readiness probe failed", result.stderr)
            is_symlink = subprocess.run(
                [bash, "-c", 'test -L "$1"', "_", bash_path(bash, link_path)],
                env=fixture["environment"],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, is_symlink.returncode, result.stdout + result.stderr)
            restored_target = subprocess.run(
                [bash, "-c", 'readlink -- "$1"', "_", bash_path(bash, link_path)],
                env=fixture["environment"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout.rstrip("\n")
            self.assertEqual("previous-launcher-target", restored_target)
            self.assertEqual(
                bash_path(bash, old_release),
                self._readlink(bash, Path(fixture["install_dir"]) / "current", fixture["environment"]),
            )

    def test_updater_refuses_unsupported_managed_path_before_switching_release(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            old_release = self._seed_previous_updater_release(bash, fixture)
            unsupported = Path(fixture["home"]) / "patch-hermes-gateway-native.py"
            unsupported.mkdir()

            result = self._run_updater(bash, fixture)

            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("unsupported managed path", result.stderr.lower())
            self.assertTrue(unsupported.is_dir())
            self.assertEqual(
                bash_path(bash, old_release),
                self._readlink(bash, Path(fixture["install_dir"]) / "current", fixture["environment"]),
            )
            self.assertNotIn("restart hermes-hub.service", Path(fixture["systemctl_log"]).read_text(encoding="utf-8"))

    def test_updater_refuses_empty_managed_symlink_target_before_switch(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=True)
            old_release = self._seed_previous_updater_release(bash, fixture)
            link_path = Path(fixture["home"]) / "hermes-hub-linux.sh"
            target_file = Path(fixture["home"]) / "old-launcher-target"
            target_file.write_text("old launcher", encoding="utf-8")
            subprocess.run(
                [bash, "-c", 'ln -s "$1" "$2"', "_", "old-launcher-target", bash_path(bash, link_path)],
                env=fixture["environment"],
                text=True,
                capture_output=True,
                check=True,
            )
            bash_env = Path(temporary) / "bash-env.sh"
            with bash_env.open("a", encoding="utf-8", newline="\n") as stream:
                stream.write(
                    "\nreadlink() {\n"
                    '  if [ "${1:-}" = "-z" ] && [ "${3:-}" = "${FAKE_READLINK_EMPTY_PATH:-}" ]; then\n'
                    "    printf '\\0'\n"
                    "    return 0\n"
                    "  fi\n"
                    '  command readlink "$@"\n'
                    "}\n"
                )
            fixture["environment"]["FAKE_READLINK_EMPTY_PATH"] = bash_path(bash, link_path)

            result = self._run_updater(bash, fixture)

            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("empty managed symlink target", result.stderr.lower())
            is_symlink = subprocess.run(
                [bash, "-c", 'test -L "$1"', "_", bash_path(bash, link_path)],
                env=fixture["environment"],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, is_symlink.returncode, result.stdout + result.stderr)
            self.assertEqual(
                bash_path(bash, old_release),
                self._readlink(bash, Path(fixture["install_dir"]) / "current", fixture["environment"]),
            )

    def test_updater_probe_failure_rolls_back_current_version_and_units(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_updater_fixture(Path(temporary), bash, probe_ok=False)
            install_dir = Path(fixture["install_dir"])
            service_dir = Path(fixture["service_dir"])
            environment = fixture["environment"]
            old_version = "1.0.0"
            old_release = install_dir / "releases" / f"{old_version}-existing"
            old_release.mkdir(parents=True)
            for name in UPDATER_REQUIRED_FILES:
                shutil.copy2(SCRIPTS / name, old_release / name)
            (old_release / "VERSION").write_text(f"{old_version}\n", encoding="utf-8", newline="\n")
            (install_dir / "VERSION").write_text(f"{old_version}\n", encoding="utf-8", newline="\n")
            service_dir.mkdir(parents=True)
            old_units = {
                name: f"old unit sentinel: {name}\n"
                for name in (
                    "hermes-hub.service",
                    "hermes-hub-linux-update.service",
                    "hermes-hub-linux-update.timer",
                    "hermes-hub-backup.service",
                    "hermes-hub-backup.timer",
                    "hermes-power-monitor.service",
                )
            }
            for name, content in old_units.items():
                (service_dir / name).write_text(content, encoding="utf-8", newline="\n")
            subprocess.run(
                [
                    bash,
                    "-c",
                    'ln -s "$1" "$2" && test -L "$2"',
                    "_",
                    bash_path(bash, old_release),
                    bash_path(bash, install_dir / "current"),
                ],
                env=environment,
                text=True,
                capture_output=True,
                check=True,
            )
            backup_helper = Path(fixture["bin_dir"]) / "hermes-hub-backup"
            subprocess.run(
                [
                    bash,
                    "-c",
                    'ln -s "$1" "$2" && test -L "$2"',
                    "_",
                    bash_path(bash, install_dir / "current" / "hermes-hub-backup.py"),
                    bash_path(bash, backup_helper),
                ],
                env=environment,
                text=True,
                capture_output=True,
                check=True,
            )

            result = self._run_updater(bash, fixture)
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("gateway readiness probe failed", result.stderr)
            self.assertIn("rolling back gateway release", result.stderr)
            self.assertEqual(old_version, (install_dir / "VERSION").read_text(encoding="utf-8").strip())
            self.assertEqual(
                self._readlink(bash, old_release, environment),
                self._readlink(bash, install_dir / "current", environment),
            )
            for name, content in old_units.items():
                self.assertEqual(content, (service_dir / name).read_text(encoding="utf-8"))
            self.assertTrue(backup_helper.is_file())
            self.assertFalse(backup_helper.is_symlink())
            self.assertEqual((old_release / "hermes-hub-backup.py").read_bytes(), backup_helper.read_bytes())
            managed_links = (
                Path(fixture["home"]) / "hermes-hub-linux.sh",
                Path(fixture["home"]) / "patch-hermes-gateway-native.py",
                Path(fixture["bin_dir"]) / "hermes-hub-linux-update",
                Path(fixture["bin_dir"]) / "hermes-hub-agent-update",
                Path(fixture["bin_dir"]) / "hermes-wait-tailscale.sh",
                Path(fixture["bin_dir"]) / "hermes-wait-llama.sh",
                Path(fixture["bin_dir"]) / "hermes-wait-tailscale",
                Path(fixture["bin_dir"]) / "hermes-wait-llama",
                Path(fixture["bin_dir"]) / "hermes-power-monitor.sh",
                Path(fixture["bin_dir"]) / "hermes-power-monitor",
            )
            missing_links = subprocess.run(
                [
                    bash,
                    "-c",
                    'for path in "$@"; do [ ! -e "$path" ] && [ ! -L "$path" ] || exit 1; done',
                    "_",
                    *(bash_path(bash, link) for link in managed_links),
                ],
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, missing_links.returncode, missing_links.stdout + missing_links.stderr)
            self.assertFalse(any(path.name.startswith(f'{fixture["version"]}-') for path in (install_dir / "releases").iterdir()))
            systemctl_log = Path(fixture["systemctl_log"]).read_text(encoding="utf-8")
            self.assertGreaterEqual(systemctl_log.count("--user restart hermes-hub.service"), 2)
            self.assertEqual(
                1,
                Path(fixture["curl_log"]).read_text(encoding="utf-8").splitlines().count(
                    "https://probe.invalid/v1/capabilities"
                ),
            )
            failed_release = install_dir / "failed-release"
            self.assertEqual(
                f'{fixture["version"]}|{fixture["asset_digest"]}',
                failed_release.read_text(encoding="utf-8").strip(),
            )
            curl_before_retry = Path(fixture["curl_log"]).read_text(encoding="utf-8").splitlines()
            systemctl_before_retry = Path(fixture["systemctl_log"]).read_text(encoding="utf-8")

            retry = self._run_updater(bash, fixture)

            self.assertEqual(0, retry.returncode, retry.stdout + retry.stderr)
            self.assertIn("Quarantined failed release", retry.stdout)
            curl_after_retry = Path(fixture["curl_log"]).read_text(encoding="utf-8").splitlines()
            asset_url = f'https://assets.invalid/HermesHub-{fixture["version"]}-linux-gateway.tar.gz'
            self.assertEqual(curl_before_retry.count(asset_url), curl_after_retry.count(asset_url))
            self.assertEqual(
                curl_before_retry.count("https://probe.invalid/v1/capabilities"),
                curl_after_retry.count("https://probe.invalid/v1/capabilities"),
            )
            self.assertEqual(systemctl_before_retry, Path(fixture["systemctl_log"]).read_text(encoding="utf-8"))

    def test_launcher_env_merge_preserves_unowned_keys(self):
        script = (SCRIPTS / "hermes-hub-linux.sh").read_text(encoding="utf-8")
        merge_program = heredoc_between(
            script,
            'python3 - "$HERMES_ENV" <<\'PY\'\n',
            "\nPY\n\n# Prefer CUDA",
        )
        with tempfile.TemporaryDirectory() as temporary:
            env_file = Path(temporary) / ".env"
            env_file.write_text("UNRELATED_TOKEN=keep-me\nHERMES_API_KEY=old\nHERMES_API_KEY=duplicate\n", encoding="utf-8")
            environment = os.environ.copy()
            environment.update(
                {
                    "HERMES_API_KEY": "new-key",
                    "HERMES_HUB_CONVERSATIONS_PATH": "/tmp/conversations with spaces.json",
                    "API_SERVER_ENABLED": "true",
                }
            )
            subprocess.run(
                [sys.executable, "-c", merge_program, str(env_file)],
                env=environment,
                text=True,
                capture_output=True,
                check=True,
            )
            result = env_file.read_text(encoding="utf-8")
        self.assertIn("UNRELATED_TOKEN=keep-me", result)
        self.assertEqual(1, result.count("HERMES_API_KEY="))
        self.assertIn("HERMES_API_KEY=new-key", result)
        self.assertIn('HERMES_HUB_CONVERSATIONS_PATH="/tmp/conversations with spaces.json"', result)

    def test_launcher_requires_resident_gpu_stt_and_tts_by_default(self):
        script = (SCRIPTS / "hermes-hub-linux.sh").read_text(encoding="utf-8")
        for expected in (
            'HERMES_KOKORO_PRELOAD_REQUIRED="${HERMES_KOKORO_PRELOAD_REQUIRED:-1}"',
            'HERMES_KOKORO_REQUIRE_GPU="${HERMES_KOKORO_REQUIRE_GPU:-1}"',
            'HERMES_WHISPER_PRELOAD_REQUIRED="${HERMES_WHISPER_PRELOAD_REQUIRED:-1}"',
            'HERMES_WHISPER_DEVICE="${HERMES_WHISPER_DEVICE:-cuda}"',
            'HERMES_WHISPER_DEVICE_INDEX="${HERMES_WHISPER_DEVICE_INDEX:-1}"',
        ):
            self.assertIn(expected, script)

    def test_launcher_restores_persisted_primary_and_legacy_hub_alias(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        script = (SCRIPTS / "hermes-hub-linux.sh").read_text(encoding="utf-8")
        start = script.index('HERMES_HOME="')
        end = script.index('\nHERMES_MAX_ITERATIONS=', start)
        auth_preamble = script[start:end]
        with tempfile.TemporaryDirectory() as temporary:
            hermes_home = Path(temporary) / ".hermes"
            hermes_home.mkdir()
            primary = "a" * 64
            (hermes_home / ".env").write_text(
                f"API_SERVER_KEY={primary}\n"
                f"HERMES_API_KEY={primary}\n"
                f"HERMESAPIKEY={primary}\n"
                "HERMES_HUB_API_KEY=hermes-hub\n"
                "HERMES_GATEWAY_API_KEY=hermes-hub\n",
                encoding="utf-8",
            )
            environment = os.environ.copy()
            for key in (
                "API_SERVER_KEY",
                "HERMES_API_KEY",
                "HERMESAPIKEY",
                "HERMES_HUB_API_KEY",
                "HERMES_GATEWAY_API_KEY",
            ):
                environment.pop(key, None)
            environment["HERMES_HOME"] = bash_path(bash, hermes_home)
            environment["HERMES_NATIVE_GATEWAY_PATCH"] = "false"
            command = auth_preamble + "\nprintf '%s|%s|%s' \"$API_SERVER_KEY\" \"$HERMES_HUB_API_KEY\" \"$HERMES_GATEWAY_API_KEY\""
            result = subprocess.run(
                [bash, "-c", command],
                env=environment,
                text=True,
                capture_output=True,
                check=True,
            )
            self.assertEqual(f"{primary}|hermes-hub|hermes-hub", result.stdout)
            self.assertFalse((hermes_home / "api_server.key").exists())

    def test_launcher_preserves_explicit_legacy_short_primary_key(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        script = (SCRIPTS / "hermes-hub-linux.sh").read_text(encoding="utf-8")
        start = script.index('HERMES_HOME="')
        end = script.index('\nHERMES_MAX_ITERATIONS=', start)
        auth_preamble = script[start:end]
        with tempfile.TemporaryDirectory() as temporary:
            hermes_home = Path(temporary) / ".hermes"
            hermes_home.mkdir()
            (hermes_home / ".env").write_text("HERMES_API_KEY=hermes-hub\n", encoding="utf-8")
            environment = os.environ.copy()
            for key in (
                "API_SERVER_KEY",
                "HERMES_API_KEY",
                "HERMESAPIKEY",
                "HERMES_HUB_API_KEY",
                "HERMES_GATEWAY_API_KEY",
            ):
                environment.pop(key, None)
            environment["HERMES_HOME"] = bash_path(bash, hermes_home)
            environment["HERMES_NATIVE_GATEWAY_PATCH"] = "false"
            command = auth_preamble + "\nprintf '%s|%s|%s' \"$API_SERVER_KEY\" \"$HERMES_API_KEY\" \"$HERMES_HUB_API_KEY\""
            result = subprocess.run(
                [bash, "-c", command],
                env=environment,
                text=True,
                capture_output=True,
                check=True,
            )
            self.assertEqual("hermes-hub|hermes-hub|hermes-hub", result.stdout)
            self.assertFalse((hermes_home / "api_server.key").exists())

    def test_updater_probe_restores_hub_key_from_persisted_env(self):
        script = (SCRIPTS / "hermes-hub-linux-update.sh").read_text(encoding="utf-8")
        self.assertIn('HERMES_ENV_FILE="${HERMES_ENV_FILE:-$HOME/.hermes/.env}"', script)
        resolver = heredoc_between(script, "resolve_probe_key() {\n", "\n}\n")
        self.assertIn('read_env_value "$HERMES_ENV_FILE" "$key_name"', resolver)
        self.assertLess(resolver.index("HERMES_HUB_API_KEY HERMES_GATEWAY_API_KEY"), resolver.index("API_SERVER_KEY HERMES_API_KEY"))

    def test_media_roots_keep_broad_terminal_root_last(self):
        launcher = (SCRIPTS / "hermes-hub-linux.sh").read_text(encoding="utf-8")
        service = (SCRIPTS / "hermes-hub-linux.service").read_text(encoding="utf-8")
        launcher_line = next(line for line in launcher.splitlines() if line.startswith('HERMES_MEDIA_ROOTS="'))
        service_line = next(line for line in service.splitlines() if line.startswith("Environment=HERMES_MEDIA_ROOTS="))
        self.assertLess(launcher_line.index("$HERMES_HUB_UPLOAD_PATH"), launcher_line.index("$HERMES_TERMINAL_CWD"))
        self.assertLess(launcher_line.index("$HERMES_NEWS_LIBRARY_PATH"), launcher_line.index("$HERMES_TERMINAL_CWD"))
        self.assertTrue(service_line.endswith(":%h"), service_line)

    def test_power_monitor_env_parser_handles_matching_quotes(self):
        bash = shutil.which("bash")
        if not bash and os.name == "nt":
            candidate = Path(r"C:\Program Files\Git\bin\bash.exe")
            bash = str(candidate) if candidate.is_file() else None
        if not bash:
            self.skipTest("bash unavailable")
        script = (SCRIPTS / "hermes-power-monitor.sh").read_text(encoding="utf-8")
        start = script.index("read_env_value() {")
        end = script.index("\n}\n\nHERMES_API_KEY", start) + 2
        function_source = script[start:end]
        command = function_source + '\ntmp="$(mktemp)"; printf \'%s\\n\' "$TEST_ENV_LINE" > "$tmp"; read_env_value "$tmp" HERMES_API_KEY; rm -f "$tmp"'
        for line, expected in (
            ("HERMES_API_KEY='single-quoted'", "single-quoted"),
            ('HERMES_API_KEY="double-quoted"', "double-quoted"),
            ("HERMES_API_KEY=plain-value", "plain-value"),
        ):
            environment = os.environ.copy()
            environment["TEST_ENV_LINE"] = line
            result = subprocess.run([bash, "-c", command], env=environment, text=True, capture_output=True, check=True)
            self.assertEqual(expected, result.stdout)

    def test_compiled_transaction_rejects_invalid_source_without_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            first = Path(temporary) / "first.py"
            second = Path(temporary) / "second.py"
            first.write_text("VALUE = 1\n", encoding="utf-8")
            second.write_text("VALUE = 2\n", encoding="utf-8")
            with self.assertRaises(Exception):
                self.patcher._write_compiled_transaction(
                    [(first, "VALUE = 10\n"), (second, "def broken(:\n")]
                )
            self.assertEqual("VALUE = 1\n", first.read_text(encoding="utf-8"))
            self.assertEqual("VALUE = 2\n", second.read_text(encoding="utf-8"))

    def test_compiled_transaction_rolls_back_after_partial_replace(self):
        with tempfile.TemporaryDirectory() as temporary:
            first = Path(temporary) / "first.py"
            second = Path(temporary) / "second.py"
            first.write_text("VALUE = 1\n", encoding="utf-8")
            second.write_text("VALUE = 2\n", encoding="utf-8")
            real_replace = self.patcher.os.replace
            failed = False

            def fail_second_prepared(source, destination):
                nonlocal failed
                source_path = Path(source)
                destination_path = Path(destination)
                if destination_path == second and "hermes-native" in source_path.name and not failed:
                    failed = True
                    raise OSError("injected replace failure")
                return real_replace(source, destination)

            with mock.patch.object(self.patcher.os, "replace", side_effect=fail_second_prepared):
                with self.assertRaises(OSError):
                    self.patcher._write_compiled_transaction(
                        [(first, "VALUE = 10\n"), (second, "VALUE = 20\n")]
                    )
            self.assertEqual("VALUE = 1\n", first.read_text(encoding="utf-8"))
            self.assertEqual("VALUE = 2\n", second.read_text(encoding="utf-8"))

    def test_runtime_hardening_compiles_and_is_idempotent(self):
        source = textwrap.dedent(
            '''
            import asyncio
            import hmac
            import os
            import re
            import time
            from pathlib import Path
            from typing import Any, Dict, List, Optional

            def _hermes_hub_upload_root():
                return Path(".")

            def _hermes_hub_safe_upload_name(filename: str, mime_type: str) -> str:
                name = filename or "attachment"
                return name[:160]

            def _hermes_hub_save_upload(filename: str, mime_type: str, data_url: str) -> Dict[str, Any]:
                return {"filename": filename}

            def _hermes_hub_media_roots() -> List["Path"]:
                return [Path(".")]

            def _hermes_hub_resolve_media_path(media_id: str, extra_root: Optional[str] = None) -> Optional["Path"]:
                for root in _hermes_hub_media_roots():
                    for candidate in root.rglob(media_id):
                        return candidate
                return None

            def _hermes_hub_is_tailnet_peer(request):
                return False

            def _hermes_hub_media_cache_path(source: "Path") -> "Path":
                return Path("cache.mp4")

            def _hermes_hub_transcode_mp4(source: "Path") -> "Path":
                return source

            def _collect_hardware_snapshot():
                return {}

            def _hermes_hub_video_library_payload(request=None):
                extensions = {".mp4"}
                root = Path(".")
                for path in sorted(root.rglob("*"), key=lambda p: p.stat().st_mtime if p.exists() else 0, reverse=True):
                    _ = path
                return {}

            def _hermes_hub_news_library_payload(request=None):
                extensions = {".html"}
                root = Path(".")
                candidates = sorted(root.rglob("*"), key=lambda p: p.stat().st_mtime if p.exists() else 0, reverse=True)
                return {"items": list(candidates)}

            def _hermes_hub_read_json(path: "Path", default: Dict[str, Any]) -> Dict[str, Any]:
                return dict(default)

            def _hermes_hub_write_json(path: "Path", payload: Dict[str, Any]) -> None:
                path.write_text("{}", encoding="utf-8")

            _hermes_hub_conversation_event_subscribers = set()

            def _hermes_hub_conversations_payload():
                return {"items": []}

            def _hermes_hub_merge_conversations(items):
                return {"items": items, "merged": len(items)}

            def _hermes_hub_delete_conversation(conversation_id):
                return {"id": conversation_id}

            def _hermes_hub_extract_backup_conversations(body):
                return body.get("conversations", [])

            def _hermes_hub_conversation_event_payload(reason, result=None):
                return {"reason": reason}

            def _hermes_hub_publish_conversation_event(reason: str, result: Optional[Dict[str, Any]] = None) -> None:
                payload = _hermes_hub_conversation_event_payload(reason, result)
                for queue in list(_hermes_hub_conversation_event_subscribers):
                    queue.put_nowait(payload)

            def _hermes_hub_number(value, fallback=0.0):
                return fallback

            def _multimodal_validation_error(exc: ValueError, *, param: str) -> "web.Response":
                return None

            def _hermes_hub_kokoro_executor():
                return None

            def _hermes_hub_kokoro_speech_bytes(*args):
                return b""

            class Server:
                def _check_auth(self, request):
                    return None

                async def _handle_audio_transcriptions(self, request: "web.Request") -> "web.Response":
                    field = await request.multipart()
                    audio_data = await field.read()
                    return audio_data

                async def _handle_audio_speech(self, request: "web.Request") -> "web.Response":
                    body = await request.json()
                    text = str(body.get("input") or "")
                    voice, lang = "if_sara", "it"
                    try:
                        speed = float(body.get("speed") or 1.0)
                    except Exception:
                        speed = 1.0
                    try:
                        loop = asyncio.get_running_loop()
                        audio = await loop.run_in_executor(_hermes_hub_kokoro_executor(), _hermes_hub_kokoro_speech_bytes, text, voice, lang, speed)
                        return audio
                    except Exception:
                        return None

                async def _handle_hub_hardware(self, request: "web.Request") -> "web.Response":
                    return web.json_response(_collect_hardware_snapshot())

                async def _handle_get_hub_conversations(self, request: "web.Request") -> "web.Response":
                    return web.json_response(_hermes_hub_conversations_payload())

                async def _handle_get_hub_conversations_events(self, request: "web.Request") -> "web.StreamResponse":
                    queue = asyncio.Queue()
                    _hermes_hub_conversation_event_subscribers.add(queue)
                    response = web.StreamResponse()
                    await response.prepare(request)
                    return response

                async def _handle_put_hub_conversation(self, request: "web.Request") -> "web.Response":
                    body = await request.json()
                    return web.json_response(_hermes_hub_merge_conversations([body]))

                async def _handle_post_hub_conversations_import(self, request: "web.Request") -> "web.Response":
                    body = await request.json()
                    return web.json_response(_hermes_hub_merge_conversations(body.get("items", [])))

                async def _handle_delete_hub_conversation(self, request: "web.Request") -> "web.Response":
                    return web.json_response(_hermes_hub_delete_conversation("id"))

                async def _handle_video_library(self, request: "web.Request") -> "web.Response":
                    return web.json_response(_hermes_hub_video_library_payload(request))

                async def _handle_news_library(self, request: "web.Request") -> "web.Response":
                    return web.json_response(_hermes_hub_news_library_payload(request))

                async def _handle_hub_media(self, request: "web.Request") -> "web.StreamResponse":
                    path = _hermes_hub_resolve_media_path("x")
                    return web.FileResponse(path)

                async def _handle_hub_media_upload(self, request: "web.Request") -> "web.Response":
                    body = await request.json()
                    return web.json_response(_hermes_hub_save_upload("x", "x", body["data_url"]))

                async def _handle_models(self, request: "web.Request") -> "web.Response":
                    return web.json_response({})

            def _hermes_hub_preload_whisper():
                return None

            _hermes_hub_preload_whisper()
            '''
        )
        hardened, changes = self.patcher._harden_runtime(source)
        compile(hardened, "<hardened-gateway>", "exec")
        self.assertTrue(changes)
        self.assertIn("HERMES_HUB_RUNTIME_HARDENING_V1", hardened)
        self.assertIn("field.read_chunk", hardened)
        self.assertIn('field.name == "beam_size"', hardened)
        self.assertIn("_hermes_hub_transcribe_file, tmp_path, beam_size", hardened)
        self.assertIn("HERMES_HUB_STREAMING_TTS_V1", hardened)
        self.assertIn("application/vnd.hermes.framed-wav", hardened)
        self.assertIn('len(first_audio).to_bytes(4, "big")', hardened)
        self.assertIn('await response.write((0).to_bytes(4, "big"))', hardened)
        self.assertNotIn("audio_data = await field.read()", hardened)
        self.assertIn("run_in_executor(_hermes_hub_transcode_executor()", hardened)
        self.assertIn("asyncio.Queue(maxsize=1)", hardened)
        self.assertIn("_hermes_hub_publish_conversation_event_payload(event)", hardened)
        self.assertIn('path.with_suffix(path.suffix + ".corrupt")', hardened)
        self.assertIn("NamedTemporaryFile", hardened)
        self.assertIn("_hermes_hub_warmup_whisper", hardened)
        self.assertIn("list(segments)", hardened)
        self.assertIn("Required Whisper GPU preload failed", hardened)
        self.assertIn(".result(timeout=timeout)", hardened)
        self.assertNotIn("Whisper preload scheduled", hardened)
        hardened_again, second_changes = self.patcher._harden_runtime(hardened)
        self.assertEqual(hardened, hardened_again)
        self.assertEqual([], second_changes)

    def test_kokoro_mixed_language_segments_preserve_italian_and_switch_english_terms(self):
        source = UPSTREAM_GATEWAY_FIXTURE.read_text(encoding="utf-8")
        patched, _ = self.patcher._patch_text(source)
        self.assertIn("Required Kokoro GPU preload failed", patched)
        self.assertIn("Kokoro CUDA initialization failed and CPU fallback is disabled", patched)
        runtime_start = patched.index("# HERMES_HUB_KOKORO_GPU_V7")
        runtime_end = patched.index("\ndef _hermes_hub_preload_kokoro():", runtime_start)
        namespace: dict[str, object] = {}
        exec(patched[runtime_start:runtime_end], namespace)
        segment = namespace["_hermes_hub_tts_segments"]

        with mock.patch.dict(
            os.environ,
            {
                "HERMES_KOKORO_TTS_MIXED_LANGUAGE": "1",
                "HERMES_KOKORO_TTS_ENGLISH_VOICE": "af_bella",
            },
            clear=False,
        ):
            self.assertEqual(
                [
                    ("Apri ", "it", None),
                    ("YouTube", "en-us", "af_bella"),
                    (" e ", "it", None),
                    ("GPT-5.6", "en-us", "af_bella"),
                    (".", "it", None),
                ],
                segment("Apri YouTube e GPT-5.6.", "it"),
            )

        with mock.patch.dict(os.environ, {"HERMES_KOKORO_TTS_MIXED_LANGUAGE": "0"}, clear=False):
            self.assertEqual([("Apri YouTube.", "it", None)], segment("Apri YouTube.", "it"))

    def test_shell_scripts_parse_when_bash_is_available(self):
        bash = shutil.which("bash")
        if not bash and os.name == "nt":
            for candidate in (r"C:\Program Files\Git\bin\bash.exe", r"C:\Program Files\Git\usr\bin\bash.exe"):
                if Path(candidate).is_file():
                    bash = candidate
                    break
        if not bash:
            self.skipTest("bash unavailable")
        for name in (
            "hermes-hub-linux-update.sh",
            "hermes-hub-agent-update.sh",
            "hermes-hub-linux.sh",
            "hermes-power-monitor.sh",
            "rehub-patch.sh",
            "hermes-wait-llama.sh",
            "hermes-wait-tailscale.sh",
        ):
            subprocess.run([bash, "-n", str(SCRIPTS / name)], check=True)

    def _prepare_agent_updater_fixture(self, root: Path, bash: str, *, probe_payloads: list[str], fail_restart_on: int = 0) -> dict[str, object]:
        """Create disposable Git repo plus fake network/service edges."""
        home, agent, remote, bin_dir = (root / name for name in ("home", "agent", "remote.git", "bin"))
        home.mkdir()
        bin_dir.mkdir()
        subprocess.run(["git", "init", "--bare", str(remote)], check=True, capture_output=True)
        subprocess.run(["git", "init", "-b", "main", str(agent)], check=True, capture_output=True)
        gateway = agent / "gateway" / "platforms"
        gateway.mkdir(parents=True)
        shutil.copyfile(CURRENT_UPSTREAM_GATEWAY_FIXTURE, gateway / "api_server.py")
        subprocess.run(["git", "-C", str(agent), "add", "."], check=True)
        subprocess.run(["git", "-C", str(agent), "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-m", "base"], check=True, capture_output=True)
        subprocess.run(["git", "-C", str(agent), "remote", "add", "origin", str(remote)], check=True)
        subprocess.run(["git", "-C", str(agent), "push", "-u", "origin", "main"], check=True, capture_output=True)
        subprocess.run(["git", "-C", str(remote), "symbolic-ref", "HEAD", "refs/heads/main"], check=True)
        producer = root / "producer"
        subprocess.run(["git", "clone", "--branch", "main", str(remote), str(producer)], check=True, capture_output=True)
        (producer / "candidate.txt").write_text("candidate\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(producer), "add", "."], check=True)
        subprocess.run(["git", "-C", str(producer), "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-m", "candidate"], check=True, capture_output=True)
        subprocess.run(["git", "-C", str(producer), "push", "origin", "main"], check=True, capture_output=True)
        real_python = Path(sys.executable)
        python_wrapper = bin_dir / "python"
        python_wrapper.write_text(f'#!/usr/bin/env bash\nif [ "$1" = "-m" ] && [ "${{2:-}}" = "pip" ]; then exit 0; fi\nexec "{real_python}" "$@"\n', encoding="utf-8")
        python3_wrapper = bin_dir / "python3"
        python3_wrapper.write_text(f'#!/usr/bin/env bash\nif [ "$1" = "-m" ] && [ "${{2:-}}" = "pip" ]; then exit 0; fi\nexec "{real_python}" "$@"\n', encoding="utf-8")
        hermes = bin_dir / "hermes"
        hermes.write_text("#!/usr/bin/env bash\necho candidate\n", encoding="utf-8")
        curl = bin_dir / "curl"
        payload_file = root / "probe-payloads"
        payload_file.write_text("\n---PAYLOAD---\n".join(probe_payloads), encoding="utf-8")
        curl.write_text(textwrap.dedent("""\
            #!/usr/bin/env bash
            count_file="$FAKE_COUNTER_DIR/curl"
            count=0; [ -f "$count_file" ] && count=$(cat "$count_file")
            count=$((count + 1)); printf '%s' "$count" > "$count_file"
            python3 - "$FAKE_PROBE_PAYLOADS" "$count" <<'PY'
            import sys
            chunks = open(sys.argv[1], encoding="utf-8").read().split("\\n---PAYLOAD---\\n")
            print(chunks[min(int(sys.argv[2]) - 1, len(chunks) - 1)])
            PY
            """), encoding="utf-8")
        systemctl = bin_dir / "systemctl"
        systemctl.write_text(textwrap.dedent("""\
            #!/usr/bin/env bash
            count_file="$FAKE_COUNTER_DIR/systemctl"
            count=0; [ -f "$count_file" ] && count=$(cat "$count_file")
            count=$((count + 1)); printf '%s' "$count" > "$count_file"
            [ "$FAKE_FAIL_RESTART_ON" = "$count" ] && exit 1
            exit 0
            """), encoding="utf-8")
        for executable in (python_wrapper, python3_wrapper, hermes, curl, systemctl):
            executable.chmod(0o755)
        counter_dir = root / "counters"
        counter_dir.mkdir()
        bash_env = root / "agent-updater-env.sh"
        bash_env.write_text(textwrap.dedent("""\
            curl() {
              local count_file="$FAKE_COUNTER_DIR/curl" count=0
              [ -f "$count_file" ] && count=$(cat "$count_file")
              count=$((count + 1)); printf '%s' "$count" > "$count_file"
              python3 - "$FAKE_PROBE_PAYLOADS" "$count" <<'PY'
            import sys
            chunks = open(sys.argv[1], encoding="utf-8").read().split("\\n---PAYLOAD---\\n")
            print(chunks[min(int(sys.argv[2]) - 1, len(chunks) - 1)])
            PY
            }
            systemctl() {
              local count_file="$FAKE_COUNTER_DIR/systemctl" count=0
              [ -f "$count_file" ] && count=$(cat "$count_file")
              count=$((count + 1)); printf '%s' "$count" > "$count_file"
              [ "$FAKE_FAIL_RESTART_ON" = "$count" ] && return 1
              return 0
            }
            sleep() { return 0; }
            """), encoding="utf-8")
        environment = os.environ.copy()
        environment.update({
            "PATH": f"{bash_path(bash, bin_dir)}:{environment['PATH']}",
            "HERMES_HOME": bash_path(bash, home),
            "HERMES_HUB_AGENT_ROOT": bash_path(bash, agent),
            "HERMES_HUB_AGENT_VENV_BIN": bash_path(bash, bin_dir),
            "HERMES_HUB_AGENT_COMMAND": bash_path(bash, hermes),
            "HERMES_HUB_PATCHER": bash_path(bash, PATCHER_PATH),
            "HERMES_HUB_API_KEY": "test-key",
            "HERMES_HUB_UPDATE_PROBE_URL": "https://probe.invalid/v1/capabilities",
            "HERMES_HUB_AGENT_UPDATE_PROBE_ATTEMPTS": "1",
            "HERMES_HUB_AGENT_UPDATE_PROBE_SLEEP_SECONDS": "1",
            "FAKE_COUNTER_DIR": bash_path(bash, counter_dir),
            "FAKE_PROBE_PAYLOADS": bash_path(bash, payload_file),
            "FAKE_FAIL_RESTART_ON": str(fail_restart_on),
            "BASH_ENV": bash_path(bash, bash_env),
        })
        return {"agent": agent, "home": home, "environment": environment}

    def _run_agent_updater(self, bash: str, fixture: dict[str, object], mode: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [bash, bash_path(bash, SCRIPTS / "hermes-hub-agent-update.sh"), mode],
            env=fixture["environment"], text=True, capture_output=True,
        )

    def test_agent_updater_refuses_staged_and_untracked_before_fetch(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_agent_updater_fixture(Path(temporary), bash, probe_payloads=["{}"])
            agent = fixture["agent"]
            (agent / "staged.txt").write_text("do not reset\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(agent), "add", "staged.txt"], check=True)
            staged = self._run_agent_updater(bash, fixture, "--apply")
            self.assertEqual(2, staged.returncode)
            self.assertIn("staged", staged.stderr)
            self.assertTrue((agent / "staged.txt").exists())
            subprocess.run(["git", "-C", str(agent), "reset", "--", "staged.txt"], check=True)
            (agent / "untracked.txt").write_text("do not reset\n", encoding="utf-8")
            untracked = self._run_agent_updater(bash, fixture, "--apply")
            self.assertEqual(2, untracked.returncode)
            self.assertIn("untracked", untracked.stderr)
            self.assertTrue((agent / "untracked.txt").exists())

    def test_agent_updater_cleans_verified_patcher_backups_without_allowing_user_files(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        valid = json.dumps({"object": "hermes.api_server.capabilities", "platform": "hermes-agent", "auth": {"type": "bearer"}, "runtime": {"mode": "server_agent"}, "features": {"hermes_native": True, "native_responses": True}, "endpoints": {"responses": {"method": "POST", "path": "/v1/responses"}}, "version": "candidate"})
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_agent_updater_fixture(Path(temporary), bash, probe_payloads=[valid, valid])
            applied = self._run_agent_updater(bash, fixture, "--apply")
            self.assertEqual(0, applied.returncode, applied.stdout + applied.stderr)
            agent = fixture["agent"]
            self.assertEqual([], list(agent.glob("gateway/platforms/api_server.py.bak-hermes-native-*")))
            second_check = self._run_agent_updater(bash, fixture, "--check")
            self.assertEqual(0, second_check.returncode, second_check.stdout + second_check.stderr)
            user_file = agent / "user-owned.txt"
            user_file.write_text("must survive\n", encoding="utf-8")
            blocked = self._run_agent_updater(bash, fixture, "--check")
            self.assertEqual(2, blocked.returncode)
            self.assertIn("untracked", blocked.stderr)
            self.assertTrue(user_file.exists())

    def test_agent_updater_rejects_invalid_numeric_env_before_state_mutation(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_agent_updater_fixture(Path(temporary), bash, probe_payloads=["{}"])
            fixture["environment"]["HERMES_HUB_AGENT_UPDATE_PROBE_ATTEMPTS"] = "zero"
            result = self._run_agent_updater(bash, fixture, "--check")
            self.assertEqual(2, result.returncode)
            self.assertIn("HERMES_HUB_AGENT_UPDATE_PROBE_ATTEMPTS", result.stderr)
            self.assertFalse((fixture["home"] / "hub_gateway_runtime.json").exists())

    def test_agent_updater_healthy_state_clears_stale_failure(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        valid = json.dumps({"object": "hermes.api_server.capabilities", "platform": "hermes-agent", "auth": {"type": "bearer"}, "runtime": {"mode": "server_agent"}, "features": {"hermes_native": True, "native_responses": True}, "endpoints": {"responses": {"method": "POST", "path": "/v1/responses"}}, "version": "candidate"})
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_agent_updater_fixture(Path(temporary), bash, probe_payloads=["{}", valid])
            state = fixture["home"] / "hub_gateway_runtime.json"
            state.write_text(json.dumps({"failure": {"reason": "old"}, "status": "rolled_back"}), encoding="utf-8")
            failed = self._run_agent_updater(bash, fixture, "--check")
            self.assertNotEqual(0, failed.returncode)
            rejected = json.loads(state.read_text(encoding="utf-8"))
            self.assertNotEqual("healthy", rejected["status"])
            self.assertIn("failure", rejected)
            result = self._run_agent_updater(bash, fixture, "--check")
            self.assertEqual(0, result.returncode, result.stderr)
            current = json.loads(state.read_text(encoding="utf-8"))
            self.assertEqual("healthy", current["status"])
            self.assertNotIn("failure", current)

    def test_agent_updater_keeps_third_party_dependencies_immutable(self):
        updater = (SCRIPTS / "hermes-hub-agent-update.sh").read_text(encoding="utf-8")
        self.assertIn('pip install --disable-pip-version-check --no-deps -e "${AGENT_ROOT}[all]"', updater)
        self.assertGreaterEqual(updater.count('"$VENV_BIN/python" -m pip check'), 2)
        self.assertIn("operator intervention required", updater)

    def test_agent_updater_rejects_false_positive_probe_and_rolls_back(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        valid = json.dumps({"object": "hermes.api_server.capabilities", "platform": "hermes-agent", "auth": {"type": "bearer"}, "runtime": {"mode": "server_agent"}, "features": {"hermes_native": True, "native_responses": True}, "endpoints": {"responses": {"method": "POST", "path": "/v1/responses"}, "hermes_native": {"method": "POST", "path": "/v1/hermes/native"}}, "version": "candidate"})
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_agent_updater_fixture(Path(temporary), bash, probe_payloads=["{}", valid])
            result = self._run_agent_updater(bash, fixture, "--apply")
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            state = json.loads((fixture["home"] / "hub_gateway_runtime.json").read_text(encoding="utf-8"))
            self.assertEqual("rolled_back", state["status"], result.stdout + result.stderr + repr(state))
            self.assertIn("readiness probe", state["failure"]["reason"])

    def test_agent_updater_surfaces_rollback_failure(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._prepare_agent_updater_fixture(Path(temporary), bash, probe_payloads=["{}"], fail_restart_on=2)
            result = self._run_agent_updater(bash, fixture, "--apply")
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            state = json.loads((fixture["home"] / "hub_gateway_runtime.json").read_text(encoding="utf-8"))
            self.assertEqual("rollback_failed", state["status"])
            self.assertIn("restart during rollback", state["failure"]["reason"])


    def test_jarvis_stt_emits_single_final_transcript_without_partial_streaming(self):
        patched, _ = self.patcher._patch_text(UPSTREAM_GATEWAY_FIXTURE.read_text(encoding="utf-8"))
        transcribe_start = patched.index("def _hermes_hub_transcribe_file")
        transcribe_block = patched[
            transcribe_start:patched.index(
                "def _hermes_hub_cached_hardware_snapshot", transcribe_start
            )
        ]
        self.assertIn(
            'return "".join(segment.text for segment in segments).strip()', transcribe_block
        )
        self.assertNotIn("yield", transcribe_block)
        self.assertNotIn("partial", transcribe_block)
        self.assertNotIn("overlap", transcribe_block)
        handler_start = patched.index("async def _handle_audio_transcriptions")
        handler_block = patched[
            handler_start:patched.index("async def _handle_audio_speech", handler_start)
        ]
        self.assertEqual(
            1, handler_block.count('web.json_response({"text": result_text})')
        )
        self.assertNotIn("StreamResponse", handler_block)
        self.assertNotIn("partial", handler_block)
        self.assertNotIn("overlap", handler_block)
        self.assertNotIn("yield", handler_block)
        self.assertIn("max(1, min(10", handler_block)

    def test_streaming_tts_uses_framed_wav_segments_with_plain_wav_fallback(self):
        patched, _ = self.patcher._patch_text(UPSTREAM_GATEWAY_FIXTURE.read_text(encoding="utf-8"))
        self.assertIn("# HERMES_HUB_STREAMING_TTS_V1", patched)
        handler_start = patched.index("async def _handle_audio_speech")
        handler_block = patched[
            handler_start:patched.index("def _hermes_hub_preload_kokoro", handler_start)
        ]
        self.assertIn(
            '"Content-Type": "application/vnd.hermes.framed-wav"', handler_block
        )
        self.assertIn('"X-Hermes-Audio-Stream": "framed-wav-v1"', handler_block)
        self.assertIn('body.get("stream") is True', handler_block)
        self.assertIn(
            'return web.Response(body=first_audio, content_type="audio/wav")', handler_block
        )
        self.assertIn('len(first_audio).to_bytes(4, "big")', handler_block)
        self.assertIn('await response.write((0).to_bytes(4, "big"))', handler_block)
        self.assertIn("for chunk in chunks[1:]:", handler_block)


    def test_modular_openai_routes_add_native_events_idempotently(self):
        source = textwrap.dedent(
            '''
            class _ResponsesStream:
                async def emit_status(self, payload):
                    pass

                # queue tag -> (method name, payload adapter)
                _TAG_HANDLERS = {}

                async def dispatch(self, item):
                    if isinstance(item, tuple) and len(item) == 2 and isinstance(item[0], str):
                        tag, payload = item
                        await self.flush_batch()
                        handler = self._TAG_HANDLERS.get(tag)
                        if handler is not None:
                            method, adapt = handler
                            await getattr(self, method)(adapt(payload))

            class _OpenAIRoutes:
                async def write_chat(self, response, stream_q):
                    try:
                        async for delta in stream_q:
                            if delta is None:
                                break
                            elif isinstance(delta, tuple) and len(delta) == 2 and delta[0] == "__reasoning__":
                                pass
                    except Exception:
                        pass

                async def write_responses(self, st, stream_q):
                    try:
                        await st.emit_created()
                    except Exception:
                        pass

                async def _handle_chat_completions(self, request):
                    if stream:
                        def _on_tool_complete(tool_call_id, function_name, function_args, function_result):
                            pass

                        # tool_progress_callback deliberately NOT wired: it would duplicate the structured
                        # start/complete callbacks (which carry the tool_call id).
                        agent_task, agent_ref = self._spawn_stream_agent(
                            _stream_q, on_done=end_stream_run, tool_start_callback=_on_tool_start,
                            tool_complete_callback=_on_tool_complete, approval_notify_callback=approval_notify,
                            approval_session_key=completion_id, **run_kwargs)

                async def _handle_responses(self, request):
                    if stream:
                        def _on_tool_progress(event_type, name, preview, args, **kwargs):
                            return  # structured start/complete callbacks carry the call id; progress ignored

                        def _on_tool_start(tool_call_id, function_name, function_args):
                            _stream_q.put_threadsafe(("__tool_started__", {
                                "tool_call_id": tool_call_id, "name": function_name,
                                "arguments": function_args or {}}))

                        def _on_tool_complete(tool_call_id, function_name, function_args, function_result):
                            _stream_q.put_threadsafe(("__tool_completed__", {
                                "tool_call_id": tool_call_id, "name": function_name,
                                "arguments": function_args or {}, "result": function_result}))
            '''
        )

        patched, changes = self.patcher._patch_modular_openai_routes(source)
        compile(patched, "<modular-openai-routes>", "exec")
        self.assertIn('"type": "hermes.native.protocol"', patched)
        self.assertIn('tag == "__hermes_raw_event__"', patched)
        self.assertIn('delta[0] == "__hermes_raw_event__"', patched)
        self.assertIn('"event": "tool.started"', patched)
        self.assertIn('"event": "tool.completed"', patched)
        self.assertTrue(any("reasoning" in change for change in changes))

        second_pass, second_changes = self.patcher._patch_modular_openai_routes(patched)
        self.assertEqual(patched, second_pass)
        self.assertEqual([], second_changes)


class GatewayLauncherSelectionTests(unittest.TestCase):
    def _launcher_fixture(self, root: Path, bash: str):
        home = root / "home"
        hermes_home = home / ".hermes"
        default_bin = home / ".local" / "bin"
        spaced_bin = root / "custom bin with spaces"
        default_bin.mkdir(parents=True)
        spaced_bin.mkdir(parents=True)
        log = root / "hermes-calls.log"
        arguments = root / "hermes-arguments.txt"
        bash_env = root / "launcher-bash-env.sh"
        bash_env.write_text(
            'python3() { "$FAKE_REAL_PYTHON" "$@"; }\n',
            encoding="utf-8",
            newline="\n",
        )

        def write_hermes(path: Path):
            path.write_text(
                "#!/usr/bin/env bash\n"
                "printf '%s\\n' \"$*\" >> \"$HERMES_STUB_LOG\"\n"
                "if [ \"$#\" -eq 1 ] && [ \"$1\" = \"--version\" ]; then\n"
                "  printf 'Hermes Stub 9.9\\n'\n"
                "  exit 0\n"
                "fi\n"
                "printf '%s\\n' \"$@\" > \"$HERMES_STUB_ARGUMENTS\"\n",
                encoding="utf-8",
                newline="\n",
            )
            path.chmod(0o755)
            subprocess.run(
                [bash, "-c", 'chmod +x -- "$1"', "_", bash_path(bash, path)],
                check=True,
                capture_output=True,
            )

        default_hermes = default_bin / "hermes"
        custom_hermes = spaced_bin / "hermes cli"
        write_hermes(default_hermes)
        write_hermes(custom_hermes)

        environment = os.environ.copy()
        environment.update(
            {
                "HOME": bash_path(bash, home),
                "HERMES_HOME": bash_path(bash, hermes_home),
                "HERMES_TERMINAL_CWD": bash_path(bash, root),
                "HERMES_STUB_LOG": bash_path(bash, log),
                "HERMES_STUB_ARGUMENTS": bash_path(bash, arguments),
                "BASH_ENV": bash_path(bash, bash_env),
                "FAKE_REAL_PYTHON": bash_path(bash, Path(sys.executable)),
                "PATH": bash_path(bash, default_bin) + ":/usr/local/bin:/usr/bin:/bin",
                "API_SERVER_KEY": "launcher-test-key",
                "HERMES_INFERENCE_MODEL": "launcher-test-model",
                "HERMES_NATIVE_GATEWAY_PATCH": "false",
                "HERMES_WAIT_ON_START": "false",
                "HERMES_KOKORO_PRELOAD": "0",
                "HERMES_KOKORO_PRELOAD_REQUIRED": "0",
                "HERMES_KOKORO_REQUIRE_GPU": "0",
                "HERMES_WHISPER_PRELOAD": "0",
                "HERMES_WHISPER_PRELOAD_REQUIRED": "0",
                "HERMES_HUB_AGENT_ROOT": bash_path(bash, hermes_home / "hermes-agent"),
                "MSYS": "winsymlinks:sys",
            }
        )
        environment.pop("HERMES_HUB_GATEWAY_HERMES_BIN", None)
        environment.pop("HERMES_HUB_NATIVE_PATCHER", None)
        return {
            "home": home,
            "hermes_home": hermes_home,
            "default_hermes": default_hermes,
            "custom_hermes": custom_hermes,
            "log": log,
            "arguments": arguments,
            "environment": environment,
        }

    def _run_launcher(self, bash: str, fixture):
        return subprocess.run(
            [bash, bash_path(bash, SCRIPTS / "hermes-hub-linux.sh")],
            env=fixture["environment"],
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
        )

    def test_launcher_uses_default_hermes_binary_for_version_and_exec(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._launcher_fixture(Path(temporary), bash)
            result = self._run_launcher(bash, fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(
                ["--version", "gateway run --replace"],
                Path(fixture["log"]).read_text(encoding="utf-8").splitlines(),
            )
            self.assertEqual(
                ["gateway", "run", "--replace"],
                Path(fixture["arguments"]).read_text(encoding="utf-8").splitlines(),
            )
            runtime = json.loads((Path(fixture["hermes_home"]) / "hub_gateway_runtime.json").read_text(encoding="utf-8"))
            self.assertEqual("Hermes Stub 9.9", runtime["agent_version"])

    def test_launcher_uses_explicit_hermes_binary_without_word_splitting(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._launcher_fixture(Path(temporary), bash)
            fixture["environment"]["HERMES_HUB_GATEWAY_HERMES_BIN"] = bash_path(bash, fixture["custom_hermes"])
            result = self._run_launcher(bash, fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(
                ["--version", "gateway run --replace"],
                Path(fixture["log"]).read_text(encoding="utf-8").splitlines(),
            )
            self.assertTrue(fixture["default_hermes"].is_file())

    def test_launcher_rejects_invalid_explicit_hermes_binary_without_fallback(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._launcher_fixture(Path(temporary), bash)
            invalid = Path(temporary) / "not executable"
            invalid.write_text("not a binary", encoding="utf-8")
            invalid.chmod(0o644)
            subprocess.run(
                [bash, "-c", 'chmod 644 -- "$1"', "_", bash_path(bash, invalid)],
                check=True,
                capture_output=True,
            )
            fixture["environment"]["HERMES_HUB_GATEWAY_HERMES_BIN"] = bash_path(bash, invalid)
            result = self._run_launcher(bash, fixture)
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("HERMES_HUB_GATEWAY_HERMES_BIN", result.stderr)
            self.assertFalse(Path(fixture["log"]).exists())
            self.assertFalse(Path(fixture["hermes_home"]).exists())

    def test_launcher_accepts_native_patcher_override_as_one_regular_file(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._launcher_fixture(Path(temporary), bash)
            marker = Path(temporary) / "custom patcher invoked"
            patcher = Path(temporary) / "patcher dir" / "patcher.py"
            patcher.parent.mkdir()
            patcher.write_text(
                "from pathlib import Path\n"
                "import os\n"
                f"Path({str(marker)!r}).write_text(os.environ['HERMES_HUB_GATEWAY_PACKAGE'], encoding='utf-8')\n",
                encoding="utf-8",
            )
            fixture["environment"]["HERMES_NATIVE_GATEWAY_PATCH"] = "true"
            fixture["environment"]["HERMES_HUB_NATIVE_PATCHER"] = bash_path(bash, patcher)
            result = self._run_launcher(bash, fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(SCRIPTS.resolve(), Path(marker.read_text(encoding="utf-8")).resolve())

    def test_launcher_rejects_invalid_native_patcher_override_before_setup(self):
        bash = find_bash()
        if not bash:
            self.skipTest("bash unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self._launcher_fixture(Path(temporary), bash)
            invalid = Path(temporary) / "missing patcher.py"
            fixture["environment"]["HERMES_HUB_NATIVE_PATCHER"] = bash_path(bash, invalid)
            result = self._run_launcher(bash, fixture)
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("HERMES_HUB_NATIVE_PATCHER", result.stderr)
            self.assertFalse(Path(fixture["log"]).exists())
            self.assertFalse(Path(fixture["hermes_home"]).exists())


if __name__ == "__main__":
    unittest.main()
