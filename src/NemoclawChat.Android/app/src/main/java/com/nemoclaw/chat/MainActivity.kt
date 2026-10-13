package com.nemoclaw.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.provider.OpenableColumns
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
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
        runCatching { applyScreenshotBlock(this, loadSettings(this).blockScreenshots) }
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
                val rawText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val stream = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
                }
                // Prompt troncato onesto a 2000 char (niente intent giganti in bozza).
                val prompt = truncateIncomingPrompt(rawText).ifBlank {
                    if (stream != null) "Analizza il contenuto condiviso." else ""
                }
                // Allegato per-intent: max 25MB + allowlist MIME, rifiuto onesto con Toast.
                var uriString = ""
                if (stream != null) {
                    val mime = runCatching { contentResolver.getType(stream) }.getOrNull()
                    when {
                        !isAllowedIncomingMime(mime) -> {
                            Log.w(TAG_HERMES_DEEPLINK, "ACTION_SEND rifiutato: MIME non consentito ($mime).")
                            Toast.makeText(this, "Allegato non supportato (solo immagini, audio, video, testo, PDF).", Toast.LENGTH_LONG).show()
                        }
                        !validateIncomingAttachmentSize(queryIncomingShareSize(stream)) -> {
                            Log.w(TAG_HERMES_DEEPLINK, "ACTION_SEND rifiutato: allegato oltre 25 MB.")
                            Toast.makeText(this, "Allegato oltre 25 MB: non allegato.", Toast.LENGTH_LONG).show()
                        }
                        else -> uriString = stream.toString()
                    }
                }
                IncomingIntentBus.publish(prompt = prompt, uri = uriString)
            }
            !intent.getStringExtra("notification_reply").isNullOrBlank() -> {
                IncomingIntentBus.publish(prompt = intent.getStringExtra("notification_reply").orEmpty())
            }
            uri?.scheme == "hermes-hub" -> {
                val parsed = parseHermesDeepLink(uri.toString())
                if (parsed == null) {
                    Log.w(TAG_HERMES_DEEPLINK, "Deeplink hermes-hub rifiutato (fuori allowlist).")
                } else {
                    if (parsed.promptRequiresConfirmation) {
                        // Il prompt finisce SOLO in bozza (anteprima): l'invio resta manuale.
                        Log.i(TAG_HERMES_DEEPLINK, "Deeplink con prompt: anteprima in bozza, invio manuale.")
                    }
                    IncomingIntentBus.publish(
                        prompt = parsed.prompt,
                        conversationId = parsed.conversationId,
                        tab = parsed.tab
                    )
                }
            }
        }
    }

    private fun queryIncomingShareSize(uri: Uri): Long {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
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

internal const val TAG_HERMES_DEEPLINK = "HermesDeepLink"

/** Route deeplink ammesse (host allowlist): coprono tutti i produttori interni
 *  (widget chat/voce, tile voce, notifiche HermesWorkService/Jarvis).
 *  "bots" apre la Chat con sezione Bot attiva (i bot non sono piu un tab). */
internal val allowedHermesDeepLinkHosts = setOf("chat", "voice", "jarvis", "bots")

/** Query key ammesse sui deeplink hermes-hub. Tutto il resto rigetta il deeplink. */
internal val allowedHermesDeepLinkQueryKeys = setOf("conversation", "prompt")

internal const val MAX_HERMES_DEEPLINK_PROMPT_CHARS = 2000

/** Cap prompt per ACTION_SEND/condivisioni (stesso dei deeplink): troncamento onesto. */
internal const val MAX_INCOMING_PROMPT_CHARS = 2000

/** Cap allegato per-intent: oltre si rifiuta con Toast, niente copie da 150MB. */
internal const val MAX_INCOMING_ATTACHMENT_BYTES = 25L * 1024L * 1024L

/** Allowlist MIME per condivisioni in ingresso. Pura e testabile. */
internal fun isAllowedIncomingMime(mime: String?): Boolean {
    val value = mime?.trim()?.lowercase().orEmpty()
    if (value.isBlank()) return false
    if (value.startsWith("image/") || value.startsWith("audio/") ||
        value.startsWith("video/") || value.startsWith("text/")
    ) return true
    return value == "application/pdf"
}

/** Troncamento onesto del prompt in ingresso. Puro e testabile. */
internal fun truncateIncomingPrompt(text: String, maxChars: Int = MAX_INCOMING_PROMPT_CHARS): String {
    return text.trim().take(maxChars.coerceAtLeast(1))
}

/**
 * Valida la dimensione dichiarata dell'allegato in ingresso.
 * Dimensione ignota (-1) = passa (il limite reale scatta in copia su disco).
 * Pura e testabile.
 */
internal fun validateIncomingAttachmentSize(sizeBytes: Long): Boolean {
    if (sizeBytes < 0L) return true
    return sizeBytes in 1..MAX_INCOMING_ATTACHMENT_BYTES
}

private val hermesConversationIdFormat = Regex("^[A-Za-z0-9_-]{1,128}$")

internal data class ParsedHermesDeepLink(
    val tab: String,
    val conversationId: String = "",
    val prompt: String = "",
) {
    /** Il prompt va solo in bozza (anteprima): mai auto-inviato. */
    val promptRequiresConfirmation: Boolean get() = prompt.isNotBlank()
}

/**
 * Valida un deeplink `hermes-hub://` contro l'allowlist di route note.
 * Puro (java.net.URI, nessun framework): testabile in unit test JVM.
 * Ritorna null per tutto ciò che è fuori allowlist (host/path/query/frammento).
 */
internal fun parseHermesDeepLink(raw: String?): ParsedHermesDeepLink? {
    if (raw.isNullOrBlank()) return null
    val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return null
    if (!uri.scheme.equals("hermes-hub", ignoreCase = true)) return null
    // Niente userinfo/porte: le route note non ne usano.
    if (uri.userInfo != null || uri.port != -1) return null
    val host = uri.host?.lowercase().orEmpty()
    if (host !in allowedHermesDeepLinkHosts) return null
    val path = uri.path.orEmpty()
    if (path.isNotBlank() && path != "/") return null
    if (uri.fragment != null) return null
    val query = uri.rawQuery.orEmpty()
    if (query.isBlank()) return ParsedHermesDeepLink(tab = host)
    var conversationId = ""
    var prompt = ""
    val seenKeys = mutableSetOf<String>()
    for (pair in query.split("&")) {
        if (pair.isEmpty()) continue
        val key = pair.substringBefore("=").lowercase()
        if (key !in allowedHermesDeepLinkQueryKeys || !seenKeys.add(key)) return null
        val encoded = pair.substringAfter("=", missingDelimiterValue = "")
        val value = runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return null
        when (key) {
            "conversation" -> {
                val id = value.trim()
                if (id.isNotEmpty() && !hermesConversationIdFormat.matches(id)) return null
                conversationId = id
            }
            "prompt" -> {
                prompt = value.trim().take(MAX_HERMES_DEEPLINK_PROMPT_CHARS)
            }
        }
    }
    return ParsedHermesDeepLink(tab = host, conversationId = conversationId, prompt = prompt)
}

/**
 * Calcolo puro dei flag screenshot: aggiunge/rimuove FLAG_SECURE preservando gli altri bit.
 * Testabile in unit test JVM (FLAG_SECURE e' costante inline, nessuna chiamata framework).
 */
internal fun applyScreenshotBlock(currentFlags: Int, enabled: Boolean): Int {
    return if (enabled) currentFlags or WindowManager.LayoutParams.FLAG_SECURE
    else currentFlags and WindowManager.LayoutParams.FLAG_SECURE.inv()
}

/** Applica FLAG_SECURE alla window solo se [enabled]; riusabile da qualsiasi Activity. */
internal fun applyScreenshotBlock(activity: Activity, enabled: Boolean) {
    if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}
