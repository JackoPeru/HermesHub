package com.nemoclaw.chat

import android.content.Context
import androidx.core.content.edit
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Save
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.features.git.GitHubPr
import com.nemoclaw.chat.features.git.diffLineKind
import com.nemoclaw.chat.features.git.fetchPrDiff
import com.nemoclaw.chat.features.git.listOpenPrs
import com.nemoclaw.chat.features.git.mergePr
import com.nemoclaw.chat.features.git.parseGitHubRepo
import com.nemoclaw.chat.loadGithubToken
import com.nemoclaw.chat.saveGithubToken
import kotlinx.coroutines.launch

@Composable
internal fun ProjectsScreen(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    onNewChat: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenConversation: (String) -> Unit
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    val projects = remember(refreshKey) {
        loadConversations(context).filter { it.kind == "Progetto" }.sortedByDescending { it.updatedAt }
    }
    var selectedId by rememberSaveable { mutableStateOf(settings.activeProjectId.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id.orEmpty()) }
    val selected = projects.firstOrNull { it.id == selectedId }
    var title by rememberSaveable { mutableStateOf(selected?.title.orEmpty()) }
    var instructions by rememberSaveable { mutableStateOf(selected?.projectInstructions.orEmpty()) }
    var status by remember { mutableStateOf("Pronto.") }
    val prScope = rememberCoroutineScope()
    var ghToken by remember { mutableStateOf(loadGithubToken(context).orEmpty()) }
    var prList by remember { mutableStateOf(emptyList<GitHubPr>()) }
    var prMsg by remember { mutableStateOf("") }
    var prOpen by remember { mutableStateOf<Int?>(null) }
    var prDiff by remember { mutableStateOf("") }
    var prMergeArm by remember { mutableStateOf<Int?>(null) }
    var prBusy by remember { mutableStateOf(false) }
    fun prRepo() = parseGitHubRepo(selected?.repositoryUrl.orEmpty())
    fun loadPrs() {
        val repo = prRepo()
        if (repo == null) {
            prMsg = "Repository non GitHub."
            return
        }
        if (ghToken.isBlank()) {
            prMsg = "Token GitHub mancante qui sotto."
            return
        }
        prBusy = true
        prScope.launch {
            try {
                prList = listOpenPrs(repo.first, repo.second, ghToken)
                prMsg = if (prList.isEmpty()) "Nessuna PR aperta." else "${prList.size} PR aperte."
            } catch (failed: Exception) {
                prMsg = failed.message ?: "Errore rete."
            } finally {
                prBusy = false
            }
        }
    }
    fun openPrDiff(number: Int) {
        val repo = prRepo() ?: return
        prOpen = number
        prDiff = ""
        prMergeArm = null
        prScope.launch {
            try {
                prDiff = fetchPrDiff(repo.first, repo.second, number, ghToken)
                if (prDiff.isBlank()) prMsg = "Diff vuoto o illeggibile."
            } catch (failed: Exception) {
                prMsg = failed.message ?: "Errore rete."
            }
        }
    }
    fun mergePr(number: Int) {
        val repo = prRepo() ?: return
        prScope.launch {
            try {
                val done = mergePr(repo.first, repo.second, number, ghToken)
                prMsg = if (done) "PR #$number unita." else "Merge rifiutato."
                prMergeArm = null
                if (done) loadPrs()
            } catch (failed: Exception) {
                prMsg = failed.message ?: "Errore rete."
            }
        }
    }

    fun edit(project: LocalConversation?) {
        selectedId = project?.id.orEmpty()
        title = project?.title.orEmpty()
        instructions = project?.projectInstructions.orEmpty()
        if (project != null) {
            onSettingsChanged(settings.withActiveProject(project))
            status = "Progetto selezionato. Contesto applicato automaticamente."
        }
    }

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
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Progetti", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text("Scegli un nome e, se serve, un system prompt personalizzato. Il resto e' automatico.", color = AppColors.Muted, fontSize = 14.sp)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("I TUOI PROGETTI", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { edit(null); status = "Inserisci nome e system prompt facoltativo." },
                        colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                    ) { Text("Nuovo") }
                }
                if (projects.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(32.dp))
                        Text("Nessun progetto", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Crea il primo progetto per organizzare chat e contesto.", color = AppColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                        Button(
                            onClick = { edit(null); status = "Inserisci nome e system prompt facoltativo." },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009))
                        ) { Text("Crea progetto") }
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        projects.forEach { project ->
                            VideoFeedChip(project.title, selected = project.id == selectedId) { edit(project) }
                        }
                    }
                }
            }
        }
        item { HorizontalDivider(color = AppColors.Border) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("DETTAGLIO PROGETTO", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(if (selected == null) "Nuovo progetto" else selected.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (selected?.id == settings.activeProjectId) Text("PROGETTO ATTIVO", color = AppColors.Success, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                SettingsField("Nome progetto", title, { title = it })
                SettingsField("System prompt (facoltativo)", instructions, { instructions = it })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            runCatching {
                                saveProjectWorkspace(
                                    context = context,
                                    projectId = selectedId.ifBlank { null },
                                    title = title,
                                    description = selected?.description.orEmpty(),
                                    workspacePath = selected?.workspacePath.orEmpty(),
                                    repositoryUrl = selected?.repositoryUrl.orEmpty(),
                                    instructions = instructions,
                                    memory = selected?.projectMemory.orEmpty(),
                                    authorizedTools = selected?.authorizedTools.orEmpty()
                                )
                            }.onSuccess { saved ->
                                selectedId = saved.id
                                refreshKey++
                                onSettingsChanged(settings.withActiveProject(saved))
                                status = "Progetto salvato e attivato."
                            }.onFailure { status = it.message ?: "Salvataggio progetto fallito." }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009))
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Salva e attiva")
                    }
                    TextButton(
                        enabled = selected != null,
                        onClick = {
                            selected?.let {
                                onSettingsChanged(settings.withActiveProject(it))
                                onNewChat()
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent, disabledContentColor = AppColors.Faint)
                    ) {
                        Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Apri chat")
                    }
                }
                Text(status, color = AppColors.Muted, fontSize = 12.sp)
            }
        }
        item {
            val repo = prRepo()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("PULL REQUEST", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                if (repo == null) {
                    Text("Repository non GitHub: niente PR da qui.", color = AppColors.Muted, fontSize = 12.sp)
                } else {
                    Text("${repo.first}/${repo.second}", color = Color.White, fontSize = 14.sp)
                    SettingsField("Token GitHub", ghToken, {
                        ghToken = it
                        saveGithubToken(context, it)
                    })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { loadPrs() },
                            enabled = !prBusy
                        ) { Text("Leggi PR") }
                    }
                    if (prMsg.isNotBlank()) {
                        Text(prMsg, color = AppColors.Muted, fontSize = 12.sp)
                    }
                    prList.forEach { pr ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openPrDiff(pr.number) }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                "#${pr.number} ${pr.title}",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text(
                                "${pr.state} · ${pr.author} · ${pr.createdAt}",
                                color = AppColors.Muted,
                                fontSize = 11.sp
                            )
                        }
                        if (prOpen == pr.number) {
                            if (prDiff.isBlank()) {
                                Text("Carico diff…", color = AppColors.Muted, fontSize = 12.sp)
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 320.dp)
                                        .verticalScroll(rememberScrollState())
                                        .background(AppColors.Panel, RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    prDiff.lines().take(400).forEach { line ->
                                        val color = when (diffLineKind(line)) {
                                            '+' -> AppColors.Success
                                            '-' -> AppColors.Error
                                            else -> Color.White
                                        }
                                        Text(line.take(160), color = color, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                    }
                                }
                                if (pr.state == "open") {
                                    if (prMergeArm == pr.number) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Button(onClick = { mergePr(pr.number) }) { Text("Conferma merge") }
                                            TextButton(onClick = { prMergeArm = null }) { Text("Annulla", color = AppColors.Accent) }
                                        }
                                    } else {
                                        TextButton(onClick = { prMergeArm = pr.number }) { Text("Unisci PR", color = AppColors.Accent) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

