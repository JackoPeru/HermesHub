package com.nemoclaw.chat

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import com.nemoclaw.chat.ui.apple.AppleMotion
import com.nemoclaw.chat.ui.apple.AppleType
import com.nemoclaw.chat.ui.apple.BubbleBackground
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HealthDashboardScreen(context: Context, settings: AppSettings, onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshRevision by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<HealthHistoryResult?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(
        settings.healthIncludeSteps,
        settings.healthIncludeSleep,
        settings.healthIncludeWorkouts,
        settings.healthIncludeHeartRate,
        refreshRevision
    ) {
        loading = true
        history = HealthSync.readHistory(context, settings)
        loading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        BubbleBackground()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(AppColors.Accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.FavoriteBorder, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Salute", color = Color.White, fontSize = AppleType.ScreenTitle, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Dati letti dal telefono tramite Samsung Health e Health Connect. Riepiloghi wellness, non diagnosi mediche.",
                        color = AppColors.Faint,
                        fontSize = AppleType.Footnote
                    )
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { refreshRevision++ }, enabled = !loading, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Aggiorna dati") }
                TextButton(onClick = onOpenSettings, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Impostazioni salute") }
            }
        }
        when (val current = history) {
            null -> item { HealthLoadingPanel() }
            is HealthHistoryResult.Unavailable -> item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.Link, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                    Text("Dati non disponibili", color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(current.message, color = AppColors.Faint, fontSize = AppleType.RowTitleSmall, textAlign = TextAlign.Center)
                    Text("Apri Impostazioni salute, collega Health Connect e abilita almeno una categoria.", color = AppColors.Faint, fontSize = AppleType.Footnote, textAlign = TextAlign.Center)
                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Collega Health Connect", fontWeight = FontWeight.SemiBold) }
                    HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
                }
            }
            is HealthHistoryResult.Success -> {
                val items = current.items
                val today = items.lastOrNull()
                if (today == null) {
                    item { HealthLoadingPanel() }
                } else {
                    item { HealthTodayPanel(today) }
                    item {
                        WellbeingBarChart(
                            title = "Passi · ultimi 7 giorni",
                            unit = "passi",
                            items = items,
                            color = AppColors.Accent,
                            value = { it.steps?.toFloat() }
                        )
                    }
                    item {
                        WellbeingBarChart(
                            title = "Sonno · ultimi 7 giorni",
                            unit = "minuti",
                            items = items,
                            color = Color(0xFF7C8CFF),
                            value = { it.sleepMinutes?.toFloat() }
                        )
                    }
                    item {
                        WellbeingBarChart(
                            title = "Allenamento · ultimi 7 giorni",
                            unit = "minuti",
                            items = items,
                            color = Color(0xFF4DD6A7),
                            value = { it.workoutMinutes?.toFloat() }
                        )
                    }
                    item {
                        AnimatedVisibility(
                            visible = items.any { it.heartRateAverage != null },
                            enter = AppleMotion.enter(),
                            exit = AppleMotion.exit()
                        ) {
                            WellbeingBarChart(
                                title = "Frequenza cardiaca media · ultimi 7 giorni",
                                unit = "bpm",
                                items = items,
                                color = Color(0xFFFF6B82),
                                value = { it.heartRateAverage?.toFloat() }
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun HealthLoadingPanel() {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LinearProgressIndicator(modifier = Modifier.width(84.dp), color = AppColors.Accent, trackColor = AppColors.Border)
            Text("Lettura dati Health Connect...", color = AppColors.Faint, fontSize = AppleType.RowTitleSmall)
        }
        HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
    }
}

@Composable
internal fun HealthTodayPanel(summary: DailyWellbeingSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(("Oggi · ${summary.date}").uppercase(), color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HealthMetric("Passi", summary.steps?.toString() ?: "—", Modifier.weight(1f))
                HealthMetric("Calorie", summary.activeCaloriesKcal?.let { String.format(java.util.Locale.ITALY, "%.0f kcal", it) } ?: "—", Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HealthMetric("Sonno", summary.sleepMinutes?.let { "${it / 60}h ${it % 60}m" } ?: "—", Modifier.weight(1f))
                HealthMetric("Allenamento", summary.workoutMinutes?.let { "${it} min" } ?: "—", Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HealthMetric("Sessioni", summary.workoutCount?.toString() ?: "—", Modifier.weight(1f))
                HealthMetric("FC media", summary.heartRateAverage?.let { String.format(java.util.Locale.ITALY, "%.0f bpm", it) } ?: "—", Modifier.weight(1f))
            }
            AnimatedVisibility(
                visible = summary.heartRateMin != null,
                enter = AppleMotion.enter(),
                exit = AppleMotion.exit()
            ) {
                summary.heartRateMin?.let { min ->
                    Text(
                        "Frequenza cardiaca oggi: ${String.format(java.util.Locale.ITALY, "%.0f", min)}–${String.format(java.util.Locale.ITALY, "%.0f", summary.heartRateMax ?: min)} bpm",
                        color = AppColors.Faint,
                        fontSize = AppleType.Footnote
                    )
                }
            }
            HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
        }
}

@Composable
internal fun HealthMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label.uppercase(), color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun WellbeingBarChart(
    title: String,
    unit: String,
    items: List<DailyWellbeingSummary>,
    color: Color,
    value: (DailyWellbeingSummary) -> Float?
) {
    val values = items.map { value(it)?.coerceAtLeast(0f) ?: 0f }
    val peak = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("max ${String.format(java.util.Locale.ITALY, "%.0f", peak)} $unit", color = AppColors.Faint, fontSize = AppleType.Caption)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
                val gap = 8.dp.toPx()
                val width = ((size.width - gap * (values.size - 1)) / values.size.coerceAtLeast(1)).coerceAtLeast(2.dp.toPx())
                values.forEachIndexed { index, current ->
                    val height = if (current <= 0f) 2.dp.toPx() else (current / peak) * size.height
                    val left = index * (width + gap)
                    drawRect(
                        color = if (current <= 0f) AppColors.Border else color,
                        topLeft = Offset(left, size.height - height),
                        size = Size(width, height)
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                items.forEach { item -> Text(item.date.takeLast(2), color = AppColors.Faint, fontSize = 10.sp) }
            }
            HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
        }
}

