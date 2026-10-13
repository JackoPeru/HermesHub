"""Test comportamentali per le funzioni pure generate dal patcher gateway.

Harness reale (come tests/test_media_auth.py): _patch_text sulla fixture,
compile del sorgente generato, exec via AST delle funzioni pure in namespace
con stub. Ogni test esegue davvero la funzione su fixture/env temporanei:
se la logica e rotta, fallisce (niente assertIn su stringhe).
"""
from __future__ import annotations

import ast
import copy
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from typing import List, Optional
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from hermes_hub_gateway.adapters.hermes.legacy_patcher import _patch_text  # noqa: E402

FIXTURE = ROOT / "tests" / "fixtures" / "hermes-agent-0a62610f1-api_server.py"
WANTED = {
    "_hermes_hub_api_keys",
    "_hermes_hub_safe_upload_name",
    "_hermes_hub_bounded_media_matches",
    "_hermes_hub_media_roots",
    "_hermes_hub_env_int",
}
KEY_ENV_VARS = (
    "API_SERVER_KEY",
    "HERMES_API_KEY",
    "HERMESAPIKEY",
    "HERMES_HUB_API_KEY",
    "HERMES_GATEWAY_API_KEY",
)


def load_generated_namespace() -> dict:
    clean = FIXTURE.read_text(encoding="utf-8")
    generated, _ = _patch_text(clean)
    compile(generated, str(FIXTURE), "exec")
    tree = ast.parse(generated)
    found = {n.name: n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in WANTED}
    assert set(found) == WANTED, f"funzioni generate mancanti: {WANTED - set(found)}"
    ns: dict = {"List": List, "Optional": Optional, "Path": Path, "os": os, "time": time}
    mod = ast.fix_missing_locations(
        ast.Module(body=[copy.deepcopy(found[k]) for k in sorted(found)], type_ignores=[])
    )
    exec(compile(mod, "<generated-pure>", "exec"), ns)  # noqa: S102 - test locale
    return ns


def clear_key_env() -> dict:
    saved = {k: os.environ.pop(k, None) for k in KEY_ENV_VARS}
    return saved


def restore_env(saved: dict) -> None:
    for key, value in saved.items():
        if value is None:
            os.environ.pop(key, None)
        else:
            os.environ[key] = value


class GeneratedApiKeysTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ns = load_generated_namespace()

    def test_no_keys_configured_means_empty(self):
        saved = clear_key_env()
        try:
            self.assertEqual(self.ns["_hermes_hub_api_keys"](""), [])
        finally:
            restore_env(saved)

    def test_primary_key_is_accepted(self):
        saved = clear_key_env()
        try:
            self.assertEqual(self.ns["_hermes_hub_api_keys"]("abc"), ["abc"])
        finally:
            restore_env(saved)

    def test_semicolon_and_comma_lists_are_split(self):
        saved = clear_key_env()
        try:
            self.assertEqual(self.ns["_hermes_hub_api_keys"]("a;b, c"), ["a", "b", "c"])
        finally:
            restore_env(saved)

    def test_env_keys_are_accepted_and_deduplicated(self):
        saved = clear_key_env()
        try:
            with mock.patch.dict(os.environ, {"HERMES_API_KEY": "env1, env2"}):
                self.assertEqual(self.ns["_hermes_hub_api_keys"]("env1"), ["env1", "env2"])
        finally:
            restore_env(saved)


class GeneratedSafeUploadNameTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ns = load_generated_namespace()

    def test_traversal_is_sanitized_and_suffix_kept(self):
        name = self.ns["_hermes_hub_safe_upload_name"]("../../etc/passwd", "image/png")
        self.assertNotIn("/", name)
        self.assertNotIn("..", name)
        self.assertTrue(name.endswith(".png"))

    def test_missing_suffix_comes_from_mime(self):
        safe = self.ns["_hermes_hub_safe_upload_name"]
        self.assertTrue(safe("photo", "image/png").endswith(".png"))
        self.assertTrue(safe("photo", "application/octet-stream").endswith(".bin"))

    def test_empty_name_falls_back_to_attachment(self):
        name = self.ns["_hermes_hub_safe_upload_name"]("", "image/jpeg")
        self.assertTrue(name.startswith("attachment"))
        self.assertTrue(name.endswith(".jpg"))

    def test_name_is_capped_at_160_chars(self):
        name = self.ns["_hermes_hub_safe_upload_name"]("a" * 300 + ".png", "image/png")
        self.assertLessEqual(len(name), 160)
        self.assertTrue(name.endswith(".png"))


class GeneratedBoundedMatchesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ns = load_generated_namespace()

    def test_exact_name_matches_recursively(self):
        bounded = self.ns["_hermes_hub_bounded_media_matches"]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "root"
            (root / "sub").mkdir(parents=True)
            (root / "song.mp3").write_text("x", encoding="utf-8")
            (root / "sub" / "song.mp3").write_text("y", encoding="utf-8")
            (root / "other.txt").write_text("z", encoding="utf-8")
            hits = sorted(str(p) for p in bounded(root, "song.mp3"))
            self.assertEqual(len(hits), 2)
            self.assertIn(str(root / "song.mp3"), hits)

    def test_prefix_search_matches_starts(self):
        bounded = self.ns["_hermes_hub_bounded_media_matches"]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "root"
            root.mkdir()
            (root / "song.mp3").write_text("x", encoding="utf-8")
            (root / "other.txt").write_text("z", encoding="utf-8")
            hits = [str(p) for p in bounded(root, "song", prefix=True)]
            self.assertEqual(len(hits), 1)
            self.assertTrue(hits[0].endswith("song.mp3"))

    def test_no_match_and_missing_dir_return_empty(self):
        bounded = self.ns["_hermes_hub_bounded_media_matches"]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "root"
            root.mkdir()
            self.assertEqual(bounded(root, "nope.mp3"), [])
            self.assertEqual(bounded(root / "missing", "song.mp3"), [])

    def test_symlinked_file_pointing_outside_is_excluded(self):
        bounded = self.ns["_hermes_hub_bounded_media_matches"]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "root"
            root.mkdir()
            secret = Path(tmp) / "secret.mp3"
            secret.write_text("s", encoding="utf-8")
            link = root / "leak.mp3"
            try:
                link.symlink_to(secret)
            except (NotImplementedError, OSError) as exc:
                self.skipTest(f"symlinks unavailable: {exc}")
            hits = [str(p) for p in bounded(root, "leak.mp3")]
            self.assertEqual(hits, [])


class GeneratedMediaRootsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ns = load_generated_namespace()

    def test_env_libraries_define_roots_in_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            paths = {k: str(Path(tmp) / v) for k, v in (
                ("HERMES_HUB_UPLOAD_PATH", "u"),
                ("HERMES_VIDEO_LIBRARY_PATH", "v"),
                ("HERMES_NEWS_LIBRARY_PATH", "n"),
            )}
            env = dict(paths, HERMES_MEDIA_ROOTS="")
            with mock.patch.dict(os.environ, env, clear=False):
                roots = [str(p) for p in self.ns["_hermes_hub_media_roots"]()]
            self.assertEqual(roots, [paths["HERMES_HUB_UPLOAD_PATH"],
                                     paths["HERMES_VIDEO_LIBRARY_PATH"],
                                     paths["HERMES_NEWS_LIBRARY_PATH"]])

    def test_duplicate_roots_are_collapsed(self):
        with tempfile.TemporaryDirectory() as tmp:
            dup = str(Path(tmp) / "dup")
            env = {"HERMES_HUB_UPLOAD_PATH": dup, "HERMES_VIDEO_LIBRARY_PATH": dup,
                   "HERMES_NEWS_LIBRARY_PATH": dup,
                   "HERMES_MEDIA_ROOTS": dup}
            with mock.patch.dict(os.environ, env, clear=False):
                roots = [str(p) for p in self.ns["_hermes_hub_media_roots"]()]
            self.assertEqual(len(roots), 1)


if __name__ == "__main__":
    unittest.main()
