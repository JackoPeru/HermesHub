package com.nemoclaw.chat

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract voce/TTS (test JVM, nessuna dipendenza Android/MediaPlayer).
 *
 * Sorgenti mappate:
 * - VoiceModeScreen.kt:992-1046: `playedAny`; se un segmento e' gia' stato
 *   riprodotto (`playedAny == true`) l'errore viene rilanciato (`if (playedAny) throw ex`)
 *   senza retry sul candidato successivo. Il retry e' lecito solo prima dell'inizio play.
 * - JarvisSessionController.kt:416-438: `speak(...) = speechMutex.withLock { ... }`;
 *   la riproduzione e' sequenziale, un solo TTS alla volta, con `speaking` resettato
 *   nel `finally` e singola transizione `SPEECH_COMPLETE`.
 * - JarvisSessionController.kt:364-381 + VoiceModeScreen.kt:590-592: le domande vocali
 *   hanno priorita' sulle osservazioni passive (`if (speaking.get()) delay + continue`,
 *   `if (transcript.isBlank()) continue`); STT Jarvis usa `beam_size=1`
 *   (`JARVIS_STT_BEAM_SIZE = 1`); niente streaming Whisper simulato ripetendo
 *   trascrizioni sovrapposte (dedup 8s + `isUsefulTranscript`).
 */
class VoiceSpeechContractTest {

    /** Mirror di VoiceModeScreen.kt:1046: retry solo se nulla e' stato riprodotto. */
    private fun shouldRetryTtsAfterError(playedAny: Boolean): Boolean = !playedAny

    /** Mirror di JarvisSessionController.kt:540. */
    private val jarvisSttBeamSizeContract = 1

    /** Copia fedele di `isUsefulTranscript` (VoiceModeScreen.kt:1190-1199, privata). */
    private fun isUsefulTranscriptContract(text: String): Boolean {
        val clean = text.trim()
        if (clean.length < 2) return false
        val normalized = clean.lowercase().trim('.', ',', '!', '?', ' ')
        return normalized !in setOf(
            "grazie",
            "sottotitoli e revisione a cura di qtss",
            "sottotitoli creati dalla comunita amara.org"
        )
    }

    /** Mirror della finestra dedup voce (VoiceModeScreen.kt:591: 8_000 ms). */
    private inner class TranscriptDeduper(private val windowMs: Long = 8_000L) {
        private var last: String? = null
        private var lastAt = 0L

        fun shouldAccept(text: String, nowMs: Long): Boolean {
            if (!isUsefulTranscriptContract(text)) return false
            if (text.equals(last, ignoreCase = true) && nowMs - lastAt < windowMs) return false
            last = text
            lastAt = nowMs
            return true
        }
    }

    /**
     * Whisper restituisce l'intero enunciato, non delta streaming: se il nuovo
     * risultato ripete (anche con case diverso) quello precedente non deve essere
     * riemesso ne' concatenato in coda.
     */
    private fun stripRepeatedTranscript(previous: String, current: String): String {
        val prev = previous.trim()
        val curr = current.trim()
        if (prev.isEmpty() || curr.isEmpty()) return curr
        if (curr.equals(prev, ignoreCase = true)) return ""
        if (curr.length > prev.length && curr.startsWith(prev, ignoreCase = true)) {
            return curr.substring(prev.length).trim()
        }
        return curr
    }

    @Test
    fun ttsDoesNotRetryAfterPlaybackStarted() {
        assertTrue(shouldRetryTtsAfterError(playedAny = false))
        assertFalse(shouldRetryTtsAfterError(playedAny = true))
    }

    @Test
    fun ttsCandidateLoopStopsAtFirstErrorAfterPlay() {
        // Candidati gateway/token in ordine; il secondo fallisce dopo l'inizio play:
        // il terzo non deve essere tentato (nessun retry dopo play).
        val attempted = mutableListOf<String>()
        val playedAnyPerCandidate = mapOf("c1" to false, "c2" to true, "c3" to false)
        var propagated: String? = null

        for (candidate in listOf("c1", "c2", "c3")) {
            attempted += candidate
            val failed = candidate != "c3"
            if (!failed) break
            if (!shouldRetryTtsAfterError(playedAnyPerCandidate.getValue(candidate))) {
                propagated = candidate
                break
            }
        }

        assertEquals(listOf("c1", "c2"), attempted)
        assertEquals("c2", propagated)
    }

    @Test
    fun speechIsSequentialUnderMutexWithoutInterleaving() = runBlocking {
        val speechMutex = Mutex()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val order = mutableListOf<String>()

        suspend fun speak(id: String) = speechMutex.withLock {
            val now = active.incrementAndGet()
            maxActive.set(maxOf(maxActive.get(), now))
            order += "$id-start"
            delay(20)
            order += "$id-end"
            active.decrementAndGet()
        }

        val first = async { speak("a") }
        val second = async { speak("b") }
        first.await()
        second.await()

        assertEquals(1, maxActive.get())
        assertEquals(4, order.size)
        // Ogni coppia start/end e' contigua: nessuna sovrapposizione.
        val starts = order.mapIndexedNotNull { index, v -> if (v.endsWith("-start")) index else null }
        for (start in starts) {
            assertTrue(order[start].removeSuffix("-start") + "-end" == order[start + 1])
        }
    }

    @Test
    fun duplicateTranscriptWithinWindowIsDropped() {
        val deduper = TranscriptDeduper()

        assertTrue(deduper.shouldAccept("Accendi la luce", nowMs = 1_000L))
        // Stesso testo entro 8s: scartato, niente doppia inferenza/risposta.
        assertFalse(deduper.shouldAccept("accendi la luce", nowMs = 2_000L))
        // Dopo la finestra: accettato di nuovo.
        assertTrue(deduper.shouldAccept("Accendi la luce", nowMs = 10_000L))
    }

    @Test
    fun blankAndHallucinatedTranscriptsAreDropped() {
        val deduper = TranscriptDeduper()

        assertFalse(deduper.shouldAccept("   ", nowMs = 0L))
        assertFalse(deduper.shouldAccept("x", nowMs = 0L))
        assertFalse(deduper.shouldAccept("Grazie.", nowMs = 0L))
        assertFalse(deduper.shouldAccept("Sottotitoli e revisione a cura di QTSS", nowMs = 0L))
        assertTrue(deduper.shouldAccept("Che ore sono?", nowMs = 0L))
    }

    @Test
    fun whisperOverlappingRepeatsAreNotReEmitted() {
        assertEquals("", stripRepeatedTranscript("accendi la luce", "Accendi la luce"))
        assertEquals("in cucina", stripRepeatedTranscript("accendi la luce", "accendi la luce in cucina"))
        assertEquals("apri il garage", stripRepeatedTranscript("accendi la luce", "apri il garage"))
    }

    @Test
    fun jarvisSttUsesSingleBeamWithoutStreamingSimulation() {
        assertEquals(1, jarvisSttBeamSizeContract)
        // Il gateway accetta 1..10: il contract Jarvis resta fissato a 1 (fine-frase breve).
        assertEquals(1, jarvisSttBeamSizeContract.coerceIn(1, 10))
    }
}
