package com.nemoclaw.chat

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.core.WorkLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun BotLiveBanner(statusText: String, turnStartMs: Long = 0L, lastSignalMs: Long = 0L) {
    // Timer vivo a 1s mentre il turno corre: l'utente vede che e tutto attivo,
    // non un'etichetta congelata. Mai fasi inventate: solo tempi misurati.
    // Gate lifecycle come ShimmerText: in background il ticker si ferma.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val bannerLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var bannerResumed by remember { mutableStateOf(true) }
    DisposableEffect(bannerLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> bannerResumed = true
                Lifecycle.Event.ON_PAUSE -> bannerResumed = false
                else -> Unit
            }
        }
        bannerLifecycle.addObserver(observer)
        onDispose { bannerLifecycle.removeObserver(observer) }
    }
    LaunchedEffect(turnStartMs, bannerResumed) {
        nowMs = System.currentTimeMillis()
        while (bannerResumed) {
            kotlinx.coroutines.delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    val elapsed = if (turnStartMs > 0) formatTurnElapsed(nowMs - turnStartMs) else null
    val stale = if (lastSignalMs > 0) staleTurnLabel(nowMs - lastSignalMs) else null
    val main = (stale ?: statusText.ifBlank { "Sta lavorando…" })
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.size(6.dp).background(AppColors.Accent, CircleShape))
        ShimmerText(main, enabled = true)
        if (elapsed != null) {
            Text(
                elapsed,
                color = AppColors.Faint,
                fontSize = 12.sp,
                modifier = Modifier.semantics { contentDescription = "Turno attivo da $elapsed" }
            )
        }
    }
}


@Composable
internal fun ChatModelSessionBar(
    state: ChatStateHolder,
    settings: AppSettings,
    context: Context,
    botSettings: AppSettings,
    botApiKey: String?,
    botProfile: String?,
    botMultiplexEnabled: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    autoApproveMode: String = "off"
) {
    val caps = state.chatCapabilities
    var showSteerDialog by remember { mutableStateOf(false) }
    var steerText by remember { mutableStateOf("") }
    var steerStatus by remember { mutableStateOf("") }
    val streaming = state.streamingState
    val runId = streaming?.activeRunId
    val steerable = !runId.isNullOrBlank() && streaming?.isDone == false &&
        (caps?.supportsSteer() ?: false) && streaming?.runStatus !in listOf("completed", "failed", "cancelled")

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val routeLabel = when (state.sessionRoute) {
                "sessions" -> "Sessione Hermes${state.hermesSessionId?.take(8)?.let { " · ${it}" }.orEmpty()}"
                "legacy-fallback" -> "Legacy (sessions non riuscite)"
                else -> "Legacy"
            }
            Text(routeLabel, color = AppColors.Faint, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (autoApproveMode == WorkLimits.AUTO_APPROVE_SESSION || autoApproveMode == WorkLimits.AUTO_APPROVE_ALWAYS) {
                Surface(
                    color = AppColors.Elevated,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.5.dp, AppColors.Accent)
                ) {
                    Text(
                        if (autoApproveMode == WorkLimits.AUTO_APPROVE_ALWAYS) "AUTO SEMPRE" else "AUTO SESSIONE",
                        color = AppColors.Accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            if (steerable) {
                IconButton(onClick = { steerStatus = ""; showSteerDialog = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Edit, contentDescription = "Correggi run in corso", tint = AppColors.Faint, modifier = Modifier.size(18.dp)) }
            }
        }
        if (streaming?.pendingSteer?.isNotBlank() == true) {
            Text("Steer non consegnato: riproponilo come turno successivo.", color = AppColors.Faint, fontSize = 11.sp)
        }
    }

    if (showSteerDialog && runId != null) {
        AlertDialog(
            onDismissRequest = { showSteerDialog = false },
            title = { Text("Correggi run in corso", color = Color.White, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Inviata al run $runId. Consegna al prossimo tool boundary; non crea un nuovo turno.", fontSize = 12.sp, color = AppColors.Muted)
                    TextField(value = steerText, onValueChange = { steerText = it }, placeholder = { Text("Nuova istruzione...") })
                    if (steerStatus.isNotBlank()) Text(steerStatus, fontSize = 12.sp, color = AppColors.Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val payload = steerText.trim()
                    if (payload.isEmpty()) {
                        steerStatus = "Testo obbligatorio."
                        return@TextButton
                    }
                    scope.launch {
                        // Singolo invio, niente retry su esito incerto.
                        val (code, body) = steerHermesRun(botSettings, runId, payload, botApiKey, botProfile, botMultiplexEnabled)
                        steerStatus = when {
                            code in 200..299 -> "Guida accodata (consegna al prossimo tool boundary)."
                            code == 409 -> "Run non più steerable (409): è terminato o in arresto."
                            code == 401 || code == 403 -> "Chiave rifiutata per questo profilo (HTTP $code)."
                            code == 404 -> "Run non trovato (404)."
                            else -> "Steer fallito: HTTP $code ${body.take(160)}"
                        }
                        if (code in 200..299) {
                            steerText = ""
                        }
                    }
                }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Invia correzione", tint = AppColors.Accent) }
            },
            dismissButton = { IconButton(onClick = { showSteerDialog = false }) { Icon(Icons.Rounded.Close, contentDescription = "Chiudi", tint = AppColors.Muted) } }
        )
    }
}


@Composable
internal fun BackgroundWorkBanner(
    work: BackgroundWorkUi,
    onStop: () -> Unit
) {
    val ctx = LocalContext.current
    AppleBannerRow(
        icon = Icons.Rounded.Refresh,
        tint = AppColors.Accent,
        title = if (work.goal.isBlank()) "Hermes al lavoro in background" else "Hermes al lavoro: ${work.goal.take(WorkLimits.TRUNC_80)}",
        subtitle = work.statusText
    ) {
        IconButton(
            onClick = {
                onStop()
                Toast.makeText(ctx, "Interruzione richiesta.", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(Icons.Rounded.Stop, contentDescription = "Ferma lavoro", tint = AppColors.Error, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Riga banner stile Apple: niente card, solo hairline + riga densa.
 * Unico contenitore per tutti i banner chat (coerenza visiva).
 */
@Composable
private fun AppleBannerRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    actions: @Composable RowScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        HorizontalDivider(thickness = 0.5.dp, color = AppColors.Border)
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = AppColors.Muted,
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Clip
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        HorizontalDivider(thickness = 0.5.dp, color = AppColors.Border)
    }
}


/**
 * Banner turni staccati (kill/rete morta, server continua per
 * continue_on_disconnect): follow live mentre gira, recupero guidato poi.
 */
@Composable
internal fun LiveFollowBanner(
    chars: Int,
    onStop: () -> Unit
) {
    AppleBannerRow(
        icon = Icons.Rounded.CloudSync,
        tint = AppColors.Success,
        title = "Seguo il turno live",
        subtitle = "$chars caratteri · si aggiorna da solo"
    ) {
        TextButton(
            onClick = onStop,
            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
        ) {
            Text("Ferma", color = AppColors.Accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun DetachedFollowBanner(
    subtitle: String,
    checking: Boolean,
    onRecheck: () -> Unit,
    onDismiss: () -> Unit
) {
    AppleBannerRow(
        icon = Icons.Rounded.CloudSync,
        tint = AppColors.Accent,
        title = "Hermes continua sul server",
        subtitle = subtitle
    ) {
        TextButton(
            onClick = onRecheck,
            enabled = !checking,
            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
        ) {
            Text(
                if (checking) "Attendi…" else "Ricontrolla",
                color = if (checking) AppColors.Muted else AppColors.Accent,
                fontWeight = FontWeight.SemiBold
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Ignora", tint = AppColors.Muted, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun DetachedResumeNote(
    text: String,
    onDismiss: () -> Unit
) {
    AppleBannerRow(
        icon = Icons.Rounded.CloudSync,
        tint = AppColors.Muted,
        title = text,
        subtitle = ""
    ) {
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Chiudi", tint = AppColors.Muted, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Novita dal server in attesa: il live non si e mosso, l'utente decide
 * con Mostra. Stesso stile degli altri banner, una sola azione chiara.
 */
@Composable
internal fun ServerStagedBanner(
    count: Int,
    onShow: () -> Unit,
    onDismiss: () -> Unit
) {
    AppleBannerRow(
        icon = Icons.Rounded.CloudSync,
        tint = AppColors.Success,
        title = if (count == 1) "↓ 1 nuovo dal server" else "↓ $count nuovi dal server",
        subtitle = ""
    ) {
        TextButton(
            onClick = onShow,
            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
        ) {
            Text("Mostra", color = AppColors.Accent, fontWeight = FontWeight.SemiBold)
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Ignora", tint = AppColors.Muted, modifier = Modifier.size(16.dp))
        }
    }
}

