package com.nemoclaw.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.ui.apple.AppleGlass
import com.nemoclaw.chat.ui.apple.AppleType

/**
 * Dashboard home: mix di widget verso le sezioni principali, senza sidebar.
 * Sottotitoli statici (niente IO: la dashboard resta leggera).
 */
internal data class DashboardEntry(val tab: Tab, val subtitle: String)

internal fun dashboardEntries(): List<DashboardEntry> = listOf(
    DashboardEntry(Tab.Bots, "Gestisci i bot"),
    DashboardEntry(Tab.Voice, "Parla con Hermes"),
    DashboardEntry(Tab.Jarvis, "Vista e automazioni"),
    DashboardEntry(Tab.Video, "Libreria video"),
    DashboardEntry(Tab.Images, "Foto e media"),
    DashboardEntry(Tab.News, "Notizie"),
    DashboardEntry(Tab.Hardware, "CPU, GPU, dischi"),
    DashboardEntry(Tab.Health, "Benessere"),
    DashboardEntry(Tab.Cron, "Attività pianificate"),
    DashboardEntry(Tab.Archive, "Chat salvate"),
    DashboardEntry(Tab.Artifacts, "File generati"),
    DashboardEntry(Tab.Projects, "Spazi di lavoro"),
    DashboardEntry(Tab.Characters, "Voci e identità"),
    DashboardEntry(Tab.Screen, "Desktop remoto"),
    DashboardEntry(Tab.Search, "Cerca ovunque"),
    DashboardEntry(Tab.Notifications, "Avvisi"),
    DashboardEntry(Tab.Continuity, "Riprendi il filo"),
    DashboardEntry(Tab.Audit, "Registro attività"),
    DashboardEntry(Tab.Profile, "Profilo e dispositivi"),
    DashboardEntry(Tab.Settings, "Impostazioni")
)

@Composable
private fun DashboardWidgetCard(
    entry: DashboardEntry,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppleGlass(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        onClick = onOpen
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.Accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    entry.tab.icon,
                    contentDescription = null,
                    tint = AppColors.Accent,
                    modifier = Modifier.size(21.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    entry.tab.label,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    entry.subtitle,
                    color = AppColors.Faint,
                    fontSize = AppleType.Footnote,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun ServerDashboardGrid(onOpenTab: (Tab) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "SEZIONI",
            color = AppColors.Accent,
            fontSize = AppleType.Footnote,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        dashboardEntries().chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { entry ->
                    DashboardWidgetCard(
                        entry = entry,
                        onOpen = { onOpenTab(entry.tab) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
