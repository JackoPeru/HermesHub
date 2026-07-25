package com.nemoclaw.chat.jarvis.meta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nemoclaw.chat.BuildConfig
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
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Configura Meta Wearables")
                    Text(status)
                    Button(
                        onClick = { bridge?.startRegistration(this@JarvisMetaSetupActivity) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = bridge != null
                    ) { Text("Registra tramite Meta AI") }
                    Button(
                        onClick = { bridge?.requestCameraPermission() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = bridge != null
                    ) { Text("Consenti fotocamera occhiali") }
                    if (BuildConfig.DEBUG) {
                        Button(
                            onClick = { bridge?.enableMockPhoneCamera(this@JarvisMetaSetupActivity) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = bridge != null
                        ) { Text("Attiva Mock Device Kit") }
                    }
                    Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Chiudi") }
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
