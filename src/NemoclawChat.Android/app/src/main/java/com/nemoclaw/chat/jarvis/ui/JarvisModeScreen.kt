package com.nemoclaw.chat.jarvis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nemoclaw.chat.AppColors
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.BuildConfig
import com.nemoclaw.chat.jarvis.JarvisInitiativeMode
import com.nemoclaw.chat.jarvis.JarvisSessionController
import com.nemoclaw.chat.jarvis.meta.JarvisMetaSetupActivity

@Composable
internal fun JarvisModeScreen(settings: AppSettings, apiKey: String?) {
    val context = LocalContext.current
    val state by JarvisSessionController.state.collectAsStateWithLifecycle()
    val availableModes = remember {
        JarvisInitiativeMode.entries.sortedBy(::initiativeModeOrder)
    }
    var requestedModeName by rememberSaveable {
        mutableStateOf(state.initiativeMode.name)
    }
    var objective by rememberSaveable {
        mutableStateOf(state.objective.orEmpty())
    }
    var preferPhoneDebug by rememberSaveable { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) pendingStart = true
    }
    val requestedMode = availableModes.firstOrNull { it.name == requestedModeName }
        ?: state.initiativeMode

    LaunchedEffect(state.active, state.initiativeMode) {
        if (state.active) requestedModeName = state.initiativeMode.name
    }
    LaunchedEffect(state.active, state.objective) {
        if (state.active) objective = state.objective.orEmpty()
    }
    LaunchedEffect(pendingStart) {
        if (!pendingStart) return@LaunchedEffect
        pendingStart = false
        JarvisSessionController.start(
            context = context,
            settings = settings,
            apiKey = apiKey,
            mode = requestedMode,
            objective = objective.trim(),
            preferPhoneDebug = BuildConfig.DEBUG && preferPhoneDebug
        )
    }

    val phaseLabel = jarvisPhaseLabel(state.phase.toString())
    val gatewayLabel = state.gatewayStatus.orEmpty().ifBlank {
        if (settings.gatewayUrl.isBlank()) "Da configurare" else "Configurato"
    }
    val credentialLabel = if (apiKey.isNullOrBlank()) "non salvata" else "salvata"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Jarvis Mode", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Assistenza visiva temporanea tramite Hermes locale.",
                    color = AppColors.Muted,
                    fontSize = 13.sp
                )
            }
        }

        item {
            JarvisPanel(title = "Stato sessione") {
                JarvisStatusRow("Sessione", phaseLabel)
                JarvisStatusRow("Occhiali", state.deviceStatus.orEmpty().ifBlank { "Non connessi" })
                JarvisStatusRow("Video", if (state.active && state.visionActive) "Attivo" else if (state.active) "In pausa" else "Inattivo")
                JarvisStatusRow("Audio", state.audioRoute.orEmpty().ifBlank { "Non instradato" })
                JarvisStatusRow("Gateway", gatewayLabel)
                if (state.singleModel) {
                    JarvisStatusRow(
                        "Modello principale",
                        availabilityLabel(state.fastModelAvailable && state.reasoningModelAvailable)
                    )
                } else {
                    JarvisStatusRow("Osservatore rapido", availabilityLabel(state.fastModelAvailable))
                    JarvisStatusRow("Modello ragionante", availabilityLabel(state.reasoningModelAvailable))
                }
            }
        }

        item {
            JarvisPanel(title = "Modalita") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableModes.forEach { mode ->
                        val selected = requestedMode == mode
                        Button(
                            onClick = {
                                requestedModeName = mode.name
                                if (state.active) JarvisSessionController.setMode(context, mode)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) AppColors.Accent else AppColors.Elevated,
                                contentColor = if (selected) Color.Black else Color.White
                            )
                        ) {
                            Text(initiativeModeLabel(mode), fontSize = 11.sp, maxLines = 2)
                        }
                    }
                }

                OutlinedTextField(
                    value = objective,
                    onValueChange = { objective = it.take(240) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.active,
                    label = { Text("Obiettivo corrente") },
                    placeholder = { Text("Aiutami a montare questo computer") },
                    minLines = 2,
                    maxLines = 4
                )

                if (BuildConfig.DEBUG) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Fotocamera telefono", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Fallback diagnostico build debug", color = AppColors.Muted, fontSize = 12.sp)
                        }
                        Switch(
                            checked = preferPhoneDebug,
                            onCheckedChange = { preferPhoneDebug = it },
                            enabled = !state.active
                        )
                    }
                }
                Button(
                    onClick = { context.startActivity(Intent(context, JarvisMetaSetupActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.active,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Elevated)
                ) {
                    Text(if (BuildConfig.META_DAT_ENABLED) "Configura occhiali Meta" else "Info Meta DAT")
                }
            }
        }

        if (state.active) {
            item {
                JarvisPanel(title = "Attivita") {
                    JarvisStatusRow("Modello attivo", state.currentModel.orEmpty().ifBlank { "In attesa" })
                    JarvisStatusRow("Ultima latenza", latencyLabel(state.lastLatencyMs))
                    Text("Ultima osservazione", color = AppColors.Muted, fontSize = 12.sp)
                    Text(
                        state.lastObservation.orEmpty().ifBlank { "Nessuna osservazione rilevante." },
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    if (state.shortTermSummary.orEmpty().isNotBlank()) {
                        Text("Memoria breve", color = AppColors.Muted, fontSize = 12.sp)
                        Text(state.shortTermSummary.orEmpty(), color = Color.White, fontSize = 13.sp)
                    }
                    if (state.conversationTopic.orEmpty().isNotBlank()) {
                        JarvisStatusRow(
                            "Finestra conversazione",
                            state.conversationTopic.orEmpty() + if (state.awaitingFollowup) " · aperta" else ""
                        )
                    }
                    if (state.situation.orEmpty().isNotBlank()) {
                        Text("Situazione", color = AppColors.Muted, fontSize = 12.sp)
                        Text(state.situation.orEmpty(), color = Color.White, fontSize = 13.sp)
                    }
                    if (state.lastInterventionText.orEmpty().isNotBlank()) {
                        Text("Ultimo intervento", color = AppColors.Muted, fontSize = 12.sp)
                        Text(state.lastInterventionText.orEmpty(), color = Color.White, fontSize = 13.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { JarvisSessionController.sendFeedback(true) },
                                modifier = Modifier.weight(1f),
                                enabled = state.feedbackStatus == null
                            ) { Text("Utile") }
                            Button(
                                onClick = { JarvisSessionController.sendFeedback(false) },
                                modifier = Modifier.weight(1f),
                                enabled = state.feedbackStatus == null,
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Elevated)
                            ) { Text("Non utile") }
                        }
                        state.feedbackStatus?.let {
                            Text(it, color = AppColors.Muted, fontSize = 12.sp)
                        }
                    }
                    if (state.transcript.orEmpty().isNotBlank()) {
                        Text("Trascrizione", color = AppColors.Muted, fontSize = 12.sp)
                        Text(state.transcript.orEmpty(), color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }

        if (state.error.orEmpty().isNotBlank()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1F1F)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        state.error.orEmpty(),
                        modifier = Modifier.padding(14.dp),
                        color = Color(0xFFFFB4AB)
                    )
                }
            }
        }

        item {
            if (state.active) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (state.visionActive) JarvisSessionController.pauseView(context)
                            else JarvisSessionController.resumeView(context)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Elevated)
                    ) {
                        Text(if (state.visionActive) "Pausa vista" else "Riprendi vista")
                    }
                    Button(
                        onClick = { JarvisSessionController.stop(context) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9E2424))
                    ) {
                        Text("Termina")
                    }
                }
            } else {
                Button(
                    onClick = {
                        if (settings.gatewayUrl.isBlank()) {
                            JarvisSessionController.rejectStart("Configura Hermes API URL nelle Impostazioni.")
                            return@Button
                        }
                        val permissions = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            // Android requires CAMERA before starting a camera-typed FGS,
                            // including DAT sessions whose frames come from the glasses.
                            add(Manifest.permission.CAMERA)
                        }
                        val missing = permissions.filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) pendingStart = true
                        else permissionLauncher.launch(missing.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Avvia Jarvis Mode")
                }
            }
        }

        item {
            Text(
                "Frame e audio restano effimeri. Credenziale Hermes $credentialLabel; nessun endpoint personale incorporato.",
                color = AppColors.Faint,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 18.dp)
            )
        }
    }
}

@Composable
private fun JarvisPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.Panel),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
private fun JarvisStatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AppColors.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.widthIn(max = 190.dp)
        )
    }
}

private fun initiativeModeOrder(mode: JarvisInitiativeMode): Int = when {
    mode.name.contains("question", ignoreCase = true) -> 0
    mode.name.contains("assist", ignoreCase = true) -> 1
    else -> 2
}

private fun initiativeModeLabel(mode: JarvisInitiativeMode): String = when {
    mode.name.contains("question", ignoreCase = true) -> "Solo domande"
    mode.name.contains("assist", ignoreCase = true) -> "Assistivo"
    else -> "Proattivo"
}

private fun jarvisPhaseLabel(value: String): String = when {
    value.contains("start", ignoreCase = true) -> "Avvio"
    value.contains("active", ignoreCase = true) || value.contains("running", ignoreCase = true) -> "Attiva"
    value.contains("pause", ignoreCase = true) -> "In pausa"
    value.contains("stop", ignoreCase = true) -> "Chiusura"
    value.contains("error", ignoreCase = true) -> "Errore"
    else -> "Inattiva"
}

private fun availabilityLabel(available: Boolean): String = if (available) "Disponibile" else "Non disponibile"

private fun latencyLabel(value: Any?): String {
    val millis = (value as? Number)?.toLong() ?: return "Nessun dato"
    return if (millis > 0L) "$millis ms" else "Nessun dato"
}
