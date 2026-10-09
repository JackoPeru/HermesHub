import importlib.util
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PATCHER_PATH = ROOT / "scripts" / "hermes_hub_gateway" / "adapters" / "hermes" / "legacy_patcher.py"
UPSTREAM_FIXTURE = ROOT / "tests" / "fixtures" / "hermes-agent-v2026.7.7.2-api_server.py"


def load_patcher():
    spec = importlib.util.spec_from_file_location("runtime_package_test_patcher", PATCHER_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def extract_runtime_store(source):
    start = source.index("def _hermes_hub_gateway_runtime_store():")
    end = source.index("def _hermes_hub_sqlite_source(", start)
    return source[start:end]


_FIXED_SEARCH = '''    selected_candidate = None
    for root in roots:
        for candidate in (root, root / "scripts"):
            if (candidate / "hermes_hub_gateway").is_dir():
                selected_candidate = candidate
                break
        if selected_candidate is not None:
            break

    if selected_candidate is not None:
        selected_path = str(selected_candidate)
        if selected_path in _sys.path:
            _sys.path.remove(selected_path)
        _sys.path.insert(0, selected_path)

    if selected_candidate is None and _importlib_util.find_spec("hermes_hub_gateway") is None:'''
_OLD_SEARCH = '''    package_found = False
    for root in roots:
        candidates = [root, root / "scripts"]
        for candidate in candidates:
            if (candidate / "hermes_hub_gateway").is_dir():
                package_found = True
                if str(candidate) not in _sys.path:
                    _sys.path.insert(0, str(candidate))

    if not package_found and _importlib_util.find_spec("hermes_hub_gateway") is None:'''

_SUBPROCESS_DRIVER = textwrap.dedent(
    """
    import importlib.util
    import os
    import sys
    from pathlib import Path

    root = Path(sys.argv[1])
    helper_path = Path(sys.argv[2])
    configured = Path(sys.argv[3])
    seeded_paths = [str(Path(value)) for value in sys.argv[4:]]
    current = root / ".local" / "share" / "hermes-hub-gateway" / "current"
    file_root = root / "runtime"
    Path.home = classmethod(lambda cls: root)
    os.environ["HERMES_HUB_GATEWAY_PACKAGE"] = str(configured)
    importlib.util.find_spec = lambda name: None
    sys.path[:] = [entry for entry in sys.path if entry not in {str(current), str(configured)}]
    sys.path[:0] = seeded_paths

    namespace = {"__file__": str(file_root / "api_server.py"), "os": os}
    exec(compile(helper_path.read_text(encoding="utf-8"), "<generated-runtime>", "exec"), namespace)
    runtime = namespace["_hermes_hub_gateway_runtime_store"]()
    print(getattr(runtime, "origin", "none"))
    print(sys.path[0] if sys.path else "")
    """
)


class RuntimePackageSelectionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.patcher = load_patcher()
        upstream = UPSTREAM_FIXTURE.read_text(encoding="utf-8")
        cls.generated_source, _ = cls.patcher._patch_text(upstream)
        cls.runtime_store = extract_runtime_store(cls.generated_source)

    def write_package(self, root, origin):
        package = root / "hermes_hub_gateway"
        infrastructure = package / "infrastructure"
        infrastructure.mkdir(parents=True)
        (package / "__init__.py").write_text("", encoding="utf-8")
        (infrastructure / "__init__.py").write_text("", encoding="utf-8")
        (infrastructure / "runtime_store.py").write_text(
            textwrap.dedent(
                f"""
                class HubRuntimeStore:
                    @classmethod
                    def from_environment(cls):
                        return type("Runtime", (), {{"origin": {origin!r}}})()
                """
            ),
            encoding="utf-8",
        )

    def run_generated_helper(self, root, configured, seeded_paths=()):
        runtime_dir = root / "runtime"
        runtime_dir.mkdir(parents=True, exist_ok=True)
        helper_path = runtime_dir / "helper.py"
        helper_path.write_text(self.runtime_store, encoding="utf-8")
        return subprocess.run(
            [
                sys.executable,
                "-c",
                _SUBPROCESS_DRIVER,
                str(root),
                str(helper_path),
                str(configured),
                *(str(path) for path in seeded_paths),
            ],
            cwd=root,
            capture_output=True,
            check=True,
            text=True,
        ).stdout.splitlines()

    def test_generated_helper_prefers_configured_and_moves_it_to_sys_path_front(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            configured = root / "configured"
            current = root / ".local" / "share" / "hermes-hub-gateway" / "current"
            file_root = root / "runtime"
            self.write_package(configured, "configured")
            self.write_package(current, "current")
            self.write_package(file_root, "file")

            output = self.run_generated_helper(root, configured, (current, configured))

        self.assertEqual("configured", output[0])
        self.assertEqual(str(configured), output[1])

    def test_current_package_precedes_file_package_when_configured_package_is_missing(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            configured = root / "missing-package"
            current = root / ".local" / "share" / "hermes-hub-gateway" / "current"
            file_root = root / "runtime"
            self.write_package(current, "current")
            self.write_package(file_root, "file")

            output = self.run_generated_helper(root, configured, (file_root, current))

        self.assertEqual("current", output[0])
        self.assertEqual(str(current), output[1])

    def test_existing_v1_helper_is_upgraded_and_second_pass_is_byte_identical(self):
        self.assertIn(_FIXED_SEARCH, self.runtime_store)
        old_helper = self.runtime_store.replace(_FIXED_SEARCH, _OLD_SEARCH, 1)
        self.assertIn("package_found = False", old_helper)
        old_v1_source = self.generated_source.replace(self.runtime_store, old_helper, 1)

        upgraded, changes = self.patcher._patch_sqlite_sync_v1(old_v1_source)
        self.assertIn("SQLite runtime package precedence", changes)
        self.assertEqual(self.runtime_store, extract_runtime_store(upgraded))

        second_pass, second_changes = self.patcher._patch_sqlite_sync_v1(upgraded)
        self.assertEqual(upgraded, second_pass)
        self.assertEqual([], second_changes)

    def test_generated_helper_keeps_legacy_fallback_when_no_package_exists(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = self.run_generated_helper(root, root / "missing-package")

        self.assertEqual("none", output[0])


if __name__ == "__main__":
    unittest.main()
