package com.nemoclaw.chat

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration

/**
 * Application dell'app: inoltra la pressione memoria alla cache bitmap
 * condivisa, cosi il processo sopravvive invece di farsi killare (LMK)
 * con stream/coda non persistiti dentro.
 */
internal class HermesApp : Application(), ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching { BitmapImageLoader.onTrimMemory(level) }
    }
}
