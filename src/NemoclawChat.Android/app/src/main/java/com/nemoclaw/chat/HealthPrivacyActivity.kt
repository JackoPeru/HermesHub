package com.nemoclaw.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nemoclaw.chat.ui.apple.AppleType
import com.nemoclaw.chat.ui.apple.AuroraBackground

class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HealthPrivacyNotice() }
    }
}

@Composable
private fun HealthPrivacyNotice() {
    Box(
        modifier = Modifier.fillMaxSize().background(AppColors.Background)
    ) {
        AuroraBackground()
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Dati salute e privacy", color = Color.White, fontSize = AppleType.ScreenTitle, fontWeight = FontWeight.SemiBold)
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF14171C))
                    .border(0.5.dp, AppColors.Border, RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Hermes Hub legge i dati di Samsung Health tramite Health Connect solo dopo consenso esplicito.", color = AppColors.Muted, fontSize = AppleType.Footnote)
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                Text("Puoi scegliere passi, calorie attive, sonno, allenamenti e frequenza cardiaca aggregata. Il battito continuo e i record grezzi non vengono inviati né memorizzati da Hermes Hub.", color = AppColors.Muted, fontSize = AppleType.Footnote)
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                Text("L'app invia soltanto un riepilogo giornaliero al tuo endpoint Hermes configurato, usando la chiave API protetta nel dispositivo. La sincronizzazione può essere disattivata o i permessi revocati in Health Connect.", color = AppColors.Muted, fontSize = AppleType.Footnote)
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                Text("Queste informazioni servono a benessere e organizzazione personale; non costituiscono diagnosi, terapia o assistenza medica.", color = AppColors.Muted, fontSize = AppleType.Footnote)
            }
        }
    }
}
