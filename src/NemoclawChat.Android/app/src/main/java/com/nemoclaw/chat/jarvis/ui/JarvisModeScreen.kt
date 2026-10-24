package com.nemoclaw.chat.jarvis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nemoclaw.chat.AppColors
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.BuildConfig
import com.nemoclaw.chat.R
import com.nemoclaw.chat.jarvis.JarvisInitiativeMode
import com.nemoclaw.chat.jarvis.JarvisSessionController
import com.nemoclaw.chat.jarvis.meta.JarvisMetaSetupActivity
import com.nemoclaw.chat.ui.apple.AppleGlass
import com.nemoclaw.chat.ui.apple.AppleMotion
import com.nemoclaw.chat.ui.apple.AppleType

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
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            // Android requires CAMERA before starting a camera-typed FGS,
            // including DAT sessions whose frames come from the glasses.
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val missing = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            pendingStart = true
        } else {
            JarvisSessionController.rejectStart(
                "Permessi fotocamera, microfono e Bluetooth obbligatori per Jarvis Mode."
            )
        }
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
    val metaSetupTitle = if (BuildConfig.META_DAT_ENABLED) "Configura occhiali Meta" else "Info Meta DAT"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Image(
            painter = painterResource(id = R.drawable.aurora_home),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .align(Alignment.TopCenter)
        )
        LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            JarvisBrandHeader()
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Jarvis Mode",
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    JarvisReadyPill(phaseLabel)
                }
                Text(
                    "Assistenza visiva temporanea tramite Hermes locale.",
                    color = AppColors.Muted,
                    fontSize = 17.sp,
                    lineHeight = 22.sp
                )
            }
        }

        item {
            JarvisWaveform()
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(AppColors.Accent)
                    )
                    Text(
                        "Modalita",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = AppleType.RowTitle
                    )
                }
                AppleGlass(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        availableModes.forEach { mode ->
                            val selected = requestedMode == mode
                            Button(
                                onClick = {
                                    requestedModeName = mode.name
                                    if (state.active) JarvisSessionController.setMode(context, mode)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) AppColors.Accent else Color.Transparent,
                                    contentColor = if (selected) Color(0xFF171009) else Color.White
                                )
                            ) {
                                Text(
                                    initiativeModeLabel(mode),
                                    fontSize = AppleType.RowTitleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppColors.Panel),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.06f))
                        ) {
                            Icon(
                                Icons.Rounded.MyLocation,
                                contentDescription = null,
                                tint = AppColors.Accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                "Obiettivo corrente",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = AppleType.RowTitle
                            )
                            Text(
                                objective.ifBlank { "Aiutami a montare questo computer" },
                                color = AppColors.Muted,
                                fontSize = AppleType.Footnote,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
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
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.Accent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                            disabledBorderColor = Color.White.copy(alpha = 0.10f),
                            focusedLabelColor = Color.White,
                            unfocusedLabelColor = AppColors.Muted,
                            cursorColor = AppColors.Accent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            disabledTextColor = Color.White.copy(alpha = 0.5f),
                            focusedPlaceholderColor = AppColors.Faint,
                            unfocusedPlaceholderColor = AppColors.Faint,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent
                        )
                    )
                }
            }
        }

        if (BuildConfig.DEBUG) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fotocamera telefono", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = AppleType.RowTitle)
                        Text("Fallback diagnostico build debug", color = AppColors.Muted, fontSize = AppleType.Footnote)
                    }
                    Switch(
                        checked = preferPhoneDebug,
                        onCheckedChange = { preferPhoneDebug = it },
                        enabled = !state.active
                    )
                }
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
                        availabilityLabel(state.fastModelAvailable || state.reasoningModelAvailable)
                    )
                } else {
                    JarvisStatusRow("Osservatore rapido", availabilityLabel(state.fastModelAvailable))
                    JarvisStatusRow("Modello ragionante", availabilityLabel(state.reasoningModelAvailable))
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppColors.Panel),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                    ) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        metaSetupTitle,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = AppleType.RowTitle,
                        modifier = Modifier.weight(1f),
                        maxLines = 2
                    )
                    IconButton(
                        onClick = { context.startActivity(Intent(context, JarvisMetaSetupActivity::class.java)) },
                        enabled = !state.active
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = metaSetupTitle, tint = AppColors.Faint)
                    }
                }
            }
        }

        if (state.active) {
            item {
                AnimatedVisibility(
                    visible = true,
                    enter = AppleMotion.enter(),
                    exit = AppleMotion.exit()
                ) {
                JarvisPanel(title = "Attivita") {
                    JarvisStatusRow("Modello attivo", state.currentModel.orEmpty().ifBlank { "In attesa" })
                    JarvisStatusRow("Ultima latenza", latencyLabel(state.lastLatencyMs))
                    Text("Ultima osservazione", color = AppColors.Muted, fontSize = AppleType.Footnote)
                    Text(
                        state.lastObservation.orEmpty().ifBlank { "Nessuna osservazione rilevante." },
                        color = Color.White,
                        fontSize = AppleType.RowTitle
                    )
                    if (state.shortTermSummary.orEmpty().isNotBlank()) {
                        Text("Memoria breve", color = AppColors.Muted, fontSize = AppleType.Footnote)
                        Text(state.shortTermSummary.orEmpty(), color = Color.White, fontSize = AppleType.RowTitleSmall)
                    }
                    if (state.conversationTopic.orEmpty().isNotBlank()) {
                        JarvisStatusRow(
                            "Finestra conversazione",
                            state.conversationTopic.orEmpty() + if (state.awaitingFollowup) " · aperta" else ""
                        )
                    }
                    if (state.situation.orEmpty().isNotBlank()) {
                        Text("Situazione", color = AppColors.Muted, fontSize = AppleType.Footnote)
                        Text(state.situation.orEmpty(), color = Color.White, fontSize = AppleType.RowTitleSmall)
                    }
                    if (state.lastInterventionText.orEmpty().isNotBlank()) {
                        Text("Ultimo intervento", color = AppColors.Muted, fontSize = AppleType.Footnote)
                        Text(state.lastInterventionText.orEmpty(), color = Color.White, fontSize = AppleType.RowTitleSmall)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = { JarvisSessionController.sendFeedback(true) },
                                enabled = state.feedbackStatus == null
                            ) { Icon(Icons.Rounded.ThumbUp, contentDescription = "Utile", tint = Color.White) }
                            IconButton(
                                onClick = { JarvisSessionController.sendFeedback(false) },
                                enabled = state.feedbackStatus == null
                            ) { Icon(Icons.Rounded.ThumbDown, contentDescription = "Non utile", tint = Color.White) }
                        }
                        state.feedbackStatus?.let {
                            Text(it, color = AppColors.Muted, fontSize = AppleType.Footnote)
                        }
                    }
                    if (state.transcript.orEmpty().isNotBlank()) {
                        Text("Trascrizione", color = AppColors.Muted, fontSize = AppleType.Footnote)
                        Text(state.transcript.orEmpty(), color = Color.White, fontSize = AppleType.RowTitleSmall)
                    }
                }
                }
            }
        }

        if (state.error.orEmpty().isNotBlank()) {
            item {
                AnimatedVisibility(
                    visible = true,
                    enter = AppleMotion.enter(),
                    exit = AppleMotion.exit()
                ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1F1F)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        state.error.orEmpty(),
                        modifier = Modifier.padding(14.dp),
                        color = Color(0xFFFFB4AB)
                    )
                }
                }
            }
        }

        item {
            AppleGlass(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
            if (state.active) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (state.visionActive) JarvisSessionController.pauseView(context)
                            else JarvisSessionController.resumeView(context)
                        }
                    ) {
                        Icon(
                            if (state.visionActive) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.visionActive) "Pausa vista" else "Riprendi vista",
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = { JarvisSessionController.stop(context) }
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = "Termina sessione", tint = AppColors.Error)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                IconButton(
                    onClick = {
                        if (settings.gatewayUrl.isBlank()) {
                            JarvisSessionController.rejectStart("Configura Hermes API URL nelle Impostazioni.")
                            return@IconButton
                        }
                        val missing = requiredPermissions.filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) pendingStart = true
                        else permissionLauncher.launch(missing.toTypedArray())
                    }
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Avvia Jarvis Mode", tint = Color.White)
                }
                }
            }
            }
        }

        item {
            Text(
                "Frame e audio restano effimeri. Credenziale Hermes $credentialLabel; nessun endpoint personale incorporato.",
                color = AppColors.Faint,
                fontSize = AppleType.Caption,
                modifier = Modifier.padding(bottom = 18.dp)
            )
        }
        }
    }
}

@Composable
private fun JarvisBrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.14f)), CircleShape)
        ) {
            Text(
                "☤",
                color = AppColors.Accent,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Text(
                "Jarvis",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Hermes Hub",
                color = AppColors.Faint,
                fontSize = 12.sp
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0xFF1A1D23))
                .border(BorderStroke(1.5.dp, AppColors.Accent.copy(alpha = 0.75f)), CircleShape)
        ) {
            Text(
                "H",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun JarvisReadyPill(text: String) {
    AppleGlass(shape = RoundedCornerShape(999.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(AppColors.Success)
            )
            Text(
                text,
                color = Color.White,
                fontSize = AppleType.Footnote,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun JarvisWaveform() {
    val fractions = listOf(0.32f, 0.48f, 0.64f, 0.84f, 1f, 0.84f, 0.64f, 0.48f, 0.32f)
    val barBrush = Brush.verticalGradient(
        0.0f to Color(0xFFFFC25E),
        1.0f to Color(0xFFE8930C)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        fractions.forEach { fraction ->
            Box(
                modifier = Modifier
                    .size(width = 10.dp, height = 76.dp * fraction)
                    .clip(RoundedCornerShape(999.dp))
                    .background(barBrush)
            )
        }
    }
}

@Composable
private fun JarvisPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.Panel),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(AppColors.Accent)
                )
                Text(
                    title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = AppleType.RowTitle
                )
            }
            content()
        }
    }
}

@Composable
private fun JarvisStatusRow(label: String, value: String) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f))
            )
            Text(
                label,
                color = Color.White,
                fontSize = AppleType.RowTitleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                color = AppColors.Muted,
                fontSize = AppleType.RowTitleSmall,
                modifier = Modifier.widthIn(max = 190.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(0.5.dp)
                .background(Color.White.copy(alpha = 0.08f))
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
