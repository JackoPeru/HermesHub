package com.nemoclaw.chat.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisModelsTest {
    @Test
    fun `event parser preserves final SSE fields`() {
        val event = JarvisEvent.parse(
            """{"type":"assistant.speak","id":"7","text":"Controlla il connettore.","route":"reasoning","autonomous":true,"metrics":{"total_ms":123.9}}"""
        )
        assertEquals("7", event.id)
        assertEquals("assistant.speak", event.type)
        assertEquals("reasoning", event.route)
        assertEquals(123L, event.totalLatencyMs)
        assertTrue(event.autonomous)
    }

    @Test
    fun `event parser uses SSE metadata fallback`() {
        val event = JarvisEvent.parse("{}", eventId = "9", eventName = "session.ready")
        assertEquals("9", event.id)
        assertEquals("session.ready", event.type)
        assertFalse(event.autonomous)
    }

    @Test
    fun `event parser exposes reactor memory and situation`() {
        val event = JarvisEvent.parse(
            """{"type":"memory.summary","summary":"Montaggio in corso.","topic":"Scheda madre","open_loop":true,"situation":"Verificare il connettore"}"""
        )
        assertEquals("Montaggio in corso.", event.summary)
        assertEquals("Scheda madre", event.topic)
        assertEquals("Verificare il connettore", event.situation)
        assertTrue(event.openLoop)
    }

    @Test
    fun `initiative wire values are strict`() {
        assertEquals(JarvisInitiativeMode.PROACTIVE, JarvisInitiativeMode.fromWire("proactive"))
        assertEquals(JarvisInitiativeMode.ASSISTIVE, JarvisInitiativeMode.fromWire("unexpected"))
    }
}
