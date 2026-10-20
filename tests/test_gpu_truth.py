"""Test verita' GPU: attribuzione VRAM per-processo e modelli caricati.

Il manager diceva "LLM su GPU" guardando solo i GB totali: un modello video
caricato in Comfy risultava LLM. Ora la VRAM e' attribuita ai processi
(tabby/exllama vs comfy) e i modelli media si leggono dagli fd dei processi
Comfy (funziona anche con load manuale da UI).

Pattern tests/test_manager_pure_behavior.py: AST dal sorgente, exec con stub.
"""

import ast
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

NAMES = {
    "proc_backend_label",
    "attribute_vram_mb",
    "model_files_from_targets",
    "llm_model_from_models",
}


def load_namespace():
    src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    funcs = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in NAMES]
    found = {n.name for n in funcs}
    assert found == NAMES, f"funzioni non trovate: {NAMES - found}"
    loaded: set[str] = set()
    for func in funcs:
        for sub in ast.walk(func):
            if isinstance(sub, ast.Name) and isinstance(sub.ctx, ast.Load):
                loaded.add(sub.id)
    ns: dict = {"float": float, "str": str, "list": list, "dict": dict,
                "set": set, "len": len, "isinstance": isinstance}
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(
            node.targets[0], ast.Name
        ) and node.targets[0].id in loaded:
            try:
                exec(compile(ast.Module(body=[node], type_ignores=[]),
                             "<const>", "exec"), ns)
            except Exception:
                pass
    for func in funcs:
        exec(compile(ast.Module(body=[func], type_ignores=[]), "<func>", "exec"), ns)
    return ns


class GpuTruthTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ns = load_namespace()

    def test_label_da_cgroup(self):
        label = self.ns["proc_backend_label"]
        self.assertEqual("llm", label("", "12:devices:/system.slice/hermes-tabby.service"))
        self.assertEqual("media", label("", "0::/user.slice/user@1000/hermes-comfyui.service"))
        self.assertEqual("media", label("", "hermes-comfyui-direct.service"))

    def test_label_da_cmdline(self):
        label = self.ns["proc_backend_label"]
        self.assertEqual("llm", label("/opt/hermes/runtimes/exllamav3-venv/bin/python main.py", ""))
        self.assertEqual("media", label("/opt/hermes/runtimes/comfyui/venv/bin/python main.py --listen 8188", ""))
        self.assertEqual("other", label("/usr/bin/python3 something.py", ""))
        self.assertEqual("unknown", label("", ""))

    def test_cgroup_vince_su_cmdline(self):
        label = self.ns["proc_backend_label"]
        self.assertEqual("media", label("/opt/hermes/runtimes/exllamav3-venv/bin/python main.py",
                                        "hermes-comfyui.service"))

    def test_attribuzione_vram(self):
        totals = self.ns["attribute_vram_mb"]([(14000.0, "llm"), (9800.0, "media"),
                                               (300.0, "other"), (10.0, "weird")])
        self.assertEqual(14000.0, totals["llm"])
        self.assertEqual(9800.0, totals["media"])
        self.assertEqual(300.0, totals["other"])
        self.assertEqual(10.0, totals["unknown"])
        self.assertEqual({"llm": 0.0, "media": 0.0, "other": 0.0, "unknown": 0.0},
                         self.ns["attribute_vram_mb"](None))

    def test_file_modelli_da_target(self):
        files = self.ns["model_files_from_targets"](
            ["/models/qwen_image_2.1-Q4_K.gguf",
             "anon_inode:[eventfd]",
             "/models/qwen_image_2.1-Q4_K.gguf",
             "/models/vae.safetensors",
             "/usr/lib/libc.so.6",
             "pipe:[123]"])
        self.assertEqual(["qwen_image_2.1-Q4_K.gguf", "vae.safetensors"], files)
        self.assertEqual([], self.ns["model_files_from_targets"](None))

    def test_modello_llm_da_models(self):
        parse = self.ns["llm_model_from_models"]
        self.assertEqual("qwen",
                         parse({"object": "list", "data": [{"id": "qwen"}]}))
        self.assertEqual("", parse({}))
        self.assertEqual("", parse(None))
        self.assertEqual("", parse({"data": []}))


if __name__ == "__main__":
    unittest.main()
