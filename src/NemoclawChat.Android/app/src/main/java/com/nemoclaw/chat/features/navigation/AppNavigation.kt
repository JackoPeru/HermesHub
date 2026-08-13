package com.nemoclaw.chat

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier

internal enum class Tab(val label: String, val icon: ImageVector) {
    Chat("Chat", Icons.Rounded.ChatBubbleOutline),
    Voice("Voce", Icons.Rounded.Mic),
    Jarvis("Jarvis", Icons.Rounded.Visibility),
    Projects("Progetti", Icons.Rounded.FolderOpen),
    Artifacts("Artifact", Icons.Rounded.FolderOpen),
    Search("Ricerca", Icons.AutoMirrored.Rounded.ManageSearch),
    Archive("Archivio", Icons.Rounded.FolderOpen),
    Cron("Cron", Icons.Rounded.TaskAlt),
    Notifications("Notifiche", Icons.Rounded.Notifications),
    Continuity("Continuità", Icons.Rounded.ContentCopy),
    Audit("Audit", Icons.Rounded.TaskAlt),
    Server("Server", Icons.Rounded.Dns),
    Hardware("Hardware", Icons.Rounded.Memory),
    Health("Salute", Icons.Rounded.FavoriteBorder),
    Video("Video", Icons.Rounded.PlayCircle),
    News("News", Icons.AutoMirrored.Rounded.Article),
    Settings("Impostazioni", Icons.Rounded.Tune),
    Profile("Profilo", Icons.Rounded.AccountCircle)
}

internal fun tabForIncomingRoute(value: String): Tab = when {
    value.equals("voice", ignoreCase = true) -> Tab.Voice
    value.equals("jarvis", ignoreCase = true) -> Tab.Jarvis
    value.equals("projects", ignoreCase = true) -> Tab.Projects
    value.equals("artifacts", ignoreCase = true) -> Tab.Artifacts
    value.equals("archive", ignoreCase = true) -> Tab.Archive
    value.equals("settings", ignoreCase = true) -> Tab.Settings
    else -> Tab.Chat
}

@Composable
internal fun AppNavigation(
    selectedTab: Tab,
    topBar: @Composable () -> Unit,
    sidebar: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selectedTab != Tab.Chat) {
                topBar()
            }
            Box(modifier = Modifier.weight(1f)) {
                content()
            }
        }
        sidebar()
    }
}
