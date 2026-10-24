package com.nemoclaw.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import com.nemoclaw.chat.ui.apple.AppleType
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URL

@Composable
internal fun ImageFeedCard(
    settings: AppSettings,
    item: ImageLibraryItem,
    apiKey: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imageUrl = remember(settings.gatewayUrl, item.mediaUrl) { resolveWorkspaceUrl(settings, item.mediaUrl) }
    val bitmap by produceState<Bitmap?>(initialValue = null, imageUrl, apiKey) {
        // Card piccola: decodifica campionata a 256px (il fullscreen usa il full).
        value = withContext(Dispatchers.IO) { loadRemoteBitmap(settings, imageUrl, apiKey, FEED_THUMBNAIL_MAX_DIM) }
    }
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.Panel),
            contentAlignment = Alignment.Center
        ) {
            val loaded = bitmap
            if (loaded != null) {
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = item.title.ifBlank { item.filename },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Rounded.Image, contentDescription = null, tint = AppColors.Muted, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            item.title.ifBlank { item.filename },
            color = Color.White,
            fontSize = AppleType.RowTitleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${item.sizeBytes.toReadableFileSize()} - ${folderLabel(item.folder)}",
            color = AppColors.Faint,
            fontSize = AppleType.Footnote,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun createManualVideoItem(rawUrl: String): VideoLibraryItem? {    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: "link esterno"
    return VideoLibraryItem(
        id = "manual:${trimmed.hashCode()}",
        title = "URL video manuale",
        filename = host,
        mediaUrl = trimmed,
        thumbnailUrl = "",
        path = trimmed,
        mimeType = "video/url",
        sizeBytes = 0L,
        durationMs = 0L,
        modifiedAt = System.currentTimeMillis()
    )
}

internal fun resolveVideoPlaybackUrl(settings: AppSettings, item: VideoLibraryItem): String {
    if (item.compatUrl.isBlank() &&
        item.playbackUrl.contains("format=mp4", ignoreCase = true) &&
        item.playbackUrl.contains("/v1/media/", ignoreCase = true) &&
        item.mediaUrl.isNotBlank()
    ) {
        return resolveWorkspaceUrl(settings, item.mediaUrl)
    }
    val raw = item.playbackUrl.ifBlank { item.mediaUrl }
    return resolveWorkspaceUrl(settings, raw)
}

internal fun resolveVideoCompatUrl(settings: AppSettings, item: VideoLibraryItem): String {
    val raw = item.compatUrl.ifBlank {
        if (item.playbackUrl.contains("format=mp4", ignoreCase = true) &&
            item.playbackUrl.contains("/v1/media/", ignoreCase = true)
        ) {
            return resolveWorkspaceUrl(settings, item.playbackUrl)
        }
        val base = item.mediaUrl.ifBlank { item.playbackUrl }
        if (base.isBlank()) return ""
        val resolvedBase = resolveWorkspaceUrl(settings, base)
        if (!resolvedBase.contains("/v1/media/", ignoreCase = true) ||
            resolvedBase.contains("format=mp4", ignoreCase = true)
        ) {
            return resolvedBase
        }
        val separator = if (resolvedBase.contains("?")) "&" else "?"
        return "$resolvedBase${separator}format=mp4"
    }
    return resolveWorkspaceUrl(settings, raw)
}

@Composable
internal fun VideoFeedChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = if (selected) AppColors.Accent else AppColors.Panel,
        contentColor = if (selected) Color(0xFF171009) else Color.White,
        shape = RoundedCornerShape(20.dp),
        border = if (selected) null else BorderStroke(0.5.dp, AppColors.Border)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }
}

@Composable
internal fun VideoFeedCard(settings: AppSettings, item: VideoLibraryItem, apiKey: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VideoThumbnail(settings, item, apiKey, modifier = Modifier.width(120.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = AppleType.RowTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Hermes Hub - ${item.sizeBytes.toReadableFileSize()} - ${formatVideoTimestamp(item.modifiedAt)}",
                    color = AppColors.Faint,
                    fontSize = AppleType.Footnote,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(22.dp))
        }
        HorizontalDivider(color = AppColors.Border.copy(alpha = 0.6f), thickness = 0.5.dp)
    }
}

@Composable
internal fun VideoThumbnail(settings: AppSettings, item: VideoLibraryItem, apiKey: String?, modifier: Modifier = Modifier) {
    val videoUrl = remember(settings.gatewayUrl, item.mediaUrl, item.playbackUrl) { resolveVideoPlaybackUrl(settings, item) }
    val thumbUrl = remember(settings.gatewayUrl, item.thumbnailUrl) {
        item.thumbnailUrl.takeIf { it.isNotBlank() }?.let { resolveWorkspaceUrl(settings, it) }
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, videoUrl, thumbUrl, apiKey) {
        value = withContext(Dispatchers.IO) {
            // Card piccola: miniature campionate a 256px (il fullscreen usa il full).
            thumbUrl?.let { loadRemoteBitmap(settings, it, apiKey, FEED_THUMBNAIL_MAX_DIM) } ?: loadVideoThumbnail(settings, videoUrl, apiKey, FEED_THUMBNAIL_MAX_DIM)
        }
    }
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.linearGradient(listOf(AppColors.Panel, Color.Black, AppColors.Accent.copy(alpha = 0.22f))),
                    size = size
                )
            }
            Icon(Icons.Rounded.PlayCircle, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(56.dp))
        }
        val duration = formatVideoDuration(item.durationMs)
        if (duration.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(duration, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

internal fun fullscreenLandscapeOrientation(autoRotateEnabled: Boolean, displayRotation: Int): Int {
    if (autoRotateEnabled) return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    return if (displayRotation == android.view.Surface.ROTATION_270) {
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun isSystemAutoRotateEnabled(context: Context): Boolean = runCatching {
    Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
}.getOrDefault(false)

internal fun Activity.currentDisplayRotation(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.rotation ?: android.view.Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.rotation
    }
}

@Composable
internal fun FullscreenVideoOrientationEffect(enabled: Boolean, activity: Activity?) {
    DisposableEffect(enabled, activity) {
        if (!enabled || activity == null) {
            onDispose { }
        } else {
            val previousOrientation = activity.requestedOrientation
            val targetOrientation = fullscreenLandscapeOrientation(
                autoRotateEnabled = isSystemAutoRotateEnabled(activity),
                displayRotation = activity.currentDisplayRotation()
            )
            val changedOrientation = previousOrientation != targetOrientation
            if (changedOrientation) {
                activity.requestedOrientation = targetOrientation
            }
            onDispose {
                if (
                    changedOrientation &&
                    !activity.isFinishing &&
                    !activity.isDestroyed &&
                    activity.requestedOrientation == targetOrientation
                ) {
                    activity.requestedOrientation = previousOrientation
                }
            }
        }
    }
}

@Composable
internal fun FullscreenVideoSystemUi() {
    val dialogView = LocalView.current
    DisposableEffect(dialogView) {
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        if (dialogWindow == null) {
            onDispose { }
        } else {
            val previousCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialogWindow.attributes.layoutInDisplayCutoutMode
            } else {
                null
            }
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialogWindow.attributes = dialogWindow.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
            val controller = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && previousCutoutMode != null) {
                    dialogWindow.attributes = dialogWindow.attributes.apply {
                        layoutInDisplayCutoutMode = previousCutoutMode
                    }
                }
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun createVideoPlayerView(
    context: Context,
    player: Player,
    onControllerVisibilityChanged: ((Boolean) -> Unit)? = null
): PlayerView {
    return PlayerView(context).apply {
        useController = true
        controllerAutoShow = true
        controllerHideOnTouch = true
        controllerShowTimeoutMs = 3_500
        setKeepContentOnPlayerReset(true)
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        if (onControllerVisibilityChanged != null) {
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    onControllerVisibilityChanged(visibility == android.view.View.VISIBLE)
                }
            )
        }
        this.player = player
    }
}

