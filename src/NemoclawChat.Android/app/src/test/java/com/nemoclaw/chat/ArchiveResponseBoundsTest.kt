package com.nemoclaw.chat

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchiveResponseBoundsTest {
    private val body = ByteArray((MAX_JSON_RESPONSE_BYTES + 1024).toInt()) { 'x'.code.toByte() }

    @Test fun archiveImportAcceptsTheDocumentedArchiveBudget() {
        LocalHttpTestServer(body).use { server ->
            val response = executeJsonRequest(server.url("/v1/hub/conversations/import"), JSONObject(), "POST", null)
            assertEquals(200, response.first)
            assertEquals(body.size, response.second.length)
        }
    }

    @Test fun ordinaryPostStillRejectsLargeBodies() {
        LocalHttpTestServer(body).use { server ->
            assertThrows(IOException::class.java) {
                executeJsonRequest(server.url("/v1/runs"), JSONObject(), "POST", null)
            }
        }
    }

    @Test fun archiveTextInAQueryDoesNotIncreaseTheGetBudget() {
        LocalHttpTestServer(body).use { server ->
            assertThrows(IOException::class.java) {
                executeHttpGet(server.url("/v1/capabilities?hint=/v1/hub/conversations"), null)
            }
        }
    }
}
