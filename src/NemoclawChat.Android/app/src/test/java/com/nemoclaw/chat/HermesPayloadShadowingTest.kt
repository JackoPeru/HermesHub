package com.nemoclaw.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix shadowing: approval_id/request_id/id letti separatamente,
 * prima non-blank vince (chiave presente ma blank non nasconde le altre).
 */
class HermesPayloadShadowingTest {

    @Test
    fun blankApprovalIdDoesNotHideRequestId() {
        val body = JSONObject()
            .put("run_id", "run_1")
            .put("approval", JSONObject()
                .put("approval_id", "")
                .put("request_id", "rq_1")
                .put("tool", "exec_command")
                .put("choices", org.json.JSONArray(listOf("once", "deny"))))
            .toString()
        val parsed = parseRunApprovalPayload(body, "run_1")
        assertNotNull(parsed)
        val safe = parsed ?: return
        assertEquals("rq_1", safe.requestId)
        assertTrue(safe.approvalId.isNotBlank())
    }

    @Test
    fun onlyLegacyIdIsAccepted() {
        val body = JSONObject()
            .put("run_id", "run_2")
            .put("approval", JSONObject()
                .put("id", "legacy_9")
                .put("tool", "exec_command"))
            .toString()
        val parsed = parseRunApprovalPayload(body, "run_2")
        assertNotNull(parsed)
        val safe = parsed ?: return
        assertTrue(safe.approvalId.isNotBlank() || safe.requestId.isNotBlank())
    }

    @Test
    fun distinctIdsArePreserved() {
        val body = JSONObject()
            .put("run_id", "run_3")
            .put("approval", JSONObject()
                .put("approval_id", "ap_1")
                .put("request_id", "rq_1")
                .put("tool", "exec_command")
                .put("choices", org.json.JSONArray(listOf("once", "deny"))))
            .toString()
        val parsed = parseRunApprovalPayload(body, "run_3")
        assertNotNull(parsed)
        val safe = parsed ?: return
        assertEquals("ap_1", safe.approvalId)
        assertEquals("rq_1", safe.requestId)
    }

    @Test
    fun emptyApprovalIsRejected() {
        assertNull(parseRunApprovalPayload("""{"approval":{}}""", "r"))
        assertNull(parseRunApprovalPayload("""{"status":"running"}""", "r"))
        assertNull(parseRunApprovalPayload("not json", "r"))
    }

    @Test
    fun nestedPayloadApprovalKeepsFirstNonBlank() {
        val inner = JSONObject()
            .put("approval_id", "   ")
            .put("request_id", "rq_nested")
            .put("id", "id_fallback")
        val parsed = parseHermesApprovalRequest(inner)
        assertEquals("rq_nested", parsed.requestId)
        assertTrue(parsed.approvalId.isNotBlank())
    }
}
