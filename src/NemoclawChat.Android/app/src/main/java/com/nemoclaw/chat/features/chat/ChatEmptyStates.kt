package com.nemoclaw.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BotEmptyState(
    displayName: String?,
    opening: Boolean,
    unreachable: Boolean = false,
    detail: String? = null,
    onRetry: (() -> Unit)? = null
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 22.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        if (opening) {
            LinearProgressIndicator()
            Spacer(modifier = Modifier.height(10.dp))
        }
        Text(
            text = when {
                opening -> "Apro la chat…"
                unreachable -> "Bot non raggiungibile"
                else -> displayName ?: "Bot"
            },
            modifier = Modifier.semantics { displayName?.let { contentDescription = it } },
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 27.sp,
            lineHeight = 32.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(10.dp))
        val detailCapped = detail?.takeIf { it.isNotBlank() }?.take(80)
        Text(
            text = when {
                opening -> "Recupero la storia condivisa con Hermes desktop."
                unreachable -> if (detailCapped == null) {
                    "Il gateway non risponde: controlla la connessione e tocca Riprova."
                } else {
                    "Il gateway non risponde ($detailCapped): controlla la connessione e tocca Riprova."
                }
                else -> "Questa e la chat persistente del bot: la stessa su telefono e desktop. Scrivi per iniziare."
            },
            color = AppColors.Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
        if (unreachable && onRetry != null) {
            Spacer(modifier = Modifier.height(14.dp))
            TextButton(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
            ) {
                Text("Riprova")
            }
        }
    }
}

@Composable
internal fun EmptyState(onPrompt: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Image(
            painter = painterResource(id = R.drawable.chatclaw_logo),
            contentDescription = "Logo Hermes Hub",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(24.dp))
        )
        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = "Che vuoi fare oggi?",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 27.sp,
            lineHeight = 32.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Chat live, cron e strumenti Hermes Agent sul tuo home-server.",
            color = AppColors.Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(28.dp))
        Text("OPERAZIONI RAPIDE", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(10.dp))
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SuggestionButton("Prepara setup Hermes", "Avvia Hermes su Tailscale o LAN") {
                onPrompt("Preparami i passaggi per avviare Hermes Agent API Server su Tailscale/LAN.")
            }
            SuggestionButton("Controlla server", "Stato, modello e capabilities") {
                onPrompt("Controlla stato Hermes, modello disponibile e capabilities API.")
            }
            SuggestionButton("Crea ordine agente", "Task con approve prima dei rischi") {
                onPrompt("Crea un task agente sicuro con richiesta approve/deny prima di ogni azione rischiosa.")
            }
        }
    }
}

@Composable
internal fun SuggestionButton(text: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        color = AppColors.Panel,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AppColors.Border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(7.dp).background(AppColors.Accent, CircleShape))
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = AppColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("›", color = AppColors.Faint, fontSize = 22.sp)
        }
    }
}

@Composable
internal fun PremiumPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(content = content)
        HorizontalDivider(color = AppColors.Border.copy(alpha = 0.78f), thickness = 1.dp)
    }
}

@Composable
internal fun Card(
    modifier: Modifier = Modifier,
    colors: Any? = null,
    shape: Shape = RoundedCornerShape(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    PremiumPanel(modifier = modifier, content = content)
}

