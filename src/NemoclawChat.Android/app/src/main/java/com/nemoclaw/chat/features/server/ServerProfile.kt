package com.nemoclaw.chat

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProfileScreen(
    context: Context,
    settings: AppSettings,
    onOpenTab: (Tab) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    // Archivio MAI sul main: parse disco lento, va in IO e riempie dopo.
    var conversations by remember { mutableStateOf(emptyList<LocalConversation>()) }
    LaunchedEffect(Unit) {
        conversations = withContext(Dispatchers.IO) { loadConversations(context) }
    }
    val version = remember { appVersion(context) }
    var updateState by remember { mutableStateOf(UpdateDownloadState()) }
    var memory by remember { mutableStateOf(HubMemoryState()) }
    var memoryStatus by remember { mutableStateOf("Memoria gateway non ancora letta.") }
    val managerBase = remember(settings.gatewayUrl) { gpuManagerBase(settings.gatewayUrl) }
    var showRebootConfirm by remember { mutableStateOf(false) }
    var rebootBusy by remember { mutableStateOf(false) }
    var rebootStatus by remember { mutableStateOf("") }

    fun doReboot() {
        // Riavvio manager via laya (il POST diretto /system/reboot e'
        // controllo laya: la chiave app non basta piu'). Host reboot solo via ssh.
        scope.launch {
            rebootBusy = true
            rebootStatus = try {
                val bridge = gpuBridgeBase(settings.gatewayUrl)
                postLayaCommand(bridge, "riavvia il manager", loadGpuAppSecret(context))
            } catch (ex: Exception) {
                "Errore invio riavvio: ${ex.message ?: ex.javaClass.simpleName}"
            }
            rebootBusy = false
        }
    }

    LaunchedEffect(settings.gatewayUrl) {
        val loaded = loadHubMemory(settings, loadGatewaySecret(context))
        memory = loaded.first
        memoryStatus = loaded.second
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
                    Icon(Icons.Rounded.AccountCircle, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Profilo", color = Color.White, fontSize = AppleType.ScreenTitle, fontWeight = FontWeight.SemiBold)
                        Text("Profilo locale. Nessun account cloud, nessun token provider nel client.", color = AppColors.Faint, fontSize = AppleType.Footnote)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.chatclaw_logo),
                        contentDescription = "Logo Hermes Hub",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(18.dp))
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Profilo locale", color = Color.White, fontSize = AppleType.ScreenTitle, fontWeight = FontWeight.SemiBold)
                        Text("Home-server Hermes Agent locale", color = AppColors.Muted, fontSize = AppleType.Footnote)
                        Text("App: $version", color = AppColors.Muted, fontSize = AppleType.Footnote)
                    }
                }
                HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
            }
        }
        item {
            ServerMetric("Hermes API", settings.gatewayUrl, if (settings.demoMode) "Fallback locale attivo" else "Solo Hermes")
        }
        item {
            ServerMetric("Archivio locale", "${conversations.size} elementi", "Cronologia e progetti salvati sul dispositivo.")
        }
        item {
            ServerMetric("Privacy", "Locale-first", "Chat/settings restano sul dispositivo finche' non colleghi Hermes. API key salvata in Keystore.")
        }
        item {
            ServerMetric("Parita Windows", "Allineata", "Chat, archivio, progetti/recenti, cron, Hermes server, prestazioni, video, news, settings e profilo presenti anche su Android.")
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AREE RAPIDE", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Schermate secondarie spostate qui per lasciare la barra bassa pulita.", color = AppColors.Muted, fontSize = AppleType.Footnote)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onOpenTab(Tab.Server) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Hermes") }
                    TextButton(onClick = { onOpenTab(Tab.Cron) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Cron") }
                    TextButton(onClick = { onOpenTab(Tab.Notifications) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Notifiche") }
                    TextButton(onClick = { onOpenTab(Tab.Hardware) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Prestazioni") }
                    TextButton(onClick = { onOpenTab(Tab.News) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("News") }
                    TextButton(onClick = { onOpenTab(Tab.Settings) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Impostazioni") }
                    TextButton(onClick = { onOpenTab(Tab.Projects) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Progetti") }
                    TextButton(onClick = { onOpenTab(Tab.Archive) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Archivio") }
                }
                HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SERVER HERMES", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(
                    "Riavvio completo del server (come sudo reboot now). Da usare se il server non risponde più.",
                    color = AppColors.Muted,
                    fontSize = AppleType.Footnote
                )
                AnimatedVisibility(
                    visible = rebootStatus.isNotBlank(),
                    enter = AppleMotion.enter(),
                    exit = AppleMotion.exit()
                ) {
                    Text(rebootStatus, color = AppColors.Faint, fontSize = AppleType.Footnote)
                }
                Button(
                    onClick = { showRebootConfirm = true },
                    enabled = !rebootBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (rebootBusy) "Riavvio in corso..." else "Riavvia server", fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PROGETTO ATTIVO", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                if (settings.activeProjectId.isBlank()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                        Text("Nessun progetto selezionato.", color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Text("Scegli un progetto per continuare.", color = AppColors.Muted, fontSize = AppleType.Footnote, textAlign = TextAlign.Center)
                        Button(onClick = { onOpenTab(Tab.Projects) }, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)), shape = RoundedCornerShape(14.dp)) { Text("Apri Progetti", fontWeight = FontWeight.SemiBold) }
                    }
                } else {
                    Text("Attivo: ${settings.activeProjectName}", color = AppColors.Muted, fontSize = AppleType.Footnote)
                    TextButton(onClick = { onOpenTab(Tab.Projects) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Apri Progetti") }
                }
                HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("MEMORIA HERMES", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(memoryStatus, color = AppColors.Faint, fontSize = AppleType.Footnote)
                SettingsField("Preferenze video", memory.videoPreferences, { memory = memory.copy(videoPreferences = it) })
                SettingsField("Preferenze news", memory.newsPreferences, { memory = memory.copy(newsPreferences = it) })
                SettingsField("Stile risposta", memory.responseStyle, { memory = memory.copy(responseStyle = it) })
                SettingsField("Regole progetto", memory.projectRules, { memory = memory.copy(projectRules = it) })
                SettingsField("Note generali", memory.generalNotes, { memory = memory.copy(generalNotes = it) })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            memoryStatus = "Salvo memoria gateway..."
                            scope.launch {
                                memoryStatus = saveHubMemory(settings, memory, loadGatewaySecret(context))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Salva memoria", fontWeight = FontWeight.SemiBold) }
                    TextButton(
                        onClick = {
                            memory = HubMemoryState()
                            memoryStatus = "Contenuti locali svuotati. Premi Salva memoria per cancellare sul gateway."
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Error)
                    ) { Text("Svuota memoria") }
                }
                HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AGGIORNAMENTI", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Installata: $version", color = AppColors.Muted, fontSize = AppleType.Footnote)
                updateState.latestVersion?.let { Text("Latest: $it", color = AppColors.Muted, fontSize = AppleType.Footnote) }
                updateState.releaseAssetUrl?.let { Text("Asset: ${it.substringAfterLast('/')}", color = AppColors.Muted, fontSize = AppleType.Footnote) }
                updateState.downloadedApkPath?.let { Text("APK pronto: $it", color = AppColors.Muted, fontSize = AppleType.Footnote) }
                Text(updateState.status, color = AppColors.Faint)
                if (updateState.releaseSummary.isNotBlank()) {
                    Text(
                        "Changelog:\n${updateState.releaseSummary}",
                        color = Color.White,
                        fontSize = AppleType.RowTitleSmall
                    )
                }
                AnimatedVisibility(
                    visible = updateState.progress != null,
                    enter = AppleMotion.enter(),
                    exit = AppleMotion.exit()
                ) {
                    LinearProgressIndicator(
                        progress = { updateState.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = AppColors.Accent,
                        trackColor = AppColors.Border
                    )
                        Text(
                            downloadProgressLabel(updateState.progress, updateState.downloadLabel),
                            color = AppColors.Faint,
                            fontSize = AppleType.Footnote
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = {
                                updateState = updateState.copy(
                                    status = "Controllo GitHub Releases...",
                                    progress = null,
                                    downloadLabel = "",
                                    downloadedApkPath = null,
                                    isDownloading = false,
                                    releaseSummary = ""
                                )
                                scope.launch {
                                    val result = checkGithubUpdate(version)
                                    val downloadedApk = if (result.hasUpdate) {
                                        result.latestVersion?.let { findDownloadedUpdateApk(context, it) }
                                    } else {
                                        null
                                    }
                                    updateState = updateState.copy(
                                        status = if (downloadedApk != null) {
                                            "Aggiornamento gia' scaricato. Premi Aggiorna per installarlo."
                                        } else if (result.hasUpdate && result.assetUrl != null) {
                                            "${result.message} Scarica l'APK dentro l'app e poi premi Aggiorna."
                                        } else {
                                            result.message
                                        },
                                        releaseAssetUrl = result.assetUrl,
                                        latestVersion = result.latestVersion,
                                        hasUpdate = result.hasUpdate,
                                        progress = if (downloadedApk != null) 1f else null,
                                        downloadLabel = downloadedApk?.length()?.toReadableFileSize() ?: "",
                                        downloadedApkPath = downloadedApk?.absolutePath,
                                        releaseSummary = result.releaseSummary
                                    )
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                        ) {
                            Text("Controlla aggiornamenti")
                        }
                        if (updateState.hasUpdate && updateState.releaseAssetUrl != null && updateState.downloadedApkPath == null && !updateState.isDownloading) {
                            Button(
                                onClick = {
                                    val assetUrl = updateState.releaseAssetUrl ?: return@Button
                                    scope.launch {
                                        updateState = updateState.copy(
                                            status = "Scaricamento APK in corso...",
                                            isDownloading = true,
                                            progress = 0f,
                                            downloadLabel = ""
                                        )
                                        val downloaded = downloadUpdateApk(
                                            context = context,
                                            assetUrl = assetUrl,
                                            version = updateState.latestVersion ?: version
                                        ) { fraction, status, label ->
                                            updateState = updateState.copy(
                                                progress = fraction,
                                                status = status,
                                                downloadLabel = label,
                                                isDownloading = true
                                            )
                                        }

                                        updateState = if (downloaded != null) {
                                            updateState.copy(
                                                status = "APK pronto. Premi Aggiorna per avviare l'installazione Android.",
                                                downloadedApkPath = downloaded.absolutePath,
                                                isDownloading = false,
                                                progress = 1f,
                                                downloadLabel = downloaded.length().toReadableFileSize()
                                            )
                                        } else {
                                            updateState.copy(
                                                status = "Download non riuscito. Premi Controlla e riprova.",
                                                downloadedApkPath = null,
                                                isDownloading = false,
                                                progress = null,
                                                downloadLabel = ""
                                            )
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Scarica APK", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (updateState.downloadedApkPath != null && !updateState.isDownloading) {
                            TextButton(
                                onClick = {
                                    val apkPath = updateState.downloadedApkPath ?: return@TextButton
                                    val status = installDownloadedApk(context, apkPath)
                                    updateState = updateState.copy(status = status)
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                            ) {
                                Text("Installa aggiornamento")
                            }
                        }
                    }
                    HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
                }
        }
    }
    }
    if (showRebootConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootConfirm = false },
            containerColor = AppColors.Surface,
            title = { Text("Riavviare il manager?", color = Color.White) },
            text = { Text("laya riavvia hermes-gpu-manager (non l'intero server: per quello serve ssh). Code e modelli si interrompono e tornano da soli.", color = AppColors.Muted) },
            confirmButton = {
                Button(
                    onClick = { showRebootConfirm = false; doReboot() },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Error, contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Riavvia ora", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showRebootConfirm = false }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Annulla") }
            }
        )
    }
}

