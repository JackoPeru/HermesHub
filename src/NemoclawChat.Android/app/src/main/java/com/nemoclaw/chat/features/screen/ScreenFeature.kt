package com.nemoclaw.chat.features.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nemoclaw.chat.AppColors
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.BitmapImageLoader
import com.nemoclaw.chat.ScreenStatusInfo
import com.nemoclaw.chat.buildScreenViewerUrl
import com.nemoclaw.chat.buildScreenWsUrl
import com.nemoclaw.chat.decodeScreenFrame
import com.nemoclaw.chat.fetchScreenFrameBytes
import com.nemoclaw.chat.getScreenStatus
import com.nemoclaw.chat.gpuManagerBase
import com.nemoclaw.chat.loadGatewaySecret
import com.nemoclaw.chat.postScreenRelease
import com.nemoclaw.chat.postScreenTakeover
import com.nemoclaw.chat.postScreenTicket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private class ScreenJsBridge(val onState: (String) -> Unit) {
    @JavascriptInterface
    fun onState(payload: String) {
        onState(payload)
    }
}

/**
 * Viewer live del desktop del bot (noVNC in WebView).
 * Il ticket e' single-use da 30 s: mint e load avvengono in sequenza stretta.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ScreenViewer(
    settings: AppSettings,
    viewOnly: Boolean,
    onViewerState: (String) -> Unit = {},
    onTicket: (ticketViewerId: String) -> Unit = {}
) {
    val appContext = LocalContext.current.applicationContext
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    var loadNonce by remember { mutableStateOf(0) }
    val webViewRef = remember { arrayOfNulls<android.webkit.WebView>(1) }
    LaunchedEffect(loadNonce) {
        if (loadNonce == 0) return@LaunchedEffect
        val key = withContext(Dispatchers.IO) { loadGatewaySecret(appContext) }
        val base = gpuManagerBase(settings.gatewayUrl)
        val minted = withContext(Dispatchers.IO) { postScreenTicket(settings, key) }
        if (minted == null) {
            onViewerState("ticket_failed")
            return@LaunchedEffect
        }
        onTicket(minted.second)
        val ws = buildScreenWsUrl(base, minted.first)
        viewerUrl = if (ws == null) null else buildScreenViewerUrl(ws, viewOnly)
        if (viewerUrl == null) onViewerState("ticket_failed")
    }
    DisposableEffect(Unit) {
        loadNonce++
        onDispose {
            webViewRef[0]?.let {
                it.stopLoading()
                it.loadUrl("about:blank")
                it.destroy()
            }
            webViewRef[0] = null
        }
    }
    val url = viewerUrl
    if (url == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = AppColors.Accent)
                Text("Connessione allo schermo…", color = AppColors.Muted, fontSize = 13.sp)
            }
        }
        return
    }
    AndroidView(
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                // Stessi-origin senza dipendenze: gli asset noVNC sono serviti
                // dall'intercettore (niente WebViewAssetLoader).
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: android.webkit.WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url ?: return null
                        if (url.scheme != "https" || url.host != "appassets.androidplatform.net") return null
                        var path = url.path?.removePrefix("/assets/") ?: return null
                        if (".." in path || path.isBlank()) return null
                        val mime = when (path.substringAfterLast('.', "").lowercase()) {
                            "html" -> "text/html"
                            "js" -> "text/javascript"
                            "css" -> "text/css"
                            "png" -> "image/png"
                            "svg" -> "image/svg+xml"
                            "json" -> "application/json"
                            "woff2" -> "font/woff2"
                            "woff" -> "font/woff"
                            "ttf" -> "font/ttf"
                            else -> "application/octet-stream"
                        }
                        return try {
                            val stream = ctx.assets.open(path)
                            WebResourceResponse(mime, "utf-8", stream)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                val webSettings = this.settings
                webSettings.javaScriptEnabled = true
                webSettings.domStorageEnabled = true
                webSettings.mediaPlaybackRequiresUserGesture = false
                webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                webSettings.loadWithOverviewMode = true
                webSettings.useWideViewPort = true
                webSettings.builtInZoomControls = true
                webSettings.displayZoomControls = false
                addJavascriptInterface(ScreenJsBridge { payload ->
                    val state = runCatching { JSONObject(payload).optString("state") }.getOrDefault("")
                    onViewerState(state)
                }, "HermesScreen")
                setBackgroundColor(android.graphics.Color.BLACK)
                loadUrl(url)
                webViewRef[0] = this
            }
        },
        update = { view ->
            if (view.url != url) view.loadUrl(url)
            view.evaluateJavascript("window.HermesScreenCtl && HermesScreenCtl.setViewOnly(${if (viewOnly) "true" else "false"})", null)
        },
        modifier = Modifier.fillMaxSize().background(Color.Black)
    )
}

@Composable
internal fun ScreenPreviewImage(
    settings: AppSettings,
    width: Int = 480,
    refreshMs: Long = 5_000L
) {
    // applicationContext: nessun retain dell'Activity nel polling.
    val appContext = LocalContext.current.applicationContext
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lastSignature by remember { mutableStateOf<BitmapImageLoader.FrameSignature?>(null) }
    LaunchedEffect(settings.gatewayUrl, width) {
        lastSignature = null
        while (isActive) {
            val key = withContext(Dispatchers.IO) { loadGatewaySecret(appContext) }
            val bytes = withContext(Dispatchers.IO) { fetchScreenFrameBytes(settings, key, width) }
            // Skip re-decode se i byte sono identici ai precedenti (hash+lunghezza).
            val signature = BitmapImageLoader.frameSignature(bytes)
            if (signature != null && signature == lastSignature) {
                delay(refreshMs)
                continue
            }
            lastSignature = signature
            // reqWidth = larghezza view (loader: sampling + cache).
            val decoded = withContext(Dispatchers.IO) { decodeScreenFrame(bytes, width) }
            bitmap = decoded
            delay(refreshMs)
        }
    }
    val current = bitmap
    if (current != null) {
        androidx.compose.foundation.Image(
            bitmap = current.asImageBitmap(),
            contentDescription = "Anteprima schermo bot",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101418)), contentAlignment = Alignment.Center) {
            Text("Schermo non disponibile", color = AppColors.Muted, fontSize = 12.sp)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ScreenScreen(
    context: Context,
    settings: AppSettings,
    onOpenBot: () -> Unit = {}
) {
    // applicationContext: il polling trattiene solo il contesto app, mai l'Activity.
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ScreenStatusInfo?>(null) }
    var statusError by remember { mutableStateOf("") }
    var holding by remember { mutableStateOf(false) }
    var viewerId by remember { mutableStateOf("") }
    var viewerState by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var reloadNonce by remember { mutableStateOf(0) }

    suspend fun refreshStatus(): ScreenStatusInfo? {
        val key = withContext(Dispatchers.IO) { loadGatewaySecret(appContext) }
        return withContext(Dispatchers.IO) { getScreenStatus(settings, key) }
    }

    LaunchedEffect(settings.gatewayUrl, reloadNonce) {
        while (isActive) {
            val next = runCatching { refreshStatus() }.getOrNull()
            if (next != null) {
                status = next
                statusError = ""
                if (next.holder != "human" && holding) holding = false
            } else if (status == null) {
                statusError = "Stato schermo non leggibile."
            }
            delay(5_000L)
        }
    }

    fun doTakeover() {
        scope.launch {
            busy = true
            try {
                val key = withContext(Dispatchers.IO) { loadGatewaySecret(appContext) }
                val ok = withContext(Dispatchers.IO) { postScreenTakeover(settings, key, viewerId.ifBlank { null }) }
                if (ok) {
                    holding = true
                    reloadNonce++
                } else {
                    statusError = "Take over rifiutato."
                }
            } finally {
                busy = false
            }
        }
    }

    fun doRelease(force: Boolean = false) {
        scope.launch {
            busy = true
            try {
                val key = withContext(Dispatchers.IO) { loadGatewaySecret(appContext) }
                withContext(Dispatchers.IO) { postScreenRelease(settings, key, viewerId.ifBlank { null }) }
                holding = false
                reloadNonce++
            } finally {
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Rounded.Computer, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(26.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Schermo bot", color = Color.White, fontSize = 20.sp)
                val st = status
                Text(
                    when {
                        st == null && statusError.isNotBlank() -> statusError
                        st == null -> "Connessione…"
                        !st.running -> "Schermo spento"
                        st.humanInControl -> if (holding) "Tu hai il controllo" else "Umano al controllo"
                        else -> "Bot is in control"
                    },
                    color = if (st?.humanInControl == true) Color(0xFFFF7B8E) else AppColors.Muted,
                    fontSize = 12.sp
                )
            }
            if (holding) {
                Button(
                    onClick = { doRelease() },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
                ) { Text("Hand back") }
            } else {
                OutlinedButton(onClick = { doTakeover() }, enabled = !busy && status?.running == true) {
                    Text("Take over")
                }
            }
            IconButton(onClick = { reloadNonce++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Ricarica", tint = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            val st = status
            if (st?.running == true) {
                androidx.compose.runtime.key(reloadNonce, holding) {
                    ScreenViewer(
                        settings = settings,
                        viewOnly = !holding,
                        onViewerState = { viewerState = it },
                        onTicket = { viewerId = it }
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (status == null) "Connessione al gateway…" else "Schermo spento: avvialo dal bot o via CLI.",
                            color = AppColors.Muted, fontSize = 13.sp
                        )
                        if (viewerState.isNotBlank()) Text(viewerState, color = AppColors.Faint, fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Guardando non disturbi il bot. Take over mette in pausa i suoi tool schermo; Hand back (o chiudere) restituisce il controllo.",
            color = AppColors.Faint, fontSize = 11.sp
        )
    }
}
