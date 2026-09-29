package com.nemoclaw.chat

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract di cancellazione chat (test JVM, nessuna dipendenza Android).
 *
 * Sorgenti mappate:
 * - features/chat/ChatFeature.kt:1045-1059 -> `state.activeStreamJob?.cancel()` + POST
 *   `/v1/runs/{id}/stop` quando `activeRunId` e' noto; la cancel locale da sola non basta.
 * - ChatStream.kt:1165 -> `awaitClose { call.cancel() }`: la cancel del flow cancella la call OkHttp.
 * - ChatStream.kt:2067 -> `invokeOnCancellation { call.cancel() }`: la cancel del job cancella
 *   anche le richieste non-SSE (`executeCancellableRequest`).
 * - ChatStream.kt:1032 -> `ensureActive()`: il parser e' cooperativo, nessun evento dopo cancel.
 * - ChatFeature.kt:912-925 -> `CancellationException -> interrupted = true`; il `finally`
 *   salva una sola snapshot finale "Interrotto", nessun checkpoint/salvataggio tardivo dopo.
 */
class ChatCancellationContractTest {

    private class FakeCancellable(val name: String) {
        private val cancelled = AtomicBoolean(false)
        fun cancel() = cancelled.set(true)
        fun isCancelled(): Boolean = cancelled.get()
    }

    /**
     * Modello minimo della sessione di stream: rete + parser + polling condividono
     * il destino del job di stream. Dopo [cancel] ogni evento/checkpoint/salvataggio
     * tardivo viene scartato; resta un solo finalize "interrotto".
     */
    private class StreamSessionContract {
        val networkCall = FakeCancellable("network")
        val parser = FakeCancellable("parser")
        val polling = FakeCancellable("polling")
        private val cancelled = AtomicBoolean(false)
        var checkpointSaves = 0
            private set
        var lateSaves = 0
            private set
        var finalSnapshots = 0
            private set

        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            networkCall.cancel()
            parser.cancel()
            polling.cancel()
        }

        fun isCancelled(): Boolean = cancelled.get()

        /** Evento parser arrivato dopo la cancel: deve essere scartato. */
        fun onParserEvent(): Boolean = !cancelled.get()

        /** Checkpoint intermedio stile `collectFlow`: vietato dopo cancel. */
        fun checkpoint(): Boolean {
            if (cancelled.get()) return false
            checkpointSaves++
            return true
        }

        /** Salvataggio tardivo (rete/parser/polling in ritardo): vietato dopo cancel. */
        fun lateSave(): Boolean {
            if (cancelled.get()) return false
            lateSaves++
            return true
        }

        /** Unica snapshot finale "Interrotto" del `finally`: ammessa una sola volta. */
        fun finalizeInterrupted(): Boolean {
            if (finalSnapshots >= 1) return false
            finalSnapshots++
            return true
        }
    }

    @Test
    fun cancelCancelsNetworkParserAndPollingTogether() {
        val session = StreamSessionContract()

        assertTrue(session.checkpoint())

        session.cancel()

        assertTrue(session.isCancelled())
        assertTrue(session.networkCall.isCancelled())
        assertTrue(session.parser.isCancelled())
        assertTrue(session.polling.isCancelled())
    }

    @Test
    fun eventsAfterCancelAreDroppedWithoutLateSave() {
        val session = StreamSessionContract()
        session.cancel()

        assertFalse(session.onParserEvent())
        assertFalse(session.checkpoint())
        assertFalse(session.lateSave())

        assertEquals(0, session.checkpointSaves)
        assertEquals(0, session.lateSaves)
    }

    @Test
    fun checkpointsStopAfterCancelButSingleFinalSnapshotRemains() {
        val session = StreamSessionContract()
        assertTrue(session.checkpoint())

        session.cancel()

        assertFalse(session.checkpoint())
        assertTrue(session.finalizeInterrupted())
        // Nessun secondo salvataggio finale tardivo.
        assertFalse(session.finalizeInterrupted())
        assertEquals(1, session.checkpointSaves)
        assertEquals(1, session.finalSnapshots)
        assertEquals(0, session.lateSaves)
    }

    @Test
    fun jobCancelPropagatesToStreamChildren() = runBlocking {
        val parent = Job()
        val networkReleased = AtomicBoolean(false)
        val parserReleased = AtomicBoolean(false)

        val networkChild = launch(parent, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                networkReleased.set(true)
                throw e
            }
        }
        val parserChild = launch(parent, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                parserReleased.set(true)
                throw e
            }
        }

        parent.cancel()
        runCatching { parent.join() }

        assertTrue(networkChild.isCancelled)
        assertTrue(parserChild.isCancelled)
        assertTrue(networkReleased.get())
        assertTrue(parserReleased.get())
    }
}
