#!/usr/bin/env python3
"""Create, verify, and explicitly restore offline Hermes Hub SQLite backups."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import sqlite3
import stat
import sys
import tempfile
import time
import uuid
from contextlib import closing
from pathlib import Path, PurePosixPath


FORMAT = "hermes-hub-sqlite-backup"
VERSION = 1
DEFAULT_DEADLINE = 120.0
MAX_DEADLINE = 120.0
DEFAULT_LOCK_TIMEOUT = 30.0
MAX_LOCK_TIMEOUT = 120.0
MAX_RETENTION = 365
SNAPSHOT_NAME = re.compile(r"^\d{8}T\d{12}Z-[0-9a-f]{8}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


class BackupError(Exception):
    """An expected backup, verification, or restore failure."""


def _positive_float(value: str, label: str, maximum: float) -> float:
    try:
        result = float(value)
    except ValueError as error:
        raise BackupError(f"{label} must be a positive number") from error
    if not 0 < result <= maximum:
        raise BackupError(f"{label} must be greater than 0 and at most {maximum:g}")
    return result


def _retention(value: str | int) -> int:
    try:
        result = int(value)
    except (TypeError, ValueError) as error:
        raise BackupError("retention must be a positive integer") from error
    if not 1 <= result <= MAX_RETENTION:
        raise BackupError(f"retention must be between 1 and {MAX_RETENTION}")
    return result


def _deadline() -> float:
    value = _positive_float(
        os.environ.get("HERMES_HUB_BACKUP_DEADLINE_SECONDS", str(DEFAULT_DEADLINE)),
        "backup deadline",
        MAX_DEADLINE,
    )
    return time.monotonic() + value


def _check_deadline(deadline: float) -> None:
    if time.monotonic() >= deadline:
        raise BackupError("backup deadline exceeded")


def _fsync_file(path: Path) -> None:
    with path.open("rb+") as source:
        os.fsync(source.fileno())


def _fsync_directory(path: Path) -> None:
    if os.name == "nt":
        return
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _hash_file(path: Path, deadline: float | None = None) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            if deadline is not None:
                _check_deadline(deadline)
            digest.update(chunk)
    return digest.hexdigest()


def _quote_identifier(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


def _inspect_database(path: Path, deadline: float) -> tuple[list[tuple[str, str, str, str | None]], dict[str, int]]:
    _check_deadline(deadline)
    uri = path.resolve(strict=True).as_uri() + "?mode=ro"
    with closing(sqlite3.connect(uri, uri=True, timeout=5.0)) as connection:
        for pragma in ("quick_check", "integrity_check"):
            results = connection.execute(f"PRAGMA {pragma}").fetchall()
            if not results or any(row[0] != "ok" for row in results):
                raise BackupError(f"SQLite {pragma} failed for {path}")
        schema = connection.execute(
            "SELECT type, name, tbl_name, sql FROM sqlite_master "
            "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name"
        ).fetchall()
        rows = {}
        for kind, name, _table, _sql in schema:
            if kind == "table":
                _check_deadline(deadline)
                rows[name] = connection.execute(
                    f"SELECT count(*) FROM {_quote_identifier(name)}"
                ).fetchone()[0]
        return schema, rows


def _validate_source(path: Path) -> Path:
    try:
        info = path.lstat()
    except OSError as error:
        raise BackupError(f"database source is unavailable: {path}") from error
    if stat.S_ISLNK(info.st_mode):
        raise BackupError(f"database source must not be a symlink: {path}")
    if not stat.S_ISREG(info.st_mode):
        raise BackupError(f"database source must be a regular file: {path}")
    return path.resolve(strict=True)


def _source_label(path: Path, home: Path) -> str:
    try:
        return path.resolve(strict=False).relative_to(home.resolve(strict=False)).as_posix()
    except ValueError:
        return path.name


def _sources(arguments: argparse.Namespace, home: Path) -> list[tuple[Path, str]]:
    hub = Path(os.environ.get("HERMES_HUB_SQLITE_PATH", str(home / "hub_state.sqlite3"))).expanduser()
    sources = [(hub, _source_label(hub, home))]
    sources.extend((Path(value).expanduser(), _source_label(Path(value).expanduser(), home)) for value in arguments.database)
    if arguments.include_agent_state:
        def add_known(relative: str) -> None:
            path = home / relative
            try:
                info = path.lstat()
            except OSError:
                return
            if stat.S_ISREG(info.st_mode) and not stat.S_ISLNK(info.st_mode):
                sources.append((path, relative))

        root_names = (
            "state.db",
            "response_store.db",
            "projects.db",
            "kanban.db",
            "shared-state.db",
            "runs_idempotency.db",
            "verification_evidence.db",
        )
        for name in root_names:
            add_known(name)
        for name in ("executions.db", "notepad.db"):
            add_known(f"cron/{name}")
        for group in ("profiles", "projects"):
            parent = home / group
            if not parent.is_dir() or parent.is_symlink():
                continue
            for directory in sorted(parent.iterdir()):
                if directory.is_dir() and not directory.is_symlink():
                    for name in ("state.db", "projects.db", "response_store.db", "kanban.db"):
                        add_known((directory / name).relative_to(home).as_posix())
    return sources


def _lock(path: Path, deadline: float):
    timeout = _positive_float(
        os.environ.get("HERMES_HUB_BACKUP_LOCK_TIMEOUT_SECONDS", str(DEFAULT_LOCK_TIMEOUT)),
        "lock timeout",
        MAX_LOCK_TIMEOUT,
    )
    lock_deadline = min(deadline, time.monotonic() + timeout)
    flags = os.O_CREAT | os.O_RDWR | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags, 0o600)
    os.chmod(path, 0o600)
    info = os.fstat(descriptor)
    if not stat.S_ISREG(info.st_mode):
        os.close(descriptor)
        raise BackupError("backup lock must be a regular file")
    try:
        if os.name == "nt":
            import msvcrt

            if info.st_size == 0:
                os.write(descriptor, b"\0")
            while True:
                os.lseek(descriptor, 0, os.SEEK_SET)
                try:
                    msvcrt.locking(descriptor, msvcrt.LK_NBLCK, 1)
                    return descriptor, ("windows", msvcrt)
                except OSError:
                    if time.monotonic() >= lock_deadline:
                        raise BackupError("timed out waiting for backup lock")
                    _check_deadline(deadline)
                    time.sleep(min(0.05, max(0.0, lock_deadline - time.monotonic())))
        else:
            import fcntl

            while True:
                try:
                    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    return descriptor, ("posix", fcntl)
                except BlockingIOError:
                    if time.monotonic() >= lock_deadline:
                        raise BackupError("timed out waiting for backup lock")
                    _check_deadline(deadline)
                    time.sleep(min(0.05, max(0.0, lock_deadline - time.monotonic())))
    except BaseException:
        os.close(descriptor)
        raise


def _unlock(lock: tuple[int, tuple[str, object]]) -> None:
    descriptor, (kind, module) = lock
    try:
        if kind == "windows":
            module.locking(descriptor, module.LK_UNLCK, 1)
        else:
            module.flock(descriptor, module.LOCK_UN)
    finally:
        os.close(descriptor)


def _snapshot_name() -> str:
    now = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    return f"{now}-{uuid.uuid4().hex[:8]}"


def _new_directory(parent: Path, prefix: str) -> Path:
    path = Path(tempfile.mkdtemp(prefix=prefix, dir=parent))
    os.chmod(path, 0o700)
    return path


def _snapshot_name_for_source(index: int, source: Path) -> str:
    name = re.sub(r"[^A-Za-z0-9_.-]", "_", source.name).strip(".") or "database.sqlite3"
    return f"db-{index:03d}-{name}"


def _backup_database(source: Path, destination: Path, deadline: float) -> None:
    _check_deadline(deadline)
    _validate_source(source)
    uri = source.resolve(strict=True).as_uri() + "?mode=ro"
    with closing(sqlite3.connect(uri, uri=True, timeout=5.0)) as source_connection:
        with closing(sqlite3.connect(destination, timeout=5.0)) as target_connection:
            os.chmod(destination, 0o600)

            def progress(_status: int, _remaining: int, _total: int) -> None:
                _check_deadline(deadline)

            source_connection.backup(target_connection, pages=128, progress=progress, sleep=0.01)
            target_connection.commit()
            journal_mode = target_connection.execute("PRAGMA journal_mode=DELETE").fetchone()
            if not journal_mode or str(journal_mode[0]).lower() != "delete":
                raise BackupError("snapshot database could not be finalized in DELETE journal mode")
            target_connection.commit()
    os.chmod(destination, 0o600)
    _fsync_file(destination)
    _inspect_database(destination, deadline)


def _manifest(snapshot: Path) -> dict[str, object]:
    manifest_path = snapshot / "manifest.json"
    try:
        info = manifest_path.lstat()
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
            raise BackupError("manifest must be a regular file")
        document = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise BackupError(f"cannot read backup manifest: {manifest_path}") from error
    if not isinstance(document, dict) or document.get("format") != FORMAT or document.get("version") != VERSION:
        raise BackupError("unsupported backup manifest format or version")
    created_at = document.get("created_at")
    if not isinstance(created_at, str) or not created_at.endswith("Z"):
        raise BackupError("manifest has invalid UTC creation time")
    try:
        parsed = dt.datetime.fromisoformat(created_at[:-1] + "+00:00")
    except ValueError as error:
        raise BackupError("manifest has invalid UTC creation time") from error
    if parsed.utcoffset() != dt.timedelta(0):
        raise BackupError("manifest creation time is not UTC")
    entries = document.get("databases")
    if not isinstance(entries, list) or not entries:
        raise BackupError("manifest has no databases")
    expected_top = {"format", "version", "created_at", "databases"}
    if set(document) != expected_top:
        raise BackupError("manifest contains unexpected fields")
    names = set()
    expected_files = {"manifest.json"}
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"snapshot", "source", "label", "size_bytes", "sha256"}:
            raise BackupError("manifest database entry has invalid fields")
        name = entry["snapshot"]
        relative = PurePosixPath(name) if isinstance(name, str) else PurePosixPath("..")
        if (
            not isinstance(name, str)
            or "\\" in name
            or relative.is_absolute()
            or not relative.parts
            or any(part in ("", ".", "..") for part in relative.parts)
            or len(relative.parts) != 1
            or name == "manifest.json"
            or name in names
        ):
            raise BackupError("manifest contains an unsafe or duplicate database name")
        if not isinstance(entry["source"], str) or not entry["source"]:
            raise BackupError("manifest database source is invalid")
        if not isinstance(entry["label"], str) or not entry["label"]:
            raise BackupError("manifest database label is invalid")
        if type(entry["size_bytes"]) is not int or entry["size_bytes"] <= 0:
            raise BackupError("manifest database size is invalid")
        if not isinstance(entry["sha256"], str) or not SHA256.fullmatch(entry["sha256"]):
            raise BackupError("manifest database SHA-256 is invalid")
        names.add(name)
        expected_files.add(name)
    try:
        actual_files = {child.name for child in snapshot.iterdir()}
    except OSError as error:
        raise BackupError(f"cannot list snapshot directory: {snapshot}") from error
    if actual_files != expected_files:
        raise BackupError("snapshot contains missing or unexpected files")
    return document


def _verify_snapshot(snapshot: Path, deadline: float) -> tuple[dict[str, object], dict[str, tuple[list[tuple[str, str, str, str | None]], dict[str, int]]]]:
    try:
        snapshot_info = snapshot.lstat()
    except OSError as error:
        raise BackupError(f"snapshot directory is unavailable: {snapshot}") from error
    if stat.S_ISLNK(snapshot_info.st_mode) or not stat.S_ISDIR(snapshot_info.st_mode):
        raise BackupError("snapshot must be a real directory, not a symlink")
    manifest = _manifest(snapshot)
    inspections = {}
    for entry in manifest["databases"]:
        _check_deadline(deadline)
        database = snapshot / entry["snapshot"]
        try:
            info = database.lstat()
        except OSError as error:
            raise BackupError(f"snapshot database is missing: {entry['snapshot']}") from error
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
            raise BackupError(f"snapshot database must be a regular file: {entry['snapshot']}")
        if info.st_size != entry["size_bytes"]:
            raise BackupError(f"snapshot database size mismatch: {entry['snapshot']}")
        if _hash_file(database, deadline) != entry["sha256"]:
            raise BackupError(f"snapshot database SHA-256 mismatch: {entry['snapshot']}")
        inspections[entry["snapshot"]] = _inspect_database(database, deadline)
    return manifest, inspections


def _make_backup(arguments: argparse.Namespace, deadline: float) -> Path:
    home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes"))).expanduser()
    root = Path(os.environ.get("HERMES_HUB_BACKUP_DIR", str(home / "backups" / "hub"))).expanduser()
    if os.path.lexists(root) and root.is_symlink():
        raise BackupError("backup root must not be a symlink")
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not root.is_dir():
        raise BackupError("backup root must be a directory")
    os.chmod(root, 0o700)
    lock = _lock(root / ".backup.lock", deadline)
    staging = None
    try:
        sources = _sources(arguments, home)
        validated = [(_validate_source(path), label) for path, label in sources]
        staging = _new_directory(root, ".tmp-")
        entries = []
        for index, (source, label) in enumerate(validated, start=1):
            _check_deadline(deadline)
            relative_name = _snapshot_name_for_source(index, source)
            target = staging / relative_name
            _backup_database(source, target, deadline)
            entries.append(
                {
                    "snapshot": relative_name,
                    "source": str(source),
                    "label": label,
                    "size_bytes": target.stat().st_size,
                    "sha256": _hash_file(target, deadline),
                }
            )
        document = {
            "format": FORMAT,
            "version": VERSION,
            "created_at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z"),
            "databases": entries,
        }
        manifest_path = staging / "manifest.json"
        with manifest_path.open("x", encoding="utf-8", newline="\n") as destination:
            os.chmod(manifest_path, 0o600)
            json.dump(document, destination, ensure_ascii=True, separators=(",", ":"))
            destination.write("\n")
            destination.flush()
            os.fsync(destination.fileno())
        _check_deadline(deadline)
        _fsync_directory(staging)
        final = root / _snapshot_name()
        os.rename(staging, final)
        staging = None
        _fsync_directory(root)
        _apply_retention(root, _retention(arguments.retention), final, deadline)
        return final
    finally:
        if staging is not None:
            shutil.rmtree(staging, ignore_errors=True)
        _unlock(lock)


def _apply_retention(root: Path, keep: int, current: Path, deadline: float) -> None:
    eligible = []
    for candidate in root.iterdir():
        if candidate == current or not SNAPSHOT_NAME.fullmatch(candidate.name):
            continue
        _check_deadline(deadline)
        try:
            _verify_snapshot(candidate, deadline)
        except BackupError:
            continue
        eligible.append(candidate)
    eligible.sort(key=lambda path: path.name, reverse=True)
    remove = eligible[max(0, keep - 1) :]
    for candidate in remove:
        _check_deadline(deadline)
        shutil.rmtree(candidate)
    if remove:
        _fsync_directory(root)


def _paths_overlap(first: Path, second: Path) -> bool:
    first = first.resolve(strict=False)
    second = second.resolve(strict=False)
    return first == second or first in second.parents or second in first.parents


def _restore(snapshot: Path, destination: Path, deadline: float) -> Path:
    manifest, source_inspections = _verify_snapshot(snapshot, deadline)
    if os.path.lexists(destination):
        raise BackupError("restore destination already exists")
    if not destination.parent.is_dir():
        raise BackupError("restore destination parent must exist and be a directory")
    destination_resolved = destination.resolve(strict=False)
    snapshot_resolved = snapshot.resolve(strict=True)
    if _paths_overlap(destination_resolved, snapshot_resolved):
        raise BackupError("restore destination overlaps snapshot")
    for entry in manifest["databases"]:
        source_path = Path(entry["source"])
        if _paths_overlap(destination_resolved, source_path):
            raise BackupError("restore destination overlaps a database source")
    staging = _new_directory(destination.parent, ".hermes-hub-restore-")
    try:
        for entry in manifest["databases"]:
            _check_deadline(deadline)
            source = snapshot / entry["snapshot"]
            target = staging / entry["snapshot"]
            _backup_database(source, target, deadline)
            restored_inspection = _inspect_database(target, deadline)
            if restored_inspection != source_inspections[entry["snapshot"]]:
                raise BackupError(f"restored database content check failed: {entry['snapshot']}")
        _fsync_directory(staging)
        os.rename(staging, destination)
        _fsync_directory(destination.parent)
        return destination
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", action="append", default=[], metavar="PATH")
    parser.add_argument("--include-agent-state", action="store_true")
    parser.add_argument("--retention", metavar="COUNT")
    parser.add_argument("--verify", metavar="SNAPSHOT")
    parser.add_argument("--restore-from", metavar="SNAPSHOT")
    parser.add_argument("--restore-to", metavar="NEW_DIRECTORY")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    arguments = parser.parse_args(argv)
    try:
        deadline = _deadline()
        if arguments.verify:
            if arguments.database or arguments.include_agent_state or arguments.retention or arguments.restore_from or arguments.restore_to:
                raise BackupError("--verify cannot be combined with backup or restore options")
            _verify_snapshot(Path(arguments.verify).expanduser(), deadline)
            print(f"Verified: {Path(arguments.verify).expanduser()}")
            return 0
        if bool(arguments.restore_from) != bool(arguments.restore_to):
            raise BackupError("--restore-from and --restore-to must be supplied together")
        if arguments.restore_from:
            if arguments.database or arguments.include_agent_state or arguments.retention:
                raise BackupError("restore cannot be combined with backup options")
            restored = _restore(
                Path(arguments.restore_from).expanduser(),
                Path(arguments.restore_to).expanduser(),
                deadline,
            )
            print(f"Restored offline snapshot to: {restored}")
            return 0
        retention = arguments.retention or os.environ.get("HERMES_HUB_BACKUP_RETENTION", "7")
        arguments.retention = _retention(retention)
        snapshot = _make_backup(arguments, deadline)
        print(f"Backup created: {snapshot}")
        return 0
    except (BackupError, OSError, sqlite3.Error) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
