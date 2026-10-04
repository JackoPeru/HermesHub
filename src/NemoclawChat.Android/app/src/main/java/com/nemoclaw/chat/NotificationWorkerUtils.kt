package com.nemoclaw.chat

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

const val HERMES_NOTIFICATION_WORK = "hermes_notification_work"
const val HERMES_NOTIFICATION_CHANNEL = "hermes_hub_notifications"

fun scheduleHermesNotificationWorker(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    val requestBuilder = PeriodicWorkRequestBuilder<HermesNotificationWorker>(15, TimeUnit.MINUTES)
        .addTag(HERMES_NOTIFICATION_WORK)
        .setConstraints(constraints)
        .setBackoffCriteria(
            androidx.work.BackoffPolicy.LINEAR,
            5,
            TimeUnit.MINUTES
        )


    val request = requestBuilder.build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        HERMES_NOTIFICATION_WORK,
        ExistingPeriodicWorkPolicy.UPDATE,
        request
    )
}

/**
 * Fallback one-shot per Doze: quando il polling del service rileva
 * PowerManager.isDeviceIdleMode, registra questo lavoro così il controllo
 * della run riprende anche con restrizioni Doze. WorkManager è già dipendenza
 * (work-runtime-ktx), nessun TODO necessario.
 */
fun scheduleHermesRunFallbackWorker(context: Context, runId: String) {
    val cleanRunId = runId.trim()
    if (cleanRunId.isEmpty()) return
    val input = Data.Builder().putString("run_id", cleanRunId).build()
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val request = OneTimeWorkRequestBuilder<HermesNotificationWorker>()
        .addTag("hermes_run_fallback_$cleanRunId")
        .setConstraints(constraints)
        .setInputData(input)
        .build()
    runCatching {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "hermes_run_fallback_$cleanRunId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
