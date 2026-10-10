package com.nemoclaw.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal object HermesStreamRuntime {
    /**
     * Scope UI per i collector degli stream (aggiornano SnapshotState e
     * composizione: devono girare sul Main thread).
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Scope IO per lavoro background fire-and-forget (POST di rete, download,
     * sync archivi, listener infiniti): MAI sul Main, niente ANR. I usi
     * toccano solo rete/storage e fanno hop esplicito su Main per Toast/UI.
     */
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
