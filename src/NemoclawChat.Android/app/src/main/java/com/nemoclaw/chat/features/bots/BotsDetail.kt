package com.nemoclaw.chat.features.bots

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.nemoclaw.chat.features.screen.ScreenScreen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.AppColors
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.ScreenStatusInfo
import com.nemoclaw.chat.loadBotAutoApproveMap
import com.nemoclaw.chat.saveBotAutoApprove
import com.nemoclaw.chat.core.WorkLimits
import com.nemoclaw.chat.loadBotAutoApproveMap
import com.nemoclaw.chat.saveBotAutoApprove

private enum class BotDetailTab(val label: String) { Chat("Chat"), Screen("Screen"), Info("Info") }

@Composable
internal fun BotDetailScreen(
    bot: HermesBotItem,
    context: Context,
    settings: AppSettings,
    screenStatus: ScreenStatusInfo?,
    screenPreview: Bitmap?,
    onBack: () -> Unit,
    onOpenChat: (HermesBotItem) -> Unit,
    onOpenScreen: () -> Unit,
    onOpenCron: () -> Unit,
    // Disabilita "Apri" mentre l'open esterno e in corso (anti doppio tap).
    busy: Boolean = false
) {
    // applicationContext: remember/static non trattengono mai l'Activity.
    val appContext = context.applicationContext
    var tab by remember(bot.identityKey) { mutableStateOf(BotDetailTab.Chat) }
    var autoMode by remember(bot.identityKey) {
        mutableStateOf(loadBotAutoApproveMap(appContext)[bot.profile] ?: "off")
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro", tint = Color.White)
            }
            BotAvatar(bot.displayName, bot.profile, 40.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(bot.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Text(
                    "@${bot.handle} · " + if (screenStatus?.running == true) "schermo live" else "schermo spento",
                    color = AppColors.Muted, fontSize = 11.sp
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BotDetailTab.values().forEach { entry ->
                    val selected = tab == entry
                    TextButton(
                        onClick = { tab = entry },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(
                            entry.label,
                            color = if (selected) AppColors.Accent else AppColors.Muted,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
            HorizontalDivider(color = AppColors.Border)
        }
        when (tab) {
            BotDetailTab.Chat -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (bot.description.isNotBlank()) Text(bot.description, color = AppColors.Muted, fontSize = 14.sp)
                    Button(
                        // Delega all'handler esterno (singola POST persistente):
                        // prima faceva POST qui + POST fuori (doppia sessione).
                        onClick = { onOpenChat(bot) },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Apri Bot Chat", color = Color(0xFF171009)) }
                    Text("Auto-approvazione run di ${bot.displayName}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((label, value) in listOf("Chiedi" to WorkLimits.AUTO_APPROVE_OFF, "Sessione" to WorkLimits.AUTO_APPROVE_SESSION, "Sempre" to WorkLimits.AUTO_APPROVE_ALWAYS)) {
                            val selected = autoMode == value
                            TextButton(
                                onClick = {
                                    saveBotAutoApprove(appContext, bot.profile, value)
                                    autoMode = value
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(
                                    if (selected) "✓ $label" else label,
                                    color = if (selected) AppColors.Accent else AppColors.Muted
                                )
                            }
                        }
                    }
                    Text("Mai deny automatico; ogni auto-approvazione resta visibile.", color = AppColors.Faint, fontSize = 12.sp)
                }
            }
            BotDetailTab.Screen -> {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    ScreenScreen(context = context, settings = settings)
                }
            }
            BotDetailTab.Info -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("PROFILO", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(bot.profile, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(color = AppColors.Border)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("CONNESSIONE", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(bot.connectionLabel, color = Color.White, fontSize = 14.sp)
                    }
                    HorizontalDivider(color = AppColors.Border)
                    if (bot.description.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("DESCRIZIONE", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text(bot.description, color = AppColors.Muted, fontSize = 14.sp)
                        }
                        HorizontalDivider(color = AppColors.Border)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("ROUTINE", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        TextButton(onClick = onOpenCron, modifier = Modifier.heightIn(min = 48.dp)) { Text("Routine e Cron", color = AppColors.Accent) }
                        TextButton(onClick = onOpenScreen, modifier = Modifier.heightIn(min = 48.dp)) { Text("Apri schermo live", color = AppColors.Accent) }
                    }
                }
            }
        }
    }
}

