package com.nemoclaw.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.ui.apple.AppleGlass

/**
 * Selettore [Chat | Bot] in alto alla chat: pill compatta (~36dp).
 * Il segmento illuminato dice in che sezione si e: Chat = conversazioni
 * proprie, Bot = sezione bot con la forever-chat canonica condivisa col
 * desktop. Lo stato e issato in AppRoot; il contenuto sotto scorre con
 * slide orizzontale.
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
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .selectableGroup()
            .semantics { contentDescription = "Selettore Chat Bot" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        AppleGlass(
            shape = CircleShape,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChatBotSegment(
                    label = "Chat",
                    selected = !botActive,
                    onClick = onSelectChat
                )
                ChatBotSegment(
                    label = "Bot",
                    selected = botActive,
                    onClick = onSelectBot
                )
            }
        }
    }
}

@Composable
private fun ChatBotSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val stateDesc = if (selected) "Sezione $label attiva" else "Sezione $label non attiva"
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = LocalIndication.current
            )
            .semantics(mergeDescendants = true) {
                stateDescription = stateDesc
            },
        color = if (selected) AppColors.Accent.copy(alpha = 0.16f) else Color.Transparent,
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(6.dp)
                        .background(AppColors.Accent, CircleShape)
                )
            }
            Text(
                text = label,
                color = if (selected) AppColors.Accent else AppColors.Muted,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
