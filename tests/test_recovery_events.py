"""Test Fase 0 del supervisore: event store + vocabolario (nessun fastapi).

Importa `recovery.*` mettendo gpu-manager su sys.path (come test_character_id):
il package e pura stdlib e non deve mai dipendere da fastapi. Un test lancia un
interprete fresco per provarlo davvero. Aggiunge anche un'asserzione a livello
di sorgente (come test_manager_safety) che `set_state` in manager.py registri un
evento, senza importare manager.py (fastapi/yaml assenti in CI).
"""
from __future__ import annotations

import ast
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "gpu-manager"))

from recovery import (  # noqa: E402 (sys.path setup sopra)
    ALLOWED_ACTIONS,
    SEVERITIES,
    Action,
    Event,
    EventStore,
)


class TestVocabulary(unittest.TestCase):
    def test_severities_congelate(self):
        self.assertEqual(("debug", "info", "warning", "error", "critical"), SEVERITIES)

    def test_allowlist_congelata(self):
        self.assertEqual(
            ("restore_llm", "cooldown_media", "cancel_stale_jobs", "retry_job", "notify_user"),
            ALLOWED_ACTIONS,
        )

    def test_action_valida(self):
        action = Action(name="restore_llm", params={"context": "watchdog"},
                        reason="llm down", confidence=0.9, source="rules")
        self.assertEqual("restore_llm", action.name)
        self.assertEqual({"context": "watchdog"}, action.params)
        self.assertEqual(0.9, action.confidence)

    def test_action_nome_non_ammesso_solleva(self):
        with self.assertRaises(ValueError):
            Action(name="rm -rf /")
        with self.assertRaises(ValueError):
            Action(name="restart_everything")

    def test_action_confidence_fuori_range_solleva(self):
        with self.assertRaises(ValueError):
            Action(name="retry_job", confidence=1.5)
        with self.assertRaises(ValueError):
            Action(name="retry_job", confidence=-0.01)

    def test_action_default(self):
        action = Action(name="notify_user")
        self.assertEqual({}, action.params)
        self.assertEqual("", action.reason)
        self.assertEqual(0.0, action.confidence)
        self.assertEqual("", action.source)


class TestEventStoreBase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.db_path = str(Path(self._tmp.name) / "events.db")
        self.store = EventStore(self.db_path)

    def tearDown(self):
        self.store.close()
        self._tmp.cleanup()


class TestEventStoreRecord(TestEventStoreBase):
    def test_record_ritorna_event(self):
        event = self.store.record("state", "LLM_READY->GPU_FREE", data={"from": "LLM_READY"})
        self.assertIsInstance(event, Event)
        self.assertEqual("state", event.kind)
        self.assertEqual("info", event.severity)
        self.assertEqual("LLM_READY->GPU_FREE", event.message)
        self.assertEqual({"from": "LLM_READY"}, event.data)

    def test_recent_newest_first(self):
        self.store.record("a", "uno")
        time.sleep(0.02)
        self.store.record("b", "due")
        time.sleep(0.02)
        self.store.record("c", "tre")
        messages = [e.message for e in self.store.recent()]
        self.assertEqual(["tre", "due", "uno"], messages)

    def test_filtro_kind(self):
        self.store.record("llm", "unload accepted")
        self.store.record("job", "job x queued")
        self.store.record("llm", "restore ok")
        got = self.store.recent(kind="llm")
        self.assertEqual(["restore ok", "unload accepted"], [e.message for e in got])
        self.assertTrue(all(e.kind == "llm" for e in got))

    def test_filtro_since(self):
        first = self.store.record("a", "vecchio")
        time.sleep(0.02)
        second = self.store.record("b", "nuovo")
        assert first is not None and second is not None
        mid = (first.ts + second.ts) / 2
        got = self.store.recent(since=mid)
        self.assertEqual(["nuovo"], [e.message for e in got])
        # since a zero: tutto torna.
        self.assertEqual(2, len(self.store.recent(since=0.0)))

    def test_limit(self):
        for i in range(5):
            self.store.record("k", f"m{i}")
        self.assertEqual(2, len(self.store.recent(limit=2)))

    def test_ordine_a_parita_di_ts(self):
        # Parita di ts: l'ordine e per id di inserimento, piu recente prima.
        ts_backup = time.time
        try:
            time.time = lambda: 1000.0  # type: ignore[assignment]
            self.store.record("k", "primo")
            self.store.record("k", "secondo")
        finally:
            time.time = ts_backup  # type: ignore[assignment]
        self.assertEqual(["secondo", "primo"], [e.message for e in self.store.recent()])

    def test_severity_fuori_vocabolario_normalizzata(self):
        event = self.store.record("k", "m", severity="catastrofico")
        assert event is not None
        self.assertEqual("info", event.severity)
        self.assertEqual("info", self.store.recent()[0].severity)

    def test_data_non_serializzabile_non_solleva(self):
        obj = object()
        event = self.store.record("k", "m", data={"x": obj})
        self.assertIsNotNone(event)
        self.assertEqual(1, self.store.count())


class TestEventStorePrune(TestEventStoreBase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.db_path = str(Path(self._tmp.name) / "events.db")
        self.store = EventStore(self.db_path, max_events=5)

    def test_prune_oltre_max_events(self):
        for i in range(8):
            self.store.record("k", f"e{i}")
        self.assertEqual(5, self.store.count())
        self.assertEqual(["e7", "e6", "e5", "e4", "e3"],
                         [e.message for e in self.store.recent(limit=100)])


class TestEventStoreCountClear(TestEventStoreBase):
    def test_count_e_clear(self):
        self.assertEqual(0, self.store.count())
        self.store.record("a", "1")
        self.store.record("b", "2")
        self.assertEqual(2, self.store.count())
        self.assertEqual(2, self.store.clear())
        self.assertEqual(0, self.store.count())
        self.assertEqual([], self.store.recent())


class TestEventStoreDegraded(unittest.TestCase):
    def test_path_non_scrivibile_non_solleva(self):
        with tempfile.TemporaryDirectory() as tmp:
            blocker = Path(tmp) / "blocker"
            blocker.write_text("sono un file, non una cartella", encoding="utf-8")
            # Il path padre e un FILE: mkdir/connect falliscono -> store degradato.
            with self.assertLogs("hermes-gpu-manager.recovery", level="WARNING") as logs:
                store = EventStore(str(blocker / "sub" / "events.db"))
            try:
                self.assertIsNone(store.record("k", "m"))
                self.assertEqual(0, store.count())
                self.assertEqual([], store.recent())
                self.assertEqual(0, store.clear())
            finally:
                store.close()  # mai eccezione
            self.assertTrue(any("non disponibile" in m for m in logs.output))

    def test_record_dopo_close_non_solleva(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = EventStore(str(Path(tmp) / "events.db"))
            store.record("k", "m")
            store.close()
            self.assertIsNone(store.record("k", "m2"))


class TestNoFastapiImport(unittest.TestCase):
    def test_import_in_interprete_fresco(self):
        gp = str(REPO / "gpu-manager")
        code = (
            "import sys;"
            f"sys.path.insert(0, {gp!r});"
            "import recovery, recovery.events, recovery.types;"
            "assert 'fastapi' not in sys.modules, 'fastapi importato';"
            "print(recovery.SEVERITIES[0])"
        )
        proc = subprocess.run(
            [sys.executable, "-c", code],
            capture_output=True, text=True, cwd=str(REPO),
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("debug", proc.stdout.strip())

    def test_sorgenti_recovery_senza_fastapi(self):
        for name in ("__init__.py", "types.py", "events.py"):
            text = (REPO / "gpu-manager" / "recovery" / name).read_text(encoding="utf-8")
            tree = ast.parse(text)
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    for alias in node.names:
                        self.assertNotEqual("fastapi", alias.name.split(".")[0],
                                            f"fastapi importato in recovery/{name}")
                elif isinstance(node, ast.ImportFrom):
                    module = (node.module or "").split(".")[0]
                    self.assertNotEqual("fastapi", module,
                                        f"fastapi importato in recovery/{name}")


class TestManagerWiring(unittest.TestCase):
    """Asserzioni a livello di SORGENTE: nessun import di manager.py (fastapi)."""

    @classmethod
    def setUpClass(cls):
        cls.src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
        cls.tree = ast.parse(cls.src)

    def test_set_state_registra_evento(self):
        node = next(
            n for n in self.tree.body
            if isinstance(n, ast.FunctionDef) and n.name == "set_state"
        )
        seg = ast.get_source_segment(self.src, node)
        assert seg is not None
        self.assertIn("_emit(", seg)
        self.assertIn('"state"', seg)
        self.assertIn('"ERROR"', seg)

    def test_import_guard_e_accessore(self):
        self.assertIn("import recovery", self.src)
        self.assertIn("def _events()", self.src)
        self.assertIn('@app.get("/events")', self.src)


if __name__ == "__main__":
    unittest.main()
