package com.nemoclaw.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Renderer Generative UI v1. Stato locale per istanza (niente model call per
 * slider/filtri/step); azioni "agent" viaggiano come messaggi [genui ...].
 */
@Composable
internal fun GenUiBlock(block: VisualBlock, onSendText: ((String) -> Unit)? = null) {
    val spec = remember(block.genuiSpec) {
        GenUiValidator.validateBlock(block.id, block.genuiVersion, block.genuiFallback, block.genuiStateSeed, block.genuiSpec)
    }
    if (spec == null) {
        Text(
            block.genuiFallback.ifBlank { "Interfaccia non disponibile in questa versione dell'app." },
            color = Color.White,
            fontSize = 14.sp
        )
        return
    }
    val seed = remember(block.id, block.genuiSpec) { GenUiValidator.initialState(block.genuiStateSeed) }
    val storeKey = remember(block.id, block.genuiSpec) { genUiSnapshotKey(block.id, block.genuiSpec) }
    val ui = remember(storeKey) { GenUiUiState(storeKey, seed) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GenUiNodeView(spec, ui, block.id, onSendText, 0)
    }
}

/** Holder osservabile con write-through sullo store (persistenza al salvataggio). */
internal class GenUiUiState(val key: String, seed: Map<String, GenUiValue>) {
    val map = mutableStateMapOf<String, GenUiValue>().also {
        it.putAll(GenUiStateStore.stateFor(key, seed))
    }
    val initial: Map<String, GenUiValue> = seed.toMap()

    fun snapshot(): Map<String, GenUiValue> = map.toMap()

    fun setValueState(k: String, v: GenUiValue) {
        map[k] = v
        GenUiStateStore.stateFor(key, emptyMap())[k] = v
    }

    fun applyLocal(action: JSONObject) {
        when (action.optString("op")) {
            "set" -> {
                val k = action.optString("key")
                if (k.isNotBlank()) setValueState(k, jsonToGenUiValue(action.opt("value")))
            }
            "toggle" -> {
                val k = action.optString("key")
                if (k.isNotBlank()) {
                    val cur = map[k]
                    setValueState(k, GenUiValue.Bool(!(cur is GenUiValue.Bool && cur.v)))
                }
            }
            "step" -> {
                val k = action.optString("key")
                if (k.isNotBlank()) {
                    val delta = (action.opt("delta") as? Number)?.toDouble() ?: 1.0
                    setValueState(k, GenUiValue.Num((map[k]?.asNumber() ?: 0.0) + delta))
                }
            }
            "goto" -> {
                val k = action.optString("key")
                if (k.isNotBlank()) setValueState(k, jsonToGenUiValue(action.opt("value")))
            }
            "reset" -> {
                map.clear()
                map.putAll(initial)
                GenUiStateStore.reset(key, initial)
            }
        }
    }
}

internal fun jsonToGenUiValue(v: Any?): GenUiValue = when (v) {
    is Number -> GenUiValue.Num(v.toDouble())
    is Boolean -> GenUiValue.Bool(v)
    is String -> GenUiValue.Str(v)
    else -> GenUiValue.Str("")
}

@Composable
private fun GenUiNodeView(node: JSONObject, ui: GenUiUiState, uiId: String, onSendText: ((String) -> Unit)?, level: Int) {
    if (level > 12) return
    val state = ui.snapshot()
    if (!evalGenUiCondition(node.optJSONObject("visible_if"), state)) return
    val enabled = evalGenUiCondition(node.optJSONObject("enabled_if"), state)
    when (node.optString("kind")) {
        "column" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            genUiChildren(node, "children").forEach { GenUiNodeView(it, ui, uiId, onSendText, level + 1) }
        }
        "row" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            genUiChildren(node, "children").forEach { Box(Modifier.weight(1f)) { GenUiNodeView(it, ui, uiId, onSendText, level + 1) } }
        }
        "grid" -> {
            val cols = node.optInt("columns", 2).coerceIn(1, 6)
            genUiChildren(node, "children").chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { Box(Modifier.weight(1f)) { GenUiNodeView(it, ui, uiId, onSendText, level + 1) } }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        "card" -> Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.Elevated),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (node.optString("title").isNotBlank()) {
                    Text(node.optString("title"), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                genUiChildren(node, "children").forEach { GenUiNodeView(it, ui, uiId, onSendText, level + 1) }
            }
        }
        "section" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (node.optString("title").isNotBlank()) {
                Text(node.optString("title").uppercase(), color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            genUiChildren(node, "children").forEach { GenUiNodeView(it, ui, uiId, onSendText, level + 1) }
        }
        "tabs" -> GenUiTabs(node, ui, uiId, onSendText, level, enabled)
        "accordion" -> GenUiAccordion(node, ui, uiId, onSendText, level, enabled)
        "text" -> Text(
            renderGenUiText(node.optString("text"), state),
            color = Color.White,
            fontSize = 14.sp
        )
        "markdown" -> MarkdownBlock(renderGenUiText(node.optString("text"), state))
        "metric" -> MetricBlock(genUiMetricBlock(node, state))
        "progress" -> ProgressBlock(genUiProgressBlock(node, state))
        "button" -> GenUiButton(node, ui, uiId, onSendText, enabled)
        "slider" -> GenUiSlider(node, ui, enabled)
        "switch", "checkbox" -> GenUiToggle(node, ui, node.optString("kind") == "switch", enabled)
        "radio" -> GenUiRadio(node, ui, enabled)
        "select" -> GenUiSelect(node, ui, enabled)
        "input", "textarea", "date" -> GenUiTextInput(node, ui, enabled)
        "number" -> GenUiNumber(node, ui, enabled)
        "table" -> GenUiTable(node, state)
        "chart" -> GenUiChart(node, state)
        "steps" -> GenUiSteps(node, ui, enabled)
        "nodes" -> GenUiNodes(node, ui, enabled)
        "image" -> MediaFileBlock(
            VisualBlock(id = node.optString("id"), type = "media_file", mediaKind = "image", mediaUrl = node.optString("src"), alt = node.optString("alt"))
        )
        "code" -> CodeBlock(node.optString("language", "plaintext"), node.optString("code"), node.optString("filename"))
        "alert", "callout" -> CalloutBlock(
            VisualBlock(id = node.optString("id"), type = "callout", variant = node.optString("variant", "info"), text = renderGenUiText(node.optString("text"), state))
        )
        "divider" -> HorizontalDivider(color = AppColors.Border)
        "spacer" -> Spacer(Modifier.height((node.optDouble("height", 8.0)).toFloat().coerceIn(2f, 64f).dp))
    }
}

private fun genUiChildren(node: JSONObject, key: String): List<JSONObject> {
    val arr = node.optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}

// ---------- blocchi sintetici: riuso renderer esistenti ----------

private fun genUiMetricBlock(node: JSONObject, state: Map<String, GenUiValue>): VisualBlock {
    val formula = node.optString("formula", null)
    val value = if (!formula.isNullOrBlank()) {
        GenUiFormula.evalText(formula, state)
    } else {
        node.opt("value")?.let { jsonToGenUiValue(it).asText() } ?: ""
    }
    return VisualBlock(
        id = node.optString("id"), type = "metric",
        title = renderGenUiText(node.optString("label", node.optString("title")), state),
        value = value, unit = node.optString("unit"),
        status = renderGenUiText(node.optString("status"), state),
        summary = renderGenUiText(node.optString("summary"), state)
    )
}

private fun genUiProgressBlock(node: JSONObject, state: Map<String, GenUiValue>): VisualBlock {
    val formula = node.optString("formula", null)
    val value = if (!formula.isNullOrBlank()) {
        GenUiFormula.evalNumber(formula, state)
    } else {
        node.optDouble("value", 0.0)
    }
    return VisualBlock(
        id = node.optString("id"), type = "progress",
        progressValue = value, unit = node.optString("unit", "%"),
        status = renderGenUiText(node.optString("status"), state),
        summary = renderGenUiText(node.optString("summary"), state)
    )
}

private fun genUiTableBlock(node: JSONObject, state: Map<String, GenUiValue>): VisualBlock {
    // Conservato per compatibilita': il renderer GenUiTable ordina/filtra da solo.
    val cols = (0 until (node.optJSONArray("columns")?.length() ?: 0)).map { i ->
        val c = node.optJSONArray("columns")!!.optJSONObject(i)
        VisualTableColumn(c.optString("key"), c.optString("label"), c.optString("align", "left"), c.optString("format", "text"), true)
    }
    val rows = (0 until (node.optJSONArray("rows")?.length() ?: 0)).map { i ->
        val r = node.optJSONArray("rows")!!.optJSONObject(i)
        r.keys().asSequence().associateWith { r.optString(it) }
    }
    return VisualBlock(id = node.optString("id"), type = "table", title = renderGenUiText(node.optString("title"), state), columns = cols, rows = rows)
}

private fun genUiChartBlock(node: JSONObject, state: Map<String, GenUiValue>): VisualBlock {
    // Conservato per compatibilita': il renderer GenUiChart disegna da solo.
    return VisualBlock(id = node.optString("id"), type = "chart", chartType = node.optString("chart_type", "line"))
}

/** Serie risolte (formule valutate sullo stato): logica pura, testabile. */
internal data class GenUiPoint(val xText: String, val xNum: Double?, val y: Double)
internal data class GenUiResolvedSeries(val name: String, val points: List<GenUiPoint>)

internal fun resolveGenUiSeries(node: JSONObject, state: Map<String, GenUiValue>): List<GenUiResolvedSeries> {
    val out = mutableListOf<GenUiResolvedSeries>()
    val series = node.optJSONArray("series") ?: return out
    for (i in 0 until series.length()) {
        val entry = series.optJSONObject(i) ?: continue
        val points = mutableListOf<GenUiPoint>()
        val arr = entry.optJSONArray("points") ?: JSONArray()
        for (j in 0 until arr.length()) {
            val p = arr.optJSONObject(j) ?: continue
            val xRaw = p.opt("x")
            val xNum = (xRaw as? Number)?.toDouble()
            val xText = if (xRaw is Number) GenUiValue.Num(xRaw.toDouble()).asText() else p.optString("x")
            val yFormula = p.optString("y_formula", null)
            val y = if (!yFormula.isNullOrBlank() && xNum != null) {
                GenUiFormula.evalNumber(yFormula, state, mapOf("x" to GenUiValue.Num(xNum)))
            } else {
                (p.opt("y") as? Number)?.toDouble() ?: 0.0
            }
            points += GenUiPoint(xText, xNum, if (y.isFinite()) y else 0.0)
        }
        out += GenUiResolvedSeries(entry.optString("name"), points)
    }
    return out
}

private val GENUI_PALETTE = listOf(
    Color(0xFF6EA8FE), Color(0xFFFBBF24), Color(0xFFF472B6), Color(0xFFA3E635),
    Color(0xFFC084FC), Color(0xFF5EEAD4), Color(0xFFFCA5A5), Color(0xFF93C5FD)
)

@Composable
private fun GenUiChart(node: JSONObject, state: Map<String, GenUiValue>) {
    val series = resolveGenUiSeries(node, state)
    if (series.isEmpty() || series.all { it.points.isEmpty() }) {
        Text("Nessun dato.", color = AppColors.Muted, fontSize = 13.sp)
        return
    }
    val type = node.optString("chart_type", "line").lowercase()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (node.optString("title").isNotBlank()) {
            Text(renderGenUiText(node.optString("title"), state), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        if (type == "pie") {
            GenUiPie(series.first())
        } else {
            GenUiCartesian(series, type)
        }
        if (series.size > 1 || series.first().name.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                series.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            Modifier
                                .height(10.dp)
                                .fillMaxWidth(0.06f)
                                .background(GENUI_PALETTE[i % GENUI_PALETTE.size], RoundedCornerShape(2.dp))
                        )
                        Text(s.name.ifBlank { "Serie ${i + 1}" }, color = AppColors.Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        val axes = listOfNotNull(
            node.optString("x_label").takeIf { it.isNotBlank() }?.let { "x: $it" },
            node.optString("y_label").takeIf { it.isNotBlank() }?.let { "y: $it" },
            node.optString("unit").takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        if (axes.isNotBlank()) Text(axes, color = AppColors.Muted, fontSize = 12.sp)
    }
}

// ---------- controlli ----------

@Composable
private fun GenUiButton(node: JSONObject, ui: GenUiUiState, uiId: String, onSendText: ((String) -> Unit)?, enabled: Boolean) {
    var cooling by remember { mutableStateOf(0L) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val action = node.optJSONObject("action")
    Button(
        onClick = {
            if (action == null) return@Button
            val now = System.currentTimeMillis()
            if (now - cooling < 1500) return@Button
            cooling = now
            if (action.optString("kind") == "local") {
                ui.applyLocal(action)
            } else if (action.optString("kind") == "agent") {
                val target = onSendText ?: return@Button
                val payload = action.optJSONObject("payload") ?: JSONObject()
                val keys = action.optJSONArray("state_keys") ?: JSONArray()
                val snap = ui.snapshot()
                for (i in 0 until keys.length()) {
                    val k = keys.optString(i)
                    when (val v = snap[k]) {
                        is GenUiValue.Num -> payload.put(k, v.v)
                        is GenUiValue.Str -> payload.put(k, v.v)
                        is GenUiValue.Bool -> payload.put(k, v.v)
                        null -> {}
                    }
                }
                try {
                    target(buildGenUiEvent(uiId, action.optString("action_id"), payload))
                    sendError = null
                } catch (_: Exception) {
                    sendError = "Dati troppo grandi da inviare."
                }
            }
        },
        enabled = enabled && (action == null || action.optString("kind") == "local" || onSendText != null),
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009))
    ) {
        Text(node.optString("label", node.optString("title", "Vai")))
    }
    if (action != null && action.optString("kind") == "agent" && onSendText == null) {
        Text("Rispondi dalla cronologia per usare questo pulsante.", color = AppColors.Muted, fontSize = 11.sp)
    }
    sendError?.let {
        Text(it, color = Color(0xFFE05D5D), fontSize = 11.sp)
    }
}

@Composable
private fun GenUiSlider(node: JSONObject, ui: GenUiUiState, enabled: Boolean) {
    val bind = node.optString("bind")
    val min = node.optDouble("min", 0.0).toFloat()
    val max = node.optDouble("max", 1.0).toFloat()
    val cur = (ui.snapshot()[bind]?.asNumber() ?: node.optDouble("value", min.toDouble())).toFloat().coerceIn(min, max)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(node.optString("label"), color = Color.White, fontSize = 13.sp)
            Text(GenUiValue.Num(cur.toDouble()).asText(), color = AppColors.Muted, fontSize = 13.sp)
        }
        Slider(
            value = cur,
            onValueChange = { if (bind.isNotBlank()) ui.setValueState(bind, GenUiValue.Num(it.toDouble())) },
            valueRange = min..max,
            enabled = enabled && bind.isNotBlank()
        )
    }
}

@Composable
private fun GenUiToggle(node: JSONObject, ui: GenUiUiState, isSwitch: Boolean, enabled: Boolean) {
    val bind = node.optString("bind")
    val checked = (ui.snapshot()[bind] as? GenUiValue.Bool)?.v ?: node.optBoolean("value", false)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isSwitch) {
            Switch(checked = checked, onCheckedChange = { if (bind.isNotBlank() && enabled) ui.setValueState(bind, GenUiValue.Bool(it)) }, enabled = enabled)
        } else {
            Checkbox(checked = checked, onCheckedChange = { if (bind.isNotBlank() && enabled) ui.setValueState(bind, GenUiValue.Bool(it)) }, enabled = enabled)
        }
        Text(node.optString("label"), color = Color.White, fontSize = 14.sp)
    }
}

private fun genUiOptionPairs(node: JSONObject): List<Pair<String, String>> {
    val arr = node.optJSONArray("options") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val opt = arr.opt(i)
        if (opt is JSONObject) {
            val v = opt.optString("value", opt.optString("label"))
            v to opt.optString("label", v)
        } else {
            opt.toString() to opt.toString()
        }
    }
}

@Composable
private fun GenUiRadio(node: JSONObject, ui: GenUiUiState, enabled: Boolean) {
    val bind = node.optString("bind")
    val cur = ui.snapshot()[bind]?.asText() ?: node.optString("value", "")
    Column {
        if (node.optString("label").isNotBlank()) Text(node.optString("label"), color = Color.White, fontSize = 13.sp)
        genUiOptionPairs(node).forEach { (value, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = cur == value, onClick = { if (bind.isNotBlank() && enabled) ui.setValueState(bind, GenUiValue.Str(value)) }, enabled = enabled)
                Text(label, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun GenUiSelect(node: JSONObject, ui: GenUiUiState, enabled: Boolean) {
    val bind = node.optString("bind")
    val cur = ui.snapshot()[bind]?.asText() ?: node.optString("value", "")
    var open by remember { mutableStateOf(false) }
    val pairs = genUiOptionPairs(node)
    Column {
        if (node.optString("label").isNotBlank()) Text(node.optString("label"), color = Color.White, fontSize = 13.sp)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled) {
                Text(pairs.firstOrNull { it.first == cur }?.second ?: cur.ifBlank { "Scegli…" })
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                pairs.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        if (bind.isNotBlank()) ui.setValueState(bind, GenUiValue.Str(value))
                        open = false
                    })
                }
            }
        }
    }
}

@Composable
private fun GenUiTextInput(node: JSONObject, ui: GenUiUiState, enabled: Boolean) {
    val bind = node.optString("bind")
    val kind = node.optString("kind")
    val cur = ui.snapshot()[bind]?.asText() ?: node.optString("value", "")
    if (kind == "date") {
        GenUiDateField(node, ui, bind, cur, enabled)
        return
    }
    Column {
        if (node.optString("label").isNotBlank()) Text(node.optString("label"), color = Color.White, fontSize = 13.sp)
        TextField(
            value = cur,
            onValueChange = { if (bind.isNotBlank() && enabled) ui.setValueState(bind, GenUiValue.Str(it)) },
            enabled = enabled,
            placeholder = { Text(node.optString("placeholder")) },
            singleLine = kind != "textarea",
            minLines = if (kind == "textarea") 3 else 1
        )
    }
}

@Composable
private fun GenUiNumber(node: JSONObject, ui: GenUiUiState, enabled: Boolean) {
    val bind = node.optString("bind")
    val cur = ui.snapshot()[bind]?.asText() ?: node.optString("value", "")
    Column {
        if (node.optString("label").isNotBlank()) Text(node.optString("label"), color = Color.White, fontSize = 13.sp)
        TextField(
            value = cur,
            onValueChange = { if (bind.isNotBlank() && enabled) ui.setValueState(bind, GenUiValue.Str(it)) },
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenUiDateField(node: JSONObject, ui: GenUiUiState, bind: String, cur: String, enabled: Boolean) {
    var show by remember { mutableStateOf(false) }
    Column {
        if (node.optString("label").isNotBlank()) Text(node.optString("label"), color = Color.White, fontSize = 13.sp)
        OutlinedButton(onClick = { show = true }, enabled = enabled) {
            Text(cur.ifBlank { "Scegli data…" })
        }
    }
    if (show) {
        val picker = rememberDatePickerState()
        DatePickerDialog(onDismissRequest = { show = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let {
                    if (bind.isNotBlank()) {
                        ui.setValueState(bind, GenUiValue.Str(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it))))
                    }
                }
                show = false
            }) { Text("OK") }
        }) {
            DatePicker(state = picker)
        }
    }
}

@Composable
private fun GenUiSteps(node: JSONObject, ui: GenUiUiState, enabled: Boolean) {
    val items = (0 until (node.optJSONArray("items")?.length() ?: 0)).mapNotNull { node.optJSONArray("items")!!.optJSONObject(it) }
    if (items.isEmpty()) return
    val bind = node.optString("bind")
    var localStep by remember { mutableStateOf(0) }
    val idx = if (bind.isNotBlank()) {
        ((ui.snapshot()[bind]?.asNumber() ?: 0.0).toInt()).coerceIn(0, items.size - 1)
    } else {
        localStep.coerceIn(0, items.size - 1)
    }
    val item = items[idx]
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(item.optString("title"), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(item.optString("text"), color = Color.White, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEachIndexed { i, _ ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clickable(enabled = enabled) {
                            localStep = i
                            if (bind.isNotBlank()) ui.setValueState(bind, GenUiValue.Num(i.toDouble()))
                        }
                        .let {
                            it
                        }
                ) {
                    Surface(
                        color = if (i <= idx) AppColors.Accent else AppColors.Border,
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    ) {}
                }
            }
        }
        Text("Passo ${idx + 1} di ${items.size}", color = AppColors.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun GenUiNodes(node: JSONObject, ui: GenUiUiState, enabled: Boolean) {
    val entries = (0 until (node.optJSONArray("nodes")?.length() ?: 0)).mapNotNull { node.optJSONArray("nodes")!!.optJSONObject(it) }
    if (entries.isEmpty()) return
    val bind = node.optString("bind")
    var local by remember { mutableStateOf(entries.first().optString("id")) }
    val selected = if (bind.isNotBlank()) ui.snapshot()[bind]?.asText()?.takeIf { id -> entries.any { it.optString("id") == id } } ?: local else local
    val edges = (0 until (node.optJSONArray("edges")?.length() ?: 0)).mapNotNull {
        node.optJSONArray("edges")!!.optJSONArray(it)?.let { e -> e.optString(0) to e.optString(1) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { entry ->
            val id = entry.optString("id")
            val isSel = id == selected
            Surface(
                color = if (isSel) AppColors.Accent.copy(alpha = 0.25f) else AppColors.Elevated,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (isSel) AppColors.Accent else AppColors.Border),
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) {
                    local = id
                    if (bind.isNotBlank()) ui.setValueState(bind, GenUiValue.Str(id))
                }
            ) {
                Text(entry.optString("label", id), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
            }
        }
        entries.firstOrNull { it.optString("id") == selected }?.let { entry ->
            if (entry.optString("detail").isNotBlank()) {
                Text(entry.optString("detail"), color = AppColors.Muted, fontSize = 13.sp)
            }
        }
        if (edges.isNotEmpty()) {
            Text(edges.joinToString("   ") { "${it.first} → ${it.second}" }, color = AppColors.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun GenUiTabs(node: JSONObject, ui: GenUiUiState, uiId: String, onSendText: ((String) -> Unit)?, level: Int, enabled: Boolean) {
    val tabs = (0 until (node.optJSONArray("tabs")?.length() ?: 0)).mapNotNull { node.optJSONArray("tabs")!!.optJSONObject(it) }
    if (tabs.isEmpty()) return
    val bind = node.optString("bind")
    var local by remember { mutableStateOf(0) }
    val selected = if (bind.isNotBlank()) {
        ((ui.snapshot()[bind]?.asNumber() ?: 0.0).toInt()).coerceIn(0, tabs.size - 1)
    } else local
    Column {
        TabRow(selectedTabIndex = selected) {
            tabs.forEachIndexed { i, tab ->
                Tab(selected = i == selected, onClick = {
                    local = i
                    if (bind.isNotBlank() && enabled) ui.setValueState(bind, GenUiValue.Num(i.toDouble()))
                }, text = { Text(tab.optString("title")) }, enabled = enabled)
            }
        }
        Spacer(Modifier.height(6.dp))
        tabs[selected].optJSONObject("body")?.let { GenUiNodeView(it, ui, uiId, onSendText, level + 1) }
    }
}

@Composable
private fun GenUiPie(series: GenUiResolvedSeries) {
    val values = series.points.map { it.y.coerceAtLeast(0.0) }
    val total = values.sum()
    if (total <= 0.0) {
        Text("Nessun dato.", color = AppColors.Muted, fontSize = 13.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            var start = -90f
            val radius = minOf(size.width, size.height) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            values.forEachIndexed { i, v ->
                val sweep = (v / total * 360.0).toFloat()
                drawArc(GENUI_PALETTE[i % GENUI_PALETTE.size], start, sweep, true, topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2))
                start += sweep
            }
        }
        series.points.forEachIndexed { i, p ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.height(10.dp).fillMaxWidth(0.05f).background(GENUI_PALETTE[i % GENUI_PALETTE.size], RoundedCornerShape(2.dp)))
                Text("${p.xText}: ${GenUiValue.Num(p.y).asText()} (${(p.y / total * 100).toInt()}%)", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun GenUiCartesian(series: List<GenUiResolvedSeries>, type: String) {
    val numericX = series.all { s -> s.points.all { it.xNum != null } }
    val maxSlots = series.maxOf { it.points.size }.coerceAtLeast(1)
    val allY = series.flatMap { it.points.map { p -> p.y } }
    var lo = allY.minOrNull() ?: 0.0
    var hi = allY.maxOrNull() ?: 1.0
    if (type == "bar") lo = minOf(lo, 0.0)
    if (hi - lo < 1e-9) {
        hi = lo + 1.0
    } else {
        val pad = (hi - lo) * 0.08
        hi += pad
        if (type != "bar") lo -= pad
    }
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val left = 46.dp.toPx()
        val bottom = 26.dp.toPx()
        val top = 8.dp.toPx()
        val plotW = size.width - left - 8.dp.toPx()
        val plotH = size.height - top - bottom
        if (plotW <= 0 || plotH <= 0) return@Canvas
        fun xPos(s: Int, n: Int): Float {
            return if (numericX) {
                val xs = series.flatMap { it.points.mapNotNull { p -> p.xNum } }
                val xLo = xs.minOrNull() ?: 0.0
                val xHi = xs.maxOrNull() ?: 1.0
                val span = (xHi - xLo).takeIf { it > 1e-9 } ?: 1.0
                (left + (series[s].points[n].xNum!! - xLo) / span * plotW).toFloat()
            } else {
                (left + (n + 0.5f) / maxSlots * plotW).toFloat()
            }
        }
        fun yPos(v: Double): Float = (top + (1.0 - (v - lo) / (hi - lo)) * plotH).toFloat()
        val grid = AppColors.Border.copy(alpha = 0.6f)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.GRAY
            textSize = 10.dp.toPx()
        }
        for (g in 0..4) {
            val v = lo + (hi - lo) * g / 4.0
            val y = yPos(v)
            drawLine(grid, Offset(left, y), Offset(left + plotW, y), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(GenUiValue.Num(v).asText(), 2.dp.toPx(), y + 4.dp.toPx(), paint)
        }
        // etichette x (max 6)
        val labelIdx = (0 until maxSlots).filterIndexed { i, _ -> maxSlots <= 6 || i % ((maxSlots + 5) / 6) == 0 }
        val firstSeries = series.first()
        for (n in labelIdx) {
            if (n >= firstSeries.points.size) continue
            drawContext.canvas.nativeCanvas.drawText(
                firstSeries.points[n].xText.take(10), xPos(0, n) - 12.dp.toPx(),
                size.height - 8.dp.toPx(), paint
            )
        }
        series.forEachIndexed { s, serie ->
            val color = GENUI_PALETTE[s % GENUI_PALETTE.size]
            when (type) {
                "bar" -> {
                    val slotW = plotW / maxSlots
                    val barW = (slotW / series.size * 0.7f).coerceAtLeast(3f)
                    serie.points.forEachIndexed { n, p ->
                        val cx = if (numericX) xPos(s, n) else left + (n + 0.5f) / maxSlots * plotW + (s - (series.size - 1) / 2f) * barW
                        val y0 = yPos(0.0).coerceIn(top, top + plotH)
                        val y1 = yPos(p.y).coerceIn(top, top + plotH)
                        drawRoundRect(color, topLeft = Offset(cx - barW / 2, minOf(y0, y1)), size = Size(barW, abs(y0 - y1).coerceAtLeast(2f)))
                    }
                }
                "scatter" -> {
                    serie.points.forEachIndexed { n, p ->
                        drawCircle(color, 3.5.dp.toPx(), Offset(xPos(s, n), yPos(p.y)))
                    }
                }
                else -> {
                    // line + area
                    if (serie.points.size == 1) {
                        drawCircle(color, 4.dp.toPx(), Offset(xPos(s, 0), yPos(serie.points[0].y)))
                    } else if (serie.points.size > 1) {
                        val path = Path()
                        serie.points.forEachIndexed { n, p ->
                            val pt = Offset(xPos(s, n), yPos(p.y))
                            if (n == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                        }
                        if (type == "area") {
                            val fill = Path().apply {
                                addPath(path)
                                lineTo(xPos(s, serie.points.size - 1), yPos(0.0).coerceIn(top, top + plotH))
                                lineTo(xPos(s, 0), yPos(0.0).coerceIn(top, top + plotH))
                                close()
                            }
                            drawPath(fill, color.copy(alpha = 0.25f))
                        }
                        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
                        serie.points.forEachIndexed { n, p ->
                            drawCircle(color, 3.dp.toPx(), Offset(xPos(s, n), yPos(p.y)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenUiTable(node: JSONObject, state: Map<String, GenUiValue>) {
    val nodeId = node.optString("id")
    var sortCol by remember(nodeId) { mutableStateOf<String?>(null) }
    var sortAsc by remember(nodeId) { mutableStateOf(true) }
    var filter by remember(nodeId) { mutableStateOf("") }
    val cols = (0 until (node.optJSONArray("columns")?.length() ?: 0)).mapNotNull {
        node.optJSONArray("columns")!!.optJSONObject(it)
    }
    if (cols.isEmpty()) return
    var rows = (0 until (node.optJSONArray("rows")?.length() ?: 0)).mapNotNull { i ->
        node.optJSONArray("rows")!!.optJSONObject(i)?.let { r ->
            r.keys().asSequence().associateWith { k -> renderGenUiText(r.optString(k), state) }
        }
    }
    if (node.optBoolean("filter", false) && filter.isNotBlank()) {
        val q = filter.lowercase()
        rows = rows.filter { row -> row.values.any { it.lowercase().contains(q) } }
    }
    sortCol?.let { key ->
        rows = rows.sortedWith { a, b ->
            val av = a[key].orEmpty()
            val bv = b[key].orEmpty()
            val an = av.toDoubleOrNull()
            val bn = bv.toDoubleOrNull()
            val cmp = if (an != null && bn != null) an.compareTo(bn) else av.compareTo(bv)
            if (sortAsc) cmp else -cmp
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (node.optString("title").isNotBlank()) {
            Text(renderGenUiText(node.optString("title"), state), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        if (node.optBoolean("filter", false)) {
            TextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Filtra…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                cols.forEach { c ->
                    val key = c.optString("key")
                    val arrow = if (sortCol == key) (if (sortAsc) " ▲" else " ▼") else ""
                    Box(Modifier.clickable {
                        if (sortCol == key) sortAsc = !sortAsc else {
                            sortCol = key
                            sortAsc = true
                        }
                    }) {
                        TableCell(c.optString("label") + arrow, header = true)
                    }
                }
            }
            rows.take(100).forEach { row ->
                Row {
                    cols.forEach { c ->
                        TableCell(row[c.optString("key")].orEmpty(), header = false)
                    }
                }
            }
        }
        Text("${rows.size} righe", color = AppColors.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun GenUiAccordion(node: JSONObject, ui: GenUiUiState, uiId: String, onSendText: ((String) -> Unit)?, level: Int, enabled: Boolean) {
    val items = (0 until (node.optJSONArray("items")?.length() ?: 0)).mapNotNull { node.optJSONArray("items")!!.optJSONObject(it) }
    val open = remember { mutableStateMapOf<Int, Boolean>() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, item ->
            val isOpen = open[i] ?: false
            Surface(
                color = AppColors.Elevated,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, AppColors.Border),
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { open[i] = !isOpen }
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text((if (isOpen) "▾ " else "▸ ") + item.optString("title"), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (isOpen) {
                        Spacer(Modifier.height(6.dp))
                        if (item.optString("text").isNotBlank()) Text(item.optString("text"), color = Color.White, fontSize = 14.sp)
                        item.optJSONObject("body")?.let { GenUiNodeView(it, ui, uiId, onSendText, level + 1) }
                    }
                }
            }
        }
    }
}
