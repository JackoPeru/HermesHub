package com.nemoclaw.chat.features.comfy

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.AppColors
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.PollWhileStarted
import com.nemoclaw.chat.gpuManagerBase
import com.nemoclaw.chat.httpGetResponse
import com.nemoclaw.chat.loadGatewaySecret
import com.nemoclaw.chat.managerStatusErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val llmLoaded: Boolean = false
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
        llmLoaded = root.optBoolean("llm_loaded", false)
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
    if (status.state == "LLM_READY") return "LLM attivo · ${status.desired.ifBlank { "AUTO" }}"
    if (status.state.isBlank()) return "Stato sconosciuto"
    val base = comfyStateLabel(status.state)
    return if (status.queue > 0) "$base · coda ${status.queue}" else base
}

internal suspend fun loadComfyStatus(gatewayUrl: String, managerKey: String?): ComfyStatus =
    withContext(Dispatchers.IO) {
        runCatching {
            val base = gpuManagerBase(gatewayUrl)
            if (base.isBlank()) return@runCatching ComfyStatus(false, error = "Gateway non configurato")
            val (code, body) = httpGetResponse("$base/status", managerKey)
            if (code !in 200..299) {
                return@runCatching ComfyStatus(false, error = managerStatusErrorMessage(code, body).take(160))
            }
            parseComfyStatus(body) ?: ComfyStatus(false, error = "Risposta manager illeggibile")
        }.getOrElse { ComfyStatus(false, error = it.message ?: it.javaClass.simpleName) }
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
    var status by remember(settings.gatewayUrl) { mutableStateOf<ComfyStatus?>(null) }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                val key = withContext(Dispatchers.IO) { loadGatewaySecret(appContext) }
                status = loadComfyStatus(settings.gatewayUrl, key)
            } finally {
                refreshing = false
            }
        }
    }

    PollWhileStarted(settings.gatewayUrl, refreshNonce, baseIntervalMs = 5_000L) {
        val key = withContext(Dispatchers.IO) { loadGatewaySecret(appContext) }
        status = loadComfyStatus(settings.gatewayUrl, key)
        true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Torna alla chat", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Comfy", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Cosa sta facendo la GPU: stato, lavoro e avanzamento live.",
                        color = AppColors.Muted,
                        fontSize = 13.sp
                    )
                }
                IconButton(onClick = { refreshNonce++ }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Aggiorna stato", tint = Color.White)
                }
            }
        }
        val current = status
        if (current == null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "Leggo lo stato dal manager…",
                            color = AppColors.Muted,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
            }
        } else if (!current.reachable) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Manager non raggiungibile", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            current.error.ifBlank { "Controlla la connessione e riprova." },
                            color = AppColors.Muted,
                            fontSize = 13.sp,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                        )
                        TextButton(onClick = { refreshNonce++ }, modifier = Modifier.height(48.dp)) {
                            Text("Riprova", color = AppColors.Accent)
                        }
                    }
                }
            }
        } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                color = AppColors.Muted,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                        if (current.state == "MEDIA_BUSY") {
                            val pct = current.progress?.let { "${(it * 100).roundToInt()}%" } ?: "…"
                            Text("Avanzamento $pct", color = AppColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (current.progress != null) {
                                LinearProgressIndicator(
                                    progress = { current.progress },
                                    modifier = Modifier.fillMaxWidth().height(8.dp)
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
                            }
                        }
                        if (current.error.isNotBlank() || current.state == "ERROR") {
                            Text(
                                current.error.ifBlank { "Errore senza dettagli: riprova o cambia modo." },
                                color = Color(0xFFFF7B8E),
                                fontSize = 13.sp,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                            )
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Lavoro in corso", color = Color.White, fontWeight = FontWeight.SemiBold)
                        ComfyRow("Preset", current.preset.ifBlank { "—" })
                        ComfyRow("Modello", current.model.ifBlank { "—" })
                        ComfyRow("Job", current.jobId.take(8).ifBlank { "—" })
                        ComfyRow(
                            "Coda",
                            if (current.queue > 0) "${current.queue} in attesa" else "vuota"
                        )
                        if (current.hint.isNotBlank()) {
                            Text(current.hint, color = AppColors.Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Aggiornato ogni 5 secondi mentre guardi. LLM ${if (current.llmLoaded) "caricato" else "scarico"}.",
                    color = AppColors.Faint,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ComfyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AppColors.Muted, fontSize = 13.sp)
        Text(
            value,
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}
