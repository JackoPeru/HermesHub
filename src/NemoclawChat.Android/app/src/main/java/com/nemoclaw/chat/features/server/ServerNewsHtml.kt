package com.nemoclaw.chat

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
internal fun NewsHtmlScreen(context: Context, settings: AppSettings, item: NewsHtmlItem, onBack: () -> Unit) {
    val apiKey = remember { loadGatewaySecret(context) }
    val pageUrl = remember(settings.gatewayUrl, item.url) { resolveWorkspaceUrl(settings, item.url) }
    var status by remember(item.id) { mutableStateOf("Carico pagina HTML...") }
    var html by remember(item.id) { mutableStateOf("") }
    var fullScreen by rememberSaveable(item.id) { mutableStateOf(false) }

    LaunchedEffect(pageUrl, apiKey) {
        val loaded = loadNewsHtml(settings, item, apiKey)
        html = loaded.first
        status = loaded.second
    }

    DisposableEffect(fullScreen) {
        if (!fullScreen) {
            onDispose { }
        } else {
            val activity = context as? Activity
            val window = activity?.window
            val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }
            insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                insetsController?.show(WindowInsetsCompat.Type.systemBars())
                if (window != null) {
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(AppColors.Background)) {
        if (!fullScreen) {
            Column(modifier = Modifier.fillMaxSize().background(AppColors.Background)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro", tint = AppColors.Accent)
                    }
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(13.dp),
                        color = AppColors.Accent.copy(alpha = 0.18f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Language, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(status, color = AppColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { fullScreen = true }) {
                        Icon(Icons.Rounded.CropFree, contentDescription = "Apri a schermo intero", tint = AppColors.Accent)
                    }
                }
                HorizontalDivider(color = AppColors.Border)
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = ::createNewsWebView,
                    update = { updateNewsWebView(it, pageUrl, html) },
                    onRelease = ::releaseNewsWebView
                )
            }
        }

        if (fullScreen) {
            BackHandler { fullScreen = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = ::createNewsWebView,
                    update = { updateNewsWebView(it, pageUrl, html) },
                    onRelease = ::releaseNewsWebView
                )
                TextButton(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    onClick = { fullScreen = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                ) {
                    Text("Chiudi", color = AppColors.Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

