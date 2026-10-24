package com.nemoclaw.chat.features.comfy

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import com.nemoclaw.chat.ui.apple.AppleMotion
import com.nemoclaw.chat.ui.apple.AppleType
import com.nemoclaw.chat.ui.apple.AuroraBackground
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.AppColors
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.PollWhileStarted
import com.nemoclaw.chat.fastestGatewayRoot
import com.nemoclaw.chat.isWifiTransport
import com.nemoclaw.chat.gatewayProbeHttpClient
import com.nemoclaw.chat.gpuManagerBase
import com.nemoclaw.chat.httpGetResponseQuick
import com.nemoclaw.chat.loadGpuAppSecret
import com.nemoclaw.chat.managerStatusErrorMessage
import com.nemoclaw.chat.readUtf8Bounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Stato Comfy/GPU letto dal manager (GET /status). Puro e testabile.
 * progress 0..1 solo durante MEDIA_BUSY, altrimenti null.
 */
internal data class ComfyStatus(
    val reachable: Boolean,
    val state: String = "",
    val desired: String = "",
    val jobId: String = "",
    val progress: Float? = null,
    val queue: Int = 0,
    val preset: String = "",
    val model: String = "",
    val error: String = "",
    val hint: String = "",
    val llmLoaded: Boolean = false,
    val llmModel: String = "",
    val llmVramMb: Double = 0.0,
    val mediaVramMb: Double = 0.0,
    val mediaLoadedModels: List<String> = emptyList(),
    // ComfyUI diretto (best-effort): coda e ultimi errori dei prompt.
    val comfyQueue: Int? = null,
    val comfyError: String? = null,
    val comfyDirect: Boolean = false
)

internal fun parseComfyStatus(body: String): ComfyStatus? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val progress = root.optDouble("media_progress", Double.NaN)
        .takeIf { !it.isNaN() }?.toFloat()?.coerceIn(0f, 1f)
    return ComfyStatus(
        reachable = true,
        state = root.optString("current_state", ""),
        desired = root.optString("desired_mode", ""),
        jobId = root.optString("current_job", ""),
        progress = progress,
        queue = root.optInt("queue_length", 0),
        preset = root.optString("active_preset", ""),
        model = root.optString("active_media_model", ""),
        error = root.optString("last_error", ""),
        hint = root.optString("hint", ""),
        llmLoaded = root.optBoolean("llm_loaded", false),
        llmModel = root.optString("llm_model", ""),
        llmVramMb = root.optDouble("llm_vram_mb", 0.0),
        mediaVramMb = root.optDouble("media_vram_mb", 0.0),
        mediaLoadedModels = buildList {
            val arr = root.optJSONArray("media_loaded_models")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i).trim()
                    if (name.isNotBlank()) add(name)
                }
            }
        }
    )
}

/** Etichetta italiana dello stato manager. Pura e testabile. */
internal fun comfyStateLabel(state: String): String = when (state) {
    "MEDIA_BUSY" -> "Sta generando"
    "MEDIA_READY" -> "Comfy pronto"
    "MEDIA_STARTING" -> "Avvio Comfy…"
    "MEDIA_STOPPING" -> "Arresto Comfy…"
    "LLM_READY" -> "LLM attivo (chat)"
    "LLM_LOADING" -> "Caricamento LLM…"
    "LLM_UNLOADING" -> "Scaricamento LLM…"
    "GPU_FREE" -> "GPU libere"
    "DIRECT" -> "Comfy diretto"
    "BOOT" -> "Avvio…"
    "ERROR" -> "Errore"
    "" -> "Sconosciuto"
    else -> state
}

/** Sottotitolo riga menu: cosa sta facendo + a che punto. Puro e testabile. */
internal fun comfyMenuSubtitle(status: ComfyStatus?): String {
    if (status == null) return "Stato sconosciuto"
    if (!status.reachable) return if (status.error.isNotBlank()) status.error else "Non raggiungibile"
    if (status.state == "MEDIA_BUSY") {
        val pct = status.progress?.let { "${(it * 100).roundToInt()}%" } ?: "…"
        val what = status.preset.takeIf { it.isNotBlank() } ?: status.model.takeIf { it.isNotBlank() } ?: "lavoro"
        return "Sta generando · $pct · $what"
    }
    val videoName = status.mediaLoadedModels.firstOrNull()
    if (videoName != null) {
        val extra = if (status.mediaLoadedModels.size > 1) " +${status.mediaLoadedModels.size - 1}" else ""
        return "Video su GPU · $videoName$extra"
    }
    if (status.state == "LLM_READY") {
        val model = status.llmModel.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        return "LLM attivo · ${status.desired.ifBlank { "AUTO" }}$model"
    }
    if (status.state.isBlank()) return "Stato sconosciuto"
    val base = comfyStateLabel(status.state)
    return if (status.queue > 0) "$base · coda ${status.queue}" else base
}

internal suspend fun loadComfyStatus(gatewayUrl: String, managerKey: String?): ComfyStatus =
    withContext(Dispatchers.IO) {
        runCatching {
            val base = gpuManagerBase(gatewayUrl)
            if (base.isBlank()) return@runCatching ComfyStatus(false, error = "Gateway non configurato")
            val (code, body) = httpGetResponseQuick("$base/status", managerKey)
            if (code !in 200..299) {
                return@runCatching ComfyStatus(false, error = managerStatusErrorMessage(code, body).take(160))
            }
            val parsed = parseComfyStatus(body)
                ?: return@runCatching ComfyStatus(false, error = "Risposta manager illeggibile")
            // Dettaglio diretto solo quando Comfy dovrebbe essere su (altrimenti
            // e spento per disegno e il "non raggiungibile" sarebbe rumore).
            if (parsed.state.startsWith("MEDIA_") || parsed.state == "ERROR" || parsed.state == "DIRECT") {
                val direct = loadComfyDirect(gatewayUrl)
                parsed.copy(
                    comfyQueue = direct.queue,
                    comfyError = direct.error,
                    comfyDirect = direct.reachable
                )
            } else {
                parsed
            }
        }.getOrElse { ComfyStatus(false, error = it.message ?: it.javaClass.simpleName) }
    }

/** Base ComfyUI diretto: stesso host del gateway, porta 8188. Pura e testabile. */
internal fun comfyDirectBase(gatewayUrl: String): String {
    val trimmed = gatewayUrl.trim().trimEnd('/')
    val noPath = trimmed.substringBefore("/v1").substringBefore("/api")
    return if (Regex(":[0-9]+$").containsMatchIn(noPath)) {
        noPath.replace(Regex(":[0-9]+$"), ":8188")
    } else {
        "$noPath:8188"
    }
}

/**
 * Errori ComfyUI da GET /history: prompt con status_str == "error".
 * Ritorna gli ultimi (max 3) uniti, null se niente errori o body illeggibile.
 * Pura e testabile.
 */
internal fun parseComfyHistoryErrors(body: String): String? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val out = mutableListOf<String>()
    val keys = root.keys()
    while (keys.hasNext()) {
        val entry = root.optJSONObject(keys.next()) ?: continue
        val status = entry.optJSONObject("status") ?: continue
        if (!status.optString("status_str", "").equals("error", ignoreCase = true)) continue
        val messages = status.optJSONArray("messages")
        val text = if (messages != null) {
            (0 until messages.length()).mapNotNull { i ->
                when (val m = messages.opt(i)) {
                    is String -> m.takeIf { it.isNotBlank() }
                    is JSONArray -> m.optString(0).takeIf { it.isNotBlank() } ?: m.toString().takeIf { it.isNotBlank() }
                    else -> m?.toString()?.takeIf { it.isNotBlank() }
                }
            }.joinToString(" · ").trim()
        } else {
            status.optString("message", "").trim()
        }
        if (text.isNotBlank()) out += text
    }
    return out.takeLast(3).joinToString("\n\n").take(600).takeIf { it.isNotBlank() }
}

internal data class ComfyDirect(val queue: Int?, val error: String?, val reachable: Boolean)

internal suspend fun loadComfyDirect(gatewayUrl: String): ComfyDirect = withContext(Dispatchers.IO) {
    runCatching {
        fun get(path: String): Pair<Int, String>? = runCatching {
            val req = okhttp3.Request.Builder()
                .url(comfyDirectBase(gatewayUrl) + path)
                .header("Accept", "application/json")
                .header("User-Agent", "HermesHub-Android-Comfy")
                .get().build()
            gatewayProbeHttpClient.newCall(req).execute().use { it.code to it.body.byteStream().readUtf8Bounded() }
        }.getOrNull()
        val (qCode, qBody) = get("/prompt") ?: return@runCatching ComfyDirect(null, null, false)
        val queue = if (qCode in 200..299) {
            runCatching { JSONObject(qBody).optJSONObject("exec_info")?.optInt("queue_remaining", -1) }
                .getOrNull()?.takeIf { it >= 0 }
        } else {
            null
        }
        val (hCode, hBody) = get("/history?max_items=10") ?: return@runCatching ComfyDirect(queue, null, true)
        val error = if (hCode in 200..299) parseComfyHistoryErrors(hBody) else null
        ComfyDirect(queue, error, true)
    }.getOrElse { ComfyDirect(null, null, false) }
}

@Composable
internal fun ComfyScreen(
    context: Context,
    settings: AppSettings,
    onBack: () -> Unit
) {
    BackHandler(enabled = true) { onBack() }
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    var status by remember(settings.gatewayUrl, settings.localGatewayUrl) { mutableStateOf<ComfyStatus?>(null) }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                val key = withContext(Dispatchers.IO) { loadGpuAppSecret(appContext) }
                val root = fastestGatewayRoot(settings, isWifiTransport(appContext))
                status = loadComfyStatus(root.ifBlank { settings.gatewayUrl }, key)
            } finally {
                refreshing = false
            }
        }
    }

    PollWhileStarted(settings.gatewayUrl, settings.localGatewayUrl, refreshNonce, baseIntervalMs = 5_000L) {
        val key = withContext(Dispatchers.IO) { loadGpuAppSecret(appContext) }
        val root = fastestGatewayRoot(settings, isWifiTransport(appContext))
        status = loadComfyStatus(root.ifBlank { settings.gatewayUrl }, key)
        true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        AuroraBackground(intensity = 1f)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Torna alla chat", tint = Color.White)
                }
                Box(
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                        .background(AppColors.Accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = AppColors.Accent)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Comfy", color = Color.White, fontSize = AppleType.ScreenTitle, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Cosa sta facendo la GPU: stato, lavoro e avanzamento live.",
                        color = AppColors.Faint,
                        fontSize = AppleType.Footnote
                    )
                }
                TextButton(onClick = { refreshNonce++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Aggiorna", color = AppColors.Accent)
                }
            }
        }
        val current = status
        if (current == null) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                            .background(AppColors.Accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.HourglassEmpty, contentDescription = null, tint = AppColors.Accent)
                    }
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = AppColors.Accent,
                        trackColor = AppColors.Border
                    )
                    Text(
                        "Leggo lo stato dal manager…",
                        color = AppColors.Faint,
                        fontSize = AppleType.Footnote,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        } else if (!current.reachable) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                            .background(AppColors.Accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = AppColors.Accent)
                    }
                    Text(
                        "Manager non raggiungibile",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        current.error.ifBlank { "Controlla la connessione e riprova." },
                        color = AppColors.Faint,
                        fontSize = AppleType.Footnote,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                    TextButton(onClick = { refreshNonce++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Riprova", color = AppColors.Accent)
                    }
                }
            }
        } else {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("STATO", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            comfyStateLabel(current.state),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).semantics {
                                contentDescription = "Stato GPU: ${comfyStateLabel(current.state)}"
                            }
                        )
                        Text(
                            current.desired.ifBlank { "AUTO" },
                            color = AppColors.Faint,
                            fontSize = AppleType.Footnote,
                            maxLines = 1
                        )
                    }
                    HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
                    AnimatedVisibility(
                        visible = current.state == "MEDIA_BUSY",
                        enter = AppleMotion.enter(),
                        exit = AppleMotion.exit()
                    ) {
                        val pct = current.progress?.let { "${(it * 100).roundToInt()}%" } ?: "…"
                        Text("Avanzamento $pct", color = AppColors.Accent, fontSize = AppleType.RowTitleSmall, fontWeight = FontWeight.SemiBold)
                        if (current.progress != null) {
                            LinearProgressIndicator(
                                progress = { current.progress },
                                modifier = Modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = "Avanzamento $pct" },
                                color = AppColors.Accent,
                                trackColor = AppColors.Border
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = "Avanzamento $pct" },
                                color = AppColors.Accent,
                                trackColor = AppColors.Border
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = current.error.isNotBlank() || current.state == "ERROR",
                        enter = AppleMotion.enter(),
                        exit = AppleMotion.exit()
                    ) {
                        Text(
                            current.error.ifBlank { "Errore senza dettagli: riprova o cambia modo." },
                            color = AppColors.Error,
                            fontSize = AppleType.RowTitleSmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                        )
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("LAVORO IN CORSO", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ComfyRow("Preset", current.preset.ifBlank { "—" })
                        HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
                        ComfyRow("Modello", current.model.ifBlank { "—" })
                        HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
                        ComfyRow("Job", current.jobId.take(8).ifBlank { "—" })
                        HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
                        ComfyRow(
                            "Coda",
                            if (current.queue > 0) "${current.queue} in attesa" else "vuota"
                        )
                        if (current.comfyDirect && current.comfyQueue != null) {
                            HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
                            ComfyRow(
                                "Coda Comfy",
                                if (current.comfyQueue > 0) "${current.comfyQueue} in esecuzione/coda" else "libera"
                            )
                        }
                    }
                    if (current.hint.isNotBlank()) {
                        Text(current.hint, color = AppColors.Faint, fontSize = AppleType.Footnote)
                    }
                }
            }
            val comfyProblem = current.comfyError?.takeIf { it.isNotBlank() }
                ?: if (!current.comfyDirect) {
                    "Comfy diretto non raggiungibile: se doveva generare, qualcosa non va."
                } else {
                    null
                }
            if (comfyProblem != null) {
                item {
                    AnimatedVisibility(
                        visible = comfyProblem != null,
                        enter = AppleMotion.enter(),
                        exit = AppleMotion.exit()
                    ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("ERRORI COMFY", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold)
                        Text(
                            comfyProblem,
                            color = AppColors.Error,
                            fontSize = AppleType.RowTitleSmall,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Assertive
                                contentDescription = comfyProblem
                            }
                        )
                    }
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Aggiornato ogni 5 secondi mentre guardi. LLM ${if (current.llmLoaded) "caricato" else "scarico"}.",
                    color = AppColors.Faint,
                    fontSize = AppleType.Footnote
                )
            }
        }
    }
    }
}

@Composable
private fun ComfyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AppColors.Faint, fontSize = AppleType.RowTitleSmall)
        Text(
            value,
            color = Color.White,
            fontSize = AppleType.RowTitleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).semantics { contentDescription = value }
        )
    }
}
