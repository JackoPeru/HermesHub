package com.nemoclaw.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal object HermesStreamRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
