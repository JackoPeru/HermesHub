package com.nemoclaw.chat.features.screen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nemoclaw.chat.AppColors
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.BitmapImageLoader
import com.nemoclaw.chat.PollWhileStarted
import com.nemoclaw.chat.ScreenStatusInfo
import com.nemoclaw.chat.buildScreenViewerUrl
import com.nemoclaw.chat.buildScreenWsUrl
import com.nemoclaw.chat.decodeScreenFrame
import com.nemoclaw.chat.fetchScreenFrameBytes
import com.nemoclaw.chat.getScreenStatus
import com.nemoclaw.chat.gpuManagerBase
import com.nemoclaw.chat.loadGpuAppSecret
import com.nemoclaw.chat.postScreenRelease
import com.nemoclaw.chat.postScreenTakeover
import com.nemoclaw.chat.postScreenTicket
import com.nemoclaw.chat.ui.apple.AppleGlass
import com.nemoclaw.chat.ui.apple.AppleMotion
import com.nemoclaw.chat.ui.apple.AppleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLDecoder

private class ScreenJsBridge(val onState: (String) -> Unit) {
    @JavascriptInterface
    fun onState(payload: String) {
        onState(payload)
    }
}

// Controllo puro testabile: true = path asset da bloccare (fail-closed).
// Normalizza con URL-decode ricorsivo (max 2x) cosi %2e, %5c e doppie
// codifiche (%252e) non aggirano il controllo; poi cerca ".." come
// segmento, backslash e path assoluti.
internal fun isBlockedAssetPath(rawPath: String): Boolean {
    if (rawPath.isBlank()) return true
    var path = rawPath
    repeat(2) {
        val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrNull() ?: return@repeat
        if (decoded == path) return@repeat
        path = decoded
    }
    if ('\\' in path) return true
    if (path.startsWith("/")) return true
    if (path.split('/').any { it == ".." }) return true
    return false
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
        val key = withContext(Dispatchers.IO) { loadGpuAppSecret(appContext) }
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
                Text("Connessione allo schermo…", color = AppColors.Muted, fontSize = AppleType.RowTitleSmall)
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
                        val path = url.path?.removePrefix("/assets/") ?: return null
                        // Fail-closed: traversal anche codificato (%2e, backslash, assoluti).
                        if (isBlockedAssetPath(path)) return null
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
                // Bridge JS mantenuto (serve al viewer), ma superficie ridotta e fail-closed.
                webSettings.allowFileAccess = false
                webSettings.allowUniversalAccessFromFileURLs = false
                webSettings.mediaPlaybackRequiresUserGesture = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ScreenScreen(
    context: Context,
    settings: AppSettings,
    onOpenBot: () -> Unit = {},
    // Se fornito (uso dentro la sezione Bot), mostra freccia indietro.
    onBack: (() -> Unit)? = null
) {
    // applicationContext: il polling trattiene solo il contesto app, mai l'Activity.
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ScreenStatusInfo?>(null) }
    var statusError by remember { mutableStateOf("") }
    var holding by rememberSaveable { mutableStateOf(false) }
    var viewerId by rememberSaveable { mutableStateOf("") }
    var viewerState by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var reloadNonce by remember { mutableStateOf(0) }

    suspend fun refreshStatus(): ScreenStatusInfo? {
        val key = withContext(Dispatchers.IO) { loadGpuAppSecret(appContext) }
        return withContext(Dispatchers.IO) { getScreenStatus(settings, key) }
    }

    val screenApiKeyHash = remember(settings.gatewayUrl) { (loadGpuAppSecret(appContext) ?: "").hashCode() }
    PollWhileStarted(settings.gatewayUrl, reloadNonce, screenApiKeyHash, baseIntervalMs = 5_000L) {
        val next = runCatching { refreshStatus() }.getOrNull()
        if (next != null) {
            status = next
            statusError = ""
            if (next.holder != "human" && holding) holding = false
        } else if (status == null) {
            statusError = "Stato schermo non leggibile."
        }
        next != null
    }

    fun doTakeover() {
        scope.launch {
            busy = true
            try {
                val key = withContext(Dispatchers.IO) { loadGpuAppSecret(appContext) }
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
                val key = withContext(Dispatchers.IO) { loadGpuAppSecret(appContext) }
                withContext(Dispatchers.IO) { postScreenRelease(settings, key, viewerId.ifBlank { null }) }
                holding = false
                reloadNonce++
            } finally {
                busy = false
            }
        }
    }

    // Uscita dalla vista in takeover (toggle sezione, cambio tab): il
    // controllo va restituito, altrimenti resta appeso. Best-effort: scope
    // dedicato (quello della composition muore nel dispose), niente UI.
    // Rotazione (isChangingConfigurations): niente release, la vista si
    // ricrea subito e il controllo resta a noi.
    DisposableEffect(Unit) {
        onDispose {
            val changing = (context as? Activity)?.isChangingConfigurations == true
            if (holding && !changing) {
                runCatching {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val key = loadGpuAppSecret(appContext)
                        postScreenRelease(settings, key, viewerId.ifBlank { null })
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        AppleGlass(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Torna ai bot", tint = Color.White)
                }
            }
            Box(
                modifier = Modifier.size(40.dp).background(Color(0xFF302517), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Computer, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Schermo bot", color = Color.White, fontSize = AppleType.ScreenTitle, fontWeight = FontWeight.SemiBold)
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
                    fontSize = AppleType.HeroSubtitle
                )
            }
            IconButton(onClick = { reloadNonce++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Ricarica", tint = Color.White)
            }
            }
        }
        AppleGlass(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp)
        ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (holding) {
                TextButton(
                    onClick = { doRelease() },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = AppColors.Accent,
                        disabledContentColor = AppColors.Faint
                    )
                ) { Text("Hand back") }
            } else {
                Button(
                    onClick = { doTakeover() },
                    enabled = !busy && status?.running == true,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Accent,
                        contentColor = Color(0xFF171009),
                        disabledContainerColor = AppColors.Border,
                        disabledContentColor = AppColors.Faint
                    )
                ) { Text("Take over") }
            }
        }
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Viewer".uppercase(java.util.Locale.ROOT),
                color = AppColors.Accent,
                fontSize = AppleType.Eyebrow,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                shape = RoundedCornerShape(20.dp),
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
                                color = AppColors.Muted, fontSize = AppleType.RowTitleSmall
                            )
                            AnimatedVisibility(
                                visible = viewerState.isNotBlank(),
                                enter = AppleMotion.enter(),
                                exit = AppleMotion.exit()
                            ) {
                                if (viewerState.isNotBlank()) Text(viewerState, color = AppColors.Faint, fontSize = AppleType.Caption)
                            }
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Note".uppercase(java.util.Locale.ROOT),
                color = AppColors.Accent,
                fontSize = AppleType.Eyebrow,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                "Guardando non disturbi il bot. Take over mette in pausa i suoi tool schermo; Hand back (o chiudere) restituisce il controllo.",
                color = AppColors.Faint, fontSize = AppleType.Caption
            )
        }
    }
}
