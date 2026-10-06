package com.nemoclaw.chat

import com.nemoclaw.chat.features.comfy.comfyDirectBase
import com.nemoclaw.chat.features.comfy.comfyMenuSubtitle
import com.nemoclaw.chat.features.comfy.comfyStateLabel
import com.nemoclaw.chat.features.comfy.parseComfyHistoryErrors
import com.nemoclaw.chat.features.comfy.parseComfyStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyStatusTest {
    private fun fullBody(): String = JSONObject()
        .put("current_state", "MEDIA_BUSY")
        .put("desired_mode", "AUTO")
        .put("current_job", "f11b125a3b47")
        .put("media_progress", 0.5)
        .put("queue_length", 2)
        .put("active_preset", "journey_video_preview")
        .put("active_media_model", "minimax_h3")
        .put("last_error", "")
        .put("hint", "media up")
        .put("llm_loaded", false)
        .toString()

    @Test
    fun parseFullStatus() {
        val status = parseComfyStatus(fullBody())!!
        assertTrue(status.reachable)
        assertEquals("MEDIA_BUSY", status.state)
        assertEquals("AUTO", status.desired)
        assertEquals("f11b125a3b47", status.jobId)
        assertEquals(0.5f, status.progress!!, 0.001f)
        assertEquals(2, status.queue)
        assertEquals("journey_video_preview", status.preset)
        assertEquals("minimax_h3", status.model)
    }

    @Test
    fun parseInvalidBodyIsNull() {
        assertNull(parseComfyStatus(""))
        assertNull(parseComfyStatus("non json"))
    }

    @Test
    fun parseProgressClamped() {
        val over = JSONObject().put("media_progress", 3.0).toString()
        assertEquals(1f, parseComfyStatus(over)!!.progress!!, 0.001f)
        val missing = JSONObject().put("current_state", "LLM_READY").toString()
        assertNull(parseComfyStatus(missing)!!.progress)
    }

    @Test
    fun stateLabelsItalian() {
        assertEquals("Sta generando", comfyStateLabel("MEDIA_BUSY"))
        assertEquals("LLM attivo (chat)", comfyStateLabel("LLM_READY"))
        assertEquals("Errore", comfyStateLabel("ERROR"))
        assertEquals("Sconosciuto", comfyStateLabel(""))
    }

    @Test
    fun subtitleBusyShowsProgressAndPreset() {
        val status = parseComfyStatus(fullBody())!!
        assertEquals("Sta generando · 50% · journey_video_preview", comfyMenuSubtitle(status))
    }

    @Test
    fun subtitleUnreachableShowsReason() {
        val status = parseComfyStatus(JSONObject().toString())!!
        assertEquals("Stato sconosciuto", comfyMenuSubtitle(null))
        assertEquals("Non raggiungibile", comfyMenuSubtitle(status.copy(reachable = false)))
    }

    @Test
    fun subtitleLlmReady() {
        val status = parseComfyStatus(
            JSONObject().put("current_state", "LLM_READY").put("desired_mode", "AUTO").toString()
        )!!
        assertEquals("LLM attivo · AUTO", comfyMenuSubtitle(status))
    }

    @Test
    fun historyErrorsExtractsFailed() {
        val body = JSONObject()
            .put("ok1", JSONObject().put("status", JSONObject().put("status_str", "success")))
            .put("bad1", JSONObject().put("status", JSONObject()
                .put("status_str", "error")
                .put("messages", org.json.JSONArray().put("OOM: VRAM esaurita"))))
            .toString()
        assertEquals("OOM: VRAM esaurita", parseComfyHistoryErrors(body))
    }

    @Test
    fun historyErrorsEmptyWhenClean() {
        assertNull(parseComfyHistoryErrors(JSONObject().toString()))
        assertNull(parseComfyHistoryErrors("non json"))
        assertNull(parseComfyHistoryErrors(JSONObject()
            .put("ok1", JSONObject().put("status", JSONObject().put("status_str", "success")))
            .toString()))
    }

    @Test
    fun directBaseSwapsPort() {
        assertEquals("http://192.168.1.6:8188", comfyDirectBase("http://192.168.1.6:8642/v1"))
        assertEquals("http://host:8188", comfyDirectBase("http://host"))
    }
}
