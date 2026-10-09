package com.nemoclaw.chat

import com.nemoclaw.chat.features.characters.characterJobLabel
import com.nemoclaw.chat.features.characters.characterStatusLabel
import com.nemoclaw.chat.features.characters.parseCharacterJob
import com.nemoclaw.chat.features.characters.parseCharacterSummary
import com.nemoclaw.chat.features.characters.trainingPhaseLabel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CharactersTest {
    @Test
    fun statusLabels() {
        assertEquals("Bozza", characterStatusLabel("draft"))
        assertEquals("Pronto a creare", characterStatusLabel("ready_to_train"))
        assertEquals("Pronto", characterStatusLabel("ready"))
        assertEquals("Da migliorare", characterStatusLabel("needs_retrain"))
        assertEquals("Interrotto", characterStatusLabel("interrupted"))
        assertEquals("Sconosciuto", characterStatusLabel(""))
        assertEquals("Apprendimento", characterJobLabel("training"))
        assertEquals("Apprendimento identità 62%", trainingPhaseLabel(parseCharacterJob(JSONObject().put("id", "j").put("kind", "train").put("status", "training").put("progress", 0.62))))
    }

    @Test
    fun parseSummary() {
        val item = JSONObject()
            .put("id", "abc-123")
            .put("name", "Sofia")
            .put("status", "ready")
            .put("image_count", 32)
            .put("identity_score", 0.92)
            .put("recommended_engine", "lora")
            .put("default_mode", "auto")
        val parsed = parseCharacterSummary(item)!!
        assertEquals("abc-123", parsed.id)
        assertEquals("Sofia", parsed.name)
        assertEquals(32, parsed.imageCount)
        assertEquals(0.92, parsed.identityScore!!, 0.001)
        assertEquals("lora", parsed.recommendedEngine)
        assertNull(parseCharacterSummary(JSONObject().put("name", "x")))
    }

    @Test
    fun parseJobProgressClamped() {
        val job = parseCharacterJob(JSONObject().put("id", "j").put("kind", "train").put("status", "training").put("progress", 1.5))!!
        assertEquals(1.0, job.progress, 0.001)
        assertNull(parseCharacterJob(null))
        assertNull(parseCharacterJob(JSONObject()))
    }

    @Test
    fun trainingPhases() {
        fun job(kind: String, status: String, progress: Double) =
            parseCharacterJob(JSONObject().put("id", "j").put("kind", kind).put("status", status).put("progress", progress))!!
        assertEquals("Analisi foto 10%", trainingPhaseLabel(job("analyze", "preparing", 0.1)))
        assertEquals("Cache identità 10%", trainingPhaseLabel(job("train", "caching", 0.1)))
        assertEquals("Apprendimento identità 62%", trainingPhaseLabel(job("train", "training", 0.62)))
        assertEquals("Verifica coerenza 90%", trainingPhaseLabel(job("evaluate", "validating", 0.9)))
        assertEquals("Pronto", trainingPhaseLabel(null))
    }
}
