"""Test del bridge laya (canale diretto: NL -> laya -> /force).

`laya_bridge` e' solo stdlib: si importa direttamente. Si testa la logica pura
(classify/parse/compose/merge/status) e `handle_command` con I/O finto
(funzione http iniettata), senza rete ne' server. Chiude con un controllo che il
modulo NON importi fastapi e che non avvii nulla all'import.
"""
from __future__ import annotations

import ast
import subprocess
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "gpu-manager"))

import laya_bridge as lb  # noqa: E402


def snap_status(**over):
    base = {"desired_mode": "LLM", "current_state": "LLM_READY", "queue_length": 0,
            "llm_loaded": True, "llm_online": True, "media_online": False,
            "llm_model": "qwen", "llm_vram_mb": 14000.0, "media_vram_mb": 0.0,
            "media_loaded_models": []}
    base.update(over)
    return base


def make_http(*, laya_choice=None, laya_conf=0.9, force_status=200,
              force_body=None, status_status=200, status_body=None,
              fail_force_times=0):
    """Fabbrica una `http` finta che registra le chiamate e risponde a comando."""
    calls: list = []
    state = {"force_fail": fail_force_times}

    def http(method, url, payload=None, headers=None, timeout=15):
        calls.append({"method": method, "url": url, "payload": payload,
                      "headers": headers})
        if url.endswith("/api/decide"):
            if laya_choice is None:
                return 0, "laya down"
            return 200, {"answers": {lb.LAYA_QID: {
                "choice": laya_choice, "confidence": laya_conf}}}
        if url.endswith("/status"):
            return status_status, (status_body if status_body is not None
                                   else snap_status())
        if url.endswith("/force"):
            if state["force_fail"] > 0:
                state["force_fail"] -= 1
                return 0, "refused"
            return force_status, (force_body if force_body is not None
                                  else {"ok": True, "mode": payload["mode"],
                                        "state": "LLM_LOADING"})
        return 404, {}

    http.calls = calls
    return http


_noop_runner = lambda cmd, timeout=60: (0, "")  # noqa: E731
_noop_sleep = lambda s: None  # noqa: E731


class TestClassifyText(unittest.TestCase):
    def test_modi(self):
        cases = {
            "metti l'llm ready": "LLM",
            "carica il modello": "LLM",
            "riavvia tabby": "LLM",
            "attiva comfy": "MEDIA",
            "genera un video": "MEDIA",
            "voglio delle immagini": "MEDIA",
            "metti in auto": "AUTO",
            "modalita automatica": "AUTO",
            "comfy diretto": "DIRECT",
            "passa in direct": "DIRECT",
        }
        for text, expected in cases.items():
            with self.subTest(text=text):
                v = lb.classify_text(text)
                self.assertEqual("mode", v["intent"])
                self.assertEqual(expected, v["mode"])

    def test_direct_ha_priorita_su_media(self):
        self.assertEqual("DIRECT", lb.classify_text("comfy diretto")["mode"])

    def test_stato(self):
        for text in ("che succede?", "come sta il sistema", "dammi lo stato",
                     "riassunto della situazione"):
            with self.subTest(text=text):
                self.assertEqual("status", lb.classify_text(text)["intent"])

    def test_sconosciuto_e_input_ostile(self):
        for bad in ("", "ciao", 42, None, [], {}):
            with self.subTest(bad=bad):
                v = lb.classify_text(bad)  # type: ignore[arg-type]
                self.assertIn(v["intent"], ("unknown", "status"))
                self.assertIsInstance(v["intent"], str)


class TestParseAndIntent(unittest.TestCase):
    def test_parse_ok(self):
        data = {"answers": {lb.LAYA_QID: {"choice": "llm_ready", "confidence": 0.83}}}
        self.assertEqual(("llm_ready", 0.83), lb.parse_laya_choice(data))

    def test_parse_ostile(self):
        for bad in (None, 42, {}, {"answers": None}, {"answers": {}},
                    {"answers": {lb.LAYA_QID: None}},
                    {"answers": {lb.LAYA_QID: {"choice": 5, "confidence": "x"}}}):
            with self.subTest(bad=bad):
                choice, conf = lb.parse_laya_choice(bad)  # type: ignore[arg-type]
                self.assertIsInstance(choice, str)
                self.assertIsInstance(conf, float)

    def test_parse_confidence_non_numerica(self):
        data = {"answers": {lb.LAYA_QID: {"choice": "status", "confidence": "boh"}}}
        self.assertEqual(("status", 0.0), lb.parse_laya_choice(data))

    def test_intent_map(self):
        self.assertEqual(("mode", "LLM"), lb.intent_from_choice("llm_ready"))
        self.assertEqual(("mode", "MEDIA"), lb.intent_from_choice("activate_comfy"))
        self.assertEqual(("mode", "AUTO"), lb.intent_from_choice("auto_mode"))
        self.assertEqual(("mode", "DIRECT"), lb.intent_from_choice("direct_mode"))
        self.assertEqual(("status", None), lb.intent_from_choice("status"))
        self.assertEqual((None, None), lb.intent_from_choice("none"))
        self.assertEqual((None, None), lb.intent_from_choice("boh"))
        self.assertEqual((None, None), lb.intent_from_choice(None))


class TestComposeAndStatus(unittest.TestCase):
    def test_compose_stages(self):
        self.assertIn("LLM", lb.compose_reply("LLM", {"stage": "forced"}))
        self.assertIn("riavviato", lb.compose_reply("MEDIA",
                                                    {"stage": "forced_after_restart"}))
        self.assertIn("LLM", lb.compose_reply("LLM",
                                              {"stage": "forced_after_llm_restart"}))
        self.assertTrue(lb.compose_reply("LLM", {"stage": "auth"}))
        self.assertTrue(lb.compose_reply("LLM", {"stage": "bad_mode"}))
        self.assertTrue(lb.compose_reply("LLM", {"stage": "manager_down"}))
        self.assertTrue(lb.compose_reply("LLM", {}))

    def test_summarize_status(self):
        text = lb.summarize_status(snap_status(current_state="MEDIA_BUSY", queue_length=2))
        self.assertIn("MEDIA_BUSY", text)
        self.assertIn("2", text)
        self.assertTrue(lb.summarize_status(None))
        self.assertTrue(lb.summarize_status("junk"))  # type: ignore[arg-type]


class TestDeepMerge(unittest.TestCase):
    def test_merge_profondo(self):
        base = {"a": 1, "n": {"x": 1, "y": 2}}
        out = lb._deep_merge(base, {"n": {"y": 9, "z": 3}, "b": 2})
        self.assertEqual({"a": 1, "b": 2, "n": {"x": 1, "y": 9, "z": 3}}, out)
        self.assertEqual({"x": 1, "y": 2}, base["n"])  # base intatta

    def test_yaml_scalar_fallback(self):
        text = "# commento\nport: 8643\napi_key: \"segreto\"\ntrusted_keys: []\n"
        self.assertEqual("segreto", lb._yaml_scalar(text, "api_key"))
        self.assertEqual("8643", lb._yaml_scalar(text, "port"))
        self.assertEqual("", lb._yaml_scalar(text, "assente"))


class TestHandleCommand(unittest.TestCase):
    def test_laya_decide_llm(self):
        http = make_http(laya_choice="llm_ready", laya_conf=0.9)
        out = lb.handle_command(lb.DEFAULT_CONFIG, "fai partire il cervello",
                                http=http, runner=_noop_runner, sleeper=_noop_sleep)
        self.assertTrue(out["ok"])
        self.assertEqual("laya", out["backend"])
        self.assertEqual("LLM", out["mode"])
        forces = [c for c in http.calls if c["url"].endswith("/force")]
        self.assertEqual(1, len(forces))
        self.assertEqual("LLM", forces[0]["payload"]["mode"])

    def test_fallback_regole_se_laya_giu(self):
        http = make_http(laya_choice=None)
        out = lb.handle_command(lb.DEFAULT_CONFIG, "attiva comfy",
                                http=http, runner=_noop_runner, sleeper=_noop_sleep)
        self.assertEqual("rules", out["backend"])
        self.assertEqual("MEDIA", out["mode"])

    def test_soglia_bassa_torna_alle_regole(self):
        http = make_http(laya_choice="llm_ready", laya_conf=0.1)
        out = lb.handle_command(lb.DEFAULT_CONFIG, "attiva comfy",
                                http=http, runner=_noop_runner, sleeper=_noop_sleep)
        self.assertEqual("rules", out["backend"])
        self.assertEqual("MEDIA", out["mode"])

    def test_status_non_forza(self):
        http = make_http(laya_choice="status", laya_conf=0.9)
        out = lb.handle_command(lb.DEFAULT_CONFIG, "come sta?",
                                http=http, runner=_noop_runner, sleeper=_noop_sleep)
        self.assertEqual("status", out["intent"])
        self.assertFalse(any(c["url"].endswith("/force") for c in http.calls))

    def test_execute_false_non_forza(self):
        http = make_http(laya_choice="auto_mode", laya_conf=0.9)
        out = lb.handle_command(lb.DEFAULT_CONFIG, "metti in auto", execute=False,
                                http=http, runner=_noop_runner, sleeper=_noop_sleep)
        self.assertTrue(out["ok"])
        self.assertEqual("AUTO", out["mode"])
        self.assertFalse(any(c["url"].endswith("/force") for c in http.calls))

    def test_testo_vuoto(self):
        out = lb.handle_command(lb.DEFAULT_CONFIG, "   ")
        self.assertFalse(out["ok"])
        self.assertEqual("unknown", out["intent"])

    def test_manager_giu_riavvia_e_riprova(self):
        http = make_http(laya_choice="llm_ready", laya_conf=0.9, fail_force_times=1)
        calls = []
        out = lb.handle_command(
            lb.DEFAULT_CONFIG, "metti llm ready", http=http,
            runner=lambda cmd, timeout=60: (calls.append(cmd), (0, ""))[1],
            sleeper=_noop_sleep)
        self.assertTrue(out["ok"])
        self.assertEqual("forced_after_restart", out["manager"]["stage"])
        # Una restart del servizio manager e' stata tentata.
        self.assertTrue(any("restart" in c and "hermes-gpu-manager.service" in c
                            for c in calls))
        forces = [c for c in http.calls if c["url"].endswith("/force")]
        self.assertEqual(2, len(forces))

    def test_auth_fallita(self):
        http = make_http(laya_choice="llm_ready", laya_conf=0.9, force_status=401)
        out = lb.handle_command(lb.DEFAULT_CONFIG, "metti llm ready",
                                http=http, runner=_noop_runner, sleeper=_noop_sleep)
        self.assertFalse(out["ok"])
        self.assertEqual("auth", out["manager"]["stage"])

    def test_llm_riavvio_diretto_se_manager_non_torna(self):
        # Force fallisce sempre e /status non torna mai: per LLM, ultima
        # spiaggia = restart diretto del servizio tabby.
        http = make_http(laya_choice="llm_ready", laya_conf=0.9,
                         force_status=502, status_status=0)
        calls = []
        out = lb.handle_command(
            lb.DEFAULT_CONFIG, "metti llm ready", http=http,
            runner=lambda cmd, timeout=60: (calls.append(cmd), (0, ""))[1],
            sleeper=_noop_sleep)
        self.assertTrue(out["ok"])
        self.assertEqual("forced_after_llm_restart", out["manager"]["stage"])
        self.assertTrue(any("hermes-tabby.service" in c for c in calls))


class TestCallerRoles(unittest.TestCase):
    """Ruoli ingresso: app (NL completo) vs agent (solo submit)."""

    def cfg(self, **over):
        base = dict(lb.DEFAULT_CONFIG)
        base.update(over)
        return base

    def test_app_role_wins_over_agent(self):
        cfg = self.cfg(app_key="app1", agent_keys=["ag1"])
        self.assertEqual(lb.caller_role(cfg, "Bearer app1"), "app")
        self.assertEqual(lb.caller_role(cfg, "Bearer ag1"), "agent")
        self.assertEqual(lb.caller_role(cfg, "Bearer no"), "")
        self.assertEqual(lb.caller_role(cfg, ""), "")

    def test_no_keys_no_role(self):
        self.assertEqual(lb.caller_role(self.cfg(), ""), "")


class TestRebootIntent(unittest.TestCase):
    def test_riavvia_manager_rules(self):
        out = lb.handle_command(
            dict(lb.DEFAULT_CONFIG), "riavvia il manager",
            http=make_http(), runner=_noop_runner, sleeper=_noop_sleep)
        self.assertEqual("reboot", out["intent"])

    def test_reboot_executes_restart(self):
        calls = []
        http = make_http()
        out = lb.handle_command(
            dict(lb.DEFAULT_CONFIG), "riavvia il manager", http=http,
            runner=lambda cmd, timeout=60: (calls.append(cmd), (0, ""))[1],
            sleeper=_noop_sleep)
        self.assertTrue(out["ok"])
        self.assertTrue(any("restart" in c and "hermes-gpu-manager.service" in c for c in calls))

    def test_reboot_preview_no_execute(self):
        out = lb.handle_command(
            dict(lb.DEFAULT_CONFIG), "riavvia il manager", execute=False,
            http=make_http(), runner=_noop_runner, sleeper=_noop_sleep)
        self.assertTrue(out["ok"])
        self.assertEqual("reboot", out["intent"])
        self.assertFalse(out.get("executed", True))


class TestSubmitPreset(unittest.TestCase):
    def test_allowlist_blocks_unknown_and_raw(self):
        http = make_http()
        bad = lb.submit_preset_job(dict(lb.DEFAULT_CONFIG), "rm_rf", {}, http=http)
        self.assertFalse(bad["ok"])
        self.assertEqual(400, bad["status"])
        self.assertFalse(any("/jobs/" in c["url"] for c in http.calls))

    def test_forwards_preset_to_manager(self):
        calls = []
        logged = {}

        def http(method, url, payload=None, headers=None, timeout=15):
            calls.append({"method": method, "url": url, "payload": payload})
            return 202, {"job_id": "abc123", "status": "queued", "preset": "journey_video_reframe916"}

        out = lb.submit_preset_job(
            dict(lb.DEFAULT_CONFIG), "journey_video_reframe916",
            {"prompt": "ciao"}, http=http)
        self.assertTrue(out["ok"])
        self.assertEqual("abc123", out["job_id"])
        self.assertTrue(calls[0]["url"].endswith("/jobs/video"))
        self.assertNotIn("workflow", calls[0]["payload"])

    def test_prompt_cap(self):
        out = lb.submit_preset_job(
            dict(lb.DEFAULT_CONFIG), "create_image",
            {"prompt": "x" * 5000}, http=make_http())
        self.assertFalse(out["ok"])
        self.assertEqual(413, out["status"])


class TestSplitCommands(unittest.TestCase):
    def test_split_congiunzioni_e_punteggiatura(self):
        self.assertEqual(["carica l'llm", "metti in auto"],
                         lb.split_commands("carica l'llm e metti in auto"))
        self.assertEqual(["attiva comfy", "dimmi lo stato"],
                         lb.split_commands("attiva comfy, dimmi lo stato"))
        self.assertEqual(["a", "b", "c"], lb.split_commands("a; b\nc"))
        self.assertEqual(["passa a comfy"], lb.split_commands("passa a comfy"))
        self.assertEqual([], lb.split_commands("   "))
        self.assertEqual([], lb.split_commands(None))

    def test_split_rispetta_limite(self):
        parts = lb.split_commands("a e b e c e d e e", limit=3)
        self.assertEqual(3, len(parts))
        self.assertEqual("a", parts[0])


class TestMultiIntent(unittest.TestCase):
    def test_due_modi_in_un_messaggio(self):
        http = make_http(laya_choice=None,
                         status_body=snap_status(media_online=True))
        out = lb.handle_command(lb.DEFAULT_CONFIG, "attiva comfy e metti in auto",
                                http=http, runner=_noop_runner, sleeper=_noop_sleep)
        self.assertEqual("multi", out["intent"])
        self.assertEqual(["MEDIA", "AUTO"], out["modes"])
        self.assertEqual("AUTO", out["mode"])
        self.assertTrue(out["ok"])
        forces = [c["url"] for c in http.calls if c["url"].endswith("/force")]
        self.assertEqual(2, len(forces))
        self.assertEqual(2, len(out["actions"]))

    def test_multi_con_stato(self):
        http = make_http(laya_choice=None,
                         status_body=snap_status(media_online=True))
        out = lb.handle_command(lb.DEFAULT_CONFIG, "attiva comfy e dimmi lo stato",
                                http=http, runner=_noop_runner, sleeper=_noop_sleep)
        self.assertEqual("multi", out["intent"])
        self.assertIn("Manager in", out["reply"])
        self.assertTrue(out["ok"])

    def test_multi_sconosciuto_segnalato(self):
        http = make_http(laya_choice=None,
                         status_body=snap_status(media_online=True))
        out = lb.handle_command(lb.DEFAULT_CONFIG, "attiva comfy e canta una canzone",
                                http=http, runner=_noop_runner, sleeper=_noop_sleep)
        self.assertEqual("multi", out["intent"])
        # L'azione capita riesce (ok=True), la parte ignota e' segnalata.
        self.assertTrue(out["ok"])
        self.assertIn("Non ho capito", out["reply"])
        self.assertIn("Comfy attivo", out["reply"])
        self.assertFalse(any(a["intent"] == "unknown" and a["ok"]
                             for a in out["actions"]))

    def test_multi_solo_sconosciuti_fallisce(self):
        http = make_http(laya_choice=None)
        out = lb.handle_command(lb.DEFAULT_CONFIG, "canta una canzone e balla",
                                http=http, runner=_noop_runner, sleeper=_noop_sleep)
        self.assertFalse(out["ok"])

    def test_multi_dry_run_non_forza(self):
        http = make_http(laya_choice=None)
        out = lb.handle_command(lb.DEFAULT_CONFIG, "attiva comfy e metti in auto",
                                execute=False, http=http,
                                runner=_noop_runner, sleeper=_noop_sleep)
        self.assertTrue(out["ok"])
        self.assertEqual(["MEDIA", "AUTO"], out["modes"])
        self.assertFalse(any(c["url"].endswith("/force") for c in http.calls))
        self.assertIn("MEDIA", out["reply"])

    def test_singolo_resta_identico(self):
        http = make_http(laya_choice=None,
                         status_body=snap_status(media_online=True))
        out = lb.handle_command(lb.DEFAULT_CONFIG, "attiva comfy",
                                http=http, runner=_noop_runner, sleeper=_noop_sleep)
        self.assertEqual("mode", out["intent"])
        self.assertEqual("MEDIA", out["mode"])
        self.assertEqual(["MEDIA"], out["modes"])
        self.assertEqual(1, len(out["actions"]))
        self.assertIn("Comfy attivo", out["reply"])


class TestWaitBackend(unittest.TestCase):
    def test_media_pronto_subito(self):
        http = make_http(laya_choice=None,
                         status_body=snap_status(media_online=True))
        ready, status = lb.wait_backend(lb.DEFAULT_CONFIG, "MEDIA", http, _noop_sleep)
        self.assertTrue(ready)
        self.assertTrue(status["media_online"])

    def test_media_mai_pronto(self):
        http = make_http(laya_choice=None, status_body=snap_status())
        ready, _ = lb.wait_backend(dict(lb.DEFAULT_CONFIG, backend_wait_polls=3),
                                   "MEDIA", http, _noop_sleep)
        self.assertFalse(ready)

    def test_auto_non_attende(self):
        http = make_http(laya_choice=None)
        before = len(http.calls)
        ready, status = lb.wait_backend(lb.DEFAULT_CONFIG, "AUTO", http, _noop_sleep)
        self.assertTrue(ready)
        self.assertIsNone(status)
        self.assertEqual(before, len(http.calls))


class TestDisambiguazioneComfy(unittest.TestCase):
    def test_passa_a_comfy_e_media(self):
        for text in ("passa a comfy", "metti comfy", "usa comfy per generare"):
            with self.subTest(text=text):
                self.assertEqual("MEDIA", lb.classify_text(text)["mode"])

    def test_diretto_esplicito_resta_direct(self):
        for text in ("comfy diretto", "usa il direct", "passa in direct",
                     "metti diretto"):
            with self.subTest(text=text):
                self.assertEqual("DIRECT", lb.classify_text(text)["mode"])

    def test_laya_istruzioni_discriminano(self):
        instructions = lb.LAYA_QUESTIONS[lb.LAYA_QID]["instructions"]
        self.assertIn("diretto", instructions)
        criteria = lb.LAYA_QUESTIONS[lb.LAYA_QID]["criteria"]
        self.assertIn("diretto", criteria["direct_mode"])
        self.assertNotIn("diretto", criteria["activate_comfy"])


class TestSummarizeModelli(unittest.TestCase):
    def test_riassunto_con_modello_video(self):
        text = lb.summarize_status(snap_status(
            current_state="DIRECT", desired_mode="DIRECT",
            media_loaded_models=["minimax_h3_fl2va_pruned.safetensors"],
            llm_loaded=False))
        self.assertIn("DIRECT", text)
        self.assertIn("minimax_h3_fl2va_pruned.safetensors", text)
        self.assertNotIn("llm_loaded", text)

    def test_riassunto_con_llm(self):
        text = lb.summarize_status(snap_status())
        self.assertIn("LLM", text)
        self.assertIn("qwen", text)


class TestNoFastapiImport(unittest.TestCase):
    def test_import_interprete_fresco(self):
        gp = str(REPO / "gpu-manager")
        code = (
            "import sys;"
            f"sys.path.insert(0, {gp!r});"
            "import laya_bridge;"
            "assert 'fastapi' not in sys.modules, 'fastapi importato';"
            "print(laya_bridge.classify_text('attiva comfy')['mode'])"
        )
        proc = subprocess.run([sys.executable, "-c", code], capture_output=True,
                              text=True, cwd=str(REPO))
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("MEDIA", proc.stdout.strip())

    def test_sorgente_senza_fastapi(self):
        text = (REPO / "gpu-manager" / "laya_bridge.py").read_text(encoding="utf-8")
        for node in ast.walk(ast.parse(text)):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    self.assertNotEqual("fastapi", alias.name.split(".")[0])
                    self.assertNotEqual("uvicorn", alias.name.split(".")[0])
            elif isinstance(node, ast.ImportFrom):
                self.assertNotIn((node.module or "").split(".")[0], ("fastapi", "uvicorn"))


if __name__ == "__main__":
    unittest.main()
