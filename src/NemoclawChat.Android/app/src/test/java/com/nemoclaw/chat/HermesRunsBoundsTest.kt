package com.nemoclaw.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesRunsBoundsTest {
    @Test
    fun `run SSE segnala evento oltre il limite e non invia il frammento parziale`() = runBlocking {
        val line = "x".repeat(240 * 1024)
        val body = buildString {
            repeat(9) { append("data: ").append(line).append('\n') }
            append('\n')
        }.toByteArray()
        LocalHttpTestServer(body, contentType = "text/event-stream").use { server ->
            val delivered = mutableListOf<Pair<String?, String>>()

            collectRunSseEvents(server.url("/v1/runs/run-1/events"), null) { type, data ->
                if (type != "connected") delivered += type to data
            }

            assertEquals(1, delivered.size)
            assertEquals("error", delivered.single().first)
            assertTrue(delivered.single().second.contains("limite", ignoreCase = true))
            assertEquals("GET", server.awaitRequest().method)
        }
    }
}
