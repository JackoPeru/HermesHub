package com.nemoclaw.chat

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Application dell'app: inoltra la pressione memoria alla cache bitmap
 * condivisa, cosi il processo sopravvive invece di farsi killare (LMK)
 * con stream/coda non persistiti dentro.
 */
internal class HermesApp : Application(), ComponentCallbacks2 {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(ForegroundTracker)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching { BitmapImageLoader.onTrimMemory(level) }
    }
}

/**
 * Conta le activity visibili: serve al worker notifiche per NON suonare
 * mentre l'utente e dentro l'app (vedrebbe comunque tutto in chat, e le
 * notifiche dei propri prompt appena inviati sembrano un eco rotto).
 */
private object ForegroundTracker : Application.ActivityLifecycleCallbacks {
    private val started = AtomicInteger(0)

    fun isForeground(): Boolean = started.get() > 0

    override fun onActivityStarted(activity: Activity) {
        started.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        started.decrementAndGet()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

internal fun isAppForeground(): Boolean = ForegroundTracker.isForeground()
