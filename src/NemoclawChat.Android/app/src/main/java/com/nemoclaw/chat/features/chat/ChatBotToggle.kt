package com.nemoclaw.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Selettore [Chat | Bot] in alto alla chat. Il segmento illuminato dice in
 * che sezione si e: Chat = conversazioni proprie, Bot = sezione bot con la
 * chat persistente del bot (una per bot, condivisa con Hermes desktop via
 * autosync archivio). Lo stato e issato in AppRoot; il contenuto sotto
 * scorre con slide orizzontale.
 */
@Composable
internal fun ChatBotToggle(
    botActive: Boolean,
    onSelectChat: () -> Unit,
    onSelectBot: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ChatBotSegment(
            label = "Chat",
            selected = !botActive,
            onClick = onSelectChat,
            contentDescription = if (botActive) "Vai alle chat" else "Sezione chat attiva",
            modifier = Modifier.weight(1f)
        )
        ChatBotSegment(
            label = "Bot",
            selected = botActive,
            onClick = onSelectBot,
            contentDescription = if (botActive) "Sezione bot attiva" else "Vai ai bot",
            modifier = Modifier.weight(1f)
        )
    }
    HorizontalDivider(color = AppColors.Border.copy(alpha = 0.8f))
}

@Composable
private fun ChatBotSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick, onClickLabel = contentDescription),
        color = if (selected) AppColors.Accent else AppColors.Surface,
        shape = RoundedCornerShape(14.dp),
        border = if (selected) null else BorderStroke(1.dp, AppColors.Border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = (if (selected) "● " else "○ ") + label,
                color = if (selected) Color(0xFF171009) else AppColors.Muted,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
