package com.nemoclaw.chat

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun WorkspaceFeedScreen(
    context: Context,
    settings: AppSettings,
    kind: String,
    title: String,
    description: String,
    empty: String,
    chatPrompt: String,
    onOpenChatPrompt: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Feed sincronizzato localmente. I nuovi spunti arrivano dalla chat e dagli artifact Hermes.") }
    val items = remember(refreshKey) { loadWorkspaceRequests(context, kind) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(description, color = AppColors.Muted)
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(onClick = { onOpenChatPrompt(chatPrompt) }) { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = "Nuovo spunto in chat", tint = Color.White) }
                IconButton(onClick = {
                    status = "Sincronizzo artifact Hermes..."
                    scope.launch {
                        status = syncWorkspaceJobs(context, settings, kind, loadGatewaySecret(context))
                        refreshKey++
                    }
                }) { Icon(Icons.Rounded.Sync, contentDescription = "Sincronizza Hermes", tint = Color.White) }
            }
        }
        item {
            PremiumPanel {
                Text(modifier = Modifier.padding(14.dp), text = status, color = AppColors.Muted)
            }
        }
        if (items.isEmpty()) {
            item {
                PremiumPanel {
                    Text(modifier = Modifier.padding(16.dp), text = empty, color = AppColors.Muted)
                }
            }
        }
        items.forEach { feedItem ->
            item {
                WorkspaceFeedItem(context, settings, feedItem) {
                    refreshKey++
                    status = it
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceFeedItem(
    context: Context,
    settings: AppSettings,
    item: WorkspaceRequest,
    onChanged: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var feedback by remember(item.id) { mutableStateOf("") }
    PremiumPanel {
        Column(modifier = Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text("${item.status} · ${item.source}", color = AppColors.Muted, fontSize = 12.sp)
            if (item.result.isNotBlank()) {
                MarkdownText(item.result.limitText(900), color = Color.White, fontSize = 14.sp)
            }
            item.remoteId?.let { Text("Job: $it", color = AppColors.Faint, fontSize = 12.sp) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (item.streamUrl.isNotBlank()) {
                    IconButton(onClick = { openAndroidIntent(context, Intent(Intent.ACTION_VIEW, resolveWorkspaceUrl(settings, item.streamUrl).toUri())) }) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Streaming", tint = Color.White) }
                }
                if (item.downloadUrl.isNotBlank()) {
                    IconButton(onClick = { openAndroidIntent(context, Intent(Intent.ACTION_VIEW, resolveWorkspaceUrl(settings, item.downloadUrl).toUri())) }) { Icon(Icons.Rounded.Download, contentDescription = "Scarica", tint = Color.White) }
                }
                if (item.remoteId != null) {
                    IconButton(onClick = {
                        scope.launch { onChanged(runWorkspaceJobAction(settings, item, "run", loadGatewaySecret(context))) }
                    }) { Icon(Icons.Rounded.Refresh, contentDescription = "Aggiorna job", tint = Color.White) }
                }
            }
            SettingsField("Feedback per Hermes", feedback, { feedback = it })
            IconButton(onClick = {
                if (feedback.isBlank()) {
                    onChanged("Scrivi un feedback prima di inviarlo.")
                    return@IconButton
                }
                scope.launch {
                    val result = sendWorkspaceFeedback(settings, item, feedback, loadGatewaySecret(context))
                    saveWorkspaceFeedback(context, item.id, feedback, result)
                    feedback = ""
                    onChanged(result)
                }
            }) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Invia feedback", tint = Color.White) }
            if (item.feedback.isNotBlank()) {
                Text("Ultimo feedback: ${item.feedback}", color = AppColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

