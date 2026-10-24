package com.nemoclaw.chat.features.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.AppColors

internal fun formatGroupTurnTime(atMs: Long): String {
    return try {
        java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ITALIAN)
            .format(java.util.Date(atMs))
    } catch (_: Exception) {
        ""
    }
}

@Composable
internal fun GroupTurnActivity(
    result: HermesBotGroupTurnResult,
    sourceLabel: (String) -> String
) {
    var expanded by androidx.compose.runtime.remember(result) {
        androidx.compose.runtime.mutableStateOf(emptySet<Int>())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Esito: ${groupOutcomeLabel(result.outcome)} · turni: ${result.rounds} · risposte: ${result.botMessages}",
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
        val silent = result.memberResults.count { it.silent }
        if (silent > 0) {
            Text("Silenziosi: $silent", color = AppColors.Muted, fontSize = 12.sp)
        }
        result.memberResults.forEachIndexed { index, memberResult ->
            val isOpen = index in expanded
            val dot = if (memberResult.silent) AppColors.Muted else AppColors.Success
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = if (isOpen) expanded - index else expanded + index
                    }
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(dot)
                    )
                    Text(
                        "@${memberResult.member.handle} · ${memberResult.member.displayName}",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (memberResult.silent) "pass" else if (isOpen) "▲" else "▼",
                        color = AppColors.Muted,
                        fontSize = 12.sp
                    )
                }
                Text(
                    sourceLabel(memberResult.member.connectionId),
                    color = AppColors.Faint,
                    fontSize = 11.sp
                )
                if (isOpen) {
                    Text(
                        if (memberResult.silent) "(pass: nessun intervento)" else memberResult.reply,
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }
        if (result.failures.isNotEmpty()) {
            Text("Fallimenti parziali:", color = Color.White, fontWeight = FontWeight.SemiBold)
            result.failures.forEach { failure ->
                Text("• $failure", color = AppColors.Error, fontSize = 12.sp)
            }
        }
    }
}


internal fun fleetSummaryLine(bots: Int, groups: Int, turns: Int): String =
    "$bots bot · $groups gruppi · $turns turni recenti"

@Composable
internal fun FleetCard(
    bots: Int,
    groups: Int,
    turns: List<GroupTurnSummary>,
    gpuLine: String,
    onRefresh: () -> Unit
) {
    Surface(
        color = AppColors.Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.5.dp, AppColors.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "FLOTTA",
                    color = AppColors.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRefresh) { Text("Aggiorna", color = AppColors.Accent) }
            }
            Text(
                fleetSummaryLine(bots.coerceAtLeast(0), groups, turns.size),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(gpuLine, color = AppColors.Muted, fontSize = 12.sp)
            turns.take(3).forEach { summary ->
                Text(
                    "${summary.group} · ${groupOutcomeLabel(summary.outcome)} · ${formatGroupTurnTime(summary.atMs)}",
                    color = AppColors.Muted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

internal fun groupOutcomeLabel(outcome: String): String = when (outcome.lowercase()) {
    "reply" -> "Risposte ricevute"
    "bounded" -> "Limitato a 3 turni e 10 risposte"
    "pass" -> "Nessun intervento"
    "partial" -> "Parziale: alcuni membri falliti"
    "error" -> "Errore"
    "escalation" -> "Escalation"
    else -> outcome
}

