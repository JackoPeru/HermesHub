package com.nemoclaw.chat.jarvis

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JarvisViewTransitionTest {
    @Test
    fun `pausa locale e svuota i frame prima della PATCH e resta in pausa su errore`() = runBlocking {
        var state = JarvisUiState(phase = JarvisPhase.ACTIVE, active = true, sessionId = "s1", visionActive = true)
        val queue = Channel<SampledFrame>(capacity = 2)
        queue.trySend(SampledFrame(byteArrayOf(1), 1L, 1L))
        val order = mutableListOf<String>()
        val remotePatch = CompletableDeferred<Unit>()
        val frameSource = RecordingFrameSource(order)

        val job = launch {
            applyJarvisVisionTransition(
                paused = true,
                currentState = { state },
                updateState = { state = it },
                isCurrentSession = { state.active && state.sessionId == "s1" },
                pauseSource = { frameSource.pause() },
                resumeSource = { frameSource.resume() },
                stopUploader = { order += "stop-uploader" },
                startUploader = { order += "start-uploader" },
                clearPendingFrames = {
                    order += "clear"
                    while (queue.tryReceive().isSuccess) Unit
                },
                patchGateway = {
                    order += "patch"
                    remotePatch.await()
                },
                onError = { state = state.copy(error = it.message) }
            )
        }
        while ("patch" !in order) yield()

        assertEquals(listOf("pause", "stop-uploader", "clear", "patch"), order)
        assertEquals(JarvisPhase.PAUSED, state.phase)
        assertFalse(state.visionActive)
        assertFalse(queue.tryReceive().isSuccess)

        remotePatch.completeExceptionally(IllegalStateException("gateway offline"))
        job.join()
        assertEquals(JarvisPhase.PAUSED, state.phase)
        assertFalse(state.visionActive)
        assertEquals("gateway offline", state.error)
    }

    @Test
    fun `resume non riattiva camera se la sessione termina durante la PATCH`() = runBlocking {
        var state = JarvisUiState(phase = JarvisPhase.PAUSED, active = true, sessionId = "s1", visionActive = false)
        val order = mutableListOf<String>()
        val remotePatch = CompletableDeferred<Unit>()
        val frameSource = RecordingFrameSource(order)
        val job = launch {
            applyJarvisVisionTransition(
                paused = false,
                currentState = { state },
                updateState = { state = it },
                isCurrentSession = { state.active && state.sessionId == "s1" },
                pauseSource = { frameSource.pause() },
                resumeSource = { frameSource.resume() },
                stopUploader = { order += "stop-uploader" },
                startUploader = { order += "start-uploader" },
                clearPendingFrames = { order += "clear" },
                patchGateway = { order += "patch"; remotePatch.await() },
                onError = { state = state.copy(error = it.message) }
            )
        }
        while ("patch" !in order) yield()
        state = state.copy(active = false, phase = JarvisPhase.STOPPING)
        remotePatch.complete(Unit)
        job.join()

        assertEquals(listOf("patch"), order)
        assertFalse(state.visionActive)
        assertEquals(0, frameSource.resumeCalls)
    }

    private class RecordingFrameSource(private val order: MutableList<String>) : JarvisFrameSource {
        override val label: String = "fake"
        var resumeCalls = 0
        override suspend fun start(onFrame: suspend (ByteArray, Long) -> Unit, onError: (Throwable) -> Unit) = Unit
        override suspend fun pause() { order += "pause" }
        override suspend fun resume() { resumeCalls++; order += "resume" }
        override suspend fun stop() = Unit
    }
}
