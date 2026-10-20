"""Contract test tolleranza varianti upstream (deploy 0.6.282).

hermes-agent ha migrato firme e layout (modulo runs separato, annotation
non quotate, `str | None`, `list/dict` minuscoli, features standalone,
_parse_chat_messages): il patcher deve accettare entrambe le varianti di
ogni anchor, in modo idempotente. Sonda reale: --check contro d923a5eb e
601491f sul server (probetree/probetree4, already patched + exit 0).
"""

import importlib.util
import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
PATCHER_PATH = ROOT / "scripts" / "patch-hermes-gateway-native.py"


def load_patcher():
    spec = importlib.util.spec_from_file_location("upstream_variant_patcher", PATCHER_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class UpstreamVariantToleranceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.patcher = load_patcher()

    def test_resolve_first_present(self):
        resolve = self.patcher._resolve_first_present
        quoted = '    async def _handle_models(self, request: "web.Request") -> "web.Response":'
        plain = "    async def _handle_models(self, request: web.Request) -> web.Response:"
        self.assertEqual(quoted, resolve("x\n" + quoted + "\ny", (quoted, plain), "lbl"))
        self.assertEqual(plain, resolve("x\n" + plain + "\ny", (quoted, plain), "lbl"))
        with self.assertRaises(Exception):
            resolve("nothing here", (quoted, plain), "lbl")
        with self.assertRaises(Exception):
            resolve(quoted + "\n" + quoted, (quoted,), "lbl")

    def test_upsert_versioned_block_accepts_anchor_variants(self):
        upsert = self.patcher._upsert_versioned_block
        plain = "    async def _handle_models(self, request: web.Request) -> web.Response:\n"
        block = "# BEGIN\nbody\n# END"
        patched, changed = upsert(
            "head\n" + plain + "tail",
            begin="# BEGIN",
            end="# END",
            block=block,
            anchor=(
                '    async def _handle_models(self, request: "web.Request") -> "web.Response":',
                "    async def _handle_models(self, request: web.Request) -> web.Response:",
            ),
            label="probe",
        )
        self.assertTrue(changed)
        self.assertIn("# BEGIN\nbody\n# END\n" + plain, patched)
        # Secondo giro: blocco presente e identico -> nessun cambiamento.
        repached, changed_again = upsert(
            patched,
            begin="# BEGIN",
            end="# END",
            block=block,
            anchor=(
                '    async def _handle_models(self, request: "web.Request") -> "web.Response":',
                "    async def _handle_models(self, request: web.Request) -> web.Response:",
            ),
            label="probe",
        )
        self.assertFalse(changed_again)
        self.assertEqual(patched, repached)

    def test_features_dict_regex_matches_both_layouts_once(self):
        pattern = r'(^\s+(?:"features": \{|features = \{)\n)'
        legacy = '        payload = {\n            "features": {\n                "chat_completions": True,\n'
        modern = '        features = {\n            "chat_completions": True, "chat_completions_streaming": True,\n'
        for snippet in (legacy, modern):
            matches = re.findall(pattern, snippet, flags=re.MULTILINE)
            self.assertEqual(1, len(matches))

    def test_runs_event_anchor_resolver_prefers_present_variant(self):
        resolve = self.patcher._resolve_runs_event_anchor
        legacy_cb = "    def _callback(event_type: str, tool_name: str = None, preview: str = None, args=None, **kwargs):\n"
        modern_cb = "    def _callback(event_type: str, tool_name: str | None = None, preview: str | None = None, args=None, **kwargs):\n"
        self.assertEqual(modern_cb, resolve("a\n" + modern_cb + "b"))
        self.assertEqual(legacy_cb, resolve("a\n" + legacy_cb + "b"))
        self.assertIsNone(resolve("nope"))


if __name__ == "__main__":
    unittest.main()
