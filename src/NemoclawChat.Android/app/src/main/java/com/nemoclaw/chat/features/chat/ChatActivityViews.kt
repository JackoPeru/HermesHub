package com.nemoclaw.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun RawHermesEventsView(events: List<HermesRawEvent>) {
    if (events.isEmpty() || !SHOW_RAW_HERMES_EVENTS_IN_CHAT) return
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = AppColors.Surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AppColors.Border)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Eventi Hermes raw: ${events.size}", color = AppColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (expanded) {
                events.take(40).forEach { event ->
                    Text("${event.name}: ${event.json.take(800)}", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
internal fun ArchivedActivityDisclosure(
    persisted: List<AssistantActivity>,
    legacyThinking: String,
    showToolCalls: Boolean
) {
    val blocks = remember(persisted, legacyThinking, showToolCalls) {
        val compatible = if (persisted.isEmpty() && legacyThinking.isNotBlank()) {
            listOf(AssistantActivity(AssistantActivity.Kind.Reasoning, text = legacyThinking))
        } else persisted
        val filtered = compatible.filter { showToolCalls || it.kind != AssistantActivity.Kind.Tool }
        flagTranscriptBlocks(transcriptBlocksOf(filtered, "", showToolCalls), showToolCalls)
    }
    // Feed compatto inline come il desktop: oltre 8 blocchi, pill "↓ N messaggi".
    if (blocks.isNotEmpty()) {
        AgentTranscript(
            blocks = blocks,
            active = false,
            includeAgentText = false,
            turnKey = blocks.hashCode(),
            maxVisible = 8
        )
    }
}

