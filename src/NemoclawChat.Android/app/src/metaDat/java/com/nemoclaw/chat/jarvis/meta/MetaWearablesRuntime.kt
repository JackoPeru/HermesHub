package com.nemoclaw.chat.jarvis.meta

import android.content.Context
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.WearablesError
import java.util.concurrent.atomic.AtomicBoolean

internal object MetaWearablesRuntime {
    private val initialized = AtomicBoolean(false)
    private val initializationLock = Any()

    fun initialize(context: Context) {
        if (initialized.get()) return
        synchronized(initializationLock) {
            if (initialized.get()) return
            val appContext = context.applicationContext
            Wearables.initialize(appContext)
                .onSuccess { initialized.set(true) }
                .onFailure { error, cause ->
                    if (error == WearablesError.ALREADY_INITIALIZED) {
                        initialized.set(true)
                    } else {
                        throw cause ?: IllegalStateException(error.getLocalizedDescription(appContext))
                    }
                }
        }
    }
}
