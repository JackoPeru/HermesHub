package com.nemoclaw.chat.jarvis

import android.content.Context

internal interface JarvisFrameSource {
    val label: String
    suspend fun start(
        onFrame: suspend (ByteArray, Long) -> Unit,
        onError: (Throwable) -> Unit
    )
    suspend fun pause()
    suspend fun resume()
    suspend fun stop()
}

internal object JarvisFrameSourceFactory {
    fun create(context: Context, preferPhoneDebug: Boolean): JarvisFrameSource {
        if (preferPhoneDebug) return PhoneCameraFrameSource(context.applicationContext)
        val meta = runCatching {
            val type = Class.forName("com.nemoclaw.chat.jarvis.meta.MetaWearablesFrameSource")
            val constructor = type.getConstructor(Context::class.java)
            constructor.newInstance(context.applicationContext) as JarvisFrameSource
        }.getOrNull()
        return meta ?: UnavailableMetaFrameSource()
    }
}

private class UnavailableMetaFrameSource : JarvisFrameSource {
    override val label: String = "Meta DAT non incluso"
    override suspend fun start(
        onFrame: suspend (ByteArray, Long) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        error("Meta DAT non incluso. Usa una build -PenableMetaDat=true oppure il fallback telefono debug.")
    }
    override suspend fun pause() = Unit
    override suspend fun resume() = Unit
    override suspend fun stop() = Unit
}
