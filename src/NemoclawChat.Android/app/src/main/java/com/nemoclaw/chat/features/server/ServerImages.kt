package com.nemoclaw.chat

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.nemoclaw.chat.ui.apple.AppleGlass
import com.nemoclaw.chat.ui.apple.AppleType
import kotlinx.coroutines.launch

@Composable
internal fun ImageScreen(context: Context, settings: AppSettings, onOpenChatPrompt: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    // Radice iniziale: impostazione se presente, senno denudate (mai vuota).
    val startPath = remember(settings.imageLibraryPath, settings.extraImageLibraryPaths) {
        defaultImageStartPath(settings.imageLibraryPath, settings.extraImageLibraryPaths)
    }
    var currentPath by rememberSaveable(settings.gatewayUrl, startPath) {
        mutableStateOf(startPath.ifBlank { null })
    }
    var parentPath by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<ImageBrowseFolder>>(emptyList()) }
    var items by remember { mutableStateOf<List<ImageLibraryItem>>(emptyList()) }
    var status by remember { mutableStateOf("Sfoglio cartella immagini...") }
    // Foto aperta (id): la lista resta composta sotto il dialogo (niente
    // early-return), cosi scroll e miniature non si perdono. Al cambio
    // cartella il viewer si chiude (l'id non apparterra piu alla lista).
    var viewerId by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    val apiKey = remember { loadGatewaySecret(context) }
    // Browse manager (:8643/browse, ruolo app dal lockdown): mai la chiave gateway.
    val managerKey = remember { loadGpuAppSecret(context) }
    // Scroll issato: sopravvive ad apertura/chiusura viewer.
    val listState = rememberLazyListState()

    LaunchedEffect(settings.gatewayUrl, currentPath, refreshKey) {
        status = "Sfoglio cartella immagini..."
        val result = loadImageBrowse(settings, managerKey, currentPath)
        // Fail-soft: in errore tieni posizione buona (liste + parent), cosi
        // Su/indietro/breadcrumb restano vivi e non ci si incastra mai.
        if (result.ok) {
            parentPath = result.parent
            folders = result.folders
            items = result.items
            // Prima apertura senza radice: adotta il default del server (una sola volta).
            if (currentPath == null && result.path.isNotBlank()) {
                currentPath = result.path
            }
        }
        status = result.status
    }
    // Cambio cartella: chiudi eventuale viewer (foto di un'altra lista).
    LaunchedEffect(currentPath) {
        viewerId = null
    }

    val viewingIndex = viewerId?.let { id -> items.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
    if (viewingIndex != null) {
        BackHandler { viewerId = null }
        ChatImageGalleryDialog(
            items = items,
            initialIndex = viewingIndex,
            settings = settings,
            apiKey = apiKey,
            isDownloading = isDownloading,
            onClose = { viewerId = null },
            onDownload = { item ->
                scope.launch {
                    isDownloading = true
                    try {
                        openHermesMediaExternally(
                            context, settings,
                            resolveWorkspaceUrl(settings, item.mediaUrl),
                            item.filename.ifBlank { "hermes-immagine" },
                            item.mimeType.ifBlank { "image/*" }, apiKey
                        )
                    } finally {
                        isDownloading = false
                    }
                }
            }
        )
    }
    // Back fisico: risale di un livello (mai fuori dalla sezione, mai sopra il viewer).
    val parent = parentPath
    BackHandler(enabled = parent != null && viewingIndex == null) {
        currentPath = parent
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
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
                        .background(AppColors.Accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Image, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Hermes Immagini", color = Color.White, fontSize = AppleType.ScreenTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status, color = AppColors.Muted, fontSize = AppleType.HeroSubtitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onOpenChatPrompt(
                            "Modalita Immagini Hermes Hub. Usa la cartella immagini monitorata del PC Hermes: ${settings.imageLibraryPath.ifBlank { "quella del server" }}. " +
                                "Crea o modifica un'immagine e salva sempre il file finale in quella cartella, cosi appare automaticamente nella sezione Immagini. Richiesta: "
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Nuova immagine", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                TextButton(onClick = {
                    status = "Aggiorno cartella..."
                    refreshKey++
                }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Aggiorna", color = AppColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("PERCORSO", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))
            // Navigazione: Home (radice) + Su (parent server) + segmenti.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { currentPath = startPath.ifBlank { null } }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Home", color = AppColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                if (parent != null) {
                    Text("·", color = AppColors.Faint, fontSize = 14.sp)
                    TextButton(onClick = { currentPath = parent }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("↑ Su", color = AppColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                }
                val shown = currentPath
                if (!shown.isNullOrBlank()) {
                    browseSegments(shown).takeLast(3).forEach { (label, segmentPath) ->
                        Text("·", color = AppColors.Faint, fontSize = 14.sp)
                        TextButton(onClick = { currentPath = segmentPath }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) {
                            Text(label, color = if (segmentPath == shown) Color.White else AppColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (folders.isNotEmpty()) {
            item {
                Text("CARTELLE", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            items(folders, key = { "folder|" + it.path }) { folder ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { currentPath = folder.path }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppColors.NavIndicator),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(22.dp))
                        }
                        Text(
                            folder.name,
                            color = Color.White,
                            fontSize = AppleType.RowTitle,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Apri cartella", tint = AppColors.Muted)
                    }
                    HorizontalDivider(color = AppColors.Border.copy(alpha = 0.6f), thickness = 0.5.dp)
                }
            }
        }
        if (items.isEmpty() && folders.isEmpty()) {
            item {
                AppleGlass(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Image, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                        Text("Niente qui", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Text(
                            text = "Entra in una cartella o genera immagini con Hermes.",
                            color = AppColors.Muted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        // Orientamento: dove sei, cosi puoi sempre tornare indietro.
                        val where = currentPath
                        if (!where.isNullOrBlank()) {
                            Text(
                                text = where,
                                color = AppColors.Faint,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                onOpenChatPrompt(
                                    "Modalita Immagini Hermes Hub. Usa la cartella immagini monitorata del PC Hermes: ${settings.imageLibraryPath.ifBlank { "quella del server" }}. " +
                                        "Crea o modifica un'immagine e salva sempre il file finale in quella cartella, cosi appare automaticamente nella sezione Immagini. Richiesta: "
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009))
                        ) {
                            Text("Genera con Hermes", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else if (items.isNotEmpty()) {
            item {
                Text("FOTO", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        items(items.chunked(2), key = { row -> row.joinToString("|") { it.folder + "#" + it.id } }) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { item ->
                    ImageFeedCard(
                        settings = settings,
                        item = item,
                        apiKey = apiKey,
                        onClick = { viewerId = item.id },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

internal fun folderLabel(folder: String): String =
    folder.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() } ?: "Principale"

