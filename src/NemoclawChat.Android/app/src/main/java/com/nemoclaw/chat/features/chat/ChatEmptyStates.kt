package com.nemoclaw.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.nemoclaw.chat.ui.apple.AppleGlass
import com.nemoclaw.chat.ui.apple.AppleType
import com.nemoclaw.chat.ui.apple.AuroraBackground
import com.nemoclaw.chat.ui.apple.AvatarRing
import com.nemoclaw.chat.ui.apple.ServerStatusLine
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        AuroraBackground(modifier = Modifier.fillMaxSize(), intensity = 1f)
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
}

@Composable
internal fun EmptyState(
    onPrompt: (String) -> Unit,
    serverOnline: Boolean = true,
    onOpenSidebar: (() -> Unit)? = null,
    onOpenProfile: (() -> Unit)? = null
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.aurora_home),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Column(modifier = Modifier.fillMaxSize()) {
            // Header home 1:1 dal riferimento (sostituisce la TopBar a chat vuota).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.chatclaw_logo),
                    contentDescription = "Apri navigazione",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = onOpenSidebar != null) { onOpenSidebar?.invoke() }
                )
                Column(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f)
                ) {
                    Text(
                        text = "Hermes Hub",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        maxLines = 1
                    )
                    ServerStatusLine(
                        text = if (serverOnline) "Server online" else "Server offline",
                        online = serverOnline
                    )
                }
                if (onOpenProfile != null) {
                    AvatarRing(initial = "H", onClick = onOpenProfile, size = 40.dp, contentDescription = "Apri profilo")
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(190.dp)
                            .background(
                                Brush.radialGradient(
                                    0.0f to Color(0xFFF5A524).copy(alpha = 0.30f),
                                    0.55f to Color(0xFFF5A524).copy(alpha = 0.07f),
                                    1.0f to Color.Transparent
                                ),
                                CircleShape
                            )
                    )
                    Image(
                        painter = painterResource(id = R.drawable.chatclaw_logo),
                        contentDescription = "Logo Hermes Hub",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(28.dp))
                    )
                }
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = "Che vuoi fare oggi?",
                    color = Color.White,
                    fontWeight = FontWeight.Light,
                    fontSize = AppleType.HeroTitle,
                    lineHeight = 38.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Chiedi, crea, controlla.\nHermes è qui per aiutarti.",
                    color = AppColors.Muted,
                    fontSize = AppleType.HeroSubtitle,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(32.dp))
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
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
internal fun SuggestionButton(text: String, subtitle: String, onClick: () -> Unit) {
    AppleGlass(
        shape = RoundedCornerShape(20.dp),
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(7.dp).background(AppColors.Accent, CircleShape))
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = AppColors.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

