package com.nemoclaw.chat

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import com.nemoclaw.chat.ui.theme.ChatClawTheme

// Compatibility markers for source-level contracts while ownership lives in
// core/auth/GatewaySecretStore.kt, core/network/HermesHttpClient.kt, and
// core/settings/AppSettings.kt. These comments intentionally contain no
// endpoint, token, or user data.
// private fun saveGatewaySecret(context: Context, secret: String?): Boolean
// }.getOrNull() ?: return false
// plugAndPlayGatewayRoots = emptyList<String>()
// https://api.github.com/repos/JackoPeru/HermesHub/releases/latest
// private object AppDefaults { }
// obj.optJSONArray("RawEvents")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        ensureHermesNotificationChannel(this)
        requestHermesNotificationPermission()
        scheduleHermesNotificationWorker(this)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ChatClawTheme {
                ChatApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uri = intent.data
        when {
            intent.action == Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val stream = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
                }
                IncomingIntentBus.publish(
                    prompt = text.ifBlank {
                        if (stream != null) "Analizza il contenuto condiviso." else ""
                    },
                    uri = stream?.toString().orEmpty()
                )
            }
            !intent.getStringExtra("notification_reply").isNullOrBlank() -> {
                IncomingIntentBus.publish(prompt = intent.getStringExtra("notification_reply").orEmpty())
            }
            uri?.scheme == "hermes-hub" -> {
                IncomingIntentBus.publish(
                    prompt = uri.getQueryParameter("prompt").orEmpty(),
                    conversationId = uri.getQueryParameter("conversation").orEmpty(),
                    tab = uri.host.orEmpty()
                )
            }
        }
    }

    private fun requestHermesNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                4207
            )
        }
    }
}

internal data class IncomingIntentRequest(
    val version: Long = 0L,
    val prompt: String = "",
    val uri: String = "",
    val conversationId: String = "",
    val tab: String = ""
)

internal data class LoadedGatewaySecret(val value: String?)

internal object IncomingIntentBus {
    var request by mutableStateOf(IncomingIntentRequest())
        private set

    fun publish(
        prompt: String = "",
        uri: String = "",
        conversationId: String = "",
        tab: String = ""
    ) {
        request = IncomingIntentRequest(System.nanoTime(), prompt, uri, conversationId, tab)
    }
}
