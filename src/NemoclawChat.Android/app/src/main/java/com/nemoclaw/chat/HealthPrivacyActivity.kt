package com.nemoclaw.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HealthPrivacyNotice() }
    }
}

@Composable
private fun HealthPrivacyNotice() {
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Dati salute e privacy", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text("Hermes Hub legge i dati di Samsung Health tramite Health Connect solo dopo consenso esplicito.", color = AppColors.Muted)
        Text("Puoi scegliere passi, calorie attive, sonno, allenamenti e frequenza cardiaca aggregata. Il battito continuo e i record grezzi non vengono inviati né memorizzati da Hermes Hub.", color = AppColors.Muted)
        Text("L'app invia soltanto un riepilogo giornaliero al tuo endpoint Hermes configurato, usando la chiave API protetta nel dispositivo. La sincronizzazione può essere disattivata o i permessi revocati in Health Connect.", color = AppColors.Muted)
        Text("Queste informazioni servono a benessere e organizzazione personale; non costituiscono diagnosi, terapia o assistenza medica.", color = AppColors.Muted)
    }
}
