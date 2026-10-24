package com.nemoclaw.chat

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import com.nemoclaw.chat.ui.apple.AppleGlass
import com.nemoclaw.chat.ui.apple.AppleMotion
import com.nemoclaw.chat.ui.apple.AppleType
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal fun gpuManagerBase(gatewayUrl: String): String {    val trimmed = gatewayUrl.trim().trimEnd('/')
    val noPath = trimmed.substringBefore("/v1").substringBefore("/api")
    return if (Regex(":[0-9]+$").containsMatchIn(noPath)) {
        noPath.replace(Regex(":[0-9]+$"), ":8643")
    } else {
        "$noPath:8643"
    }
}

/**
 * Base del bridge laya (hermes-laya-bridge, :8645): sempre attivo e indipendente
 * dal manager, così il canale risponde anche se il manager è bloccato.
 */
internal fun gpuBridgeBase(gatewayUrl: String): String {
    val trimmed = gatewayUrl.trim().trimEnd('/')
    val noPath = trimmed.substringBefore("/v1").substringBefore("/api")
    return if (Regex(":[0-9]+$").containsMatchIn(noPath)) {
        noPath.replace(Regex(":[0-9]+$"), ":8645")
    } else {
        "$noPath:8645"
    }
}

internal data class PulseDay(val done: Int, val failed: Int)

/** Secchi giornalieri 7 giorni (oggi ultimo) da (createdAtSec, status). Puro. */
internal fun bucketPulseDays(created: List<Pair<Long, String>>, nowMs: Long): List<PulseDay> {
    val days = Array(7) { PulseDay(0, 0) }
    val dayMs = 86_400_000L
    val todayStart = (nowMs / dayMs) * dayMs
    for ((atSec, status) in created) {
        val atMs = atSec * 1000L
        if (atMs <= 0) continue
        val diff = (todayStart - (atMs / dayMs) * dayMs) / dayMs
        if (diff < 0 || diff > 6) continue
        val slot = (6 - diff.toInt()).coerceIn(0, 6)
        val prev = days[slot]
        days[slot] = if (status == "done") prev.copy(done = prev.done + 1)
        else prev.copy(failed = prev.failed + 1)
    }
    return days.toList()
}

@Composable
internal fun PulseCard(context: Context, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val base = remember(settings.gatewayUrl) { gpuManagerBase(settings.gatewayUrl) }
    var days by remember(base) { mutableStateOf(emptyList<PulseDay>()) }
    var line by remember(base) { mutableStateOf("Carico giornata…") }
    var busy by remember(base) { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            busy = true
            try {
                val key = loadGpuAppSecret(context)
                val (code, body) = httpGetResponse("$base/jobs?limit=300", key)
                if (code !in 200..299) {
                    line = "Job non leggibili (HTTP $code)."
                    return@launch
                }
                val arr = runCatching { JSONObject(body).optJSONArray("jobs") }.getOrNull()
                val created = buildList {
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            add(o.optDouble("created_at", 0.0).toLong() to o.optString("status"))
                        }
                    }
                }
                val buckets = bucketPulseDays(created, System.currentTimeMillis())
                days = buckets
                val today = buckets.last()
                line = "Oggi: ${today.done} completati · ${today.failed} falliti."
            } catch (failed: Exception) {
                line = failed.message ?: "Errore rete."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(base) { refresh() }
    AppleGlass(shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "PULSE · 7 GIORNI",
                    color = AppColors.Accent,
                    fontSize = AppleType.Footnote,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { refresh() }, enabled = !busy) { Text("Aggiorna", color = AppColors.Accent) }
            }
                Text(line, color = Color.White, fontSize = AppleType.RowTitleSmall)
            AnimatedVisibility(
                visible = days.size == 7,
                enter = AppleMotion.enter(),
                exit = AppleMotion.exit()
            ) {
                val max = (days.maxOf { it.done + it.failed }).coerceAtLeast(1)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                ) {
                    val slot = size.width / 7f
                    val barW = (slot * 0.55f).coerceAtLeast(4f)
                    days.forEachIndexed { index, day ->
                        val total = day.done + day.failed
                        if (total <= 0) return@forEachIndexed
                        val h = size.height * (total.toFloat() / max)
                        val x = index * slot + (slot - barW) / 2f
                        val doneH = h * (day.done.toFloat() / total)
                        drawRoundRect(
                            color = AppColors.Success,
                            topLeft = androidx.compose.ui.geometry.Offset(x, size.height - doneH),
                            size = androidx.compose.ui.geometry.Size(barW, doneH)
                        )
                        val failH = h - doneH
                        if (failH > 1f) {
                            drawRoundRect(
                                color = AppColors.Error,
                                topLeft = androidx.compose.ui.geometry.Offset(x, size.height - h),
                                size = androidx.compose.ui.geometry.Size(barW, failH)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GpuComputeCard(context: Context, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val base = remember(settings.gatewayUrl) { gpuManagerBase(settings.gatewayUrl) }
    val bridge = remember(settings.gatewayUrl) { gpuBridgeBase(settings.gatewayUrl) }
    var body by remember(base) { mutableStateOf<JSONObject?>(null) }
    var jobsBody by remember(base) { mutableStateOf<JSONArray?>(null) }
    var error by remember(base) { mutableStateOf("") }
    var busy by remember(base) { mutableStateOf(false) }
    var layaText by remember(bridge) { mutableStateOf("") }
    var layaReply by remember(bridge) { mutableStateOf("") }
    var layaBusy by remember(bridge) { mutableStateOf(false) }

    suspend fun refreshSuspend(): Boolean {
        busy = true
        try {
            error = runCatching {
                val key = loadGpuAppSecret(context)
                val (statusCode, statusBody) = httpGetResponse("$base/status", key)
                if (statusCode !in 200..299) {
                    throw IllegalStateException(managerStatusErrorMessage(statusCode, statusBody))
                }
                body = JSONObject(statusBody)
                val (jobsCode, jobsBodyRaw) = httpGetResponse("$base/jobs", key)
                if (jobsCode !in 200..299) {
                    throw IllegalStateException("Job manager HTTP $jobsCode")
                }
                jobsBody = JSONObject(jobsBodyRaw).optJSONArray("jobs")
            }.exceptionOrNull()?.message ?: ""
            return error.isBlank()
        } finally {
            busy = false
        }
    }

    fun refresh() {
        scope.launch {
            refreshSuspend()
        }
    }

    fun sendLaya() {
        val text = layaText.trim()
        if (text.isEmpty() || layaBusy) return
        scope.launch {
            layaBusy = true
            layaReply = runCatching {
                postLayaCommand(bridge, text, loadGpuAppSecret(context))
            }.getOrElse { it.message ?: "Errore" }
            layaBusy = false
            refresh()
        }
    }

    PollWhileStarted(base, baseIntervalMs = 10_000L) {
        if (busy) return@PollWhileStarted true
        refreshSuspend()
    }

    AppleGlass(shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MODALITÀ GPU (QWEN / COMFYUI)", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            if (body == null) {
                Text(
                    if (error.isBlank()) "Lettura stato GPU..." else "GPU Manager non raggiungibile ($base). Verifica che hermes-gpu-manager sia attivo.",
                    color = AppColors.Faint,
                    fontSize = AppleType.Footnote
                )
                IconButton(onClick = { refresh() }, enabled = !busy) { Icon(Icons.Rounded.Refresh, contentDescription = "Riprova lettura GPU", tint = AppColors.Accent) }
            } else {
            val status = body ?: JSONObject()
            val desired = status.optString("desired_mode", "?")
            val state = status.optString("current_state", "?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("LLM", "MEDIA", "AUTO").forEach { mode ->
                    Button(
                        onClick = {
                            scope.launch {
                                val layaText = when (mode) {
                                    "LLM" -> "metti LLM ready"
                                    "MEDIA" -> "attiva comfy"
                                    else -> "metti in auto"
                                }
                                layaReply = runCatching {
                                    postLayaCommand(bridge, layaText, loadGpuAppSecret(context))
                                }.getOrElse { it.message ?: "Errore" }
                                refresh()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (desired == mode) AppColors.Accent else AppColors.Panel,
                            contentColor = if (desired == mode) Color(0xFF171009) else Color.White
                        ),
                        border = if (desired == mode) null else BorderStroke(0.5.dp, AppColors.Border),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(if (desired == mode) "$mode ✓" else mode) }
                }
            }
            Text("Stato: $state", color = Color.White, fontWeight = FontWeight.SemiBold)
            AnimatedVisibility(
                visible = layaReply.isNotBlank(),
                enter = AppleMotion.enter(),
                exit = AppleMotion.exit()
            ) {
                Text(layaReply, color = AppColors.Faint, fontSize = AppleType.Footnote)
            }
            Text(
                "Qwen: ${if (status.optBoolean("llm_loaded", false)) "Online" else if (status.optBoolean("llm_online", false)) "Avvio..." else "Offline"} · " +
                    "ComfyUI: ${if (status.optBoolean("media_online", false)) "Online" else "Offline"}",
                color = AppColors.Muted,
                fontSize = AppleType.Footnote
            )
            val gpus = status.optJSONArray("gpu") ?: JSONArray()
            for (i in 0 until gpus.length()) {
                val gpu = gpus.optJSONObject(i) ?: continue
                val usedGb = gpu.optDouble("memory_used_mb", 0.0) / 1024.0
                val totalGb = gpu.optDouble("memory_total_mb", 1.0) / 1024.0
                val pct = if (totalGb > 0) (usedGb / totalGb * 100).toInt() else 0
                Text(
                    "GPU${gpu.optInt("index")} %.1f / %.1f GB (%d%%)".format(usedGb, totalGb, pct),
                    color = AppColors.Muted,
                    fontSize = AppleType.Footnote
                )
            }
            Text(
                "Coda media: ${status.optInt("queue_length", 0)}" +
                    (status.optString("current_job", "").takeIf { it.isNotBlank() }?.let { " · Job $it" } ?: ""),
                color = AppColors.Muted,
                fontSize = AppleType.Footnote
            )
            Text("MEDIA JOBS RECENTI", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            val recentJobs = jobsBody ?: JSONArray()
            if (recentJobs.length() == 0) {
                Text("Nessun job media.", color = AppColors.Muted, fontSize = AppleType.Footnote)
            }
            for (i in 0 until minOf(recentJobs.length(), 5)) {
                val job = recentJobs.optJSONObject(i) ?: continue
                val results = job.optJSONArray("result_paths")?.length() ?: 0
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${job.optString("job_id", "?")} · ${job.optString("preset", job.optString("kind", "?"))} · ${job.optString("status", "?")}",
                        color = Color.White,
                        fontSize = AppleType.Footnote,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Progresso ${(job.optDouble("progress", 0.0) * 100).toInt()}% · output $results" +
                            (job.optString("phase", "").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                            (job.optString("error", "").takeIf { it.isNotBlank() }?.let { " · Errore: $it" } ?: "") +
                            (job.optString("model", "").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        color = AppColors.Faint,
                        fontSize = AppleType.Caption
                    )
                }
                HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
            }
            status.optString("last_error", "").takeIf { it.isNotBlank() }?.let {
                Text("Ultimo errore: $it", color = AppColors.Error, fontSize = AppleType.Footnote)
            }
            status.optString("h3_license_state", "").takeIf { it.isNotBlank() }?.let {
                Text("Video H3: $it", color = AppColors.Muted, fontSize = AppleType.Footnote)
            }
                TextButton(onClick = { refresh() }, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Aggiorna") }
            }
            HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
            Text("CHAT LAYA (canale diretto)", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(
                "Chiedi in linguaggio naturale: \"metti l'LLM ready\", \"attiva comfy\", \"metti in auto\", \"come sta?\".",
                color = AppColors.Muted,
                fontSize = AppleType.Footnote
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = layaText,
                    onValueChange = { layaText = it },
                    singleLine = true,
                    placeholder = { Text("Chiedi a laya...") },
                    modifier = Modifier.weight(1f),
                    enabled = !layaBusy
                )
                Button(
                    onClick = { sendLaya() },
                    enabled = !layaBusy && layaText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(if (layaBusy) "..." else "Invia") }
            }
            AnimatedVisibility(
                visible = layaReply.isNotBlank(),
                enter = AppleMotion.enter(),
                exit = AppleMotion.exit()
            ) {
                Text(layaReply, color = Color.White, fontSize = AppleType.RowTitleSmall)
            }
        }
    }
}

