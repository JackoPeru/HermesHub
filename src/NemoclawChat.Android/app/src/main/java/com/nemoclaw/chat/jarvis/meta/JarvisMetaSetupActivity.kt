package com.nemoclaw.chat.jarvis.meta

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
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nemoclaw.chat.AppColors
import com.nemoclaw.chat.BuildConfig
import com.nemoclaw.chat.ui.apple.AppleType
import com.nemoclaw.chat.ui.apple.AuroraBackground
import com.nemoclaw.chat.ui.theme.ChatClawTheme

internal class JarvisMetaSetupActivity : ComponentActivity() {
    private var status by mutableStateOf("Inizializzazione Meta DAT...")
    private var bridge: MetaWearablesSetupBridge? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridge = if (BuildConfig.META_DAT_ENABLED) runCatching {
            Class.forName("com.nemoclaw.chat.jarvis.meta.MetaWearablesSetupBridgeImpl")
                .getConstructor()
                .newInstance() as MetaWearablesSetupBridge
        }.getOrNull() else null
        bridge?.initialize(this) { status = it }
            ?: run { status = "Meta DAT non incluso in questa build. Compila con -PenableMetaDat=true." }
        setContent {
            ChatClawTheme {
                Box(
                    modifier = Modifier.fillMaxSize().background(AppColors.Background)
                ) {
                    AuroraBackground()
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("Configura Meta Wearables", color = Color.White, fontSize = AppleType.ScreenTitle, fontWeight = FontWeight.SemiBold)
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF14171C))
                                .border(0.5.dp, AppColors.Border, RoundedCornerShape(20.dp))
                                .padding(16.dp)
                        ) {
                            Text(status, color = AppColors.Faint, fontSize = AppleType.Footnote)
                        }
                        Button(
                            onClick = { bridge?.startRegistration(this@JarvisMetaSetupActivity) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = bridge != null,
                            shape = RoundedCornerShape(20.dp)
                        ) { Text("Registra tramite Meta AI") }
                        Button(
                            onClick = { bridge?.requestCameraPermission() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = bridge != null,
                            shape = RoundedCornerShape(20.dp)
                        ) { Text("Consenti fotocamera occhiali") }
                        Button(
                            onClick = { bridge?.openDatGlassesAppUpdate(this@JarvisMetaSetupActivity) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = bridge != null,
                            shape = RoundedCornerShape(20.dp)
                        ) { Text("Aggiorna DAT sugli occhiali") }
                        if (BuildConfig.DEBUG) {
                            Button(
                                onClick = { bridge?.enableMockPhoneCamera(this@JarvisMetaSetupActivity) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = bridge != null,
                                shape = RoundedCornerShape(20.dp)
                            ) { Text("Attiva Mock Device Kit") }
                        }
                        Button(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp)
                        ) { Text("Chiudi") }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        bridge?.close()
        bridge = null
        super.onDestroy()
    }
}
