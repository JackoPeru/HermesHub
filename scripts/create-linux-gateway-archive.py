#!/usr/bin/env python3
"""Create a deterministic Linux gateway tar.gz from a prepared staging tree."""

from __future__ import annotations

import argparse
import gzip
import os
import shutil
import tarfile
import tempfile
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def archive_name(relative_path: Path) -> str:
    value = relative_path.as_posix()
    return "." if value == "." else f"./{value}"


def add_directory(archive: tarfile.TarFile, relative_path: Path, timestamp: int) -> None:
    info = tarfile.TarInfo(archive_name(relative_path))
    info.type = tarfile.DIRTYPE
    info.mode = 0o755
    info.mtime = timestamp
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    archive.addfile(info)


def add_file(archive: tarfile.TarFile, source: Path, relative_path: Path, timestamp: int) -> None:
    info = tarfile.TarInfo(archive_name(relative_path))
    info.size = source.stat().st_size
    info.mode = 0o755 if source.suffix == ".sh" else 0o644
    info.mtime = timestamp
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    with source.open("rb") as stream:
        archive.addfile(info, stream)


def create_archive(stage: Path, output: Path) -> None:
    stage = stage.resolve(strict=True)
    if not stage.is_dir():
        raise ValueError(f"Staging path is not a directory: {stage}")

    output = output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    timestamp = int(os.environ.get("SOURCE_DATE_EPOCH", "0"))
    if timestamp < 0:
        raise ValueError("SOURCE_DATE_EPOCH must be non-negative")

    directories = [Path(".")]
    files: list[tuple[Path, Path]] = []
    for source in sorted(stage.rglob("*"), key=lambda path: path.relative_to(stage).as_posix()):
        if source.is_symlink():
            raise ValueError(f"Symlinks are not allowed in the gateway bundle: {source}")
        relative_path = source.relative_to(stage)
        if source.is_dir():
            directories.append(relative_path)
        elif source.is_file():
            files.append((source, relative_path))
        else:
            raise ValueError(f"Unsupported staging entry: {source}")

    partial = output.with_name(f".{output.name}.{os.getpid()}.partial")
    try:
        with tempfile.TemporaryFile() as raw_tar:
            with tarfile.open(fileobj=raw_tar, mode="w", format=tarfile.USTAR_FORMAT) as archive:
                for relative_path in directories:
                    add_directory(archive, relative_path, timestamp)
                for source, relative_path in files:
                    add_file(archive, source, relative_path, timestamp)

            raw_tar.seek(0)
            with partial.open("wb") as compressed:
                with gzip.GzipFile(filename="", mode="wb", fileobj=compressed, compresslevel=9, mtime=timestamp) as gzip_file:
                    shutil.copyfileobj(raw_tar, gzip_file, length=1024 * 1024)
                compressed.flush()
                os.fsync(compressed.fileno())
        os.replace(partial, output)
    finally:
        partial.unlink(missing_ok=True)


def main() -> int:
    args = parse_args()
    create_archive(args.stage, args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
