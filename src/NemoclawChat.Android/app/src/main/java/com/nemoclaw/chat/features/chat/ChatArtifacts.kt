package com.nemoclaw.chat

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.nemoclaw.chat.resolveHermesUrl
import java.io.File
import java.net.URL

@Composable
internal fun ArtifactLibraryScreen(
    context: Context,
    settings: AppSettings,
    onOpenConversation: (String) -> Unit,
    onRegenerate: (String) -> Unit
) {
    var refresh by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var projectFilter by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var rename by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("Pronto.") }
    val artifacts = remember(refresh, query, projectFilter) {
        loadConversations(context).filter { item ->
            item.kind == "Artifact" &&
                (projectFilter.isBlank() || item.projectId.contains(projectFilter, true)) &&
                (query.isBlank() || item.title.contains(query, true) || item.artifactFileName.contains(query, true) || item.tags.any { it.contains(query, true) })
        }.sortedByDescending { it.updatedAt }
    }
    val selected = artifacts.firstOrNull { it.id == selectedId }

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
                        Icon(Icons.Rounded.Description, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Artifact", color = Color.White, fontSize = AppleType.ScreenTitle, fontWeight = FontWeight.SemiBold)
                    Text("Output persistenti prodotti da chat, run e automazioni.", color = AppColors.Muted, fontSize = AppleType.HeroSubtitle)
                }
            }
        }
        item {
            AppleGlass(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("FILTRI", color = AppColors.Muted, fontSize = AppleType.RowTitleSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                SettingsField("Cerca nome, file o tag", query, { query = it })
                SettingsField("Filtra progetto", projectFilter, { projectFilter = it })
                Text("${artifacts.size} artifact", color = AppColors.Muted, fontSize = AppleType.Footnote)
            }
            }
        }
        item { HorizontalDivider(color = AppColors.Border.copy(alpha = 0.6f), thickness = 0.5.dp) }
        if (artifacts.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.Description, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(32.dp))
                    Text("Nessun artifact", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Prova a modificare ricerca o filtro progetto.", color = AppColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                    TextButton(
                        onClick = { query = ""; projectFilter = "" },
                        colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                    ) { Text("Mostra tutto") }
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ARTIFACT", color = AppColors.Muted, fontSize = AppleType.RowTitleSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    artifacts.forEachIndexed { index, artifact ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedId = artifact.id; rename = artifact.title; tags = artifact.tags.joinToString(", ")
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = AppColors.NavIndicator
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Description, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(22.dp))
                                }
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(artifact.title, color = Color.White, fontSize = AppleType.RowTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${artifact.artifactType} · v${artifact.version} · ${artifact.projectId}", color = AppColors.Faint, fontSize = AppleType.Footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(artifact.description, color = AppColors.Muted, fontSize = AppleType.RowTitleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            if (artifact.id == selectedId) {
                                Text("SELEZIONATO", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(20.dp))
                            }
                        }
                        if (index < artifacts.lastIndex) HorizontalDivider(color = AppColors.Border.copy(alpha = 0.6f), thickness = 0.5.dp)
                    }
                }
            }
        }
        if (selected != null) {
            item { HorizontalDivider(color = AppColors.Border.copy(alpha = 0.6f), thickness = 0.5.dp) }
            item {
                AppleGlass(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("DETTAGLIO · V${selected.version}", color = AppColors.Muted, fontSize = AppleType.RowTitleSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("File: ${selected.artifactFileName}\nMIME: ${selected.artifactMimeType}\nChat: ${selected.sourceConversationId}\nRun: ${selected.sourceRunId}", color = AppColors.Muted, fontSize = AppleType.Footnote)
                    SettingsField("Nome", rename, { rename = it })
                    SettingsField("Tag", tags, { tags = it })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                saveArtifactMetadata(context, selected.id, rename, tags.split(',', ';').map { it.trim() }.filter { it.isNotBlank() })
                                refresh++; status = "Metadata salvati; sync gateway in coda."
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009))
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Salva metadata")
                        }
                        TextButton(
                            onClick = { if (selected.sourceConversationId.isNotBlank()) onOpenConversation(selected.sourceConversationId) },
                            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                        ) {
                            Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Anteprima")
                        }
                        TextButton(
                            onClick = {
                                if (selected.artifactUrl.isBlank()) status = "Artifact senza URL apribile." else runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, resolveHermesUrl(settings, selected.artifactUrl).toUri()))
                                }.onFailure { status = "Apertura fallita: ${it.message}" }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Apri")
                        }
                        TextButton(
                            onClick = { onRegenerate("Rigenera artifact '${selected.title}' versione ${selected.version}, progetto ${selected.projectId}, sorgente chat ${selected.sourceConversationId}.") },
                            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Rigenera")
                        }
                    }
                    Text("VERSIONI", color = AppColors.Muted, fontSize = AppleType.RowTitleSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    val key = selected.artifactFileName.ifBlank { selected.title }
                    loadConversations(context).filter { it.kind == "Artifact" && it.artifactFileName.ifBlank { it.title }.equals(key, true) }
                        .sortedByDescending { it.version }.forEach { version ->
                            Text("v${version.version} · ${formatDateTime(version.updatedAt)} · ${version.description}", color = AppColors.Muted, fontSize = AppleType.Footnote)
                        }
                    Text(status, color = AppColors.Muted, fontSize = AppleType.Footnote)
                }
                }
            }
        }
    }
    }
}

