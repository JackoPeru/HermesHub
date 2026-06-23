package com.nemoclaw.chat

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
