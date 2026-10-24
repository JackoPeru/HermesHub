package com.nemoclaw.chat

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.nemoclaw.chat.ui.apple.AppleType
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal data class RegisteredDevice(
    val id: String,
    val label: String,
    val revoked: Boolean,
    val hasToken: Boolean
)

// Colori dal sistema (niente palette parallele): Panel/Elevated/Accent/Success.
private fun hwIconForComponent(id: String, title: String): ImageVector {
    // Ri riusa solo icone gia presenti nel file (Speed / List).
    val key = id.lowercase() + " " + title.lowercase()
    return when {
        key.contains("net") || key.contains("eth") -> Icons.AutoMirrored.Rounded.List
        key.contains("mem") || key.contains("swap") || key.contains("disk") || key.contains("ssd") -> Icons.Rounded.List
        else -> Icons.Rounded.Speed
    }
}

@Composable
internal fun ServerMetric(title: String, value: String, detail: String, valueColor: Color = Color.White) {
    // Solo rivestimento: stessa firma, stessi dati, card scura 20dp + hairline 0.5dp.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.Panel)
            .border(BorderStroke(0.5.dp, AppColors.Border), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Text(value, color = valueColor, fontSize = AppleType.RowTitle, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Text(detail, color = AppColors.Faint, fontSize = AppleType.Footnote)
        HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
    }
}

@Composable
internal fun HardwareScreen(context: Context, settings: AppSettings) {
    var snapshot by remember(settings) { mutableStateOf(HardwareSnapshot()) }
    var previous by remember(settings) { mutableStateOf<HardwareSnapshot?>(null) }
    var selectedComponentId by rememberSaveable { mutableStateOf("cpu") }
    var history by remember { mutableStateOf<Map<String, List<HardwareHistoryPoint>>>(emptyMap()) }
    val apiKey = remember(settings.gatewayUrl) { loadGatewaySecret(context) }

    LaunchedEffect(settings.gatewayUrl, apiKey) {
        var failures = 0
        while (true) {
            val next = loadHardwareSnapshot(settings, apiKey)
            previous = snapshot.takeIf { it.status != "loading" }
            snapshot = next
            failures = if (next.status == "unavailable") failures + 1 else 0
            // Backoff su gateway spento: da 1s fino a 30s, niente raffica.
            kotlinx.coroutines.delay((1000L shl failures.coerceAtMost(4)).coerceAtMost(30_000L))
        }
    }

    val dtSeconds = previous?.let { ((snapshot.timestampMs - it.timestampMs).coerceAtLeast(100L)) / 1000.0 } ?: 0.0
    val downRate = previous?.let { ((snapshot.networkBytesReceived - it.networkBytesReceived).coerceAtLeast(0) / dtSeconds).toLong() } ?: 0L
    val upRate = previous?.let { ((snapshot.networkBytesSent - it.networkBytesSent).coerceAtLeast(0) / dtSeconds).toLong() } ?: 0L
    val temperatureViews = remember(snapshot.temperatures) { snapshot.temperatures.toHardwareTemperatureViews() }
    val components = remember(snapshot, previous, downRate, upRate, temperatureViews) {
        buildHardwareComponents(snapshot, temperatureViews, downRate, upRate)
    }
    val selectedComponent = components.firstOrNull { it.id == selectedComponentId } ?: components.firstOrNull()

    LaunchedEffect(snapshot.timestampMs, components) {
        if (components.isNotEmpty()) {
            if (components.none { it.id == selectedComponentId }) {
                selectedComponentId = components.first().id
            }
            val next = history.toMutableMap()
            components.forEach { component ->
                val points = next[component.id].orEmpty()
                next[component.id] = (points + HardwareHistoryPoint(component.utilizationPercent, component.temperatureC)).takeLast(120)
            }
            history = next
        }
    }

    // Solo stile mock: sfondo nero puro, niente aurora, card scure 20dp + hairline 0.5dp.
    // Logica/dati/stati/stringhe/firme invariati sopra.
    val tempView = temperatureViews.firstOrNull()
    val tempSourceComponent = components.firstOrNull { it.temperatureC != null }
    val tempHistoryValues = (tempSourceComponent?.let { history[it.id].orEmpty() }.orEmpty()).mapNotNull { it.temperatureC }
    val tempCurrent = tempView?.currentC ?: tempSourceComponent?.temperatureC
    val online = snapshot.status != "unavailable"
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        item {
            // Header: titolo + sottotitolo esistenti. Niente bottone indietro
            // decorativo (nessun callback: sarebbe un affordance morto).
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Prestazioni", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                Text(snapshot.message, color = AppColors.Faint, fontSize = AppleType.RowTitleSmall)
            }
        }
        item {
            // Card server scura 20dp: icona esistente in tile scuro + hostname + stato + dettaglio OS.
            // Valori esistenti: snapshot.hostname / snapshot.status / operatingSystem / architecture / uptime / processCount.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppColors.Panel)
                    .border(BorderStroke(0.5.dp, AppColors.Border), RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppColors.Elevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Speed, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(snapshot.hostname, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (online) AppColors.Success else AppColors.Faint)
                        )
                        Text(
                            snapshot.status,
                            color = if (online) AppColors.Success else AppColors.Faint,
                            fontSize = AppleType.Footnote,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                    Text(
                        "${snapshot.operatingSystem} ${snapshot.architecture}. Uptime ${formatHardwareUptime(snapshot.uptimeSeconds)}. Processi ${snapshot.processCount}.",
                        color = AppColors.Faint,
                        fontSize = AppleType.Footnote,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        item {
            // Griglia 2 colonne di card scure 20dp dagli stessi components esistenti.
            // Selezione invariata: tap -> selectedComponentId (stessa logica di HardwareComponentList).
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                components.chunked(2).forEach { rowItems ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowItems.forEach { component ->
                            val selected = component.id == selectedComponent?.id
                            val points = history[component.id].orEmpty().map { it.utilizationPercent }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(AppColors.Panel)
                                    .border(
                                        BorderStroke(0.5.dp, if (selected) AppColors.Accent else AppColors.Border),
                                        RoundedCornerShape(20.dp)
                                    )
                                    .clickable { selectedComponentId = component.id }
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(AppColors.Elevated),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(hwIconForComponent(component.id, component.title), contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(20.dp))
                                }
                                Text(component.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(component.primaryValue, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(component.subtitle, color = AppColors.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (component.id == "network") {
                                    HwMiniSparkline(values = points)
                                } else {
                                    LinearProgressIndicator(
                                        progress = { (component.utilizationPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = AppColors.Accent,
                                        trackColor = AppColors.Border
                                    )
                                }
                            }
                        }
                        if (rowItems.size == 1) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item {
            // Card larga Temperatura: stessi dati esistenti (temperatureViews/history), valore ambra + area chart.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppColors.Panel)
                    .border(BorderStroke(0.5.dp, AppColors.Border), RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppColors.Elevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Speed, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(22.dp))
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(tempView?.title ?: tempSourceComponent?.title ?: "Temperatura", color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatTemperature(tempCurrent), color = AppColors.Accent, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HwTempAreaChart(values = tempHistoryValues)
            }
        }
        item {
            // Riga "Dettagli": testi esistenti (DETTAGLI + subtitle componente), poi dettaglio esistente rivestito.
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(AppColors.Panel)
                        .border(BorderStroke(0.5.dp, AppColors.Border), RoundedCornerShape(20.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppColors.Elevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.List, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(22.dp))
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("DETTAGLI", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(selectedComponent?.subtitle ?: snapshot.message, color = AppColors.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HardwareComponentDetail(
                    component = selectedComponent,
                    history = selectedComponent?.let { history[it.id].orEmpty() }.orEmpty(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
    }
}

@Composable
internal fun HardwareComponentList(
    components: List<HardwareComponentView>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Solo rivestimento: stessi dati/firme, card scure 20dp + hairline 0.5dp + ambra.
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("COMPONENTI", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        if (components.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppColors.Panel)
                    .border(BorderStroke(0.5.dp, AppColors.Border), RoundedCornerShape(20.dp))
                    .padding(vertical = 24.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                Text("Nessun componente", color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("In attesa di dati hardware dal server.", color = AppColors.Faint, fontSize = AppleType.RowTitleSmall, textAlign = TextAlign.Center)
            }
        }
        components.forEachIndexed { index, component ->
            val selected = component.id == selectedId
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppColors.Panel)
                    .border(BorderStroke(0.5.dp, if (selected) AppColors.Accent else AppColors.Border), RoundedCornerShape(20.dp))
                    .clickable { onSelect(component.id) }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(component.title, color = Color.White, fontSize = AppleType.RowTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(component.primaryValue, color = AppColors.Accent, fontSize = AppleType.RowTitle, fontWeight = FontWeight.SemiBold)
                }
                Text(component.subtitle, color = AppColors.Faint, fontSize = AppleType.Footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(
                    progress = { (component.utilizationPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = AppColors.Accent,
                    trackColor = AppColors.Border
                )
                if (selected) {
                    Text("SELEZIONATO", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
            if (index != components.lastIndex) {
                HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
internal fun HardwareComponentDetail(component: HardwareComponentView?, history: List<HardwareHistoryPoint>, modifier: Modifier = Modifier) {
    // Solo rivestimento: stessi dati/firme, card scura 20dp + hairline 0.5dp + ambra.
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.Panel)
            .border(BorderStroke(0.5.dp, AppColors.Border), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (component == null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                Text("Nessun componente disponibile.", color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("Seleziona un componente per vederne i dettagli.", color = AppColors.Faint, fontSize = AppleType.RowTitleSmall, textAlign = TextAlign.Center)
            }
            return@Column
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(component.title, color = Color.White, fontSize = AppleType.ScreenTitle, fontWeight = FontWeight.SemiBold)
                Text(component.subtitle, color = AppColors.Faint, fontSize = AppleType.RowTitleSmall)
            }
            Text(component.primaryValue, color = AppColors.Accent, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
        HardwareLineChart("Utilizzo", history.map { it.utilizationPercent }, 100.0, "%", AppColors.Accent)
        HardwareLineChart("Temperatura", history.mapNotNull { it.temperatureC }, 100.0, " C", AppColors.Accent)
        HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
        Text("DETTAGLI", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            component.stats.forEach { stat ->
                Column(modifier = Modifier.widthIn(min = 120.dp, max = 220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stat.label.uppercase(), color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(stat.value, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun HardwareLineChart(title: String, values: List<Double>, maxValue: Double, unit: String, color: Color) {
    // Solo rivestimento: stessi dati/firma, linea + area ambra su card scura, hairline 0.5dp.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text((if (values.isEmpty()) "$title: n/d" else "$title: ${String.format(java.util.Locale.US, "%.1f", values.last())}$unit").uppercase(), color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Canvas(modifier = Modifier.fillMaxWidth().height(170.dp).padding(vertical = 8.dp)) {
            val chartWidth = size.width
            val chartHeight = size.height
            repeat(5) { index ->
                val y = chartHeight * index / 4f
                drawLine(color = AppColors.Border, start = Offset(0f, y), end = Offset(chartWidth, y), strokeWidth = 1f)
            }
            if (values.size < 2) return@Canvas
            val clamped = values.takeLast(120).map { it.coerceIn(0.0, maxValue) }
            val step = chartWidth / (clamped.size - 1).coerceAtLeast(1)
            val fill = Path().apply {
                moveTo(0f, chartHeight)
                clamped.forEachIndexed { i, v ->
                    lineTo(step * i, chartHeight - (v / maxValue * chartHeight).toFloat())
                }
                lineTo(chartWidth, chartHeight)
                close()
            }
            drawPath(path = fill, brush = Brush.verticalGradient(0f to color.copy(alpha = 0.28f), 1f to color.copy(alpha = 0.02f)))
            for (i in 1 until clamped.size) {
                val x1 = step * (i - 1)
                val x2 = step * i
                val y1 = chartHeight - (clamped[i - 1] / maxValue * chartHeight).toFloat()
                val y2 = chartHeight - (clamped[i] / maxValue * chartHeight).toFloat()
                drawLine(color = color, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = 4f, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun HwMiniSparkline(values: List<Double>) {
    // Mini-sparkline ambra per la card Rete: stessi history esistenti, nessun nuovo dato.
    Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
        if (values.size < 2) {
            drawLine(color = AppColors.Accent.copy(alpha = 0.6f), start = Offset(0f, size.height / 2f), end = Offset(size.width, size.height / 2f), strokeWidth = 3f, cap = StrokeCap.Round)
            return@Canvas
        }
        val clamped = values.takeLast(60).map { it.coerceIn(0.0, 100.0) }
        val step = size.width / (clamped.size - 1).coerceAtLeast(1)
        for (i in 1 until clamped.size) {
            val x1 = step * (i - 1)
            val x2 = step * i
            val y1 = size.height - (clamped[i - 1] / 100.0 * size.height).toFloat()
            val y2 = size.height - (clamped[i] / 100.0 * size.height).toFloat()
            drawLine(color = AppColors.Accent, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = 3f, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun HwTempAreaChart(values: List<Double>) {
    // Area chart: stessi valori history, griglia sobria. Niente assi numerate:
    // il polling e variabile, le ore assolute sarebbero false.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.fillMaxWidth()) {
            val chartH = 160.dp
            Canvas(modifier = Modifier.fillMaxWidth().height(chartH)) {
                    val w = size.width
                    val h = size.height
                    repeat(4) { index ->
                        val y = h * index / 3f
                        drawLine(color = AppColors.Border, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
                    }
                    if (values.size >= 2) {
                        val clamped = values.takeLast(120).map { it.coerceIn(0.0, 100.0) }
                        val step = w / (clamped.size - 1).coerceAtLeast(1)
                        val fill = Path().apply {
                            moveTo(0f, h)
                            clamped.forEachIndexed { i, v ->
                                lineTo(step * i, h - (v / 100.0 * h).toFloat())
                            }
                            lineTo(w, h)
                            close()
                        }
                        drawPath(path = fill, brush = Brush.verticalGradient(0f to AppColors.Accent.copy(alpha = 0.32f), 1f to AppColors.Accent.copy(alpha = 0.02f)))
                        for (i in 1 until clamped.size) {
                            val x1 = step * (i - 1)
                            val x2 = step * i
                            val y1 = h - (clamped[i - 1] / 100.0 * h).toFloat()
                            val y2 = h - (clamped[i] / 100.0 * h).toFloat()
                            drawLine(color = AppColors.Accent, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = 3f, cap = StrokeCap.Round)
                        }
                    } else {
                        drawLine(color = AppColors.Accent.copy(alpha = 0.7f), start = Offset(0f, h / 2f), end = Offset(w, h / 2f), strokeWidth = 3f, cap = StrokeCap.Round)
                    }
                }
            }
        }
}

internal fun buildHardwareComponents(
    snapshot: HardwareSnapshot,
    temperatures: List<HardwareTemperatureView>,
    downRate: Long,
    upRate: Long
): List<HardwareComponentView> {
    val tempByTitle = temperatures.associateBy { it.title }
    val components = mutableListOf<HardwareComponentView>()
    val cpuTemp = tempByTitle["CPU package"]?.currentC
    components += HardwareComponentView(
        id = "cpu",
        title = "CPU",
        subtitle = snapshot.processor.takeUnless { it == "-" } ?: "${snapshot.physicalCores} core / ${snapshot.logicalCores} thread",
        primaryValue = "${snapshot.cpuPercent.roundToInt().coerceIn(0, 100)}%",
        utilizationPercent = snapshot.cpuPercent,
        temperatureC = cpuTemp,
        stats = listOf(
            HardwareStatView("Utilizzo", "${snapshot.cpuPercent.roundToInt().coerceIn(0, 100)}%"),
            HardwareStatView("Temperatura", formatTemperature(cpuTemp)),
            HardwareStatView("Core", "${snapshot.physicalCores} fisici / ${snapshot.logicalCores} thread"),
            HardwareStatView("Frequenza", "${formatMhz(snapshot.currentMhz)} / max ${formatMhz(snapshot.maxMhz)}"),
            HardwareStatView("Processi", "${snapshot.processCount}"),
            HardwareStatView("Uptime", formatHardwareUptime(snapshot.uptimeSeconds))
        )
    )
    components += HardwareComponentView(
        id = "memory",
        title = "Memoria",
        subtitle = "${snapshot.memoryUsedBytes.toReadableFileSize()} / ${snapshot.memoryTotalBytes.toReadableFileSize()}",
        primaryValue = "${snapshot.memoryPercent.roundToInt().coerceIn(0, 100)}%",
        utilizationPercent = snapshot.memoryPercent,
        temperatureC = null,
        stats = listOf(
            HardwareStatView("Uso RAM", "${snapshot.memoryPercent.roundToInt().coerceIn(0, 100)}%"),
            HardwareStatView("Usata", snapshot.memoryUsedBytes.toReadableFileSize()),
            HardwareStatView("Totale", snapshot.memoryTotalBytes.toReadableFileSize()),
            HardwareStatView("Disponibile", snapshot.memoryAvailableBytes.toReadableFileSize())
        )
    )
    if (snapshot.swapTotalBytes > 0L) {
        components += HardwareComponentView(
            id = "swap",
            title = "Swap",
            subtitle = "${snapshot.swapUsedBytes.toReadableFileSize()} / ${snapshot.swapTotalBytes.toReadableFileSize()}",
            primaryValue = "${snapshot.swapPercent.roundToInt().coerceIn(0, 100)}%",
            utilizationPercent = snapshot.swapPercent,
            temperatureC = null,
            stats = listOf(
                HardwareStatView("Uso swap", "${snapshot.swapPercent.roundToInt().coerceIn(0, 100)}%"),
                HardwareStatView("Usata", snapshot.swapUsedBytes.toReadableFileSize()),
                HardwareStatView("Totale", snapshot.swapTotalBytes.toReadableFileSize())
            )
        )
    }
    val networkPercent = ((downRate + upRate).toDouble() / (125.0 * 1024.0 * 1024.0) * 100.0).coerceIn(0.0, 100.0)
    components += HardwareComponentView(
        id = "network",
        title = "Ethernet",
        subtitle = "Down ${downRate.toReadableFileSize()}/s / Up ${upRate.toReadableFileSize()}/s",
        primaryValue = "${(downRate + upRate).toReadableFileSize()}/s",
        utilizationPercent = networkPercent,
        temperatureC = temperatures.firstOrNull { it.title.startsWith("Ethernet") }?.currentC,
        stats = listOf(
            HardwareStatView("Ricezione", "${downRate.toReadableFileSize()}/s"),
            HardwareStatView("Invio", "${upRate.toReadableFileSize()}/s"),
            HardwareStatView("Totale ricevuto", snapshot.networkBytesReceived.toReadableFileSize()),
            HardwareStatView("Totale inviato", snapshot.networkBytesSent.toReadableFileSize())
        )
    )
    snapshot.gpus.sortedBy { it.index }.forEach { gpu ->
        val memoryPercent = if (gpu.memoryTotalBytes > 0L) {
            (gpu.memoryUsedBytes.toDouble() / gpu.memoryTotalBytes.toDouble() * 100.0).coerceIn(0.0, 100.0)
        } else {
            gpu.memoryUtilizationPercent
        }
        components += HardwareComponentView(
            id = "gpu-${gpu.index}",
            title = "GPU ${gpu.index}",
            subtitle = gpu.name.removePrefix("NVIDIA ").trim(),
            primaryValue = "${gpu.utilizationPercent.roundToInt().coerceIn(0, 100)}%",
            utilizationPercent = gpu.utilizationPercent,
            temperatureC = gpu.temperatureC,
            stats = listOf(
                HardwareStatView("Utilizzo GPU", "${gpu.utilizationPercent.roundToInt().coerceIn(0, 100)}%"),
                HardwareStatView("Temperatura", formatTemperature(gpu.temperatureC)),
                HardwareStatView("VRAM", "${gpu.memoryUsedBytes.toReadableFileSize()} / ${gpu.memoryTotalBytes.toReadableFileSize()} (${memoryPercent.roundToInt().coerceIn(0, 100)}%)"),
                HardwareStatView("Power", "${formatWatts(gpu.powerDrawWatts)} / ${formatWatts(gpu.powerLimitWatts)}"),
                HardwareStatView("Driver", gpu.driverVersion)
            )
        )
    }
    buildHardwareDiskGroups(snapshot.disks).forEachIndexed { index, disk ->
        val diskTemp = if (disk.isSsd) tempByTitle["SSD NVMe"]?.currentC else null
        components += HardwareComponentView(
            id = "disk-$index",
            title = if (disk.isSsd) "SSD $index" else "Disco $index",
            subtitle = disk.subtitle,
            primaryValue = "${disk.percent.roundToInt().coerceIn(0, 100)}%",
            utilizationPercent = disk.percent,
            temperatureC = diskTemp,
            stats = listOf(
                HardwareStatView("Spazio usato", "${disk.percent.roundToInt().coerceIn(0, 100)}%"),
                HardwareStatView("Usato", disk.usedBytes.toReadableFileSize()),
                HardwareStatView("Libero", disk.freeBytes.toReadableFileSize()),
                HardwareStatView("Totale", disk.totalBytes.toReadableFileSize()),
                HardwareStatView("Temperatura", formatTemperature(diskTemp)),
                HardwareStatView("Partizioni", disk.partitionsText),
                HardwareStatView("Device", disk.devicesText)
            )
        )
    }
    return components
}

internal fun buildHardwareDiskGroups(disks: List<HardwareDisk>): List<HardwareDiskGroup> {
    val physicalKeys = disks.mapNotNull { tryHardwarePhysicalDiskKey(it.device) }.distinct()
    val singlePhysicalKey = physicalKeys.singleOrNull()
    return disks
        .groupBy { hardwarePhysicalDiskKey(it.device, singlePhysicalKey) }
        .toSortedMap()
        .map { (key, itemsRaw) ->
            val items = itemsRaw.sortedBy { diskMountSortKey(it.mountpoint) }
            val total = items.sumOf { it.totalBytes.coerceAtLeast(0L) }
            val used = items.sumOf { it.usedBytes.coerceAtLeast(0L) }
            val free = items.sumOf { it.freeBytes.coerceAtLeast(0L) }
            val percent = if (total > 0L) used.toDouble() / total.toDouble() * 100.0 else 0.0
            val isSsd = key.contains("nvme", ignoreCase = true) || items.any { it.device.contains("nvme", ignoreCase = true) }
            val filesystems = items.map { it.fileSystem }.filter { it.isNotBlank() }.distinct().joinToString(", ")
            val subtitle = if (items.size == 1) {
                "${items.first().mountpoint} (${items.first().fileSystem})"
            } else {
                "${items.size} partizioni - $filesystems"
            }
            HardwareDiskGroup(
                key = key,
                isSsd = isSsd,
                subtitle = subtitle,
                totalBytes = total,
                usedBytes = used,
                freeBytes = free,
                percent = percent,
                partitionsText = items.joinToString(", ") { it.mountpoint },
                devicesText = items.map { it.device }.distinct().joinToString(", ")
            )
        }
}

internal fun hardwarePhysicalDiskKey(device: String, singlePhysicalKey: String?): String {
    val direct = tryHardwarePhysicalDiskKey(device)
    if (!direct.isNullOrBlank()) return direct
    if (!singlePhysicalKey.isNullOrBlank() && device.startsWith("/dev/mapper/", ignoreCase = true)) return singlePhysicalKey
    return device
}

internal fun tryHardwarePhysicalDiskKey(device: String): String? {
    val name = device.trim().replace("\\", "/").substringAfterLast("/")
    if (name.startsWith("nvme", ignoreCase = true)) {
        val partitionIndex = name.indexOf('p')
        return if (partitionIndex > 0) name.substring(0, partitionIndex) else name
    }
    if (name.startsWith("sd", ignoreCase = true)) {
        val base = name.dropLastWhile { it.isDigit() }
        return base.ifBlank { name }
    }
    return null
}

internal fun diskMountSortKey(mountpoint: String): String {
    return if (mountpoint == "/") " " else mountpoint
}

internal val ignoredHardwareFileSystems = setOf(
    "autofs", "cgroup", "cgroup2", "configfs", "debugfs", "devtmpfs", "efivarfs",
    "fusectl", "hugetlbfs", "mqueue", "nsfs", "overlay", "proc", "pstore", "ramfs",
    "securityfs", "squashfs", "sysfs", "tmpfs", "tracefs"
)

internal fun isMeaningfulHardwareDisk(device: String, fileSystem: String): Boolean {
    return !device.trim().startsWith("/dev/loop", ignoreCase = true) &&
        fileSystem.trim().lowercase() !in ignoredHardwareFileSystems
}

internal fun List<HardwareTemperature>.toHardwareTemperatureViews(): List<HardwareTemperatureView> {
    val hasNvmeComposite = any { it.name.equals("nvme", ignoreCase = true) && it.label.equals("Composite", ignoreCase = true) }
    return mapNotNull { temp ->
        val current = temp.currentC
        if (!current.isFinite() || current < 0.0 || current > 150.0) {
            return@mapNotNull null
        }
        if (hasNvmeComposite &&
            temp.name.equals("nvme", ignoreCase = true) &&
            temp.label.startsWith("Sensor 2", ignoreCase = true)
        ) {
            return@mapNotNull null
        }
        val rawName = temp.name.trim()
        val rawLabel = temp.label.trim()
        val name = rawName.lowercase()
        val label = rawLabel.lowercase()
        val title = when {
            name == "k10temp" && label == "tctl" -> "CPU package"
            name == "k10temp" && label.startsWith("tccd") -> "CPU CCD ${rawLabel.filter { it.isDigit() }.ifBlank { "1" }}"
            name == "nvme" && label == "composite" -> "SSD NVMe"
            name == "nvme" && label == "sensor 1" -> "SSD NVMe controller"
            name == "nvme" && label == "sensor 3" -> "SSD NVMe NAND"
            name.startsWith("spd") -> "RAM DIMM"
            name.startsWith("r8169") -> "Ethernet controller ${rawName.substringAfter("_0_", "").ifBlank { "" }}".trim()
            rawLabel.isNotBlank() && rawLabel != "-" -> rawLabel
            else -> rawName.ifBlank { "Sensore temperatura" }
        }
        val sortKey = when {
            title.startsWith("CPU package") -> 0
            title.startsWith("CPU CCD") -> 1
            title.startsWith("SSD") -> 2
            title.startsWith("RAM") -> 3
            title.startsWith("Ethernet") -> 4
            else -> 9
        }
        HardwareTemperatureView(
            title = title,
            source = "Sensore ${rawName.ifBlank { "-" }}${if (rawLabel.isNotBlank() && rawLabel != rawName) " / $rawLabel" else ""}",
            currentC = current,
            highC = sanitizeTemperatureLimit(temp.highC),
            criticalC = sanitizeTemperatureLimit(temp.criticalC),
            sortKey = sortKey
        )
    }
        .distinctBy { it.title }
        .sortedWith(compareBy<HardwareTemperatureView> { it.sortKey }.thenByDescending { it.currentC })
}

internal fun sanitizeTemperatureLimit(value: Double?): Double? {
    return value?.takeIf { it.isFinite() && it in 1.0..150.0 }
}

