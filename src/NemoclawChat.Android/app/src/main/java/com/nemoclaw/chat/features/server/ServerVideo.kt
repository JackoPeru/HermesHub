package com.nemoclaw.chat

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URL

@Composable
internal fun VideoScreen(context: Context, settings: AppSettings, onOpenChatPrompt: (String) -> Unit) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Sincronizzo cartella video Hermes...") }
    var items by remember { mutableStateOf<List<VideoLibraryItem>>(emptyList()) }
    var selectedVideoId by rememberSaveable { mutableStateOf<String?>(null) }
    var videoFilter by rememberSaveable { mutableStateOf("Tutti") }
    var manualVideoUrl by rememberSaveable { mutableStateOf("") }
    var manualVideoError by rememberSaveable { mutableStateOf("") }
    val manualVideoItem = remember(manualVideoUrl) { createManualVideoItem(manualVideoUrl) }
    val allItems = remember(items, manualVideoItem) {
        if (manualVideoItem == null) items else listOf(manualVideoItem) + items
    }
    val selectedVideo = remember(allItems, selectedVideoId) { allItems.firstOrNull { it.id == selectedVideoId } }
    val displayedItems = remember(allItems, videoFilter) {
        when (videoFilter) {
            "Recenti" -> allItems.sortedByDescending { it.modifiedAt }
            "Feedback" -> allItems.filter { loadVideoFeedback(context, it.id).isNotBlank() || loadVideoReaction(context, it.id).isNotBlank() }
            else -> allItems
        }
    }

    LaunchedEffect(settings.gatewayUrl, settings.videoLibraryPath, refreshKey) {
        val result = loadVideoLibrary(settings, loadGatewaySecret(context))
        items = result.first
        status = result.second
        if (selectedVideoId != null && selectedVideoId?.startsWith("manual:") != true && result.first.none { it.id == selectedVideoId }) {
            selectedVideoId = null
        }
    }

    if (selectedVideo != null) {
        BackHandler { selectedVideoId = null }
        VideoWatchScreen(
            context = context,
            settings = settings,
            item = selectedVideo,
            onBack = { selectedVideoId = null }
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(AppColors.Accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Videocam, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hermes Video", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(settings.videoLibraryPath.ifBlank { "Sincronizzo cartella video Hermes..." }, color = AppColors.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = {
                    onOpenChatPrompt(
                        "Modalita Video Hermes Hub. Usa la cartella video monitorata del PC Hermes: ${settings.videoLibraryPath}. " +
                            "Crea, scarica o prepara un video e salva sempre il file finale in quella cartella, cosi appare automaticamente nella sezione Video. Richiesta: "
                    )
                }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Nuovo video", tint = AppColors.Accent)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VideoFeedChip("Tutti", selected = videoFilter == "Tutti") { videoFilter = "Tutti" }
                VideoFeedChip("Recenti", selected = videoFilter == "Recenti") { videoFilter = "Recenti" }
                VideoFeedChip("Feedback", selected = videoFilter == "Feedback") { videoFilter = "Feedback" }
                TextButton(
                    onClick = {
                        status = "Aggiorno feed video..."
                        refreshKey++
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                ) { Text("Aggiorna", fontWeight = FontWeight.SemiBold) }
            }
        }
        item {
            Text(status, color = AppColors.Faint, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        item {
            Surface(color = AppColors.Panel, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AppColors.Border)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("URL VIDEO MANUALE", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    TextField(
                        value = manualVideoUrl,
                        onValueChange = {
                            manualVideoUrl = it
                            manualVideoError = ""
                        },
                        singleLine = true,
                        placeholder = { Text("https://...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                val manual = createManualVideoItem(manualVideoUrl)
                                if (manual == null) {
                                    manualVideoError = "URL non valido. Usa un link http/https diretto."
                                } else {
                                    manualVideoError = ""
                                    selectedVideoId = manual.id
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009))
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Apri video", fontWeight = FontWeight.SemiBold)
                        }
                        if (manualVideoError.isNotBlank()) {
                            Text(manualVideoError, color = AppColors.Accent, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (displayedItems.isEmpty()) {
            item {
                Surface(color = AppColors.Panel, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AppColors.Border)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Videocam, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                        Text(
                            if (items.isEmpty()) "Nessun video trovato" else "Nessun video con feedback salvato",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            if (items.isEmpty()) {
                                "Metti un file video nella cartella video Hermes sul PC e premi Aggiorna feed."
                            } else {
                                "Nessun video con feedback salvato."
                            },
                            color = AppColors.Muted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                status = "Aggiorno feed video..."
                                refreshKey++
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009))
                        ) { Text("Aggiorna feed", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
        items(displayedItems, key = { it.id }) { video ->
            VideoFeedCard(
                settings = settings,
                item = video,
                apiKey = loadGatewaySecret(context),
                onClick = { selectedVideoId = video.id }
            )
        }
    }
}

