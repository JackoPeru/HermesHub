package com.nemoclaw.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val appContext = context.applicationContext
            runCatching { scheduleHermesNotificationWorker(appContext) }
            // Dopo il reboot i binding persistiti restano: prova a riagganciare le run.
            // Su Android 12+ l'avvio da background può essere negato: mai far fallire,
            // la riapertura dell'app riaggancia comunque dal binding.
            runCatching {
                val bindings = loadActiveWorkBindings(appContext)
                if (bindings.isNotEmpty()) {
                    Log.i(TAG, "ripristino ${bindings.size} binding dopo boot")
                    for (binding in bindings.values) {
                        runCatching { HermesWorkService.start(appContext, binding, null, false) }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
