package com.nemoclaw.chat

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Generative UI v1: interfacce dichiarative generate dall'agente dentro
 * visual_blocks (type "genui"). Spec eseguibile in tests/test_genui_contract.py:
 * limiti e grammatica identici qui, in VisualBlocks.cs e nel test.
 *
 * Sicurezza: niente codice, solo dati. Formule = grammatica matematica chiusa.
 * URL solo proxy /v1/media, /v1/image o https. Blocco invalido = fallback_text.
 */
internal object GenUiContract {
    const val VERSION = 1
    const val MAX_NODES = 150
    const val MAX_DEPTH = 8
    const val MAX_TITLE = 160
    const val MAX_LABEL = 120
    const val MAX_TEXT = 4000
    const val MAX_FALLBACK = 2000
    const val MAX_FORMULA = 500
    const val MAX_URL = 1200
    const val MAX_OPTIONS = 20
    const val MAX_OPTION_CHARS = 120
    const val MAX_STATE_KEYS = 64
    const val MAX_TABLE_COLUMNS = 12
    const val MAX_TABLE_ROWS = 100
    const val MAX_CHART_SERIES = 8
    const val MAX_CHART_POINTS = 200
    const val MAX_NODES_DIAGRAM = 40
    const val MAX_EVENT_BYTES = 4096

    val ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
    val KEY_RE = Regex("^[A-Za-z0-9_.]{1,64}$")
    val HANDLER_RE = Regex("^on[A-Z]")
    val FORBIDDEN_KEYS = setOf("script", "eval")

    val KINDS = setOf(
        "column", "row", "grid", "card", "tabs", "accordion", "section",
        "text", "markdown", "metric", "progress", "button", "slider", "switch",
        "checkbox", "radio", "select", "input", "number", "date", "textarea",
        "table", "chart", "steps", "nodes", "image", "code", "alert",
        "divider", "spacer"
    )
    val COND_OPS = setOf("eq", "neq", "gt", "gte", "lt", "lte", "in", "contains", "empty", "notempty")
    val ALERT_VARIANTS = setOf("info", "warning", "error", "success")
    val CHART_TYPES = setOf("line", "bar", "area", "pie", "scatter")
    val LOCAL_OPS = setOf("set", "toggle", "step", "reset", "goto")
    val FUNC_NAMES = setOf(
        "min", "max", "clamp", "round", "floor", "ceil", "abs", "sqrt",
        "pow", "sin", "cos", "tan", "log", "exp", "if"
    )
}

/** Valore di stato UI: scalari soltanto. */
internal sealed interface GenUiValue {
    data class Num(val v: Double) : GenUiValue
    data class Str(val v: String) : GenUiValue
    data class Bool(val v: Boolean) : GenUiValue
}

internal fun GenUiValue.asNumber(): Double = when (this) {
    is GenUiValue.Num -> v
    is GenUiValue.Bool -> if (v) 1.0 else 0.0
    is GenUiValue.Str -> v.toDoubleOrNull() ?: Double.NaN
}

internal fun GenUiValue.asText(): String = when (this) {
    is GenUiValue.Num -> if (v.isFinite() && v == kotlin.math.floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()
    is GenUiValue.Str -> v
    is GenUiValue.Bool -> v.toString()
}

/** Formula: tokenizer + recursive descent, stessa grammatica del contract test. */
internal object GenUiFormula {
    internal sealed interface Tok {        data class Num(val v: Double) : Tok
        data class Name(val v: String) : Tok
        data class Op(val v: String) : Tok
    }

    internal sealed interface Node {
        data class Num(val v: Double) : Node
        data class Ref(val v: String) : Node
        data class Neg(val e: Node) : Node
        data class Bin(val op: String, val l: Node, val r: Node) : Node
        data class Cmp(val op: String, val l: Node, val r: Node) : Node
        data class Call(val name: String, val args: List<Node>) : Node
    }

    private val TOKEN_RE = Regex("""\s*(?:(\d+(?:\.\d+)?)|([A-Za-z_][A-Za-z0-9_.]*)|(==|!=|>=|<=|[+\-*/%^(),><]))""")

    fun parse(expr: String): Node {
        require(expr.isNotBlank() && expr.length <= GenUiContract.MAX_FORMULA) { "formula length" }
        val tokens = mutableListOf<Tok>()
        var pos = 0
        while (pos < expr.length) {
            val m = TOKEN_RE.matchAt(expr, pos) ?: throw IllegalArgumentException("bad token at $pos")
            if (m.range.isEmpty()) throw IllegalArgumentException("bad token at $pos")
            val (num, name, op) = m.destructured
            tokens += when {
                num.isNotEmpty() -> Tok.Num(num.toDouble())
                name.isNotEmpty() -> Tok.Name(name)
                else -> Tok.Op(op)
            }
            pos = m.range.last + 1
        }
        val parser = Parser(tokens)
        val node = parser.comparison()
        require(parser.pos == tokens.size) { "trailing tokens" }
        return node
    }

    fun check(expr: String) {
        parse(expr)
    }

    fun eval(node: Node, state: Map<String, GenUiValue>, extra: Map<String, GenUiValue> = emptyMap()): GenUiValue {
        return when (node) {
            is Node.Num -> GenUiValue.Num(node.v)
            is Node.Ref -> extra[node.v] ?: state[node.v] ?: GenUiValue.Num(Double.NaN)
            is Node.Neg -> GenUiValue.Num(-evalNum(node.e, state, extra))
            is Node.Bin -> {
                val l = evalNum(node.l, state, extra)
                val r = evalNum(node.r, state, extra)
                GenUiValue.Num(
                    when (node.op) {
                        "+" -> l + r
                        "-" -> l - r
                        "*" -> l * r
                        "/" -> l / r
                        "%" -> l % r
                        "^" -> l.pow(r)
                        else -> Double.NaN
                    }
                )
            }
            is Node.Cmp -> {
                val l = eval(node.l, state, extra)
                val r = eval(node.r, state, extra)
                GenUiValue.Bool(compare(node.op, l, r))
            }
            is Node.Call -> evalCall(node, state, extra)
        }
    }

    fun evalText(expr: String, state: Map<String, GenUiValue>, extra: Map<String, GenUiValue> = emptyMap()): String {
        return try {
            eval(parse(expr), state, extra).asText()
        } catch (_: Exception) {
            ""
        }
    }

    fun evalNumber(expr: String, state: Map<String, GenUiValue>, extra: Map<String, GenUiValue> = emptyMap()): Double {
        return try {
            eval(parse(expr), state, extra).asNumber()
        } catch (_: Exception) {
            Double.NaN
        }
    }

    private fun evalNum(node: Node, state: Map<String, GenUiValue>, extra: Map<String, GenUiValue>): Double =
        eval(node, state, extra).asNumber()

    private fun compare(op: String, l: GenUiValue, r: GenUiValue): Boolean {
        val ln = l.asNumber()
        val rn = r.asNumber()
        if (ln.isFinite() && rn.isFinite() && l !is GenUiValue.Str && r !is GenUiValue.Str) {
            return when (op) {
                "==" -> ln == rn
                "!=" -> ln != rn
                ">" -> ln > rn
                ">=" -> ln >= rn
                "<" -> ln < rn
                "<=" -> ln <= rn
                else -> false
            }
        }
        val ls = l.asText()
        val rs = r.asText()
        return when (op) {
            "==" -> ls == rs
            "!=" -> ls != rs
            else -> false
        }
    }

    private fun evalCall(node: Node.Call, state: Map<String, GenUiValue>, extra: Map<String, GenUiValue>): GenUiValue {
        if (node.name == "if") {
            require(node.args.size == 3) { "if needs 3 args" }
            val cond = eval(node.args[0], state, extra)
            val truthy = when (cond) {
                is GenUiValue.Bool -> cond.v
                is GenUiValue.Num -> cond.v.isFinite() && cond.v != 0.0
                is GenUiValue.Str -> cond.v.isNotEmpty()
            }
            return eval(if (truthy) node.args[1] else node.args[2], state, extra)
        }
        val args = node.args.map { evalNum(it, state, extra) }
        return GenUiValue.Num(
            when (node.name) {
                "min" -> args.minOrNull() ?: Double.NaN
                "max" -> args.maxOrNull() ?: Double.NaN
                "clamp" -> if (args.size == 3) args[0].coerceIn(args[1], args[2]) else Double.NaN
                "round" -> round(args.getOrElse(0) { Double.NaN })
                "floor" -> floor(args.getOrElse(0) { Double.NaN })
                "ceil" -> ceil(args.getOrElse(0) { Double.NaN })
                "abs" -> abs(args.getOrElse(0) { Double.NaN })
                "sqrt" -> sqrt(args.getOrElse(0) { Double.NaN })
                "pow" -> if (args.size == 2) args[0].pow(args[1]) else Double.NaN
                "sin" -> sin(args.getOrElse(0) { Double.NaN })
                "cos" -> cos(args.getOrElse(0) { Double.NaN })
                "tan" -> tan(args.getOrElse(0) { Double.NaN })
                "log" -> ln(args.getOrElse(0) { Double.NaN })
                "exp" -> exp(args.getOrElse(0) { Double.NaN })
                else -> Double.NaN
            }
        )
    }

    internal class Parser(val tokens: List<Tok>) {
        var pos = 0
        fun peek(): Tok? = tokens.getOrNull(pos)

        fun comparison(): Node {
            var node = additive()
            while (peek() is Tok.Op && (peek() as Tok.Op).v in setOf("==", "!=", ">", ">=", "<", "<=")) {
                val op = (peek() as Tok.Op).v
                pos++
                node = Node.Cmp(op, node, additive())
            }
            return node
        }

        fun additive(): Node {
            var node = term()
            while (peek() is Tok.Op && (peek() as Tok.Op).v in setOf("+", "-")) {
                val op = (peek() as Tok.Op).v
                pos++
                node = Node.Bin(op, node, term())
            }
            return node
        }

        fun term(): Node {
            var node = power()
            while (peek() is Tok.Op && (peek() as Tok.Op).v in setOf("*", "/", "%")) {
                val op = (peek() as Tok.Op).v
                pos++
                node = Node.Bin(op, node, power())
            }
            return node
        }

        fun power(): Node {
            val node = unary()
            if (peek() is Tok.Op && (peek() as Tok.Op).v == "^") {
                pos++
                return Node.Bin("^", node, power())
            }
            return node
        }

        fun unary(): Node {
            if (peek() is Tok.Op && (peek() as Tok.Op).v == "-") {
                pos++
                return Node.Neg(unary())
            }
            return primary()
        }

        fun primary(): Node {
            val tok = peek() ?: throw IllegalArgumentException("unexpected end")
            return when (tok) {
                is Tok.Num -> {
                    pos++
                    Node.Num(tok.v)
                }
                is Tok.Name -> {
                    pos++
                    if (!GenUiContract.FUNC_NAMES.contains(tok.v) && !GenUiContract.KEY_RE.matches(tok.v)) {
                        throw IllegalArgumentException("bad identifier ${tok.v}")
                    }
                    if (peek() is Tok.Op && (peek() as Tok.Op).v == "(") {
                        if (!GenUiContract.FUNC_NAMES.contains(tok.v)) throw IllegalArgumentException("unknown function ${tok.v}")
                        pos++
                        val args = mutableListOf<Node>()
                        if (!(peek() is Tok.Op && (peek() as Tok.Op).v == ")")) {
                            args += comparison()
                            while (peek() is Tok.Op && (peek() as Tok.Op).v == ",") {
                                pos++
                                args += comparison()
                            }
                        }
                        if (!(peek() is Tok.Op && (peek() as Tok.Op).v == ")")) throw IllegalArgumentException("missing )")
                        pos++
                        Node.Call(tok.v, args)
                    } else {
                        Node.Ref(tok.v)
                    }
                }
                is Tok.Op -> {
                    if (tok.v != "(") throw IllegalArgumentException("unexpected $tok")
                    pos++
                    val node = comparison()
                    if (!(peek() is Tok.Op && (peek() as Tok.Op).v == ")")) throw IllegalArgumentException("missing )")
                    pos++
                    node
                }
            }
        }
    }
}

/** Validatore GenUI: APPROVA con spec sanificata oppure RIFIUTA (fallback). */
internal object GenUiValidator {
    fun validateBlock(id: String, version: Int, fallback: String, stateJson: String, specJson: String): JSONObject? {
        if (!GenUiContract.ID_RE.matches(id)) return null
        if (version != GenUiContract.VERSION) return null
        if (fallback.isBlank() || fallback.length > GenUiContract.MAX_FALLBACK) return null
        val state = try {
            JSONObject(stateJson.ifBlank { "{}" })
        } catch (_: Exception) {
            return null
        }
        if (state.length() > GenUiContract.MAX_STATE_KEYS) return null
        for (key in state.keys()) {
            if (!GenUiContract.KEY_RE.matches(key)) return null
            val v = state.opt(key)
            if (v != null && v !is String && v !is Number && v !is Boolean) return null
            if (v is String && v.length > 2000) return null
            if (v is Double && !v.isFinite()) return null
        }
        val spec = try {
            JSONObject(specJson)
        } catch (_: Exception) {
            return null
        }
        val counter = Counter()
        return try {
            checkNode(spec, 1, counter)
            if (counter.count > GenUiContract.MAX_NODES) null else spec
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun initialState(stateJson: String): Map<String, GenUiValue> {
        val out = mutableMapOf<String, GenUiValue>()
        try {
            val obj = JSONObject(stateJson.ifBlank { "{}" })
            for (key in obj.keys()) {
                when (val v = obj.opt(key)) {
                    is Number -> out[key] = GenUiValue.Num(v.toDouble())
                    is Boolean -> out[key] = GenUiValue.Bool(v)
                    is String -> out[key] = GenUiValue.Str(v)
                    else -> {}
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun checkUrl(url: String) {
        require(url.isNotBlank() && url.length <= GenUiContract.MAX_URL) { "url length" }
        val low = url.trim().lowercase()
        require(!low.startsWith("javascript:") && !low.startsWith("data:") && !low.startsWith("vbscript:") && !low.startsWith("file:")) { "url scheme" }
        val trimmed = url.trim()
        require(trimmed.startsWith("/v1/media/") || trimmed.startsWith("/v1/image/") || low.startsWith("https://")) { "url allowlist" }
    }

    internal class Counter {
        var count = 0
        val ids = mutableSetOf<String>()
    }

    fun checkNode(node: JSONObject, depth: Int, counter: Counter) {
        for (key in node.keys()) {
            require(!(GenUiContract.HANDLER_RE.containsMatchIn(key) || key.lowercase() in GenUiContract.FORBIDDEN_KEYS)) { "handler key" }
        }
        val kind = node.optString("kind")
        require(GenUiContract.KINDS.contains(kind)) { "kind $kind" }
        val nodeId = node.optString("id")
        require(GenUiContract.ID_RE.matches(nodeId)) { "node id" }
        require(counter.ids.add(nodeId)) { "duplicate id" }
        counter.count++
        require(depth <= GenUiContract.MAX_DEPTH) { "depth" }
        checkCapped(node.optString("title", null), GenUiContract.MAX_TITLE, "title")
        checkCapped(node.optString("label", null), GenUiContract.MAX_LABEL, "label")
        checkCapped(node.optString("text", null), GenUiContract.MAX_TEXT, "text")
        for (formulaKey in listOf("formula", "points_formula", "y_formula")) {
            if (node.has(formulaKey) && !node.isNull(formulaKey)) GenUiFormula.check(node.optString(formulaKey))
        }
        for (condKey in listOf("visible_if", "enabled_if")) {
            if (node.has(condKey) && !node.isNull(condKey)) checkCondition(node.optJSONObject(condKey))
        }
        if (node.has("bind") && !node.isNull("bind")) {
            require(GenUiContract.KEY_RE.matches(node.optString("bind"))) { "bind" }
        }
        if (node.has("action") && !node.isNull("action")) checkAction(node.optJSONObject("action"))
        if (node.has("src") && !node.isNull("src")) checkUrl(node.optString("src"))
        val options = node.optJSONArray("options")
        if (options != null) {
            require(options.length() in 1..GenUiContract.MAX_OPTIONS) { "options" }
            for (i in 0 until options.length()) {
                val opt = options.opt(i)
                val text = if (opt is JSONObject) opt.optString("label", opt.optString("value")) else opt.toString()
                require(text.isNotBlank() && text.length <= GenUiContract.MAX_OPTION_CHARS) { "option" }
            }
        }
        if (kind == "table") {
            if (node.has("filter") && !node.isNull("filter")) {
                require(node.opt("filter") is Boolean) { "filter" }
            }
            val columns = node.optJSONArray("columns") ?: throw IllegalArgumentException("columns")
            require(columns.length() in 1..GenUiContract.MAX_TABLE_COLUMNS) { "columns" }
            val rows = node.optJSONArray("rows") ?: JSONArray()
            require(rows.length() <= GenUiContract.MAX_TABLE_ROWS) { "rows" }
        }
        if (kind == "grid" && node.has("columns") && !node.isNull("columns")) {
            val cols = node.optInt("columns", -1)
            require(cols in 1..6) { "grid columns" }
        }
        for (tab in node.optJSONArray("tabs")?.let { 0 until it.length() } ?: emptyList<Int>()) {
            val entry = node.optJSONArray("tabs")!!.optJSONObject(tab) ?: throw IllegalArgumentException("tab shape")
            require(entry.optString("title").isNotBlank()) { "tab shape" }
        }
        for (i in 0 until (node.optJSONArray("items")?.length() ?: 0)) {
            val entry = node.optJSONArray("items")!!.optJSONObject(i) ?: throw IllegalArgumentException("item shape")
            require(entry.optString("title").isNotBlank()) { "item shape" }
        }
        if (kind == "steps") require((node.optJSONArray("items")?.length() ?: 0) > 0) { "steps need items" }
        if (kind == "nodes") checkDiagramNodes(node)
        if (kind == "slider" || kind == "number") {
            for (bound in listOf("min", "max", "step")) {
                if (node.has(bound) && !node.isNull(bound)) {
                    val v = node.optDouble(bound, Double.NaN)
                    require(v.isFinite()) { bound }
                }
            }
            if (node.has("min") && !node.isNull("min") && node.has("max") && !node.isNull("max")) {
                require(node.optDouble("min") < node.optDouble("max")) { "min/max" }
            }
        }
        if ((kind == "radio" || kind == "select") && options == null) throw IllegalArgumentException("options required")
        val series = node.optJSONArray("series")
        if (series != null && kind != "chart") throw IllegalArgumentException("series only on chart")
        if ((kind == "alert" || kind == "callout") && node.has("variant") && !node.isNull("variant")) {
            require(GenUiContract.ALERT_VARIANTS.contains(node.optString("variant"))) { "variant" }
        }
        if (kind == "chart") {
            require(GenUiContract.CHART_TYPES.contains(node.optString("chart_type", "line").lowercase())) { "chart_type" }
            require(series != null && series.length() in 1..GenUiContract.MAX_CHART_SERIES) { "series" }
            for (i in 0 until series.length()) {
                val entry = series.optJSONObject(i) ?: throw IllegalArgumentException("series entry")
                val points = entry.optJSONArray("points") ?: throw IllegalArgumentException("points")
                require(points.length() in 1..GenUiContract.MAX_CHART_POINTS) { "points" }
                for (j in 0 until points.length()) {
                    val point = points.optJSONObject(j) ?: throw IllegalArgumentException("point")
                    if (point.has("y_formula") && !point.isNull("y_formula")) {
                        require(point.opt("x") is Number) { "y_formula needs numeric x" }
                        GenUiFormula.check(point.optString("y_formula"))
                    }
                }
            }
        }
        val kids = mutableListOf<JSONObject>()
        node.optJSONArray("children")?.let { arr ->
            for (i in 0 until arr.length()) kids += arr.optJSONObject(i) ?: throw IllegalArgumentException("child")
        }
        node.optJSONArray("tabs")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optJSONObject("body")?.let { kids += it }
            }
        }
        (node.optJSONArray("items") ?: JSONArray()).let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optJSONObject("body")?.let { kids += it }
            }
        }
        for (kid in kids) checkNode(kid, depth + 1, counter)
    }

    private fun checkCapped(value: String?, cap: Int, what: String) {
        if (value != null) require(value.length <= cap) { what }
    }

    private fun checkCondition(cond: JSONObject?) {
        require(cond != null) { "condition shape" }
        require(GenUiContract.KEY_RE.matches(cond.optString("key"))) { "condition key" }
        require(GenUiContract.COND_OPS.contains(cond.optString("op"))) { "condition op" }
    }

    private fun checkAction(action: JSONObject?) {
        require(action != null) { "action shape" }
        when (action.optString("kind")) {
            "local" -> {
                require(GenUiContract.LOCAL_OPS.contains(action.optString("op"))) { "local op" }
                if (action.optString("op") in setOf("set", "toggle", "step", "goto")) {
                    require(GenUiContract.KEY_RE.matches(action.optString("key"))) { "action key" }
                }
                if (action.optString("op") in setOf("set", "goto") && action.has("value") && !action.isNull("value")) {
                    val v = action.opt("value")
                    require(v is String || v is Number || v is Boolean) { "action value" }
                    if (v is Double) require(v.isFinite()) { "action value" }
                }
                if (action.optString("op") == "step" && action.has("delta") && !action.isNull("delta")) {
                    val d = action.opt("delta")
                    require(d is Number && d !is Boolean && (d as Number).toDouble().isFinite()) { "delta" }
                }
            }
            "agent" -> {
                require(GenUiContract.ID_RE.matches(action.optString("action_id"))) { "action_id" }
                val keys = action.optJSONArray("state_keys") ?: JSONArray()
                require(keys.length() <= 16) { "state_keys" }
                for (i in 0 until keys.length()) {
                    require(GenUiContract.KEY_RE.matches(keys.optString(i))) { "state_keys" }
                }
                val payload = action.optJSONObject("payload") ?: JSONObject()
                require(payload.toString().toByteArray(Charsets.UTF_8).size <= 4096) { "payload" }
            }
            else -> throw IllegalArgumentException("action kind")
        }
    }

    private fun checkDiagramNodes(node: JSONObject) {
        val nodes = node.optJSONArray("nodes") ?: throw IllegalArgumentException("nodes")
        require(nodes.length() in 1..GenUiContract.MAX_NODES_DIAGRAM) { "nodes" }
        val seen = mutableSetOf<String>()
        for (i in 0 until nodes.length()) {
            val entry = nodes.optJSONObject(i) ?: throw IllegalArgumentException("node entry")
            require(GenUiContract.ID_RE.matches(entry.optString("id"))) { "node entry" }
            seen += entry.optString("id")
        }
        val edges = node.optJSONArray("edges") ?: JSONArray()
        for (i in 0 until edges.length()) {
            val edge = edges.optJSONArray(i) ?: throw IllegalArgumentException("edge")
            require(edge.length() == 2 && seen.contains(edge.optString(0)) && seen.contains(edge.optString(1))) { "edge" }
        }
    }
}

/** Chiave store per istanza: stesso id + stessa spec = stessa istanza. */
internal fun genUiSnapshotKey(uiId: String, specJson: String): String = "$uiId:${specJson.hashCode()}"

/** Stato locale per istanza UI (chiave = uiId + hash spec): sopravvive al ricaricamento. */
internal object GenUiStateStore {
    private val snapshots = mutableMapOf<String, MutableMap<String, GenUiValue>>()
    private val lock = Any()

    fun stateFor(key: String, seed: Map<String, GenUiValue>): MutableMap<String, GenUiValue> {
        synchronized(lock) {
            return snapshots.getOrPut(key) { seed.toMutableMap() }
        }
    }

    fun snapshot(key: String): Map<String, GenUiValue> {
        synchronized(lock) {
            return snapshots[key]?.toMap() ?: emptyMap()
        }
    }

    fun snapshotJson(key: String): String {
        val obj = JSONObject()
        for ((k, v) in snapshot(key)) {
            when (v) {
                is GenUiValue.Num -> obj.put(k, v.v)
                is GenUiValue.Str -> obj.put(k, v.v)
                is GenUiValue.Bool -> obj.put(k, v.v)
            }
        }
        return obj.toString()
    }

    fun reset(key: String, seed: Map<String, GenUiValue>) {
        synchronized(lock) {
            snapshots[key] = seed.toMutableMap()
        }
    }
}

/** Evento agente: [genui <uiId> <actionId> <json>] — stesso canale dei quiz. */
internal fun buildGenUiEvent(uiId: String, actionId: String, payload: JSONObject): String {
    require(GenUiContract.ID_RE.matches(uiId)) { "event ui id" }
    require(GenUiContract.ID_RE.matches(actionId)) { "event action id" }
    val raw = payload.toString()
    require(raw.toByteArray(Charsets.UTF_8).size <= GenUiContract.MAX_EVENT_BYTES) { "event payload" }
    return "[genui $uiId $actionId $raw]"
}

/** Interpolazione {{expr}} nei testi (formule sicure, mai codice). */
internal val GENUI_INTERP_RE = Regex("\\{\\{([^{}]{1,500})\\}\\}")

internal fun renderGenUiText(template: String, state: Map<String, GenUiValue>): String {
    if (!template.contains("{{")) return template
    return GENUI_INTERP_RE.replace(template) { match ->
        GenUiFormula.evalText(match.groupValues[1], state)
    }
}

/** Condizioni visible_if / enabled_if. */
internal fun evalGenUiCondition(cond: JSONObject?, state: Map<String, GenUiValue>): Boolean {
    if (cond == null) return true
    val actual = state[cond.optString("key")] ?: return cond.optString("op") in setOf("neq", "empty", "notempty") &&
        evalEmpty(cond.optString("op"), null)
    return when (cond.optString("op")) {
        "eq" -> condEquals(actual, cond.opt("value"))
        "neq" -> !condEquals(actual, cond.opt("value"))
        "gt", "gte", "lt", "lte" -> {
            val l = actual.asNumber()
            val r = (cond.opt("value") as? Number)?.toDouble() ?: return false
            if (!l.isFinite() || !r.isFinite()) return false
            when (cond.optString("op")) {
                "gt" -> l > r
                "gte" -> l >= r
                "lt" -> l < r
                else -> l <= r
            }
        }
        "in" -> {
            val list = cond.optJSONArray("value") ?: return false
            (0 until list.length()).any { condEquals(actual, list.opt(it)) }
        }
        "contains" -> actual.asText().contains(cond.optString("value"))
        "empty" -> evalEmpty("empty", actual)
        "notempty" -> evalEmpty("notempty", actual)
        else -> false
    }
}

private fun condEquals(actual: GenUiValue, expected: Any?): Boolean {
    if (expected == null || expected == JSONObject.NULL) return false
    return when (actual) {
        is GenUiValue.Bool -> (expected as? Boolean) == actual.v
        is GenUiValue.Num -> (expected as? Number)?.toDouble() == actual.v
        is GenUiValue.Str -> expected.toString() == actual.v
    }
}

private fun evalEmpty(op: String, actual: GenUiValue?): Boolean {
    val isEmpty = when (actual) {
        null -> true
        is GenUiValue.Str -> actual.v.isEmpty()
        is GenUiValue.Num -> !actual.v.isFinite()
        is GenUiValue.Bool -> false
    }
    return if (op == "empty") isEmpty else !isEmpty
}
