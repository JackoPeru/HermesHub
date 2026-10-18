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


def _all_defs(tree: ast.Module) -> dict[str, ast.AST]:
    """Tutte le def (anche annidate come _hcid_submit), per nome."""
    found: dict[str, ast.AST] = {}
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            found.setdefault(node.name, node)
    return found


class TestDbLock(unittest.TestCase):
    """FIX1: ogni uso del db condiviso sotto `with _db_lock:` (fail-closed)."""

    @classmethod
    def setUpClass(cls):
        cls.src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
        cls.tree = ast.parse(cls.src)
        cls.funcs = _all_defs(cls.tree)

    def test_lock_globale_threading(self):
        self.assertIn("import threading", self.src)
        self.assertIn("_db_lock = threading.Lock()", self.src)

    def test_init_conn_sotto_lock(self):
        seg = ast.get_source_segment(self.src, self.funcs["_db_conn"])
        assert seg is not None
        self.assertIn("with _db_lock:", seg)

    def test_ogni_execute_sotto_lock(self):
        senza = []
        for name, node in self.funcs.items():
            seg = ast.get_source_segment(self.src, node)
            if seg and ".execute(" in seg and "_db_lock" not in seg:
                senza.append(name)
        self.assertEqual([], senza)

    def test_helper_crud_sotto_lock(self):
        for name in ("_persist_desired", "create_job", "get_job", "update_job", "queued_jobs"):
            with self.subTest(helper=name):
                seg = ast.get_source_segment(self.src, self.funcs[name])
                assert seg is not None
                self.assertIn("with _db_lock:", seg)


class TestTrainingWallclock(unittest.TestCase):
    """FIX2: default 12h generoso + enforcement con cancel onesto."""

    def test_default_dodici_ore(self):
        ns, _, _ = _load({"DEFAULT_CONFIG"})
        self.assertEqual(12, ns["DEFAULT_CONFIG"]["character_id"]["max_wallclock_hours"])

    def test_limite_default_e_override(self):
        ns, _, _ = _load({"_character_wallclock_limit_s"}, {"CONFIG": {}})
        self.assertEqual(12 * 3600.0, ns["_character_wallclock_limit_s"]())
        ns2, _, _ = _load(
            {"_character_wallclock_limit_s"},
            {"CONFIG": {"character_id": {"max_wallclock_hours": 2}}},
        )
        self.assertEqual(2 * 3600.0, ns2["_character_wallclock_limit_s"]())
        ns3, _, _ = _load(
            {"_character_wallclock_limit_s"},
            {"CONFIG": {"character_id": {"max_wallclock_hours": "rotto"}}},
        )
        self.assertEqual(12 * 3600.0, ns3["_character_wallclock_limit_s"]())

    def test_predicato_puro(self):
        ns, _, _ = _load({"_training_over_wallclock"})
        over = staticmethod(ns["_training_over_wallclock"])
        self.assertTrue(over(1000.0, 1000.0 + 13 * 3600, 12))
        self.assertFalse(over(1000.0, 1000.0 + 3600, 12))
        self.assertFalse(over("rotto", 1000.0, 12))
        self.assertFalse(over(None, 1000.0, 12))

    def test_enforce_cancella_con_log_onesto(self):
        src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
        funcs = _all_defs(ast.parse(src))
        seg = ast.get_source_segment(src, funcs["_enforce_training_wallclock"])
        assert seg is not None
        self.assertIn("superato wall-clock", seg)
        self.assertIn("cancel", seg)
        self.assertIn('status="cancelled"', seg)

    def test_worker_e_submit_chiamano_enforce(self):
        src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
        funcs = _all_defs(ast.parse(src))
        for name in ("drive_auto_once", "_hcid_submit"):
            with self.subTest(punto=name):
                seg = ast.get_source_segment(src, funcs[name])
                assert seg is not None
                self.assertIn("_enforce_training_wallclock", seg)


class TestWorkflowCapsRaw(unittest.TestCase):
    """FIX3: i workflow raw rispettano cap area (max 480p/reframe916) e length (rifiuto onesto)."""

    @classmethod
    def setUpClass(cls):
        cls.ns, _, _ = _load(
            {"validate_workflow", "_WORKFLOW_MAX_AREA", "_WORKFLOW_MAX_LENGTH",
             "ALLOWED_NODE_CLASSES"},
        )
        cls.valid = staticmethod(cls.ns["validate_workflow"])

    def test_costanti_come_preset(self):
        # Cap area = max(480p, reframe916 768x1344=1032192): il preset
        # reframe916 alza il tetto oltre 480x854 (409920), come sul server.
        self.assertEqual(1032192, self.ns["_WORKFLOW_MAX_AREA"])
        self.assertEqual(3600, self.ns["_WORKFLOW_MAX_LENGTH"])

    def test_area_480p_pass(self):
        wf = {"1": {"class_type": "EmptyLatentImage",
                    "inputs": {"width": 854, "height": 480, "batch_size": 1}}}
        self.assertEqual("", self.valid(wf))

    def test_area_reframe916_al_cap_pass(self):
        wf = {"1": {"class_type": "EmptyLatentImage",
                    "inputs": {"width": 768, "height": 1344, "batch_size": 1}}}
        self.assertEqual("", self.valid(wf))

    def test_area_oltre_rifiutata_onesta(self):
        wf = {"1": {"class_type": "EmptyLatentImage",
                    "inputs": {"width": 2048, "height": 2048, "batch_size": 1}}}
        err = self.valid(wf)
        self.assertNotEqual("", err)
        self.assertIn("854x480", err)

    def test_length_oltre_max_rifiutata(self):
        wf = {"1": {"class_type": "MiniMaxH3ImageToVideo",
                    "inputs": {"width": 854, "height": 480, "length": 5000}}}
        err = self.valid(wf)
        self.assertNotEqual("", err)
        self.assertIn("3600", err)

    def test_valori_non_numerici_mai_crash(self):
        wf = {"1": {"class_type": "EmptyLatentImage",
                    "inputs": {"width": "{{WIDTH}}", "height": 480}}}
        self.assertEqual("", self.valid(wf))


class TestSnapVideoDims(unittest.TestCase):
    """I workflow H3 raw snappano width/height a multipli di 32 (VAE 16x + patch 2x)."""

    @classmethod
    def setUpClass(cls):
        cls.ns, _, _ = _load({"_VIDEO_DIM_MULTIPLE", "snap_video_dims"})
        cls.snap = staticmethod(cls.ns["snap_video_dims"])

    def _wf(self, w, h):
        return {"4": {"class_type": "MiniMaxH3ImageToVideo",
                      "inputs": {"width": w, "height": h, "length": 107}}}

    def test_multiplo_costante_32(self):
        self.assertEqual(32, self.ns["_VIDEO_DIM_MULTIPLE"])

    def test_snap_540x960(self):
        wf = self._wf(540, 960)
        self.assertEqual(["4"], self.snap(wf))
        self.assertEqual(512, wf["4"]["inputs"]["width"])
        self.assertEqual(960, wf["4"]["inputs"]["height"])

    def test_multiplo16_non32_snappato(self):
        # 528 e' multiplo di 16 ma non di 32: latente 33 (dispari) -> crash.
        wf = self._wf(528, 960)
        self.assertEqual(["4"], self.snap(wf))
        self.assertEqual(512, wf["4"]["inputs"]["width"])

    def test_multipli_32_invariati(self):
        wf = self._wf(512, 960)
        self.assertEqual([], self.snap(wf))
        self.assertEqual(512, wf["4"]["inputs"]["width"])

    def test_immagine_senza_h3_mai_toccata(self):
        wf = {"1": {"class_type": "EmptyLatentImage",
                    "inputs": {"width": 1000, "height": 1000}}}
        self.assertEqual([], self.snap(wf))
        self.assertEqual(1000, wf["1"]["inputs"]["width"])

    def test_placeholder_non_numerico_mai_crash(self):
        wf = {"4": {"class_type": "MiniMaxH3ReferenceToVideo",
                    "inputs": {"width": "{{WIDTH}}", "height": 960}}}
        self.assertEqual([], self.snap(wf))

    def test_workflow_non_dict_mai_crash(self):
        self.assertEqual([], self.snap(None))
        self.assertEqual([], self.snap({"4": "non-dict"}))


if __name__ == "__main__":
    unittest.main()
