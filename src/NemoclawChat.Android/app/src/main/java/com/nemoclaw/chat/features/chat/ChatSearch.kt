package com.nemoclaw.chat

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.ui.apple.AppleGlass
import com.nemoclaw.chat.ui.apple.AppleType
import com.nemoclaw.chat.ui.apple.AuroraBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun UniversalSearchScreen(context: Context, settings: AppSettings, onOpen: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("Inserisci almeno 2 caratteri.") }
    var results by remember { mutableStateOf<List<Triple<String, String, Pair<String, String>>>>(emptyList()) }

    fun runSearch() {
        val needle = query.trim()
        if (needle.length < 2) { status = "Inserisci almeno 2 caratteri."; return }
        status = "Ricerca in corso..."
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                val list = mutableListOf<Triple<String, String, Pair<String, String>>>()
                loadConversations(context).forEach { item ->
                    val body = buildString {
                        append(item.title).append(' ').append(item.description).append(' ').append(item.prompt).append(' ')
                        append(item.projectMemory).append(' ').append(item.projectInstructions).append(' ').append(item.artifactFileName).append(' ').append(item.tags.joinToString(" "))
                        item.messages.forEach { message ->
                            append(' ').append(message.text)
                            message.rawEvents.forEach { append(' ').append(it.json) }
                            message.visualBlocks.forEach { append(' ').append(it.title).append(' ').append(it.caption).append(' ').append(it.text).append(' ').append(it.code).append(' ').append(it.filename) }
                        }
                    }
                    if (body.contains(needle, true)) list += Triple(item.kind, item.id, item.title to body.replace('\n', ' ').take(260))
                }
                val apiKey = loadGatewaySecret(context)
                loadCronJobs(settings, apiKey).first.filter { "${it.name} ${it.prompt} ${it.lastStatus}".contains(needle, true) }
                    .forEach { list += Triple("Cron", it.id, it.name to "${it.prompt} ${it.lastStatus}".take(260)) }
                loadHubNotifications(settings, apiKey, false).first.filter { "${it.title} ${it.message}".contains(needle, true) }
                    .forEach { list += Triple("Notifica", it.id, it.title to it.message.take(260)) }
                val memory = loadHubMemory(settings, apiKey).first
                val memoryText = "${memory.videoPreferences} ${memory.newsPreferences} ${memory.responseStyle} ${memory.projectRules} ${memory.generalNotes}"
                if (memoryText.contains(needle, true)) list += Triple("Memoria", "hub-memory", "Memoria Hermes" to memoryText.take(260))
                list
            }
            results = found
            status = "${found.size} risultati."
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(vertical = 20.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = AppColors.NavIndicator
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ManageSearch, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Ricerca universale", color = Color.White, fontSize = AppleType.ScreenTitle, fontWeight = FontWeight.SemiBold)
                    Text("Chat, tool call, artifact, progetti, memoria, cron e notifiche.", color = AppColors.Muted, fontSize = AppleType.HeroSubtitle)
                }
            }
        }
        item {
            AppleGlass(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("CERCA", color = AppColors.Muted, fontSize = AppleType.RowTitleSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                SettingsField("Cerca ovunque", query, { query = it })
                Button(
                    onClick = { runSearch() },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009))
                ) { Text("Cerca") }
                Text(status, color = AppColors.Muted, fontSize = AppleType.Footnote)
            }
            }
        }
        item { HorizontalDivider(color = AppColors.Border.copy(alpha = 0.6f), thickness = 0.5.dp) }
        if (results.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.ManageSearch, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(32.dp))
                    Text("Nessun risultato", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(status, color = AppColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                    TextButton(
                        onClick = { runSearch() },
                        colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                    ) { Text("Cerca") }
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("RISULTATI", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    results.forEachIndexed { index, result ->
                        val kindIcon = when (result.first) {
                            "Cron" -> Icons.Rounded.Refresh
                            "Notifica" -> Icons.Rounded.Notifications
                            "Memoria" -> Icons.Rounded.Memory
                            "Artifact" -> Icons.Rounded.Description
                            "Progetto" -> Icons.Rounded.FolderOpen
                            else -> Icons.Rounded.ChatBubbleOutline
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(result.first, result.second) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = AppColors.NavIndicator
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(kindIcon, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(22.dp))
                                }
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${result.first} · ${result.third.first}", color = Color.White, fontSize = AppleType.RowTitle, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(result.third.second, color = AppColors.Faint, fontSize = AppleType.Footnote, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(20.dp))
                        }
                        if (index < results.lastIndex) HorizontalDivider(color = AppColors.Border.copy(alpha = 0.6f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
    }
}

