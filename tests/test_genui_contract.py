"""Contratto Generative UI v1 (spec eseguibile del formato).

Regole specchiate nelle implementazioni native (Android GenUi.kt, Windows
VisualBlocks.cs): limiti identici, stessa grammatica formule, stesso
protocollo eventi. Se questo test e i test nativi divergono, il formato e'
rotto: allineare tutti e tre.
"""

import json
import math
import re
import unittest
from pathlib import Path

FIXTURES = Path(__file__).resolve().parent / "genui"

GENUI_VERSION = 1
ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
HANDLER_RE = re.compile(r"^on[A-Z]")
KEY_RE = re.compile(r"^[A-Za-z0-9_.]{1,64}$")
KINDS = {
    "column", "row", "grid", "card", "tabs", "accordion", "section",
    "text", "markdown", "metric", "progress", "button", "slider", "switch",
    "checkbox", "radio", "select", "input", "number", "date", "textarea",
    "table", "chart", "steps", "nodes", "image", "code", "alert",
    "divider", "spacer",
}
CONTAINERS = {"column", "row", "grid", "card", "section"}
MAX_NODES = 150
MAX_DEPTH = 8
MAX_TITLE = 160
MAX_LABEL = 120
MAX_TEXT = 4000
MAX_FALLBACK = 2000
MAX_FORMULA = 500
MAX_URL = 1200
MAX_OPTIONS = 20
MAX_OPTION_CHARS = 120
MAX_STATE_KEYS = 64
MAX_TABLE_COLUMNS = 12
MAX_TABLE_ROWS = 100
MAX_CHART_SERIES = 8
MAX_CHART_POINTS = 200
COND_OPS = {"eq", "neq", "gt", "gte", "lt", "lte", "in", "contains", "empty", "notempty"}
CHART_TYPES = {"line", "bar", "area", "pie", "scatter"}
ALERT_VARIANTS = {"info", "warning", "error", "success"}
LOCAL_OPS = {"set", "toggle", "step", "reset", "goto"}
FUNC_NAMES = {"min", "max", "clamp", "round", "floor", "ceil", "abs", "sqrt", "pow", "sin", "cos", "tan", "log", "exp", "if"}

TOKEN_RE = re.compile(
    r"\s*(?:(\d+(?:\.\d+)?)|([A-Za-z_][A-Za-z0-9_.]*)|(==|!=|>=|<=|[+\-*/%^(),><]))"
)


def tokenize(expr):
    pos = 0
    out = []
    while pos < len(expr):
        match = TOKEN_RE.match(expr, pos)
        if not match or match.end() == pos:
            raise ValueError("bad token at %d" % pos)
        num, name, op = match.groups()
        out.append(("num", float(num)) if num else ("name", name) if name else ("op", op))
        pos = match.end()
    return out


class FormulaParser:
    def __init__(self, tokens):
        self.tokens = tokens
        self.pos = 0

    def peek(self):
        return self.tokens[self.pos] if self.pos < len(self.tokens) else (None, None)

    def eat(self, kind=None, value=None):
        tok = self.peek()
        if tok[0] != kind or (value is not None and tok[1] != value):
            raise ValueError("unexpected %r" % (tok,))
        self.pos += 1
        return tok

    def parse(self):
        node = self.comparison()
        if self.pos != len(self.tokens):
            raise ValueError("trailing tokens")
        return node

    def comparison(self):
        node = self.additive()
        while self.peek()[0] == "op" and self.peek()[1] in ("==", "!=", ">", ">=", "<", "<="):
            op = self.eat("op")[1]
            node = ("cmp", op, node, self.additive())
        return node

    def additive(self):
        node = self.term()
        while self.peek()[0] == "op" and self.peek()[1] in ("+", "-"):
            op = self.eat("op")[1]
            node = ("bin", op, node, self.term())
        return node

    def term(self):
        node = self.power()
        while self.peek()[0] == "op" and self.peek()[1] in ("*", "/", "%"):
            op = self.eat("op")[1]
            node = ("bin", op, node, self.power())
        return node

    def power(self):
        node = self.unary()
        if self.peek() == ("op", "^"):
            self.eat("op")
            node = ("bin", "^", node, self.power())
        return node

    def unary(self):
        if self.peek() == ("op", "-"):
            self.eat("op")
            return ("neg", self.unary())
        return self.primary()

    def primary(self):
        kind, value = self.peek()
        if kind == "num":
            self.eat("num")
            return ("num", value)
        if kind == "name":
            self.eat("name")
            if value not in FUNC_NAMES and not KEY_RE.match(value):
                raise ValueError("bad identifier %r" % value)
            if self.peek() == ("op", "("):
                if value not in FUNC_NAMES:
                    raise ValueError("unknown function %r" % value)
                self.eat("op", "(")
                args = []
                if self.peek() != ("op", ")"):
                    args.append(self.comparison())
                    while self.peek() == ("op", ","):
                        self.eat("op", ",")
                        args.append(self.comparison())
                self.eat("op", ")")
                return ("call", value, args)
            return ("ref", value)
        if (kind, value) == ("op", "("):
            self.eat("op", "(")
            node = self.comparison()
            self.eat("op", ")")
            return node
        raise ValueError("unexpected %r" % ((kind, value),))


def check_formula(expr):
    if not isinstance(expr, str) or not expr.strip() or len(expr) > MAX_FORMULA:
        raise ValueError("formula length")
    FormulaParser(tokenize(expr)).parse()


def check_url(url):
    if not isinstance(url, str) or not url or len(url) > MAX_URL:
        raise ValueError("url length")
    low = url.strip().lower()
    if low.startswith(("javascript:", "data:", "vbscript:", "file:")):
        raise ValueError("url scheme")
    if url.strip().startswith("/v1/media/") or url.strip().startswith("/v1/image/"):
        return
    if low.startswith("https://"):
        return
    raise ValueError("url not allowlisted")


def check_condition(cond):
    if not isinstance(cond, dict):
        raise ValueError("condition shape")
    if not KEY_RE.match(str(cond.get("key", ""))):
        raise ValueError("condition key")
    if cond.get("op") not in COND_OPS:
        raise ValueError("condition op")


def check_action(action):
    if not isinstance(action, dict):
        raise ValueError("action shape")
    kind = action.get("kind")
    if kind == "local":
        if action.get("op") not in LOCAL_OPS:
            raise ValueError("local op")
        if action.get("op") in ("set", "toggle", "step", "goto"):
            if not KEY_RE.match(str(action.get("key", ""))):
                raise ValueError("action key")
        if action.get("op") in ("set", "goto") and "value" in action:
            value = action["value"]
            if value is not None and not isinstance(value, (str, int, float, bool)):
                raise ValueError("action value")
            if isinstance(value, float) and not math.isfinite(value):
                raise ValueError("action value")
        if action.get("op") == "step" and "delta" in action:
            delta = action["delta"]
            if not isinstance(delta, (int, float)) or isinstance(delta, bool):
                raise ValueError("delta")
            if not math.isfinite(delta):
                raise ValueError("delta")
    elif kind == "agent":
        if not ID_RE.match(str(action.get("action_id", ""))):
            raise ValueError("action_id")
        keys = action.get("state_keys", [])
        if not isinstance(keys, list) or len(keys) > 16 or any(not KEY_RE.match(str(k)) for k in keys):
            raise ValueError("state_keys")
        payload = action.get("payload", {})
        if not isinstance(payload, dict) or len(json.dumps(payload)) > 4096:
            raise ValueError("payload")
    else:
        raise ValueError("action kind")


class GenUiValidator:
    def __init__(self):
        self.count = 0
        self.ids = set()

    def check_block(self, block):
        if not isinstance(block, dict) or block.get("type") != "genui":
            raise ValueError("not genui")
        ui_id = block.get("id", "")
        if not ID_RE.match(str(ui_id)):
            raise ValueError("id")
        if block.get("genui_version") != GENUI_VERSION:
            raise ValueError("genui_version")
        fallback = block.get("fallback_text", "")
        if not isinstance(fallback, str) or not fallback.strip() or len(fallback) > MAX_FALLBACK:
            raise ValueError("fallback_text")
        state = block.get("state", {})
        if not isinstance(state, dict) or len(state) > MAX_STATE_KEYS:
            raise ValueError("state")
        for key, value in state.items():
            if not KEY_RE.match(str(key)):
                raise ValueError("state key")
            if isinstance(value, str) and len(value) > 2000:
                raise ValueError("state value")
            if value is not None and not isinstance(value, (str, int, float, bool)):
                raise ValueError("state value type")
            if isinstance(value, float) and not math.isfinite(value):
                raise ValueError("state number")
        spec = block.get("spec")
        if not isinstance(spec, dict):
            raise ValueError("spec")
        self.check_node(spec, 1)
        if self.count > MAX_NODES:
            raise ValueError("too many nodes")

    def check_node(self, node, depth):
        if not isinstance(node, dict):
            raise ValueError("node shape")
        for evil in node.keys():
            if isinstance(evil, str) and (HANDLER_RE.match(evil) or evil.lower() in ("script", "eval")):
                raise ValueError("handler key")
        kind = node.get("kind")
        if kind not in KINDS:
            raise ValueError("kind %r" % (kind,))
        node_id = node.get("id", "")
        if not ID_RE.match(str(node_id)):
            raise ValueError("node id")
        if node_id in self.ids:
            raise ValueError("duplicate id")
        self.ids.add(node_id)
        self.count += 1
        if depth > MAX_DEPTH:
            raise ValueError("depth")
        for text_key, cap in (("title", MAX_TITLE), ("label", MAX_LABEL), ("text", MAX_TEXT)):
            value = node.get(text_key)
            if value is not None and (not isinstance(value, str) or len(value) > cap):
                raise ValueError(text_key)
        for formula_key in ("formula", "points_formula", "y_formula"):
            if formula_key in node and node[formula_key] is not None:
                check_formula(node[formula_key])
        for cond_key in ("visible_if", "enabled_if"):
            if cond_key in node and node[cond_key] is not None:
                check_condition(node[cond_key])
        if "bind" in node and node["bind"] is not None and not KEY_RE.match(str(node["bind"])):
            raise ValueError("bind")
        if "action" in node and node["action"] is not None:
            check_action(node["action"])
        if "src" in node and node["src"] is not None:
            check_url(node["src"])
        options = node.get("options")
        if options is not None:
            if not isinstance(options, list) or not options or len(options) > MAX_OPTIONS:
                raise ValueError("options")
            for opt in options:
                text = opt.get("label", opt) if isinstance(opt, dict) else opt
                if not isinstance(text, str) or not text.strip() or len(text) > MAX_OPTION_CHARS:
                    raise ValueError("option")
        columns = node.get("columns")
        if kind == "table":
            if "filter" in node and not isinstance(node["filter"], bool):
                raise ValueError("filter")
            if not isinstance(columns, list) or not columns or len(columns) > MAX_TABLE_COLUMNS:
                raise ValueError("columns")
            for column in columns:
                if not isinstance(column, dict) or not column.get("key"):
                    raise ValueError("column shape")
        rows = node.get("rows")
        if kind == "table":
            if not isinstance(rows, list) or len(rows) > MAX_TABLE_ROWS:
                raise ValueError("rows")
        if kind == "grid" and "columns" in node and node["columns"] is not None:
            cols = node["columns"]
            if not isinstance(cols, int) or isinstance(cols, bool) or not 1 <= cols <= 6:
                raise ValueError("grid columns")
        for tab in node.get("tabs") or []:
            if not isinstance(tab, dict) or not isinstance(tab.get("title"), str):
                raise ValueError("tab shape")
        for item in node.get("items") or []:
            if not isinstance(item, dict) or not isinstance(item.get("title"), str):
                raise ValueError("item shape")
        if kind == "steps" and not node.get("items"):
            raise ValueError("steps need items")
        if kind == "nodes":
            nodes = node.get("nodes")
            if not isinstance(nodes, list) or not nodes or len(nodes) > 40:
                raise ValueError("nodes")
            seen_nodes = set()
            for entry in nodes:
                if not isinstance(entry, dict) or not ID_RE.match(str(entry.get("id", ""))):
                    raise ValueError("node entry")
                seen_nodes.add(entry["id"])
            for edge in node.get("edges") or []:
                if (not isinstance(edge, list) or len(edge) != 2
                        or edge[0] not in seen_nodes or edge[1] not in seen_nodes):
                    raise ValueError("edge")
        if kind in ("slider", "number"):
            for bound in ("min", "max", "step"):
                value = node.get(bound)
                if value is not None and (not isinstance(value, (int, float))
                                          or isinstance(value, bool) or not math.isfinite(value)):
                    raise ValueError(bound)
            if node.get("min") is not None and node.get("max") is not None:
                if not node["min"] < node["max"]:
                    raise ValueError("min/max")
        if kind in ("radio", "select") and node.get("options") is None:
            raise ValueError("options required")
        series = node.get("series")
        if series is not None and kind != "chart":
            raise ValueError("series only on chart")
        if kind in ("alert", "callout") and "variant" in node and node["variant"] is not None:
            if node["variant"] not in ALERT_VARIANTS:
                raise ValueError("variant")
        if kind == "chart":
            chart_type = node.get("chart_type", "line")
            if chart_type not in CHART_TYPES:
                raise ValueError("chart_type")
            if not isinstance(series, list) or not series or len(series) > MAX_CHART_SERIES:
                raise ValueError("series")
            for entry in series:
                points = entry.get("points", [])
                if not isinstance(points, list) or not points or len(points) > MAX_CHART_POINTS:
                    raise ValueError("points")
                for point in points:
                    if "y_formula" in point and point["y_formula"] is not None:
                        if not isinstance(point.get("x"), (int, float)):
                            raise ValueError("y_formula needs numeric x")
                        check_formula(point["y_formula"])
        kids = []
        for child_key in ("children",):
            value = node.get(child_key)
            if value is not None:
                if not isinstance(value, list):
                    raise ValueError(child_key)
                kids.extend(value)
        for list_key in ("tabs", "items"):
            for entry in node.get(list_key) or []:
                body = entry.get("body") if isinstance(entry, dict) else None
                if body is not None:
                    kids.append(body)
        for kid in kids:
            self.check_node(kid, depth + 1)


def extract_genui(doc):
    try:
        root = json.loads(doc) if isinstance(doc, str) else doc
    except (json.JSONDecodeError, TypeError, ValueError):
        return []
    if not isinstance(root, dict):
        return []
    blocks = root.get("visual_blocks")
    if not isinstance(blocks, list):
        return []
    return [b for b in blocks if isinstance(b, dict) and b.get("type") == "genui"]


def build_agent_event(ui_id, action_id, payload):
    if not ID_RE.match(ui_id) or not ID_RE.match(action_id):
        raise ValueError("event ids")
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    if len(raw) > 4096:
        raise ValueError("event payload")
    return "[genui %s %s %s]" % (ui_id, action_id, raw)


class GenUiContractTests(unittest.TestCase):
    def read(self, name):
        return (FIXTURES / name).read_text(encoding="utf-8")

    def test_valid_fixtures_pass(self):
        for name in ("valid_parametric.json", "valid_algo.json", "valid_dashboard.json",
                     "valid_tutorial.json", "valid_agent_update.json"):
            blocks = extract_genui(self.read(name))
            self.assertGreater(len(blocks), 0, name)
            for block in blocks:
                GenUiValidator().check_block(block)

    def test_same_id_update_pair(self):
        blocks = extract_genui(self.read("valid_agent_update.json"))
        self.assertEqual(2, len(blocks))
        self.assertEqual(blocks[0]["id"], blocks[1]["id"])
        # Il renderer sostituisce a parita' di id: l'ultima vince.
        merged = {}
        for block in blocks:
            GenUiValidator().check_block(block)
            merged[block["id"]] = block
        self.assertEqual(1, len(merged))
        self.assertIn("aggiornata", merged["ui-demo-5"]["title"])

    def test_compat_documents_ignored(self):
        self.assertEqual([], extract_genui(self.read("compat_plain.json")))

    def test_truncated_stream_ignored(self):
        full = self.read("valid_tutorial.json")
        for cut in (len(full) // 3, len(full) // 2, len(full) - 10):
            self.assertEqual([], extract_genui(full[:cut]))

    def test_invalid_rejected(self):
        expectations = {
            "invalid_missing_id.json": "id",
            "invalid_bad_kind.json": "kind",
            "invalid_too_deep.json": "depth",
            "invalid_too_many_nodes.json": "too many nodes",
            "invalid_bad_formula.json": "formula",
            "invalid_bad_action.json": "action",
            "invalid_bad_variant.json": "variant",
        }
        for name, _ in expectations.items():
            blocks = extract_genui(self.read(name))
            self.assertEqual(1, len(blocks), name)
            with self.assertRaises(ValueError, msg=name):
                GenUiValidator().check_block(blocks[0])

    def test_evil_rejected(self):
        for name in ("evil_bad_url.json", "evil_handlers.json"):
            blocks = extract_genui(self.read(name))
            self.assertEqual(1, len(blocks), name)
            with self.assertRaises(ValueError, msg=name):
                GenUiValidator().check_block(blocks[0])

    def test_formula_grammar(self):
        good = ["1 + 2 * 3", "amp * sin(freq * x)", "(step + 1) / 4",
                "if(c1, 1, 0)", "clamp(v, 0, 10)", "c1 + c2 + c3", "1 / freq"]
        for expr in good:
            check_formula(expr)
        bad = ["", "a + * 2", "System.exit(1)", "eval(x)", "a; b", "1 +", "__import__('os')"]
        for expr in bad:
            with self.assertRaises(ValueError, msg=expr):
                check_formula(expr)

    def test_agent_event_format(self):
        event = build_agent_event("ui-demo-5", "render", {"name": "clip2"})
        self.assertTrue(event.startswith("[genui ui-demo-5 render "))
        self.assertTrue(event.endswith("]"))
        with self.assertRaises(ValueError):
            build_agent_event("bad id!", "render", {})
        with self.assertRaises(ValueError):
            build_agent_event("ui", "render", {"big": "x" * 5000})

    def test_interpolation_tokens_safe(self):
        # Solo {{...}} con grammatica formule: niente codice. Gli identificatori
        # puntati sono chiavi flat di stato (nessun attribute access: il valutatore
        # risolve solo nomi interi dalla mappa di stato).
        check_formula("step + 1")
        check_formula("chart.x + 1")
        bad = ["", "a + * 2", "System.exit(1)", "eval(x)", "a; b", "1 +", "__import__('os')",
               "a b", "foo@bar", "1..2"]
        for expr in bad:
            with self.assertRaises(ValueError, msg=expr):
                check_formula(expr)


if __name__ == "__main__":
    unittest.main()
