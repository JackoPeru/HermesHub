"""Firma dispositivo P-256 + anti-replay sul codice GENERATO dal patcher.

Esegue le funzioni di verifica estratte dal gateway generato (stesso
pattern di test_media_auth) con chiavi ECDSA vere. Il confine openssl CLI
e' emulato via `cryptography` (assente su Windows): tutta la logica
Python (canonico, header, finestra, nonce, lookup, plumbing) e' reale.
"""

import ast
import asyncio
import base64
import hashlib
import json
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from types import ModuleType

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

REPO = Path(__file__).resolve().parents[1]
SCRIPTS = REPO / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from hermes_hub_gateway.adapters.hermes.legacy_patcher import _patch_text  # noqa: E402

FIXTURE = REPO / "tests/fixtures/hermes-agent-0a62610f1-api_server.py"

WANTED = {
    "_hermes_hub_storage_path",
    "_hermes_hub_read_json",
    "_hermes_hub_write_json",
    "_hermes_hub_device_store_path",
    "_hermes_hub_device_load",
    "_hermes_hub_device_save",
    "_hermes_hub_device_fingerprint",
    "_hermes_hub_device_check_pem",
    "_hermes_hub_device_canonical",
    "_hermes_hub_device_verify_signature",
    "_hermes_hub_require_device_signature",
}


class _FakeCompleted:
    def __init__(self, returncode: int, stdout: bytes):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = b""


def _fake_run(cmd, **kwargs):
    # Emula: openssl dgst -sha256 -verify <pub> -signature <sig> <data>
    assert cmd[0] == "openssl" and cmd[1:4] == ["dgst", "-sha256", "-verify"], cmd
    pub_file = cmd[cmd.index("-verify") + 1]
    sig_file = cmd[cmd.index("-signature") + 1]
    data_file = cmd[-1]
    pub = serialization.load_pem_public_key(Path(pub_file).read_bytes())
    assert isinstance(pub, ec.EllipticCurvePublicKey) and pub.curve.name == "secp256r1"
    try:
        pub.verify(Path(sig_file).read_bytes(), Path(data_file).read_bytes(), ec.ECDSA(hashes.SHA256()))
        return _FakeCompleted(0, b"Verified OK\n")
    except InvalidSignature:
        return _FakeCompleted(1, b"Verification failure\n")


class _FakeWebResponse:
    def __init__(self, status=200, body=None):
        self.status = status
        self.body = body


class _FakeWeb(ModuleType):
    def __init__(self):
        super().__init__("web")
        self.Request = object
        self.Response = _FakeWebResponse
        self.StreamResponse = object

    @staticmethod
    def json_response(body=None, *, status=200):
        return _FakeWebResponse(status, body)


class _FakeSubprocess(ModuleType):
    def __init__(self):
        super().__init__("subprocess")

    @staticmethod
    def run(cmd, **kwargs):
        return _fake_run(cmd, **kwargs)


class FakeRequest:
    def __init__(self, headers, body: bytes, path_qs="/v1/hub/server/maintenance", method="POST"):
        self.headers = dict(headers)
        self.path_qs = path_qs
        self.path = path_qs.split("?", 1)[0]
        self.method = method
        self._body = body

    async def read(self):
        return self._body


def make_key():
    private = ec.generate_private_key(ec.SECP256R1())
    pem = private.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode()
    return private, pem


def sign_headers(private, method, path_qs, body: bytes, skew=0):
    ts = str(int(time.time()) + skew)
    nonce = "n" + os.urandom(16).hex()
    body_sha = hashlib.sha256(body).hexdigest()
    canonical = "\n".join([method.upper(), path_qs, ts, nonce, body_sha]).encode()
    signature = private.sign(canonical, ec.ECDSA(hashes.SHA256()))
    return {
        "X-Hermes-Timestamp": ts,
        "X-Hermes-Nonce": nonce,
        "X-Hermes-Signature": base64.b64encode(signature).decode(),
    }


class DeviceSignatureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        clean = FIXTURE.read_text(encoding="utf-8")
        generated, _ = _patch_text(clean)
        compile(generated, str(FIXTURE), "exec")
        tree = ast.parse(generated)
        funcs = [
            n for n in ast.walk(tree)
            if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name in WANTED
        ]
        # Deduplica per nome (prima occorrenza): i metodi patchati vivono
        # nella classe handler, stesso corpo ovunque.
        seen: set[str] = set()
        unique = []
        for n in funcs:
            assert isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
            if n.name not in seen:
                seen.add(n.name)
                unique.append(n)
        funcs = unique
        found = {n.name for n in funcs}
        assert found == WANTED, f"funzioni non trovate: {WANTED - found}"
        cls.tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.tmp.cleanup)
        os.environ["HERMES_HUB_DEVICES_FILE"] = str(Path(cls.tmp.name) / "hub_devices.json")
        import asyncio as _asyncio
        import logging
        import os as _os
        import time as _time
        ns: dict = {
            "__name__": "generated_device",
            "web": _FakeWeb(),
            "_hermes_hub_audit_event": lambda payload: payload,
            "os": _os,
            "asyncio": _asyncio,
            "time": _time,
            "logger": logging.getLogger("generated_device"),
        }
        import typing
        ns.update({"Dict": typing.Dict, "Any": typing.Any, "Optional": typing.Optional})
        # Il confine openssl CLI resta emulato per TUTTI i test (le funzioni
        # generate importano subprocess al momento della chiamata, non qui).
        real_subprocess = sys.modules.get("subprocess")
        sys.modules["subprocess"] = _FakeSubprocess()  # type: ignore[assignment]
        if real_subprocess is not None:
            cls.addClassCleanup(sys.modules.__setitem__, "subprocess", real_subprocess)
        else:
            cls.addClassCleanup(sys.modules.pop, "subprocess", None)
        exec(compile(ast.Module(body=funcs, type_ignores=[]), "device_fns", "exec"), ns)  # noqa: S102
        cls.ns = ns
        cls.private, cls.pem = make_key()
        cls.key_id = "testdev-01"
        verify = ns["_hermes_hub_device_verify_signature"]
        save = ns["_hermes_hub_device_save"]
        save({"devices": {cls.key_id: {
            "public_key": cls.pem, "label": "test", "created": 1,
            "revoked": False, "fcm_token": "",
        }}, "nonces": {}})

    def _headers(self, body: bytes, path="/v1/hub/server/maintenance", key_id=None, skew=0):
        headers = sign_headers(self.private, "POST", path, body, skew)
        headers["X-Hermes-Key-Id"] = key_id or self.key_id
        return headers

    def test_valid_signature_passes(self) -> None:
        verify = self.ns["_hermes_hub_device_verify_signature"]
        body = b'{"operation":"diagnostic"}'
        req = FakeRequest(self._headers(body), body)
        ok, who = asyncio.run(verify(req, body))
        self.assertTrue(ok)
        self.assertEqual(self.key_id, who)

    def test_tampered_body_fails(self) -> None:
        verify = self.ns["_hermes_hub_device_verify_signature"]
        body = b'{"operation":"diagnostic"}'
        req = FakeRequest(self._headers(body), b'{"operation":"update"}')
        ok, why = asyncio.run(verify(req, b'{"operation":"update"}'))
        self.assertFalse(ok)
        self.assertEqual("bad signature", why)

    def test_replay_fails(self) -> None:
        verify = self.ns["_hermes_hub_device_verify_signature"]
        body = b'{"operation":"diagnostic"}'
        headers = self._headers(body)
        first = asyncio.run(verify(FakeRequest(headers, body), body))
        self.assertTrue(first[0])
        ok, why = asyncio.run(verify(FakeRequest(dict(headers), body), body))
        self.assertFalse(ok)
        self.assertEqual("replay", why)

    def test_stale_timestamp_fails(self) -> None:
        verify = self.ns["_hermes_hub_device_verify_signature"]
        body = b"{}"
        req = FakeRequest(self._headers(body, skew=-3600), body)
        ok, why = asyncio.run(verify(req, body))
        self.assertFalse(ok)
        self.assertEqual("stale timestamp", why)

    def test_unknown_and_revoked_key_fail(self) -> None:
        verify = self.ns["_hermes_hub_device_verify_signature"]
        body = b"{}"
        req = FakeRequest(self._headers(body, key_id="ghost-device"), body)
        ok, why = asyncio.run(verify(req, body))
        self.assertFalse(ok)
        self.assertEqual("unknown device", why)
        save = self.ns["_hermes_hub_device_save"]
        load = self.ns["_hermes_hub_device_load"]
        store = load()
        store["devices"][self.key_id]["revoked"] = True
        save(store)
        try:
            ok, why = asyncio.run(verify(FakeRequest(self._headers(body), body), body))
            self.assertFalse(ok)
            self.assertEqual("unknown device", why)
        finally:
            store["devices"][self.key_id]["revoked"] = False
            save(store)

    def test_bad_nonce_and_missing_headers_fail(self) -> None:
        verify = self.ns["_hermes_hub_device_verify_signature"]
        body = b"{}"
        headers = self._headers(body)
        headers["X-Hermes-Nonce"] = "short"
        ok, why = asyncio.run(verify(FakeRequest(headers, body), body))
        self.assertFalse(ok)
        self.assertEqual("bad nonce", why)
        ok, why = asyncio.run(verify(FakeRequest({}, body), body))
        self.assertFalse(ok)
        self.assertEqual("unknown device", why)

    def test_canonical_is_stable(self) -> None:
        canonical = self.ns["_hermes_hub_device_canonical"]
        first = canonical("POST", "/v1/x?a=1", "123", "nabc", "deadbeef")
        self.assertEqual(first, canonical("post", "/v1/x?a=1", "123", "nabc", "deadbeef"))
        self.assertEqual(5, first.count(b"\n") + 1)
        self.assertNotEqual(first, canonical("POST", "/v1/x?a=2", "123", "nabc", "deadbeef"))

    def test_gate_bootstrap_without_devices(self) -> None:
        require = self.ns["_hermes_hub_require_device_signature"]
        save = self.ns["_hermes_hub_device_save"]
        save({"devices": {}, "nonces": {}})
        try:
            self.assertIsNone(asyncio.run(require(FakeRequest({}, b"{}"))))
        finally:
            save({"devices": {self.key_id: {
                "public_key": self.pem, "label": "test", "created": 1,
                "revoked": False, "fcm_token": "",
            }}, "nonces": {}})

    def test_gate_blocks_unsigned_when_devices_active(self) -> None:
        require = self.ns["_hermes_hub_require_device_signature"]
        resp = asyncio.run(require(FakeRequest({}, b"{}")))
        self.assertIsNotNone(resp)
        self.assertEqual(403, resp.status)
        body = b'{"operation":"diagnostic"}'
        ok_resp = asyncio.run(require(FakeRequest(self._headers(body), body)))
        self.assertIsNone(ok_resp)

    def test_generated_gateway_wires_both_gates(self) -> None:
        from hermes_hub_gateway.adapters.hermes.legacy_patcher import _patch_text

        fixture = REPO / "tests/fixtures/hermes-agent-0a62610f1-api_server.py"
        generated, _ = _patch_text(fixture.read_text(encoding="utf-8"))
        compile(generated, str(fixture), "exec")
        self.assertEqual(
            2,
            generated.count("device_error = await _hermes_hub_require_device_signature(request)"),
        )
        for route in (
            '("GET", "/v1/hub/devices", self._handle_hub_devices)',
            '("POST", "/v1/hub/jobs/notify", self._handle_hub_job_notify)',
        ):
            self.assertIn(route, generated)


if __name__ == "__main__":
    unittest.main()
