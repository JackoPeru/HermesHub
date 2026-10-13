"""Safety test per 4 falle manager (nessun import manager.py: fastapi assente in CI).

Carica funzioni pure via AST-exec come tests/test_manager_stray.py.
"""
from __future__ import annotations

import ast
import logging
import os
import shutil
import tempfile
import time
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def _load(names: set[str], extra_ns: dict | None = None) -> tuple[dict, str, ast.Module]:
    src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    wanted = []
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
            isinstance(t, ast.Name) and t.id in names for t in node.targets
        ):
            wanted.append(node)
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name in names:
            wanted.append(node)
    mod = ast.Module(body=wanted, type_ignores=[])
    ns: dict = dict(extra_ns or {})
    exec(compile(mod, "manager_safety", "exec"), ns)  # noqa: S102 - test locale
    return ns, src, tree


def load_matcher_ns() -> dict:
    names = {"_STRAY_BACKEND_BINS", "_STRAY_INTERPRETERS", "_STRAY_BACKEND_SCRIPTS", "_is_stray_runtime_cmd"}
    ns, _, _ = _load(names, {"os": os})
    assert "_is_stray_runtime_cmd" in ns, "matcher non trovato"
    return ns


def load_reboot_ns() -> dict:
    import secrets as secretslib

    names = {"_REBOOT_TICKET_TTL_S", "_reboot_mint_ticket", "_reboot_consume_ticket"}
    # _reboot_tickets va isolato per test: namespace fresco ogni volta.
    src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    wanted = []
    for node in tree.body:
        if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name) and node.target.id == "_reboot_tickets":
            wanted.append(node)
        elif isinstance(node, ast.Assign) and any(
            isinstance(t, ast.Name) and t.id == "_REBOOT_TICKET_TTL_S" for t in node.targets
        ):
            wanted.append(node)
        elif isinstance(node, ast.FunctionDef) and node.name in ("_reboot_mint_ticket", "_reboot_consume_ticket"):
            wanted.append(node)
    mod = ast.Module(body=wanted, type_ignores=[])
    ns: dict = {"time": time, "secrets": secretslib}
    exec(compile(mod, "manager_reboot", "exec"), ns)  # noqa: S102 - test locale
    return ns


def load_anchor_ns() -> dict:
    names = {"_CLEANUP_ANCHOR", "_is_under_anchor"}
    ns, _, _ = _load(names, {"Path": Path})
    assert "_is_under_anchor" in ns, "anchor helper non trovato"
    return ns


class TestStrayMatcher(unittest.TestCase):
    """FIX2: solo basename esatto, mai substring, mai self."""

    @classmethod
    def setUpClass(cls):
        ns = load_matcher_ns()
        cls.match = staticmethod(ns["_is_stray_runtime_cmd"])

    def test_positivi_backend_noti(self):
        self.assertTrue(self.match("tabbyapi --port 8000"))
        self.assertTrue(self.match("/usr/local/bin/llama-mainline --model qwen"))
        self.assertTrue(self.match("/opt/hermes/runtimes/comfyui/venv/bin/python main.py --listen 127.0.0.1"))
        self.assertTrue(self.match("python3 main.py --port 8188"))
        self.assertTrue(self.match("ffmpeg -y -i in.mp4 out.mp4"))

    def test_negativi_substring(self):
        self.assertFalse(self.match(""))
        self.assertFalse(self.match("vim /tmp/comfy-notes.txt"))
        self.assertFalse(self.match("/usr/bin/mycomfybackup --help"))
        self.assertFalse(self.match("/opt/tools/my-tabbyapi-wrapper --x"))
        self.assertFalse(self.match("python /home/user/script.py"))
        self.assertFalse(self.match("firefox --new-tab"))

    def test_self_mai_match(self):
        self.assertFalse(self.match("gpu-manager --port 8643"))
        self.assertFalse(self.match("/opt/hermes/gpu-manager/venv/bin/python /opt/hermes/gpu-manager/manager.py"))

    def test_varianti_path_case(self):
        self.assertTrue(self.match("/OPT/FOO/TABBYAPI --x"))
        self.assertTrue(self.match("C:\\tools\\ffmpeg.exe -y"))
        self.assertFalse(self.match("/tmp/COMFY-notes.txt"))

    def test_strutturale_no_substring_e_pid_guard(self):
        src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
        seg = ast.get_source_segment(src, next(
            n for n in ast.parse(src).body
            if isinstance(n, ast.FunctionDef) and n.name == "_is_stray_runtime_cmd"))
        assert seg is not None
        self.assertNotIn('"comfy" in low', seg)
        self.assertNotIn('"tabbyapi" in low', seg)
        self.assertIn("os.path.basename", seg)
        cleanup = ast.get_source_segment(src, next(
            n for n in ast.parse(src).body
            if isinstance(n, ast.AsyncFunctionDef) and n.name == "cleanup_stray_cuda"))
        assert cleanup is not None
        self.assertIn("pid == 1", cleanup)
        self.assertIn("os.getpid()", cleanup)
        self.assertIn("_is_stray_runtime_cmd(fresh)", cleanup)


class TestRebootTicket(unittest.TestCase):
    """FIX1: ticket single-use 120s, consumo, scadenza, riuso."""

    def test_emissione_consumo(self):
        ns = load_reboot_ns()
        self.assertEqual(ns["_REBOOT_TICKET_TTL_S"], 120.0)
        t = ns["_reboot_mint_ticket"](now=1000.0)
        self.assertEqual(len(t), 32)
        int(t, 16)  # 32hex
        self.assertTrue(ns["_reboot_consume_ticket"](t, now=1001.0))

    def test_riuso_rifiutato(self):
        ns = load_reboot_ns()
        t = ns["_reboot_mint_ticket"](now=2000.0)
        self.assertTrue(ns["_reboot_consume_ticket"](t, now=2001.0))
        self.assertFalse(ns["_reboot_consume_ticket"](t, now=2002.0))

    def test_scadenza_rifiutata(self):
        ns = load_reboot_ns()
        t = ns["_reboot_mint_ticket"](now=3000.0)
        self.assertFalse(ns["_reboot_consume_ticket"]("sconosciuto", now=3001.0))
        self.assertFalse(ns["_reboot_consume_ticket"](t, now=3000.0 + 120.0 + 1))

    def test_strutturale_endpoint(self):
        src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
        funcs = {n.name: n for n in ast.parse(src).body if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))}
        seg = ast.get_source_segment(src, funcs["system_reboot"])
        assert seg is not None
        for token in ("_reboot_mint_ticket", "_reboot_consume_ticket", "409", '"ticket"', "expires_in"):
            self.assertIn(token, seg)
        # Mai ticket nei log: righe log.* senza valore ticket.
        for line in seg.splitlines():
            if "log." in line and "ticket" in line.lower():
                self.fail(f"ticket nei log: {line.strip()}")


class TestCleanupAnchor(unittest.TestCase):
    """FIX3: ancorato /opt/hermes, symlink skippato, niente ignore_errors."""

    def test_dentro_fuori_root(self):
        ns = load_anchor_ns()
        self.assertIn("/opt/hermes", ns["_CLEANUP_ANCHOR"].as_posix())
        fn = ns["_is_under_anchor"]
        with tempfile.TemporaryDirectory() as tmp:
            anchor = Path(tmp) / "anchor"
            anchor.mkdir()
            inside = anchor / "out"
            inside.mkdir()
            outside = Path(tmp) / "elsewhere"
            outside.mkdir()
            self.assertTrue(fn(inside, anchor))
            self.assertFalse(fn(outside, anchor))
            self.assertFalse(fn(Path("/etc/hostname"), anchor))

    def test_symlink_skippato_e_rmtree_rumorosa(self):
        src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
        funcs = {n.name: n for n in ast.parse(src).body if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))}
        seg = ast.get_source_segment(src, funcs["cleanup_old_artifacts"])
        assert seg is not None
        self.assertIn("resolve()", seg)
        self.assertIn("is_symlink()", seg)
        self.assertIn("lstat()", seg)
        self.assertIn("_is_under_anchor", seg)
        # Mai ignore_errors=True nella chiamata rmtree (il commento lo cita).
        calls = [n for n in ast.walk(ast.parse(seg)) if isinstance(n, ast.Call)]
        rmtree_calls = [c for c in calls if isinstance(c.func, ast.Attribute) and c.func.attr == "rmtree"]
        self.assertTrue(rmtree_calls, "rmtree sparita dalla cleanup")
        for call in rmtree_calls:
            self.assertFalse(any(k.arg == "ignore_errors" for k in call.keywords))

    def test_funzionale_symlink_e_abort_fuori_root(self):
        import secrets as _s  # noqa: F401 (namespace completo sotto)

        src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
        tree = ast.parse(src)
        wanted = []
        for node in tree.body:
            if isinstance(node, ast.Assign) and any(
                isinstance(t, ast.Name) and t.id in {"_CLEANUP_ANCHOR", "IMAGE_EXTS", "MEDIA_OUTPUT_DIR"} for t in node.targets):
                wanted.append(node)
            elif isinstance(node, ast.FunctionDef) and node.name in ("_is_under_anchor", "cleanup_old_artifacts"):
                wanted.append(node)
        mod = ast.Module(body=wanted, type_ignores=[])
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            anchor = base / "anchor"
            inp = anchor / "in"
            out = anchor / "out"
            inp.mkdir(parents=True)
            out.mkdir(parents=True)
            ns: dict = {"Path": Path, "CONFIG": {"media": {"input_dir": str(inp)}},
                        "TIME_NOW": time.time(), "log": logging.getLogger("test-cleanup"),
                        "time": time, "shutil": shutil}
            exec(compile(mod, "manager_cleanup", "exec"), ns)  # noqa: S102 - test locale
            ns["_CLEANUP_ANCHOR"] = anchor
            ns["MEDIA_OUTPUT_DIR"] = str(out)
            ns["CONFIG"] = {"media": {"input_dir": str(inp)}}
            # File vecchio in output: va rimosso; symlink: va skippato.
            old = out / "job123"
            old.mkdir()
            (old / "x.png").write_bytes(b"1")
            target = base / "secret.txt"
            target.write_text("segreto")
            link = out / "evil-link"
            try:
                link.symlink_to(target)
                have_link = True
            except OSError:
                have_link = False
            old_t = time.time() - 8 * 86400
            os.utime(old, (old_t, old_t))
            os.utime(old / "x.png", (old_t, old_t))
            # lstat del link recente o vecchio non importa: sempre skip.
            removed = ns["cleanup_old_artifacts"](max_age_days=7)
            if have_link:
                self.assertTrue(link.is_symlink(), "symlink cancellato: vietato")
            self.assertEqual(target.read_text(), "segreto")
            self.assertGreaterEqual(removed, 1)
            # Abort fuori anchor: niente cancellazioni.
            ns["CONFIG"] = {"media": {"input_dir": str(base / "fuori")}}
            (base / "fuori").mkdir(exist_ok=True)
            victim = anchor / "out" / "victim"
            victim.mkdir(exist_ok=True)
            os.utime(victim, (old_t, old_t))
            # Forza root fuori anchor puntando MEDIA_OUTPUT_DIR fuori.
            ns["MEDIA_OUTPUT_DIR"] = str(base / "fuori")
            got = ns["cleanup_old_artifacts"](max_age_days=7)
            self.assertEqual(got, 0)
            self.assertTrue(victim.exists(), "abort fallito: cancellato fuori policy")


class TestDisplayAcl(unittest.TestCase):
    """FIX4: takeover/release con utente non-locale; letture invariate."""

    def test_takeover_release_richiedono_utente(self):
        src = (REPO / "gpu-manager" / "display_bridge.py").read_text(encoding="utf-8")
        tree = ast.parse(src)
        funcs: dict[str, str] = {}
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name in (
                    "display_takeover", "display_release", "display_status", "display_frame", "display_ticket"):
                seg = ast.get_source_segment(src, node)
                assert seg is not None
                funcs[node.name] = seg
        self.assertIn("_require_user_control", funcs["display_takeover"])
        self.assertIn("_require_user_control", funcs["display_release"])
        self.assertIn("Depends(_require_user_control)", funcs["display_takeover"])
        self.assertNotIn("_require_user_control", funcs["display_status"])
        self.assertNotIn("_require_user_control", funcs["display_frame"])
        self.assertIn("def _require_user_control", src)
        self.assertIn('sys.modules.get("__main__")', src)


if __name__ == "__main__":
    unittest.main()
