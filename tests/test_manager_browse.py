"""Test endpoint browse read-only del manager (nessun import manager.py:
fastapi/yaml non servono in CI)."""
from __future__ import annotations

import ast
import os
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

_PURE_NAMES = {
    "_BROWSE_IMAGE_EXTENSIONS",
    "_BROWSE_MAX_FOLDERS",
    "_BROWSE_MAX_ITEMS",
    "_browse_roots",
    "_browse_jail",
    "_browse_scan",
    "_browse_mime",
}


def load_browse_namespace() -> dict:
    src = (REPO / "gpu-manager" / "manager.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    wanted = []
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
            isinstance(t, ast.Name) and t.id in _PURE_NAMES for t in node.targets
        ):
            wanted.append(node)
        elif isinstance(node, ast.FunctionDef) and node.name in _PURE_NAMES:
            wanted.append(node)
    assert len(wanted) == len(_PURE_NAMES), f"browse puro incompleto: {len(wanted)}"
    mod = ast.Module(body=wanted, type_ignores=[])
    ns: dict = {"os": os, "Path": Path}
    exec(compile(mod, "manager_browse", "exec"), ns)  # noqa: S102 - test locale
    return ns


class TestBrowseJail(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        ns = load_browse_namespace()
        cls.jail = staticmethod(ns["_browse_jail"])
        cls.roots_fn = staticmethod(ns["_browse_roots"])
        cls.scan = staticmethod(ns["_browse_scan"])
        cls.mime = staticmethod(ns["_browse_mime"])

    def test_inside_root_allowed(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "hub"
            root.mkdir()
            target = root / "sub" / "a.jpg"
            got = self.jail(target, [root.resolve()])
            self.assertEqual(target.resolve(), got)

    def test_traversal_outside_denied(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "hub"
            root.mkdir()
            outside = Path(tmp) / "secret.txt"
            outside.write_text("x")
            self.assertIsNone(self.jail(root / ".." / "secret.txt", [root.resolve()]))
            self.assertIsNone(self.jail(outside, [root.resolve()]))

    def test_absolute_outside_denied(self):
        self.assertIsNone(self.jail(Path("/etc/hostname"), [Path("/tmp/xyz-hermes")]))


class TestBrowseScan(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        ns = load_browse_namespace()
        cls.scan = staticmethod(ns["_browse_scan"])
        cls.jail = staticmethod(ns["_browse_jail"])
        cls.mime = staticmethod(ns["_browse_mime"])

    @staticmethod
    def _try_symlink(link: Path, target: Path) -> bool:
        try:
            link.symlink_to(target)
            return True
        except OSError:
            return False

    def _tree(self, base: Path) -> Path:
        root = base / "denudate"
        (root / "setA").mkdir(parents=True)
        (root / "setB").mkdir()
        (root / "setA" / "one.jpg").write_bytes(b"1")
        (root / "two.PNG").write_bytes(b"2")
        (root / "clip.mp4").write_bytes(b"3")
        (root / ".hidden.jpg").write_bytes(b"4")
        (root / "note.txt").write_bytes(b"5")
        os.utime(root / "setA" / "one.jpg", (1000, 1000))
        os.utime(root / "two.PNG", (2000, 2000))
        return root

    def test_lists_folders_and_images_only(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            root = self._tree(Path(tmp))
            # Singolo livello: le immagini nelle sottocartelle si vedono entrandoci.
            folders, items = self.scan(root.resolve(), [root.resolve()])
            self.assertEqual(["setA", "setB"], [f["name"] for f in folders])
            self.assertEqual(["two.PNG"], [i["filename"] for i in items])
            self.assertTrue(all(i["root"] == str(root.resolve()) for i in items))
            sub_folders, sub_items = self.scan((root / "setA").resolve(), [root.resolve()])
            self.assertEqual([], sub_folders)
            self.assertEqual(["one.jpg"], [i["filename"] for i in sub_items])
            self.assertEqual("one.jpg", sub_items[0]["id"])

    def test_symlink_escape_excluded(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            root = self._tree(base)
            outside = base / "outside.jpg"
            outside.write_bytes(b"9")
            made = [
                self._try_symlink(root / "evil.jpg", outside),
                self._try_symlink(root / "evildir", base),
                self._try_symlink(root / "broken.jpg", base / "missing.jpg"),
                self._try_symlink(root / "ok.jpg", root / "two.PNG"),
            ]
            if not all(made):
                self.skipTest("symlink non creabili su questa piattaforma")
            folders, items = self.scan(root.resolve(), [root.resolve()])
            names = [i["filename"] for i in items]
            self.assertIn("ok.jpg", names)
            self.assertNotIn("evil.jpg", names)
            self.assertNotIn("broken.jpg", names)
            self.assertNotIn("evildir", [f["name"] for f in folders])

    def test_mime_mapping(self):
        self.assertEqual("image/png", self.mime("a.PNG"))
        self.assertEqual("image/gif", self.mime("a.gif"))
        self.assertEqual("image/bmp", self.mime("a.bmp"))
        self.assertEqual("image/webp", self.mime("a.webp"))
        self.assertEqual("image/jpeg", self.mime("a.jpg"))
        self.assertEqual("image/jpeg", self.mime("a.jpeg"))


class TestBrowseRoots(unittest.TestCase):
    def test_hub_uploads_first(self):
        import tempfile

        ns = load_browse_namespace()
        roots_fn = staticmethod(ns["_browse_roots"])
        with tempfile.TemporaryDirectory() as tmp:
            hub = Path(tmp) / ".hermes" / "hub_uploads"
            hub.mkdir(parents=True)
            old_home = os.environ.get("HOME")
            os.environ["HOME"] = tmp
            try:
                roots = roots_fn()
            finally:
                if old_home is None:
                    del os.environ["HOME"]
                else:
                    os.environ["HOME"] = old_home
            self.assertTrue(str(roots[0]).endswith("hub_uploads"))


if __name__ == "__main__":
    unittest.main()
