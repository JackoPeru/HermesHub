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
UPSTREAM_GATEWAY_FIXTURE = ROOT / "tests" / "fixtures" / "hermes-agent-v2026.7.7.2-api_server.py"
UPDATER_REQUIRED_FILES = (
    "hermes-hub-linux.sh",
    "patch-hermes-gateway-native.py",
    "hermes-hub-linux-update.sh",
    "install-hermes-hub-linux.sh",
    "hermes-hub-linux.service",
    "hermes-hub-linux-update.service",
    "hermes-hub-linux-update.timer",
    "hermes-wait-tailscale.sh",
    "hermes-wait-llama.sh",
    "hermes-power-monitor.sh",
    "hermes-power-monitor.service",
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

    def _prepare_updater_fixture(
        self,
        root: Path,
        bash: str,
        *,
        probe_ok: bool,
        gateway_page: int = 1,
        asset_size_override: int | None = None,
    ) -> dict[str, object]:
        version = "9.8.7"
        home = root / "home"
        install_dir = root / "install"
        bin_dir = root / "bin"
        config_dir = root / "config"
        for directory in (home, install_dir, bin_dir, config_dir):
            directory.mkdir(parents=True, exist_ok=True)

        archive_path = root / f"HermesHub-{version}-linux-gateway.tar.gz"
        with tarfile.open(archive_path, "w:gz") as archive:
            for name in UPDATER_REQUIRED_FILES:
                archive.add(SCRIPTS / name, arcname=f"bundle/{name}")
            version_bytes = f"{version}\n".encode()
            version_info = tarfile.TarInfo("bundle/VERSION")
            version_info.size = len(version_bytes)
            version_info.mode = 0o644
            archive.addfile(version_info, io.BytesIO(version_bytes))

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
                                "digest": f"sha256:{hashlib.sha256(archive_path.read_bytes()).hexdigest()}",
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
        systemctl_log = root / "systemctl.log"
        bash_env = root / "bash-env.sh"
        curl_log.touch()
        systemctl_log.touch()
        bash_env.write_text(
            textwrap.dedent(
                r'''
                curl() {
                  local output="" url=""
                  while [ "$#" -gt 0 ]; do
                    case "$1" in
                      -o|--output)
                        shift
                        output="${1:-}"
                        ;;
                      http://*|https://*) url="$1" ;;
                    esac
                    shift
                  done
                  printf '%s\n' "$url" >> "$FAKE_CURL_LOG"
                  if [ -n "$output" ]; then
                    cp "$FAKE_ARCHIVE" "$output"
                  elif [[ "$url" == https://api.github.com/* ]]; then
                    if [ "$FAKE_GATEWAY_PAGE" = "2" ] && [[ "$url" == *"page=1"* ]]; then
                      cat "$FAKE_RELEASE_PAGE_ONE"
                    else
                      cat "$FAKE_RELEASE_JSON"
                    fi
                  elif [ "$url" = "$HERMES_HUB_UPDATE_PROBE_URL" ] && [ "$FAKE_PROBE_OK" = "1" ]; then
                    printf '{}\n'
                  else
                    return 22
                  fi
                }

                systemctl() {
                  printf '%s\n' "$*" >> "$FAKE_SYSTEMCTL_LOG"
                  case " $* " in
                    *" is-active "*|*" is-enabled "*) return 1 ;;
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
                "FAKE_ARCHIVE": bash_path(bash, archive_path),
                "FAKE_RELEASE_JSON": bash_path(bash, release_json),
                "FAKE_RELEASE_PAGE_ONE": bash_path(bash, first_page_json),
                "FAKE_GATEWAY_PAGE": str(gateway_page),
                "FAKE_CURL_LOG": bash_path(bash, curl_log),
                "FAKE_SYSTEMCTL_LOG": bash_path(bash, systemctl_log),
                "FAKE_REAL_PYTHON": bash_path(bash, Path(sys.executable)),
                "FAKE_PROBE_OK": "1" if probe_ok else "0",
                "MSYS": "winsymlinks:sys",
            }
        )
        environment.pop("GH_TOKEN", None)
        environment.pop("GITHUB_TOKEN", None)
        return {
            "version": version,
            "home": home,
            "install_dir": install_dir,
            "bin_dir": bin_dir,
            "service_dir": config_dir / "systemd" / "user",
            "curl_log": curl_log,
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
                self.assertIn('"""Pass through Hermes-native tool/progress metadata."""', patched)
                self.assertIn(self.patcher._HARDWARE_DISK_FILTER_MARKER, patched)
                self.assertIn('"squashfs"', patched)
                self.assertIn("device.startswith('/dev/loop')", patched)
            else:
                self.assertEqual([], changes)
        self.assertEqual(digests[0], digests[1])
        self.assertEqual(digests[1], digests[2])

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
            launcher_target = self._readlink(bash, Path(fixture["home"]) / "hermes-hub-linux.sh", environment)
            self.assertEqual(f"{current_target}/hermes-hub-linux.sh", launcher_target)
            self.assertIn("https://probe.invalid/v1/capabilities", Path(fixture["curl_log"]).read_text(encoding="utf-8"))
            systemctl_log = Path(fixture["systemctl_log"]).read_text(encoding="utf-8")
            self.assertIn("--user daemon-reload", systemctl_log)
            self.assertIn("--user restart hermes-hub.service", systemctl_log)
            self.assertIn(f"Installed: {version}", result.stdout)
            self.assertIn("Restarted and verified: hermes-hub.service", result.stdout)

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
            self.assertFalse(any(path.name.startswith(f'{fixture["version"]}-') for path in (install_dir / "releases").iterdir()))
            systemctl_log = Path(fixture["systemctl_log"]).read_text(encoding="utf-8")
            self.assertGreaterEqual(systemctl_log.count("--user restart hermes-hub.service"), 2)
            self.assertEqual(
                1,
                Path(fixture["curl_log"]).read_text(encoding="utf-8").splitlines().count(
                    "https://probe.invalid/v1/capabilities"
                ),
            )

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
        self.assertNotIn("audio_data = await field.read()", hardened)
        self.assertIn("run_in_executor(_hermes_hub_transcode_executor()", hardened)
        self.assertIn("asyncio.Queue(maxsize=1)", hardened)
        self.assertIn("_hermes_hub_publish_conversation_event_payload(event)", hardened)
        self.assertIn('path.with_suffix(path.suffix + ".corrupt")', hardened)
        self.assertIn("NamedTemporaryFile", hardened)
        hardened_again, second_changes = self.patcher._harden_runtime(hardened)
        self.assertEqual(hardened, hardened_again)
        self.assertEqual([], second_changes)

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
            "hermes-hub-linux.sh",
            "hermes-power-monitor.sh",
            "hermes-wait-llama.sh",
            "hermes-wait-tailscale.sh",
        ):
            subprocess.run([bash, "-n", str(SCRIPTS / name)], check=True)


if __name__ == "__main__":
    unittest.main()
