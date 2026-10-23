package com.nemoclaw.chat.features.bots

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.AppColors

/** Colore avatar deterministico dal profilo. Puro, testabile. */
internal fun botAvatarColor(profile: String): androidx.compose.ui.graphics.Color {
    val hue = kotlin.math.abs(profile.hashCode() % 360).toFloat()
    return androidx.compose.ui.graphics.Color.hsl(hue, 0.45f, 0.42f)
}

@Composable
internal fun BotAvatar(name: String, profile: String, size: androidx.compose.ui.unit.Dp = 52.dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(botAvatarColor(profile)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            (name.trim().firstOrNull()?.uppercase() ?: "?"),
            color = Color.White,
            fontSize = (size.value / 2.4f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun BotRosterCard(
    bot: HermesBotItem,
    screenRunning: Boolean,
    screenPreview: Bitmap?,
    chatSupported: Boolean,
    busy: Boolean,
    onOpenDetail: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenScreen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
    // Menu contestuale stile desktop (long-press / ⋮).
    pinned: Boolean = false,
    hiddenBadge: Boolean = false,
    // Anteprima ultimo messaggio stile desktop (dal registro canonico).
    previewText: String? = null,
    previewTime: String? = null,
    menu: BotMenuHost? = null,
    onMenuRequest: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                onClickLabel = "Apri dettaglio bot",
                onLongClickLabel = "Opzioni bot",
                onClick = onOpenDetail,
                onLongClick = onMenuRequest
            ).padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                BotAvatar(bot.displayName, bot.profile, 40.dp)
                Box(
                    modifier = Modifier.size(14.dp).clip(CircleShape)
                        .background(if (screenRunning) Color(0xFF4CAF50) else AppColors.Faint)
                        .align(Alignment.BottomEnd)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(bot.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
                    if (pinned) Text("FISSATO", color = AppColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (hiddenBadge) Text("NASCOSTO", color = AppColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text("@${bot.handle} · ${bot.connectionLabel}", color = AppColors.Muted, fontSize = 11.sp)
                if (!previewText.isNullOrBlank()) {
                    Text(
                        (if (!previewTime.isNullOrBlank()) "$previewTime · " else "") + previewText,
                        color = AppColors.Muted,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    if (screenRunning) "Schermo live" else if (bot.isDefault) "Profilo predefinito" else bot.profile,
                    color = if (screenRunning) Color(0xFF4CAF50) else AppColors.Faint,
                    fontSize = 12.sp
                )
            }
            Box {
                IconButton(onClick = onMenuRequest) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Opzioni bot", tint = Color.White)
                }
                // Solo la card con menuFor == key compone il DropdownMenu
                // (hoist in BotsScreen: le altre passano menu = null).
                if (menu != null && menu.expanded) {
                    BotCardMenu(bot = bot, host = menu)
                }
            }
            IconButton(onClick = onOpenChat, enabled = !busy && chatSupported) {
                Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = "Apri Bot Chat", tint = Color.White)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = AppColors.Faint)
        }
        if (screenRunning && screenPreview != null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color.Black)
                    .clickable(onClick = onOpenScreen)
            ) {
                Image(
                    bitmap = screenPreview.asImageBitmap(),
                    contentDescription = "Anteprima schermo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!bot.description.isBlank()) {
            Text(
                bot.description,
                color = AppColors.Muted,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(
                onClick = onOpenScreen,
                enabled = screenRunning,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Apri live", color = if (screenRunning) AppColors.Accent else AppColors.Faint) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Modifica bot", tint = Color.White) }
            if (canDelete) {
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Elimina bot", tint = AppColors.Error) }
            }
        }
        HorizontalDivider(color = AppColors.Border)
    }
}

