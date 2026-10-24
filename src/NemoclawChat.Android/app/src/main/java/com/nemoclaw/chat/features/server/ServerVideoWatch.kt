package com.nemoclaw.chat

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.core.net.toUri
import com.nemoclaw.chat.ui.apple.AppleMotion
import com.nemoclaw.chat.ui.apple.AppleType
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.launch

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
internal fun VideoWatchScreen(context: Context, settings: AppSettings, item: VideoLibraryItem, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val apiKey = remember { loadGatewaySecret(context) }
    var useCompatPlayback by rememberSaveable(item.id) { mutableStateOf(false) }
    val primaryVideoUrl = remember(settings.gatewayUrl, item.mediaUrl, item.playbackUrl) { resolveVideoPlaybackUrl(settings, item) }
    val compatVideoUrl = remember(settings.gatewayUrl, item.mediaUrl, item.playbackUrl, item.compatUrl) { resolveVideoCompatUrl(settings, item) }
    val videoUrl = remember(primaryVideoUrl, compatVideoUrl, useCompatPlayback) {
        if (useCompatPlayback && compatVideoUrl.isNotBlank()) compatVideoUrl else primaryVideoUrl
    }
    val player = remember(videoUrl, apiKey) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(if (shouldAuthenticateHermesUrl(settings, videoUrl)) authHeaders(apiKey) else emptyMap())
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(videoUrl.toUri()))
                prepare()
                playWhenReady = true
            }
    }
    var feedback by remember(item.id) { mutableStateOf(loadVideoFeedback(context, item.id)) }
    var reaction by remember(item.id) { mutableStateOf(loadVideoReaction(context, item.id)) }
    var status by remember(item.id) { mutableStateOf("Lascia feedback: Hermes lo usera' come memoria editoriale per i prossimi video.") }
    var fullScreen by remember(item.id) { mutableStateOf(false) }
    var fullScreenControlsVisible by remember(item.id) { mutableStateOf(true) }
    val activity = remember(context) { context.findActivity() }
    FullscreenVideoOrientationEffect(enabled = fullScreen, activity = activity)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!useCompatPlayback && compatVideoUrl.isNotBlank() && compatVideoUrl != primaryVideoUrl) {
                    useCompatPlayback = true
                    status = "Player video: ${error.errorCodeName}. Passo al proxy MP4 compatibile Hermes."
                } else {
                    status = "Player video: ${error.errorCodeName}. Nessun fallback compatibile disponibile."
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro", tint = AppColors.Accent)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (!fullScreen) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { viewContext -> createVideoPlayerView(viewContext, player) },
                            update = { view ->
                                view.player = player
                            }
                        )
                    }
                    IconButton(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.White.copy(alpha = 0.14f), CircleShape)
                            .border(0.5.dp, Color.White.copy(alpha = 0.14f), CircleShape),
                        onClick = {
                            fullScreenControlsVisible = true
                            fullScreen = true
                        }
                    ) {
                        Icon(Icons.Rounded.CropFree, contentDescription = "Schermo intero", tint = Color.White)
                    }
                }
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            Text(item.title, color = Color.White, fontSize = AppleType.RowTitle, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Hermes Hub - ${item.filename} - ${item.sizeBytes.toReadableFileSize()} - ${formatVideoTimestamp(item.modifiedAt)}",
                                color = AppColors.Faint,
                                fontSize = AppleType.Footnote,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Text(status, color = AppColors.Faint, fontSize = 12.sp)
                    Text("FEEDBACK", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        VideoReactionButton(
                            label = "Mi piace",
                            icon = Icons.Rounded.ThumbUp,
                            selected = reaction == "like"
                        ) {
                            reaction = "like"
                            status = "Invio like a Hermes..."
                            scope.launch {
                                val result = sendVideoLibraryFeedback(settings, item, feedback, reaction, loadGatewaySecret(context))
                                saveVideoFeedback(context, item.id, feedback, reaction, result)
                                status = result
                            }
                        }
                        VideoReactionButton(
                            label = "Non mi piace",
                            icon = Icons.Rounded.ThumbDown,
                            selected = reaction == "dislike"
                        ) {
                            reaction = "dislike"
                            status = "Invio dislike a Hermes..."
                            scope.launch {
                                val result = sendVideoLibraryFeedback(settings, item, feedback, reaction, loadGatewaySecret(context))
                                saveVideoFeedback(context, item.id, feedback, reaction, result)
                                status = result
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoFeedChip("Hook") { feedback = appendFeedbackSnippet(feedback, "Hook piu' forte nei primi 5 secondi") }
                        VideoFeedChip("Ritmo") { feedback = appendFeedbackSnippet(feedback, "Ritmo piu' veloce e meno pause") }
                        VideoFeedChip("Chiarezza") { feedback = appendFeedbackSnippet(feedback, "Piu' chiarezza didattica e step concreti") }
                        VideoFeedChip("Montaggio") { feedback = appendFeedbackSnippet(feedback, "Montaggio piu' pulito e meno ridondanza") }
                    }
                    Text("FEEDBACK PER HERMES", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    SettingsField("Feedback per Hermes", feedback, { feedback = it })
                    Button(
                        onClick = {
                            if (feedback.isBlank()) {
                                status = "Scrivi feedback prima di inviare."
                                return@Button
                            }
                            status = "Invio feedback a Hermes..."
                            scope.launch {
                                val result = sendVideoLibraryFeedback(settings, item, feedback, reaction, loadGatewaySecret(context))
                                saveVideoFeedback(context, item.id, feedback, reaction, result)
                                status = result
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Invia feedback", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (fullScreen) {
            Dialog(
                onDismissRequest = { fullScreen = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false
                )
            ) {
                FullscreenVideoSystemUi()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            createVideoPlayerView(viewContext, player) { visible ->
                                fullScreenControlsVisible = visible
                            }
                        },
                        update = { view ->
                            view.player = player
                        }
                    )
                    AnimatedVisibility(
                        visible = fullScreenControlsVisible,
                        enter = AppleMotion.enter(),
                        exit = AppleMotion.exit(),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(92.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent)
                                    )
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                IconButton(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.White.copy(alpha = 0.14f), CircleShape)
                                        .border(0.5.dp, Color.White.copy(alpha = 0.14f), CircleShape),
                                    onClick = { fullScreen = false }
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Chiudi schermo intero", tint = Color.White)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        color = Color.White,
                                        fontSize = AppleType.RowTitle,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "Schermo intero",
                                        color = Color.White.copy(alpha = 0.72f),
                                        fontSize = AppleType.Footnote
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoReactionButton(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) AppColors.Accent else AppColors.Panel,
            contentColor = if (selected) Color(0xFF171009) else Color.White
        )
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

