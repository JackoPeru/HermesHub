package com.nemoclaw.chat

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun NewsArticleScreen(
    context: Context,
    settings: AppSettings,
    article: WorkspaceRequest,
    onBack: () -> Unit,
    onChanged: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var feedback by remember(article.id) { mutableStateOf(article.feedback) }
    var status by remember(article.id) { mutableStateOf("Lascia feedback: Hermes lo usera' per migliorare articoli e briefing futuri.") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AppColors.Background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro", tint = AppColors.Accent) }
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = AppColors.Accent.copy(alpha = 0.18f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.Article, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(article.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${article.source} - ${article.status} - ${formatVideoTimestamp(article.updatedAt)}",
                        color = AppColors.Muted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(status, color = AppColors.Faint, fontSize = 12.sp)
                HorizontalDivider(color = AppColors.Border)
                MarkdownText(article.result.ifBlank { article.prompt }, color = Color.White, fontSize = 16.sp)
                HorizontalDivider(color = AppColors.Border)
                Text("FEEDBACK", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoFeedChip("Piu' fonti") { feedback = appendFeedbackSnippet(feedback, "Aggiungi piu' fonti verificabili") }
                    VideoFeedChip("Piu' breve") { feedback = appendFeedbackSnippet(feedback, "Sintesi piu' breve e densa") }
                    VideoFeedChip("Piu' profondo") { feedback = appendFeedbackSnippet(feedback, "Analisi piu' profonda e meno superficiale") }
                    VideoFeedChip("Tono") { feedback = appendFeedbackSnippet(feedback, "Tono piu' chiaro, diretto e operativo") }
                }
                SettingsField("Feedback per Hermes", feedback, { feedback = it })
                Button(
                    onClick = {
                        if (feedback.isBlank()) {
                            status = "Scrivi feedback prima di inviare."
                            return@Button
                        }
                        status = "Invio feedback a Hermes..."
                        scope.launch {
                            val result = sendWorkspaceFeedback(settings, article, feedback, loadGatewaySecret(context))
                            saveWorkspaceFeedback(context, article.id, feedback, result)
                            status = result
                            onChanged(result)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Invia feedback", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Invia feedback", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

