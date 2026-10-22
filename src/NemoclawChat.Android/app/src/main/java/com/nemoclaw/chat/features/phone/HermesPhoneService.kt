package com.nemoclaw.chat.features.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.nemoclaw.chat.R
import com.nemoclaw.chat.loadGatewaySecret
import com.nemoclaw.chat.resolveHermesUrl
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.loadSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Esecuzione comandi phone_use: attivo SOLO se armato in app + servizio
 * accessibilità concesso. Kill switch = notifica STOP / disarmo / revoca
 * accesso. Mai screenshot: solo albero UI testuale su richiesta.
 */
class HermesPhoneService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onServiceConnected() {
        startPolling()
        showNotification("Controllo telefono pronto (in attesa di armo).")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopPolling()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopPolling()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setPhoneArmed(this, false)
            stopPolling()
            showNotification("Controllo telefono fermato.")
        }
        return START_STICKY
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                try {
                    if (isPhoneArmed(this@HermesPhoneService)) {
                        drainCommands()
                    }
                } catch (_: Exception) {
                }
                delay(5000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun drainCommands() {
        val appContext = applicationContext
        val settings = loadSettings(appContext)
        val secret = loadGatewaySecret(appContext) ?: return
        val base = resolveHermesUrl(settings, "/v1/hub/phone/commands")
        val (code, body) = com.nemoclaw.chat.httpGetResponse("$base?status=pending", secret)
        if (code !in 200..299) return
        val arr = runCatching { JSONObject(body).optJSONArray("commands") }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val cmd = arr.optJSONObject(i) ?: continue
            val id = cmd.optString("id")
            if (id.isBlank()) continue
            val kind = cmd.optString("kind")
            val payload = cmd.optJSONObject("payload") ?: JSONObject()
            val (ok, result) = runCatching { executeCommand(kind, payload) }
                .getOrElse { false to "errore: ${it.message}" }
            appendPhoneLog(appContext, phoneLogLine(kind, id, if (ok) "ok: $result" else result))
            postResult(settings, secret, id, ok, result)
        }
    }

    private suspend fun executeCommand(kind: String, payload: JSONObject): Pair<Boolean, String> {
        return when (kind) {
            "tap" -> {
                val done = tapAt(payload.optDouble("x").toFloat(), payload.optDouble("y").toFloat())
                done to if (done) "tocco eseguito" else "gesto rifiutato"
            }
            "swipe" -> {
                val done = swipe(
                    payload.optDouble("x1").toFloat(), payload.optDouble("y1").toFloat(),
                    payload.optDouble("x2").toFloat(), payload.optDouble("y2").toFloat(),
                    payload.optInt("ms", 300)
                )
                done to if (done) "trascinamento eseguito" else "gesto rifiutato"
            }
            "text" -> {
                val node = findEditableNode()
                if (node == null) {
                    false to "nessun campo attivo"
                } else {
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            payload.optString("text")
                        )
                    }
                    val done = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    done to if (done) "testo inserito" else "inserimento rifiutato"
                }
            }
            "key" -> {
                val action = when (payload.optString("name").lowercase()) {
                    "back" -> GLOBAL_ACTION_BACK
                    "home" -> GLOBAL_ACTION_HOME
                    "recents" -> GLOBAL_ACTION_RECENTS
                    else -> return false to "tasto ignoto"
                }
                val done = performGlobalAction(action)
                done to if (done) "tasto eseguito" else "tasto rifiutato"
            }
            "open_app" -> {
                val pkg = payload.optString("package")
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launch == null) {
                    false to "app non trovata"
                } else {
                    startActivity(launch)
                    true to "app aperta"
                }
            }
            "list_apps" -> {
                val mains = packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                )
                val names = mains.take(200).mapNotNull {
                    it.activityInfo?.packageName
                }.distinct().sorted()
                true to names.joinToString(",").take(1800)
            }
            "dump_tree" -> {
                val root = rootInActiveWindow ?: return false to "nessuna finestra attiva"
                true to formatUiTree(collectUiTree(root))
            }
            else -> false to "kind non supportato"
        }
    }

    private suspend fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1, y)
        }
        return dispatchGestureSync(path, 50)
    }

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Int): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatchGestureSync(path, ms.coerceIn(50, 5000))
    }

    private suspend fun dispatchGestureSync(path: Path, ms: Int): Boolean {
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val stroke = GestureDescription.StrokeDescription(path, 0, ms.toLong())
        val dispatched = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    done.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done.complete(false)
                }
            },
            null
        )
        if (!dispatched) return false
        return runCatching {
            kotlinx.coroutines.withTimeout(10_000) { done.await() }
        }.getOrDefault(false)
    }

    private fun showNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("hermes_phone", "Controllo telefono", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val stop = PendingIntent.getService(
            this, 7, Intent(this, HermesPhoneService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, "hermes_phone")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Hermes controllo telefono")
            .setContentText(text)
            .addAction(0, "STOP", stop)
            .setOngoing(true)
            .build()
        manager.notify(PHONE_NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_STOP = "com.nemoclaw.chat.phone.STOP"
        const val PHONE_NOTIFICATION_ID = 8645
    }
}

private suspend fun postResult(settings: AppSettings, secret: String, id: String, ok: Boolean, result: String) {
    val base = resolveHermesUrl(settings, "/v1/hub/phone/commands/$id/result")
    com.nemoclaw.chat.postJson(
        base,
        JSONObject().put("ok", ok).put("result", result.take(1800)),
        secret,
        allowCompatAuth = false
    )
}
