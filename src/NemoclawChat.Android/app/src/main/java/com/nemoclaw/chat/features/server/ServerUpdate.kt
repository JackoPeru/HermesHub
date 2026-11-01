package com.nemoclaw.chat

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.ui.apple.AppleMotion
import com.nemoclaw.chat.ui.apple.AppleType
import kotlinx.coroutines.launch

/**
 * Card aggiornamento app: controlla GitHub Releases, scarica l'APK e lo
 * installa. Stessa card in fondo alla dashboard e nel Profilo (nessuna
 * logica duplicata, stato proprio per istanza).
 */
@Composable
internal fun AppUpdateCard(context: Context) {
    val scope = rememberCoroutineScope()
    val version = remember { appVersion(context) }
    var updateState by remember { mutableStateOf(UpdateDownloadState()) }
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
