from __future__ import annotations

import ast
import asyncio
import copy
import hmac
import os
import subprocess
import tempfile
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import List, Optional
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


if __name__ == "__main__":
    unittest.main()
