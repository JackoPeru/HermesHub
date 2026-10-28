"""Test comportamentali per le funzioni pure di gpu-manager/manager.py.

Pattern copiato da tests/test_manager_stray.py: ast.parse del sorgente,
estrazione dei FunctionDef puri, exec in namespace con stub. Nessun import
di manager.py (niente fastapi/yaml). Le costanti dati (frozenset/letterali)
referenziate dalle funzioni vengono caricate dal sorgente stesso, cosi il
test segue i refactor (es. allowlist esatta _STRAY_BACKEND_BINS) invece di
fossilizzarsi su stub. Se una funzione e rotta, questi test falliscono
davvero (input -> output osservabile, mai assertIn su stringhe).
"""
from __future__ import annotations

import ast
import json
import os
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BASE_STUBS = {"os": os, "json": json, "Path": Path}


def load_namespace(names: set[str], stubs: dict) -> dict:
    src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    funcs = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names]
    found = {n.name for n in funcs}
    assert found == names, f"funzioni non trovate in manager.py: {names - found}"
    # Nomi caricati dalle funzioni: le costanti dati corrispondenti vengono
    # eseguite dal sorgente (solo Assign top-level, uno alla volta: se uno
    # richiede chiamate, si salta e vale lo stub del chiamante).
    loaded: set[str] = set()
    for func in funcs:
        for sub in ast.walk(func):
            if isinstance(sub, ast.Name) and isinstance(sub.ctx, ast.Load):
                loaded.add(sub.id)
    ns: dict = dict(stubs)
    for node in tree.body:
        if (
            isinstance(node, ast.Assign)
            and len(node.targets) == 1
            and isinstance(node.targets[0], ast.Name)
            and node.targets[0].id in loaded
            and node.targets[0].id not in ns
        ):
            try:
                exec(
                    compile(ast.Module(body=[node], type_ignores=[]), "manager_consts", "exec"),
                    {"__builtins__": __builtins__},
                    ns,
                )  # noqa: S102 - test locale, solo dati
            except Exception:
                ns.pop(node.targets[0].id, None)
    exec(compile(ast.Module(body=funcs, type_ignores=[]), "manager_pure", "exec"), ns)  # noqa: S102 - test locale
    return ns


SCALAR_NS = load_namespace(
    {"_num", "_text", "_deep_merge", "_is_stray_runtime_cmd", "validate_workflow", "_browse_mime"},
    BASE_STUBS,
)

BROWSE_NS = load_namespace(
    {"_browse_home", "_browse_expand", "_browse_jail", "_valid_output"},
    BASE_STUBS,
)


class TestNumCoercion(unittest.TestCase):
    def test_non_numeric_falls_back_to_default(self):
        self.assertEqual(SCALAR_NS["_num"]("abc", 10, 0, 100), "10")

    def test_clamps_to_bounds(self):
        self.assertEqual(SCALAR_NS["_num"](200, 10, 0, 100), "100")
        self.assertEqual(SCALAR_NS["_num"](-5, 10, 0, 100), "0")

    def test_keeps_fractional_values(self):
        self.assertEqual(SCALAR_NS["_num"](3.5, 10, 0, 100), "3.5")

    def test_nan_falls_back_to_default(self):
        self.assertEqual(SCALAR_NS["_num"](float("nan"), 10, 0, 100), "10")

    def test_integers_have_no_decimal_point(self):
        self.assertEqual(SCALAR_NS["_num"](7, 10, 0, 100), "7")


class TestTextEscape(unittest.TestCase):
    def test_quotes_are_json_escaped(self):
        self.assertEqual(SCALAR_NS["_text"]('a"b'), 'a\\"b')

    def test_numeric_strings_stay_bare(self):
        self.assertEqual(SCALAR_NS["_text"]("123"), "123")

    def test_truncates_to_source_limit(self):
        limit = SCALAR_NS["MAX_PROMPT_CHARS"]
        self.assertGreater(limit, 100)
        self.assertEqual(len(SCALAR_NS["_text"]("x" * (limit + 1000))), limit)

    def test_empty_uses_default(self):
        self.assertEqual(SCALAR_NS["_text"]("", default="dflt"), "dflt")


class TestDeepMerge(unittest.TestCase):
    def test_nested_merge_keeps_sibling_keys(self):
        base = {"a": {"x": 1, "y": 2}, "k": 1}
        SCALAR_NS["_deep_merge"](base, {"a": {"y": 9, "z": 3}, "n": 2})
        self.assertEqual(base, {"a": {"x": 1, "y": 9, "z": 3}, "k": 1, "n": 2})

    def test_scalar_override_replaces(self):
        base = {"a": {"x": 1}}
        SCALAR_NS["_deep_merge"](base, {"a": 5})
        self.assertEqual(base, {"a": 5})


class TestStrayRuntimeCmd(unittest.TestCase):
    def test_known_backend_bins_are_stray_candidates(self):
        owned = SCALAR_NS["_is_stray_runtime_cmd"]
        self.assertTrue(owned("/usr/bin/tabbyapi --model q"))
        self.assertTrue(owned("/usr/bin/comfy --port 8188"))

    def test_interpreter_with_backend_script_is_stray(self):
        self.assertTrue(SCALAR_NS["_is_stray_runtime_cmd"]("python main.py"))

    def test_manager_itself_is_never_stray(self):
        owned = SCALAR_NS["_is_stray_runtime_cmd"]
        self.assertFalse(owned("/opt/hermes/gpu-manager/hermes-gpu"))
        self.assertFalse(owned("python manager.py"))

    def test_unknown_or_empty_is_never_stray(self):
        owned = SCALAR_NS["_is_stray_runtime_cmd"]
        self.assertFalse(owned(""))
        self.assertFalse(owned("   "))
        self.assertFalse(owned("/usr/bin/python3 worker.py"))

    def test_no_substring_matching_fail_closed(self):
        # Allowlist esatta: un wrapper con nome simile non e mai killato.
        self.assertFalse(SCALAR_NS["_is_stray_runtime_cmd"]("/usr/bin/my-tabbyapi-wrapper"))


class TestValidateWorkflow(unittest.TestCase):
    def test_empty_workflow_is_rejected(self):
        self.assertNotEqual(SCALAR_NS["validate_workflow"]({}), "")

    def test_real_allowlisted_class_passes(self):
        allowed = SCALAR_NS["ALLOWED_NODE_CLASSES"]
        self.assertGreater(len(allowed), 0)
        wf = {"1": {"class_type": sorted(allowed)[0], "inputs": {}}}
        self.assertEqual(SCALAR_NS["validate_workflow"](wf), "")

    def test_unknown_node_class_is_rejected(self):
        self.assertNotIn("Evil", SCALAR_NS["ALLOWED_NODE_CLASSES"])
        err = SCALAR_NS["validate_workflow"]({"1": {"class_type": "Evil", "inputs": {}}})
        self.assertIn("Evil", err)

    def test_node_missing_keys_is_rejected(self):
        self.assertNotEqual(SCALAR_NS["validate_workflow"]({"1": {"nope": 1}}), "")


class TestBrowseMime(unittest.TestCase):
    def test_image_types(self):
        mime = SCALAR_NS["_browse_mime"]
        self.assertEqual(mime("a.png"), "image/png")
        self.assertEqual(mime("a.gif"), "image/gif")
        self.assertEqual(mime("a.webp"), "image/webp")

    def test_case_insensitive_and_jpeg_default(self):
        mime = SCALAR_NS["_browse_mime"]
        self.assertEqual(mime("A.PNG"), "image/png")
        self.assertEqual(mime("f.unknown"), "image/jpeg")


class TestBrowseJail(unittest.TestCase):
    def test_tilde_expands_against_known_home(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp) / "home"
            expanded = BROWSE_NS["_browse_expand"](Path("~/video/a.png"), home)
            self.assertEqual(expanded, Path(str(home) + "/video/a.png"))

    def test_tilde_without_home_is_fail_closed(self):
        self.assertIsNone(BROWSE_NS["_browse_expand"](Path("~/x"), None))

    def test_jail_allows_path_inside_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "video"
            root.mkdir()
            target = root / "a.png"
            target.write_bytes(b"x" * 500)
            self.assertEqual(
                BROWSE_NS["_browse_jail"](target, [root.resolve()]), target.resolve()
            )

    def test_jail_resolves_parent_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "video"
            root.mkdir()
            outside = Path(tmp) / "out.txt"
            outside.write_text("o", encoding="utf-8")
            # Navigazione libera: traversal e fuori-root risolvono.
            self.assertEqual(
                BROWSE_NS["_browse_jail"](root / ".." / ".." / "out.txt", [root.resolve()]),
                (root / ".." / ".." / "out.txt").resolve(),
            )
            self.assertEqual(BROWSE_NS["_browse_jail"](outside, [root.resolve()]), outside.resolve())

    def test_valid_output_requires_real_size(self):
        with tempfile.TemporaryDirectory() as tmp:
            big = Path(tmp) / "big.png"
            big.write_bytes(b"x" * 500)
            small = Path(tmp) / "small.png"
            small.write_bytes(b"x" * 10)
            self.assertTrue(BROWSE_NS["_valid_output"](big))
            self.assertFalse(BROWSE_NS["_valid_output"](small))
            self.assertFalse(BROWSE_NS["_valid_output"](Path(tmp) / "missing.png"))


if __name__ == "__main__":
    unittest.main()
