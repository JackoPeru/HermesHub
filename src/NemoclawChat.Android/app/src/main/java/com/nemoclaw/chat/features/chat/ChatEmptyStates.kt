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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.nemoclaw.chat.ui.apple.AppleType
import com.nemoclaw.chat.ui.apple.AvatarRing
import com.nemoclaw.chat.ui.apple.BubbleBackground
import com.nemoclaw.chat.ui.apple.ServerStatusLine
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
            .background(Color(0xFF0A0A0C))
    ) {
        // Stesse bolle vive della home (coerenza totale, niente PNG statico).
        BubbleBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        if (opening) {
            LinearProgressIndicator()
            Spacer(modifier = Modifier.height(10.dp))
        }
        // Marchio bot come nell'hero (coerenza sezione bot).
        Image(
            painter = painterResource(id = R.drawable.chatclaw_logo_hero),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
        )
        Spacer(modifier = Modifier.height(18.dp))
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
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
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
                else -> "Nuova chat pulita: ogni apertura ricomincia da qui. Scrivi per iniziare."
            },
            color = AppColors.Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
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

/**
 * Tendina bot del titolo hero: stesse voci del selettore identita, senza
 * freccia ne chrome (il titolo resta identico, solo cliccabile).
 */
@Composable
private fun HeroBotMenu(picker: ChatIdentityPicker, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(20.dp)) {
        DropdownMenuItem(
            text = {
                Column {
                    Text("Hermes", color = Color.White, fontSize = AppleType.RowTitle)
                    Text("Profilo predefinito", color = AppColors.Faint, fontSize = AppleType.Footnote)
                }
            },
            trailingIcon = {
                if (picker.activeKey.isBlank()) {
                    Icon(Icons.Rounded.Check, contentDescription = "Attivo", tint = AppColors.Accent)
                }
            },
            onClick = { onDismiss(); picker.onPick("") }
        )
        if (picker.options.isNotEmpty()) HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
        picker.options.forEach { option ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(option.label, color = Color.White, fontSize = AppleType.RowTitle)
                        if (option.subtitle.isNotBlank()) {
                            Text(option.subtitle, color = AppColors.Faint, fontSize = AppleType.Footnote)
                        }
                    }
                },
                trailingIcon = {
                    if (option.key == picker.activeKey) {
                        Icon(Icons.Rounded.Check, contentDescription = "Attivo", tint = AppColors.Accent)
                    }
                },
                onClick = { onDismiss(); picker.onPick(option.key) }
            )
        }
        when {
            picker.loading -> DropdownMenuItem(
                text = { Text("Caricamento bot…", color = AppColors.Faint, fontSize = AppleType.Footnote) },
                enabled = false,
                onClick = {}
            )
            picker.options.isEmpty() && picker.status.isNotBlank() -> DropdownMenuItem(
                text = { Text(picker.status.take(120), color = AppColors.Faint, fontSize = AppleType.Footnote) },
                enabled = false,
                onClick = {}
            )
            picker.options.isEmpty() -> DropdownMenuItem(
                text = { Text("Nessun bot dal gateway: controlla la connessione e riapri.", color = AppColors.Faint, fontSize = AppleType.Footnote) },
                enabled = false,
                onClick = {}
            )
        }
    }
}

@Composable
internal fun EmptyState(
    onPrompt: (String) -> Unit,
    serverOnline: Boolean = true,
    onOpenSidebar: (() -> Unit)? = null,
    // Tendina bot sul titolo (null = titolo statico). Nessun chrome visivo.
    identityPicker: ChatIdentityPicker? = null,
    // % contesto nell'avatar + menu rapido manager (null = H senza menu).
    contextUsage: ContextUsage? = null,
    settings: AppSettings? = null,
    managerApiKey: String? = null,
    onNewChat: () -> Unit = {},
    onOpenComfy: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0C))
    ) {
        // Bolle vive del riferimento (niente PNG statico).
        BubbleBackground()
        Column(modifier = Modifier.fillMaxSize()) {
            // Header home: sotto la status bar, mai tagliato.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
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
                var botMenuOpen by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f)
                ) {
                    // Titolo-bottone invisibile: stesso testo, apre la tendina bot.
                    val picker = identityPicker
                    Box {
                        Text(
                            text = "Hermes Hub",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            maxLines = 1,
                            modifier = Modifier.then(
                                if (picker != null) {
                                    Modifier.clickable(role = Role.Button, onClick = { picker.onRefresh(); botMenuOpen = true })
                                } else Modifier
                            )
                        )
                        if (picker != null) {
                            HeroBotMenu(picker = picker, expanded = botMenuOpen, onDismiss = { botMenuOpen = false })
                        }
                    }
                    ServerStatusLine(
                        text = if (serverOnline) "Server online" else "Server offline",
                        online = serverOnline
                    )
                }
                // Avatar = % contesto; tap = menu rapido manager (niente profilo).
                var avatarMenuOpen by remember { mutableStateOf(false) }
                val usageLabel = when {
                    contextUsage == null -> "H"
                    contextUsage.delegatedToHermes && contextUsage.tokens <= 0 -> "H"
                    else -> "${contextUsage.percent.coerceIn(0, 100)}%"
                }
                Box {
                    AvatarRing(
                        initial = "H",
                        label = usageLabel,
                        onClick = { avatarMenuOpen = true },
                        size = 40.dp,
                        contentDescription = "Menu rapido: contesto $usageLabel e manager"
                    )
                    if (settings != null) {
                        ManagerQuickMenu(
                            settings = settings,
                            managerApiKey = managerApiKey,
                            onNewChat = onNewChat,
                            onOpenComfy = onOpenComfy,
                            expanded = avatarMenuOpen,
                            onDismiss = { avatarMenuOpen = false }
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Contenuto in alto come il riferimento (logo ~29%, titolo ~40%).
                Spacer(modifier = Modifier.height(108.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(112.dp)
                ) {
                    // Alone largo del riferimento: deborda dal box senza pesare nel layout.
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .background(
                                Brush.radialGradient(
                                    0.0f to Color(0xFFF5A524).copy(alpha = 0.10f),
                                    0.6f to Color(0xFFF5A524).copy(alpha = 0.03f),
                                    1.0f to Color(0xFFF5A524).copy(alpha = 0f)
                                ),
                                CircleShape
                            )
                    )
                    Image(
                        // Logo luminoso dell'utente (asset dedicato, niente boost: e gia luminoso).
                        painter = painterResource(id = R.drawable.chatclaw_logo_hero),
                        contentDescription = "Logo Hermes Hub",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(112.dp)
                            .clip(RoundedCornerShape(30.dp))
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
            }
        }
    }
}

