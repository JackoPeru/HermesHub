import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "hermes-hub-backup.py"
MANIFEST_FORMAT = "hermes-hub-sqlite-backup"


class HubBackupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.home = self.root / "hermes"
        self.home.mkdir()
        self.source = self.home / "hub_state.sqlite3"
        self.backups = self.home / "backups" / "hub"
        self.env = os.environ.copy()
        self.env.update(
            {
                "HERMES_HOME": str(self.home),
                "HERMES_HUB_BACKUP_DIR": str(self.backups),
                "HERMES_HUB_BACKUP_RETENTION": "7",
                "HERMES_HUB_BACKUP_DEADLINE_SECONDS": "5",
            }
        )
        self.create_database(self.source).close()

    @staticmethod
    def create_database(path: Path, *, rows=(), wal=False):
        path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(path)
        if wal:
            connection.execute("PRAGMA journal_mode=WAL")
        connection.execute(
            "CREATE TABLE IF NOT EXISTS records "
            "(id INTEGER PRIMARY KEY, value TEXT NOT NULL, deleted_at TEXT)"
        )
        connection.executemany(
            "INSERT OR REPLACE INTO records(id, value, deleted_at) VALUES (?, ?, ?)",
            rows,
        )
        connection.commit()
        return connection

    def run_cli(self, *arguments, env=None, expected=0):
        environment = self.env.copy()
        if env:
            environment.update({key: str(value) for key, value in env.items()})
        result = subprocess.run(
            [sys.executable, str(SCRIPT), *map(str, arguments)],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
        return result

    def create_snapshot(self, *arguments, env=None):
        self.run_cli(*arguments, env=env)
        snapshots = [
            path
            for path in self.backups.iterdir()
            if path.is_dir() and (path / "manifest.json").is_file()
        ]
        return max(snapshots, key=lambda path: path.name)

    @staticmethod
    def read_manifest(snapshot: Path):
        return json.loads((snapshot / "manifest.json").read_text(encoding="utf-8"))

    def snapshot_database_paths(self, snapshot: Path):
        manifest = self.read_manifest(snapshot)
        return [snapshot / entry["snapshot"] for entry in manifest["databases"]]

    def test_default_backup_uses_sqlite_backup_and_captures_uncheckpointed_wal(self):
        connection = self.create_database(self.source, wal=True)
        self.addCleanup(connection.close)
        connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        connection.execute("INSERT INTO records VALUES (7, 'wal-only-row', NULL)")
        connection.commit()
        wal_path = Path(str(self.source) + "-wal")
        self.assertTrue(wal_path.is_file())
        self.assertGreater(wal_path.stat().st_size, 0)
        source_digest = hashlib.sha256(self.source.read_bytes()).hexdigest()

        snapshot = self.create_snapshot()
        copied = self.snapshot_database_paths(snapshot)[0]
        with closing(sqlite3.connect(copied)) as backup:
            self.assertEqual([(7, "wal-only-row", None)], backup.execute("SELECT * FROM records").fetchall())
            self.assertEqual("ok", backup.execute("PRAGMA quick_check").fetchone()[0])
        self.assertEqual(source_digest, hashlib.sha256(self.source.read_bytes()).hexdigest())
        self.assertTrue(wal_path.is_file())

    def test_wal_snapshot_verification_and_restores_keep_exact_file_sets(self):
        connection = self.create_database(self.source, wal=True)
        self.addCleanup(connection.close)
        connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        connection.execute("INSERT INTO records VALUES (8, 'uncheckpointed', NULL)")
        connection.commit()

        wal_path = Path(str(self.source) + "-wal")
        self.assertTrue(wal_path.is_file())
        source_bytes = self.source.read_bytes()
        wal_bytes = wal_path.read_bytes()
        self.assertGreater(len(wal_bytes), 0)

        snapshot = self.create_snapshot()
        manifest = self.read_manifest(snapshot)
        snapshot_names = {entry["snapshot"] for entry in manifest["databases"]}
        expected_snapshot_files = {"manifest.json", *snapshot_names}

        def assert_snapshot_files():
            self.assertEqual(expected_snapshot_files, {path.name for path in snapshot.iterdir()})

        def assert_source_unchanged():
            self.assertEqual(source_bytes, self.source.read_bytes())
            self.assertEqual(wal_bytes, wal_path.read_bytes())
            self.assertEqual("wal", connection.execute("PRAGMA journal_mode").fetchone()[0].lower())

        assert_snapshot_files()
        for _ in range(2):
            self.run_cli("--verify", snapshot)
            assert_snapshot_files()

        for index in range(2):
            restore = self.root / f"restored-{index}"
            self.run_cli("--restore-from", snapshot, "--restore-to", restore)
            self.assertEqual(snapshot_names, {path.name for path in restore.iterdir()})
            assert_snapshot_files()
            restored_database = restore / next(iter(snapshot_names))
            with closing(sqlite3.connect(restored_database)) as restored:
                self.assertEqual(
                    [(8, "uncheckpointed", None)],
                    restored.execute("SELECT * FROM records").fetchall(),
                )
                self.assertEqual("delete", restored.execute("PRAGMA journal_mode").fetchone()[0].lower())
            self.assertEqual(snapshot_names, {path.name for path in restore.iterdir()})
            assert_source_unchanged()

        assert_source_unchanged()

    def test_manifest_records_utc_digest_size_and_no_row_contents(self):
        self.create_database(self.source, rows=[(1, "private-row-value", None)]).close()
        snapshot = self.create_snapshot()
        manifest_bytes = (snapshot / "manifest.json").read_bytes()
        manifest = self.read_manifest(snapshot)

        self.assertEqual(MANIFEST_FORMAT, manifest["format"])
        self.assertEqual(1, manifest["version"])
        created_at = datetime.fromisoformat(manifest["created_at"].replace("Z", "+00:00"))
        self.assertEqual(timezone.utc, created_at.tzinfo)
        self.assertEqual(1, len(manifest["databases"]))
        entry = manifest["databases"][0]
        self.assertEqual(
            {"snapshot", "source", "label", "size_bytes", "sha256"}, set(entry)
        )
        snapshot_file = snapshot / entry["snapshot"]
        self.assertEqual(snapshot, snapshot_file.parent)
        self.assertEqual(snapshot_file.stat().st_size, entry["size_bytes"])
        self.assertEqual(hashlib.sha256(snapshot_file.read_bytes()).hexdigest(), entry["sha256"])
        self.assertNotIn(b"private-row-value", manifest_bytes)
        self.assertTrue(self.run_cli("--verify", snapshot).stdout)

    def test_repeated_database_arguments_avoid_basename_collisions(self):
        first = self.root / "one" / "shared.sqlite3"
        second = self.root / "two" / "shared.sqlite3"
        self.create_database(first, rows=[(1, "first", None)]).close()
        self.create_database(second, rows=[(2, "second", None)]).close()

        snapshot = self.create_snapshot("--database", first, "--database", second)
        manifest = self.read_manifest(snapshot)
        self.assertEqual(3, len(manifest["databases"]))
        names = [entry["snapshot"] for entry in manifest["databases"]]
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(2, len([name for name in names if "shared.sqlite3" in name]))

    def test_include_agent_state_uses_only_fixed_one_level_allowlist(self):
        allowed = [
            self.home / "state.db",
            self.home / "response_store.db",
            self.home / "projects.db",
            self.home / "kanban.db",
            self.home / "shared-state.db",
            self.home / "runs_idempotency.db",
            self.home / "verification_evidence.db",
            self.home / "cron" / "executions.db",
            self.home / "cron" / "notepad.db",
            self.home / "profiles" / "alpha" / "state.db",
            self.home / "profiles" / "alpha" / "projects.db",
            self.home / "profiles" / "alpha" / "response_store.db",
            self.home / "profiles" / "alpha" / "kanban.db",
            self.home / "projects" / "project-a" / "state.db",
            self.home / "projects" / "project-a" / "projects.db",
            self.home / "projects" / "project-a" / "response_store.db",
            self.home / "projects" / "project-a" / "kanban.db",
        ]
        for index, path in enumerate(allowed, start=10):
            self.create_database(path, rows=[(index, str(path.name), None)]).close()
        disallowed = [
            self.home / "cron" / "nested" / "executions.db",
            self.home / "profiles" / "alpha" / "nested" / "state.db",
            self.home / "profiles" / "alpha" / "unlisted.db",
            self.home / "projects" / "project-a" / "nested" / "projects.db",
            self.home / "projects" / "project-a" / "config.yml",
        ]
        for path in disallowed:
            self.create_database(path).close()
        (self.home / "runs_idempotency.db").unlink()
        (self.home / "runs_idempotency.db").mkdir()

        snapshot = self.create_snapshot("--include-agent-state")
        labels = {entry["label"] for entry in self.read_manifest(snapshot)["databases"]}
        self.assertEqual(
            {
                "hub_state.sqlite3",
                "state.db",
                "response_store.db",
                "projects.db",
                "kanban.db",
                "shared-state.db",
                "verification_evidence.db",
                "cron/executions.db",
                "cron/notepad.db",
                "profiles/alpha/state.db",
                "profiles/alpha/projects.db",
                "profiles/alpha/response_store.db",
                "profiles/alpha/kanban.db",
                "projects/project-a/state.db",
                "projects/project-a/projects.db",
                "projects/project-a/response_store.db",
                "projects/project-a/kanban.db",
            },
            labels,
        )

    def test_restore_preserves_rows_and_tombstones(self):
        self.create_database(
            self.source,
            rows=[(1, "active", None), (2, "deleted", "2026-10-07T01:00:00Z")],
        ).close()
        snapshot = self.create_snapshot()
        destination = self.root / "restored"

        self.run_cli("--restore-from", snapshot, "--restore-to", destination)

        manifest = self.read_manifest(snapshot)
        restored = destination / manifest["databases"][0]["snapshot"]
        with closing(sqlite3.connect(restored)) as database:
            self.assertEqual(
                [(1, "active", None), (2, "deleted", "2026-10-07T01:00:00Z")],
                database.execute("SELECT * FROM records ORDER BY id").fetchall(),
            )
            self.assertEqual("ok", database.execute("PRAGMA integrity_check").fetchone()[0])

    def test_verify_and_restore_refuse_modified_snapshot_digest(self):
        snapshot = self.create_snapshot()
        snapshot_database = self.snapshot_database_paths(snapshot)[0]
        contents = bytearray(snapshot_database.read_bytes())
        contents[-1] ^= 1
        snapshot_database.write_bytes(contents)

        verify = self.run_cli("--verify", snapshot, expected=1)
        self.assertIn("SHA-256", verify.stderr)
        destination = self.root / "refused-restore"
        restore = self.run_cli("--restore-from", snapshot, "--restore-to", destination, expected=1)
        self.assertIn("SHA-256", restore.stderr)
        self.assertFalse(destination.exists())

    def test_failed_backup_keeps_previous_snapshot_and_source_unchanged(self):
        previous = self.create_snapshot()
        previous_digest = hashlib.sha256((previous / "manifest.json").read_bytes()).hexdigest()
        source_digest = hashlib.sha256(self.source.read_bytes()).hexdigest()
        invalid_source = self.root / "invalid.sqlite3"
        invalid_source.write_bytes(b"not a sqlite database")

        self.run_cli("--database", invalid_source, expected=1)

        self.assertTrue(previous.is_dir())
        self.assertEqual(previous_digest, hashlib.sha256((previous / "manifest.json").read_bytes()).hexdigest())
        self.assertEqual(source_digest, hashlib.sha256(self.source.read_bytes()).hexdigest())
        snapshots = [path for path in self.backups.iterdir() if path.is_dir() and (path / "manifest.json").is_file()]
        self.assertEqual([previous], snapshots)
        self.assertFalse(any(path.name.startswith(".tmp-") for path in self.backups.iterdir()))

    def test_retention_deletes_only_recognized_complete_snapshots(self):
        unrelated = self.backups / "pre-maintenance"
        unrelated.mkdir(parents=True)
        (unrelated / "keep.txt").write_text("keep", encoding="utf-8")
        invalid_old = self.backups / "20260101T000000Z-pre-maintenance"
        invalid_old.mkdir()
        (invalid_old / "manifest.json").write_text('{"format":"other"}', encoding="utf-8")

        for _ in range(4):
            self.create_snapshot(env={"HERMES_HUB_BACKUP_RETENTION": "2"})

        snapshots = [
            path
            for path in self.backups.iterdir()
            if path.is_dir()
            and (path / "manifest.json").is_file()
            and self.read_manifest(path).get("format") == MANIFEST_FORMAT
        ]
        self.assertEqual(2, len(snapshots))
        self.assertTrue((unrelated / "keep.txt").is_file())
        self.assertEqual('{"format":"other"}', (invalid_old / "manifest.json").read_text(encoding="utf-8"))

    def test_missing_symlink_and_nonregular_sources_are_rejected(self):
        missing = self.root / "missing.sqlite3"
        result = self.run_cli("--database", missing, expected=1)
        self.assertIn("source", result.stderr.lower())

        directory = self.root / "database-directory"
        directory.mkdir()
        result = self.run_cli("--database", directory, expected=1)
        self.assertIn("regular file", result.stderr.lower())

        symlink = self.root / "source-link.sqlite3"
        try:
            symlink.symlink_to(self.source)
        except (OSError, NotImplementedError) as error:
            self.skipTest(f"file symlink creation unavailable: {error}")
        result = self.run_cli("--database", symlink, expected=1)
        self.assertIn("symlink", result.stderr.lower())

    def test_restore_rejects_existing_destination(self):
        snapshot = self.create_snapshot()
        destination = self.root / "already-exists"
        destination.mkdir()

        result = self.run_cli("--restore-from", snapshot, "--restore-to", destination, expected=1)

        self.assertIn("exist", result.stderr.lower())
        self.assertEqual([], list(destination.iterdir()))

    def test_lock_contention_respects_bounded_deadline(self):
        self.backups.mkdir(parents=True)
        lock_path = self.backups / ".backup.lock"
        lock_file = lock_path.open("a+b")
        if os.name == "nt":
            import msvcrt

            lock_file.seek(0)
            lock_file.write(b"0")
            lock_file.flush()
            lock_file.seek(0)
            msvcrt.locking(lock_file.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl

            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.addCleanup(lock_file.close)

        started = time.monotonic()
        result = self.run_cli(
            env={
                "HERMES_HUB_BACKUP_DEADLINE_SECONDS": "0.4",
                "HERMES_HUB_BACKUP_LOCK_TIMEOUT_SECONDS": "0.2",
            },
            expected=1,
        )
        elapsed = time.monotonic() - started

        self.assertIn("lock", result.stderr.lower())
        self.assertLess(elapsed, 3)


if __name__ == "__main__":
    unittest.main()

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


class PullBackupTests(unittest.TestCase):
    SCRIPT = ROOT / "scripts" / "pull-hermes-hub-backup.ps1"
    SNAPSHOT_PATTERN = re.compile(r"^\d{8}T\d{12}Z-[0-9a-f]{8}$")

    @classmethod
    def setUpClass(cls):
        cls.powershell = find_powershell()
        cls.compiler = Path(r"C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe")
        if not cls.powershell or not cls.compiler.is_file():
            raise unittest.SkipTest("PowerShell or .NET Framework C# compiler unavailable")
        cls.fake_runtime = tempfile.TemporaryDirectory()
        root = Path(cls.fake_runtime.name)
        cls.fake_bin = root / "fake ssh clients"
        cls.fake_bin.mkdir()
        source = root / "fake_ssh.cs"
        source.write_text(
            r'''using System;
using System.IO;
using System.Threading;
class FakeOpenSsh {
  static int Main(string[] args) {
    string mode = Path.GetFileNameWithoutExtension(Environment.GetCommandLineArgs()[0]).ToLowerInvariant();
    string log = Environment.GetEnvironmentVariable("FAKE_OPENSSH_LOG");
    File.AppendAllText(log, mode + "|" + String.Join("|", args) + Environment.NewLine);
    string remoteRoot = Environment.GetEnvironmentVariable("FAKE_REMOTE_ROOT");
    if (mode == "ssh") {
      foreach (string directory in Directory.GetDirectories(remoteRoot)) Console.WriteLine(Path.GetFileName(directory));
      return 0;
    }
    string remote = args[args.Length - 2];
    string local = args[args.Length - 1];
    string[] parts = remote.Substring(remote.IndexOf(':') + 1).Split('/');
    string snapshot = parts[parts.Length - 2];
    string file = parts[parts.Length - 1];
    string delay = Environment.GetEnvironmentVariable("FAKE_SCP_SLEEP_MS");
    if (!String.IsNullOrEmpty(delay)) Thread.Sleep(Int32.Parse(delay));
    if (Environment.GetEnvironmentVariable("FAKE_SCP_FAIL_FILE") == file) return 6;
    string source = Path.Combine(remoteRoot, snapshot, file);
    File.Copy(source, local, true);
    if (Environment.GetEnvironmentVariable("FAKE_SCP_CORRUPT_FILE") == file) File.AppendAllText(local, "corrupt");
    return 0;
  }
}''',
            encoding="utf-8",
            newline="\n",
        )
        executable = root / "fake-client.exe"
        subprocess.run(
            [str(cls.compiler), "/nologo", "/target:exe", f"/out:{executable}", str(source)],
            check=True,
            capture_output=True,
            text=True,
        )
        for name in ("ssh.exe", "scp.exe"):
            shutil.copy2(executable, cls.fake_bin / name)

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "fake_runtime"):
            cls.fake_runtime.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.remote = self.root / "remote"
        self.remote.mkdir()
        self.local = self.root / "local backups"
        self.log = self.root / "openssh.log"
        self.env = os.environ.copy()
        self.env.update(
            {
                "PATH": str(self.fake_bin) + os.pathsep + os.environ.get("PATH", ""),
                "FAKE_REMOTE_ROOT": str(self.remote),
                "FAKE_OPENSSH_LOG": str(self.log),
            }
        )

    @staticmethod
    def make_snapshot(root: Path, name: str, payload: bytes = b"sqlite snapshot bytes"):
        if not PullBackupTests.SNAPSHOT_PATTERN.fullmatch(name):
            raise ValueError(name)
        snapshot = root / name
        snapshot.mkdir(parents=True)
        database_name = "db-001-state.db"
        (snapshot / database_name).write_bytes(payload)
        manifest = {
            "format": MANIFEST_FORMAT,
            "version": 1,
            "created_at": "2026-10-07T03:15:00Z",
            "databases": [
                {
                    "snapshot": database_name,
                    "source": "/home/operator/.hermes/state.db",
                    "label": "state.db",
                    "size_bytes": len(payload),
                    "sha256": hashlib.sha256(payload).hexdigest(),
                }
            ],
        }
        (snapshot / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return snapshot

    def run_pull(self, *arguments, expected=0, env=None, script=None):
        environment = self.env.copy()
        if env:
            environment.update({key: str(value) for key, value in env.items()})
        result = subprocess.run(
            [
                self.powershell,
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(script or self.SCRIPT),
                *map(str, arguments),
            ],
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
        return result

    def test_pull_selects_latest_valid_remote_snapshot_without_ssh(self):
        older = "20261007T031500000000Z-00000001"
        latest = "20261007T031600000000Z-00000002"
        self.make_snapshot(self.remote, older)
        latest_snapshot = self.make_snapshot(self.remote, latest, b"newest database")
        (self.remote / "pre-maintenance").mkdir()

        result = self.run_pull("-SshHost", "fake-host", "-LocalRoot", self.local)

        self.assertIn(latest, result.stdout)
        self.assertEqual(b"newest database", (self.local / latest / "db-001-state.db").read_bytes())
        log = self.log.read_text(encoding="utf-8")
        self.assertIn("ssh|", log)
        self.assertIn("scp|", log)
        self.assertTrue(latest_snapshot.is_dir())

    def test_pull_verifies_existing_snapshot_idempotently_without_transfer(self):
        name = "20261007T031500000000Z-00000001"
        remote = self.make_snapshot(self.remote, name)
        local = self.make_snapshot(self.local, name)

        result = self.run_pull("-SshHost", "fake-host", "-Snapshot", name, "-LocalRoot", self.local)

        self.assertIn("already verified", result.stdout.lower())
        calls = self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []
        self.assertFalse(any(line.startswith("scp|") for line in calls))
        self.assertEqual((remote / "manifest.json").read_bytes(), (local / "manifest.json").read_bytes())

        (local / "db-001-state.db").write_bytes(b"corrupted")
        self.run_pull("-SshHost", "fake-host", "-Snapshot", name, "-LocalRoot", self.local, expected=1)
        self.assertFalse(any(path.name.endswith(".partial") for path in self.local.iterdir()))

    def test_pull_failure_cleans_partial_directory_and_preserves_existing_data(self):
        name = "20261007T031500000000Z-00000001"
        self.make_snapshot(self.remote, name)

        self.run_pull(
            "-SshHost",
            "fake-host",
            "-Snapshot",
            name,
            "-LocalRoot",
            self.local,
            expected=1,
            env={"FAKE_SCP_FAIL_FILE": "db-001-state.db"},
        )

        self.assertFalse((self.local / name).exists())
        self.assertEqual([], list(self.local.iterdir()))

    def test_pull_aborts_if_inherited_whatif_prevents_private_acl(self):
        name = "20261007T031500000000Z-00000001"
        self.make_snapshot(self.remote, name)
        self.local.mkdir()

        def ps_literal(value):
            return "'" + str(value).replace("'", "''") + "'"

        wrapper = self.root / "whatif-wrapper.ps1"
        wrapper.write_text(
            f"$WhatIfPreference = $true\n"
            f". {ps_literal(self.SCRIPT)} -SshHost 'fake-host' "
            f"-Snapshot {ps_literal(name)} -LocalRoot {ps_literal(self.local)}\n",
            encoding="utf-8-sig",
        )

        result = self.run_pull(expected=1, script=wrapper)

        self.assertIn("private acl application was declined", result.stdout.lower() + result.stderr.lower())
        self.assertFalse((self.local / name).exists())
        self.assertEqual([], list(self.local.iterdir()))
        calls = self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []
        self.assertFalse(any(line.startswith("scp|") for line in calls))

    def test_pull_retains_seven_valid_tool_snapshots_and_unrelated_directories(self):
        self.local.mkdir()
        for index in range(8):
            name = f"20261006T031500000000Z-{index:08x}"
            self.make_snapshot(self.local, name)
        unrelated = self.local / "pre-maintenance"
        unrelated.mkdir()
        (unrelated / "keep.txt").write_text("keep", encoding="utf-8")
        invalid = self.local / "20261005T031500000000Z-00000001"
        invalid.mkdir()
        (invalid / "manifest.json").write_text('{"format":"other"}', encoding="utf-8")
        latest = "20261007T031500000000Z-00000001"
        self.make_snapshot(self.remote, latest)

        self.run_pull("-SshHost", "fake-host", "-Snapshot", latest, "-LocalRoot", self.local)

        valid = [
            path
            for path in self.local.iterdir()
            if self.SNAPSHOT_PATTERN.fullmatch(path.name)
            and (path / "manifest.json").is_file()
            and self.read_pull_manifest(path).get("format") == MANIFEST_FORMAT
        ]
        self.assertEqual(7, len(valid))
        self.assertTrue((self.local / latest).is_dir())
        self.assertEqual("keep", (unrelated / "keep.txt").read_text(encoding="utf-8"))
        self.assertTrue(invalid.exists())

    def test_pull_retention_refuses_external_candidate_before_recursive_delete(self):
        local_root = self.root / "local backups"
        local_root.mkdir()
        outside_root = self.root / "outside root"
        outside_root.mkdir()
        sentinel = outside_root / "keep.txt"
        sentinel.write_text("do not delete", encoding="utf-8")

        source = self.SCRIPT.read_text(encoding="utf-8")
        match = re.search(
            r"(?ms)^function Invoke-LocalRetention\(\[string\]\$KeepSnapshot, \[int\]\$RetentionLimit\) \{\n.*?^\}",
            source,
        )
        self.assertIsNotNone(match, "production retention function not found")
        keep_name = "20261007T031500000000Z-00000001"
        outside_name = "20261007T031600000000Z-00000002"

        def ps_literal(value):
            return "'" + str(value).replace("'", "''") + "'"

        harness = f"""
$script:localRoot = {ps_literal(local_root)}
$script:SnapshotPattern = [regex]'^\\d{{8}}T\\d{{12}}Z-[0-9a-f]{{8}}$'
$script:RemovedTargets = @()
$script:FakeCandidates = @(
    [PSCustomObject]@{{ Name = {ps_literal(keep_name)}; FullName = {ps_literal(local_root / keep_name)}; Attributes = [System.IO.FileAttributes]::Directory; PSIsContainer = $true }},
    [PSCustomObject]@{{ Name = {ps_literal(outside_name)}; FullName = {ps_literal(outside_root)}; Attributes = [System.IO.FileAttributes]::Directory; PSIsContainer = $true }}
)
function Get-ChildItem {{
    param([string]$LiteralPath, [switch]$Directory, [switch]$Force)
    if ([string]::Equals($LiteralPath, $script:localRoot, [System.StringComparison]::OrdinalIgnoreCase)) {{
        return $script:FakeCandidates
    }}
}}
function Test-VerifiedSnapshot {{ param([string]$Directory) return $true }}
function Remove-Item {{
    param([string]$LiteralPath, [switch]$Recurse, [switch]$Force)
    $script:RemovedTargets += $LiteralPath
}}
{match.group(0)}
$caught = $null
try {{ Invoke-LocalRetention -KeepSnapshot {ps_literal(keep_name)} -RetentionLimit 1 }} catch {{ $caught = $_ }}
if (-not $caught) {{ throw 'Expected retention to reject an outside-root candidate.' }}
if ($script:RemovedTargets.Count -ne 0) {{ throw 'Remove-Item was called for an unsafe candidate.' }}
if (-not (Test-Path -LiteralPath {ps_literal(sentinel)})) {{ throw 'Outside-root sentinel was deleted.' }}
"""
        harness_path = self.root / "retention-safety-test.ps1"
        harness_path.write_text(harness, encoding="utf-8-sig", newline="\n")
        result = subprocess.run(
            [
                self.powershell,
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(harness_path),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(sentinel.is_file())

    @staticmethod
    def read_pull_manifest(snapshot: Path):
        return json.loads((snapshot / "manifest.json").read_text(encoding="utf-8"))

    def test_pull_rejects_unsafe_host_alias_before_invoking_ssh(self):
        self.run_pull("-SshHost", "-fake-host", "-Snapshot", "20261007T031500000000Z-00000001", "-LocalRoot", self.local, expected=1)
        self.assertFalse(self.log.exists())

    def test_pull_transfer_deadline_kills_slow_client_and_cleans_partial(self):
        name = "20261007T031500000000Z-00000001"
        self.make_snapshot(self.remote, name)
        script_text = self.SCRIPT.read_text(encoding="utf-8")
        self.assertIn("$script:MaxTransferMilliseconds = 15 * 60 * 1000", script_text)
        shortened = script_text.replace(
            "$script:MaxTransferMilliseconds = 15 * 60 * 1000",
            "$script:MaxTransferMilliseconds = 1000",
            1,
        )
        temporary_script = self.root / "short-timeout.ps1"
        temporary_script.write_text(shortened, encoding="utf-8")

        result = self.run_pull(
            "-SshHost",
            "fake-host",
            "-Snapshot",
            name,
            "-LocalRoot",
            self.local,
            expected=1,
            env={"FAKE_SCP_SLEEP_MS": "5000"},
            script=temporary_script,
        )

        self.assertIn("timed out", result.stdout.lower() + result.stderr.lower())
        self.assertFalse((self.local / name).exists())
        self.assertEqual([], list(self.local.iterdir()))

    def test_pull_refuses_bad_hash_and_cleans_partial(self):
        name = "20261007T031500000000Z-00000001"
        self.make_snapshot(self.remote, name)

        self.run_pull(
            "-SshHost",
            "fake-host",
            "-Snapshot",
            name,
            "-LocalRoot",
            self.local,
            expected=1,
            env={"FAKE_SCP_CORRUPT_FILE": "db-001-state.db"},
        )

        self.assertFalse((self.local / name).exists())
        self.assertEqual([], list(self.local.iterdir()))
