package com.nemoclaw.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.core.net.toUri
import com.nemoclaw.chat.ui.apple.AppleGlass
import com.nemoclaw.chat.ui.apple.AppleType
import java.net.URL

@Composable
internal fun NewsScreen(context: Context, settings: AppSettings, onOpenChatPrompt: (String) -> Unit) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Articoli HTML creati da Hermes.") }
    var selectedHtmlId by rememberSaveable { mutableStateOf<String?>(null) }
    var htmlItems by remember { mutableStateOf<List<NewsHtmlItem>>(emptyList()) }
    val selectedHtml = remember(htmlItems, selectedHtmlId) { htmlItems.firstOrNull { it.id == selectedHtmlId } }

    LaunchedEffect(settings.gatewayUrl, refreshKey) {
        val result = loadNewsLibrary(settings, loadGatewaySecret(context))
        htmlItems = result.first
        status = result.second
    }

    if (selectedHtml != null) {
        BackHandler { selectedHtmlId = null }
        NewsHtmlScreen(
            context = context,
            settings = settings,
            item = selectedHtml,
            onBack = { selectedHtmlId = null }
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = AppColors.Accent.copy(alpha = 0.18f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Newspaper, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Hermes News", color = Color.White, fontSize = AppleType.ScreenTitle, fontWeight = FontWeight.SemiBold)
                    Text(status, color = AppColors.Muted, fontSize = AppleType.HeroSubtitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onOpenChatPrompt("Crea un articolo per la sezione News di Hermes Hub: ") }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Nuovo articolo", tint = AppColors.Accent)
                }
                // Un solo GET per refresh: basta il bump di refreshKey, il
                // LaunchedEffect sopra ricarica (niente doppio load diretto).
                IconButton(onClick = {
                    status = "Sincronizzo articoli Hermes..."
                    refreshKey++
                }) { Icon(Icons.Rounded.Refresh, contentDescription = "Aggiorna news", tint = AppColors.Muted) }
            }
        }
        item {
            Text("ARTICOLI", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        if (htmlItems.isEmpty()) {
            item {
                AppleGlass(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Newspaper, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                        Text("Nessun articolo HTML trovato.", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Text(
                            text = "Chiedi a Hermes di creare un giornale HTML e salvarlo in ${settings.newsLibraryPath}.",
                            color = AppColors.Muted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { onOpenChatPrompt("Crea un articolo per la sezione News di Hermes Hub: ") },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Nuovo articolo", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        items(htmlItems, key = { "html:${it.id}" }) { page ->
            NewsHtmlCard(item = page, onClick = { selectedHtmlId = page.id })
        }
    }
}

@Composable
internal fun NewsHtmlCard(item: NewsHtmlItem, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(AppColors.NavIndicator),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Language, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = AppleType.RowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.filename} - ${item.sizeBytes.toReadableFileSize()} - ${formatVideoTimestamp(item.modifiedAt)}",
                    color = AppColors.Faint,
                    fontSize = AppleType.Footnote,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(20.dp))
        }
        HorizontalDivider(color = AppColors.Border.copy(alpha = 0.6f), thickness = 0.5.dp)
    }
}

// Articoli statici: niente JS, niente bridge, fail-closed sui link.
internal fun createNewsWebView(context: Context): WebView = WebView(context).apply {
    settings.javaScriptEnabled = false
    settings.domStorageEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Fail-closed: URL nullo o schema non http(s) mai caricati in WebView.
            val target = request.url ?: return true
            if (!target.scheme.equals("http", ignoreCase = true) &&
                !target.scheme.equals("https", ignoreCase = true)
            ) {
                return true
            }
            // Resta in WebView solo per stesso host della pagina caricata.
            val currentHost = runCatching { (view.url ?: "").toUri().host }.getOrNull()
            if (currentHost != null && target.host.equals(currentHost, ignoreCase = true)) return false
            // Link esterni nel browser di sistema.
            runCatching {
                view.context.startActivity(
                    Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return true
        }
    }
}

internal fun updateNewsWebView(webView: WebView, pageUrl: String, html: String) {
    val content = if (html.isBlank()) {
        "<html><body style=\"font-family:sans-serif;background:#111827;color:#fff\"><p>Caricamento...</p></body></html>"
    } else {
        injectHtmlBase(html, pageUrl)
    }
    val contentKey = "$pageUrl:${content.hashCode()}"
    if (webView.tag == contentKey) return
    webView.tag = contentKey
    webView.loadDataWithBaseURL(pageUrl, content, "text/html", "utf-8", null)
}

internal fun releaseNewsWebView(webView: WebView) {
    webView.stopLoading()
    webView.webViewClient = WebViewClient()
    webView.loadUrl("about:blank")
    webView.clearHistory()
    webView.removeAllViews()
    webView.destroy()
}

