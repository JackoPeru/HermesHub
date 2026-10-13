from __future__ import annotations

import ast
import asyncio
import base64
import copy
import hmac
import os
import re
import subprocess
import tempfile
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, Dict, List, Optional
from unittest import mock

import sys

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from hermes_hub_gateway.adapters.hermes.legacy_patcher import _patch_text  # noqa: E402


FIXTURE = ROOT / "tests" / "fixtures" / "hermes-agent-0a62610f1-api_server.py"
TOKEN = "media-test-token"


class _Response:
    def __init__(self, status=200, *, headers=None, body=None, path=None):
        self.status = status
        self.headers = headers or {}
        self.body = body
        self.path = path


class _Web:
    Request = object
    StreamResponse = object
    Response = _Response

    @staticmethod
    def FileResponse(path, *, headers=None):
        return _Response(200, headers=headers, path=Path(path))

    @staticmethod
    def json_response(body, *, status=200):
        return _Response(status, body=body)


class _Request:
    def __init__(self, *, remote="203.0.113.8", headers=None, query=None, media_id="item.txt"):
        self.remote = remote
        self.headers = headers or {}
        self.query = query or {}
        self.match_info = {"media_id": media_id}


def _function_nodes(tree: ast.Module, names: set[str]) -> dict[str, ast.FunctionDef]:
    found = {}
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name in names:
            found[node.name] = node
    return found


class GeneratedMediaAuthTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        clean = FIXTURE.read_text(encoding="utf-8")
        cls.generated, _ = _patch_text(clean)
        compile(cls.generated, str(FIXTURE), "exec")
        cls.tree = ast.parse(cls.generated)
        cls.functions = _function_nodes(
            cls.tree,
            {
                "_hermes_hub_api_keys",
                "_hermes_hub_is_tailnet_peer",
                "_hermes_hub_media_roots",
                "_hermes_hub_resolve_media_path",
                "_hermes_hub_bounded_media_matches",
            },
        )
        methods = [
            node
            for node in ast.walk(cls.tree)
            if isinstance(node, ast.AsyncFunctionDef) and node.name == "_handle_hub_media"
        ]
        if not methods:
            raise AssertionError("generated gateway has no _handle_hub_media handler")
        cls.media_method = methods[-1]

    def _generated_namespace(self, *, include_resolver=False):
        names = {"_hermes_hub_api_keys", "_hermes_hub_is_tailnet_peer"}
        if include_resolver:
            names.update(
                {
                    "_hermes_hub_media_roots",
                    "_hermes_hub_resolve_media_path",
                    "_hermes_hub_bounded_media_matches",
                }
            )
        nodes = [copy.deepcopy(self.functions[name]) for name in names if name in self.functions]
        namespace = {
            "List": List,
            "Optional": Optional,
            "Path": Path,
            "asyncio": asyncio,
            "hmac": hmac,
            "os": os,
            "time": time,
            "web": _Web,
            "_hermes_hub_env_int": lambda _name, default, *_bounds: default,
        }
        if not include_resolver:
            server = ast.ClassDef(
                name="GeneratedMediaServer",
                bases=[],
                keywords=[],
                body=[copy.deepcopy(self.media_method)],
                decorator_list=[],
            )
            nodes.append(server)
        module = ast.fix_missing_locations(ast.Module(body=nodes, type_ignores=[]))
        exec(compile(module, "<generated-media-handlers>", "exec"), namespace)
        return namespace

    def _server(self, namespace, media_path):
        class Server(namespace["GeneratedMediaServer"]):
            _api_key = TOKEN

            def _check_auth(self, request):
                if request.headers.get("Authorization") == f"Bearer {TOKEN}":
                    return None
                return _Response(401, body={"error": "unauthorized"})

        pool = ThreadPoolExecutor(max_workers=1)
        namespace["_hermes_hub_io_executor"] = lambda: pool
        namespace["_hermes_hub_resolve_media_path"] = lambda *_args: media_path
        return Server(), pool

    def test_anonymous_media_requests_require_auth_from_every_peer(self):
        namespace = self._generated_namespace()
        with mock.patch.dict(os.environ, {}, clear=True):
            for remote, forwarded in (
                ("203.0.113.8", "100.64.1.2"),
                ("127.0.0.1", ""),
                ("100.64.1.2", ""),
            ):
                with self.subTest(remote=remote, forwarded=forwarded):
                    server, pool = self._server(namespace, Path("/tmp/media"))
                    request = _Request(
                        remote=remote,
                        headers={"X-Forwarded-For": forwarded} if forwarded else {},
                    )
                    response = asyncio.run(server._handle_hub_media(request))
                    pool.shutdown(wait=True)
                    self.assertEqual(401, response.status)

    def test_invalid_bearer_is_rejected_and_bearer_and_legacy_query_keys_serve_media(self):
        namespace = self._generated_namespace()
        with tempfile.TemporaryDirectory() as tmp:
            media = Path(tmp) / "media.txt"
            media.write_text("media", encoding="utf-8")
            cases = [
                (_Request(headers={"Authorization": "Bearer wrong"}), 401),
                (_Request(headers={"Authorization": f"Bearer {TOKEN}"}), 200),
                *(
                    (_Request(query={key: TOKEN}), 200)
                    for key in ("hub_token", "api_key", "token")
                ),
            ]
            for request, expected_status in cases:
                with self.subTest(headers=request.headers, query=request.query):
                    server, pool = self._server(namespace, media)
                    response = asyncio.run(server._handle_hub_media(request))
                    pool.shutdown(wait=True)
                    self.assertEqual(expected_status, response.status)
                    if expected_status == 200:
                        self.assertEqual(media, response.path)
                        self.assertEqual("private, no-store", response.headers["Cache-Control"])

    def test_emitted_peer_helper_uses_direct_peer_and_ignores_forwarded_for(self):
        namespace = self._generated_namespace()
        helper = namespace["_hermes_hub_is_tailnet_peer"]
        self.assertFalse(
            helper(_Request(remote="203.0.113.8", headers={"X-Forwarded-For": "100.64.1.2"}))
        )
        self.assertTrue(helper(_Request(remote="127.0.0.1")))
        self.assertTrue(helper(_Request(remote="100.64.1.2")))

    def test_generated_resolver_rejects_parent_traversal_outside_configured_root(self):
        namespace = self._generated_namespace(include_resolver=True)
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {}, clear=True):
            root = Path(tmp) / "root"
            root.mkdir()
            outside = Path(tmp) / "outside.txt"
            outside.write_text("outside", encoding="utf-8")
            with mock.patch.dict(
                os.environ,
                {
                    "HERMES_MEDIA_ROOTS": str(root),
                    "HERMES_HUB_UPLOAD_PATH": str(root),
                    "HERMES_VIDEO_LIBRARY_PATH": str(root),
                    "HERMES_NEWS_LIBRARY_PATH": str(root),
                },
            ):
                resolver = namespace["_hermes_hub_resolve_media_path"]
                self.assertIsNone(resolver("../outside.txt"))
                self.assertIsNone(resolver(str(outside)))

    def test_generated_resolver_rejects_symlink_or_junction_escape(self):
        namespace = self._generated_namespace(include_resolver=True)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "root"
            root.mkdir()
            outside = Path(tmp) / "outside"
            outside.mkdir()
            secret = outside / "secret.txt"
            secret.write_text("outside", encoding="utf-8")
            link = root / "escape"
            if os.name == "nt":
                environment = os.environ.copy()
                environment["HERMES_TEST_JUNCTION_PATH"] = str(link)
                environment["HERMES_TEST_JUNCTION_TARGET"] = str(outside)
                result = subprocess.run(
                    [
                        "powershell.exe",
                        "-NoProfile",
                        "-Command",
                        "New-Item -ItemType Junction -Path $env:HERMES_TEST_JUNCTION_PATH -Target $env:HERMES_TEST_JUNCTION_TARGET -ErrorAction Stop | Out-Null",
                    ],
                    env=environment,
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                )
                if result.returncode:
                    self.skipTest(f"junction creation unavailable: {result.stderr or result.stdout}")
                media_id = "escape/secret.txt"
            else:
                try:
                    link.symlink_to(secret)
                except (NotImplementedError, OSError) as exc:
                    self.skipTest(f"symlinks unavailable: {exc}")
                media_id = "escape"
            with mock.patch.dict(
                os.environ,
                {
                    "HERMES_MEDIA_ROOTS": str(root),
                    "HERMES_HUB_UPLOAD_PATH": str(root),
                    "HERMES_VIDEO_LIBRARY_PATH": str(root),
                    "HERMES_NEWS_LIBRARY_PATH": str(root),
                },
            ):
                self.assertIsNone(namespace["_hermes_hub_resolve_media_path"](media_id))

    def test_clean_and_already_patched_output_compile_and_are_byte_idempotent(self):
        patched_again, _ = _patch_text(self.generated)
        self.assertEqual(self.generated, patched_again)
        compile(patched_again, "<already-patched-gateway>", "exec")


PATCHER_SOURCE_PATH = ROOT / "scripts" / "hermes_hub_gateway" / "adapters" / "hermes" / "legacy_patcher.py"

MEDIA_HANDLER_NAMES = {
    "_handle_hub_media",
    "_handle_hub_media_upload",
    "_handle_image_library",
    "_handle_news_library",
}


class GeneratedMediaGuardrailsTests(unittest.TestCase):
    """Guardrail fail-closed per i media generati (nessun segreto nei log)."""

    @classmethod
    def setUpClass(cls):
        clean = FIXTURE.read_text(encoding="utf-8")
        cls.generated, _ = _patch_text(clean)
        compile(cls.generated, str(FIXTURE), "exec")
        cls.tree = ast.parse(cls.generated)
        cls.patcher_source = PATCHER_SOURCE_PATH.read_text(encoding="utf-8")

    def _media_handler_nodes(self):
        return [
            node
            for node in ast.walk(self.tree)
            if isinstance(node, ast.AsyncFunctionDef) and node.name in MEDIA_HANDLER_NAMES
        ]

    def test_generated_media_handlers_have_no_tailnet_bypass(self):
        handlers = self._media_handler_nodes()
        self.assertTrue(handlers, "codice generato senza handler media")
        for node in handlers:
            with self.subTest(handler=node.name):
                segment = ast.get_source_segment(self.generated, node) or ""
                self.assertNotIn("_hermes_hub_is_tailnet_peer", segment)

    def test_patcher_templates_have_no_tailnet_auth_bypass(self):
        # Nel patcher resta solo la definizione dell'helper, nessuna chiamata di bypass.
        self.assertNotIn("if _hermes_hub_is_tailnet_peer(request):", self.patcher_source)
        self.assertNotIn("auth_error = None", self.patcher_source)
        self.assertNotIn("auth_error=None", self.patcher_source)

    def _generated_functions(self, names):
        module = ast.parse(self.generated)
        found = [
            copy.deepcopy(node)
            for node in module.body
            if isinstance(node, ast.FunctionDef) and node.name in names
        ]
        self.assertEqual(
            len(names),
            len(found),
            f"funzioni mancanti nel codice generato: {sorted(names)}",
        )
        namespace = {
            "Any": Any,
            "Dict": Dict,
            "List": List,
            "Optional": Optional,
            "Path": Path,
            "asyncio": asyncio,
            "base64": base64,
            "hmac": hmac,
            "os": os,
            "re": re,
            "time": time,
            "web": _Web,
            "_hermes_hub_env_int": lambda _name, default, *_bounds: default,
        }
        exec(
            compile(ast.fix_missing_locations(ast.Module(body=found, type_ignores=[])), "<generated-media-guardrails>", "exec"),
            namespace,
        )
        return namespace

    def test_extra_root_outside_media_roots_is_ignored(self):
        namespace = self._generated_functions(
            {
                "_hermes_hub_api_keys",
                "_hermes_hub_is_tailnet_peer",
                "_hermes_hub_media_roots",
                "_hermes_hub_resolve_media_path",
                "_hermes_hub_bounded_media_matches",
            }
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "root"
            root.mkdir()
            inner = root / "inner.txt"
            inner.write_text("dentro", encoding="utf-8")
            outside = Path(tmp) / "fuori"
            outside.mkdir()
            (outside / "secret.txt").write_text("fuori", encoding="utf-8")
            with mock.patch.dict(
                os.environ,
                {
                    "HERMES_MEDIA_ROOTS": str(root),
                    "HERMES_HUB_UPLOAD_PATH": str(root),
                    "HERMES_VIDEO_LIBRARY_PATH": str(root),
                    "HERMES_NEWS_LIBRARY_PATH": str(root),
                },
            ):
                resolver = namespace["_hermes_hub_resolve_media_path"]
                # Root esterna ignorata: i file sotto i roots restano raggiungibili.
                self.assertEqual(inner, resolver("inner.txt", extra_root=str(outside)))
                # ...ma nulla fuori dai roots diventa raggiungibile.
                self.assertIsNone(resolver("secret.txt", extra_root=str(outside)))
                # Root uguale a un media root resta accettata.
                self.assertEqual(inner, resolver("inner.txt", extra_root=str(root)))

    def test_patcher_resolver_variants_contain_extra_root(self):
        positions = [
            match.start()
            for match in re.finditer(r"roots\.insert\(0", self.patcher_source)
        ]
        self.assertGreaterEqual(len(positions), 4, "attese almeno 4 varianti con roots.insert")
        for pos in positions:
            with self.subTest(offset=pos):
                window = self.patcher_source[max(0, pos - 600) : pos]
                self.assertIn("in requested.parents", window)

    def test_generated_save_upload_rejects_over_cap_and_invalid_base64(self):
        namespace = self._generated_functions(
            {
                "_hermes_hub_env_int",
                "_hermes_hub_upload_root",
                "_hermes_hub_safe_upload_name",
                "_hermes_hub_save_upload",
            }
        )
        save_upload = namespace["_hermes_hub_save_upload"]
        with tempfile.TemporaryDirectory() as tmp:
            env = {
                "HERMES_HUB_UPLOAD_PATH": str(Path(tmp) / "uploads"),
                "HERMES_HUB_JSON_UPLOAD_MAX_MB": "1",
                "HERMES_HUB_MAX_UPLOAD_MB": "1",
            }
            with mock.patch.dict(os.environ, env):
                big = base64.b64encode(b"x" * (2 * 1024 * 1024)).decode("ascii")
                with self.assertRaises(ValueError):
                    save_upload(
                        "grande.bin",
                        "application/octet-stream",
                        "data:application/octet-stream;base64," + big,
                    )
                with self.assertRaises(ValueError):
                    save_upload(
                        "rotto.bin",
                        "application/octet-stream",
                        "data:application/octet-stream;base64,!!!non-base64!!!",
                    )
                ok = save_upload(
                    "piccolo.txt",
                    "text/plain",
                    "data:text/plain;base64," + base64.b64encode(b"ciao").decode("ascii"),
                )
                self.assertEqual(4, ok["size_bytes"])

    def test_generated_save_upload_default_cap_is_finite(self):
        namespace = self._generated_functions(
            {
                "_hermes_hub_env_int",
                "_hermes_hub_upload_root",
                "_hermes_hub_safe_upload_name",
                "_hermes_hub_save_upload",
            }
        )
        save_upload = namespace["_hermes_hub_save_upload"]
        with tempfile.TemporaryDirectory() as tmp:
            env = {
                "HERMES_HUB_UPLOAD_PATH": str(Path(tmp) / "uploads"),
                "HERMES_HUB_JSON_UPLOAD_MAX_MB": "0",
                "HERMES_HUB_MAX_UPLOAD_MB": "0",
            }
            with mock.patch.dict(os.environ, env):
                # Con limite non configurato il default resta finito: 2MB rifiutati...
                big = base64.b64encode(b"y" * (2 * 1024 * 1024)).decode("ascii")
                with self.assertRaises(ValueError):
                    save_upload(
                        "grande.bin",
                        "application/octet-stream",
                        "data:application/octet-stream;base64," + big,
                    )
                # ...e gli upload leciti passano.
                ok = save_upload(
                    "piccolo.txt",
                    "text/plain",
                    "data:text/plain;base64," + base64.b64encode(b"ok").decode("ascii"),
                )
                self.assertEqual(2, ok["size_bytes"])

    def test_patcher_save_upload_variants_enforce_finite_default(self):
        # Solo le definizioni reali (parentesi letterale), non i riferimenti nelle regex.
        marker = "def _hermes_hub_save_upload("
        positions = [
            match.start()
            for match in re.finditer(re.escape(marker), self.patcher_source)
        ]
        self.assertGreaterEqual(len(positions), 2, "attese almeno due varianti di save_upload")
        for pos in positions:
            with self.subTest(offset=pos):
                window = self.patcher_source[pos : pos + 2600]
                self.assertTrue(
                    "64 * 1024 * 1024" in window
                    or '_hermes_hub_env_int("HERMES_HUB_JSON_UPLOAD_MAX_MB", 64, 1, 1024)' in window,
                    "default finito assente",
                )
                self.assertIn("validate=True", window)
                self.assertIn("(len(encoded) * 3) // 4", window)
                self.assertNotIn("validate=False", window)
                self.assertNotIn("(max_bytes > 0 and", window)


if __name__ == "__main__":
    unittest.main()
