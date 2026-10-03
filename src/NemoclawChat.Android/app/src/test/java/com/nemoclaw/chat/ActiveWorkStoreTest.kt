package com.nemoclaw.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Binding lavoro-background e derivazione stato (JVM pura, niente Android).
 * La persistenza SharedPreferences resta nel codice production ma fuori dai test;
 * qui si coprono codec, decisioni e parsing approval.
 */
class ActiveWorkStoreTest {

    @Test
    fun bindingRoundtripPreservesAllFields() {
        val binding = ActiveWorkBinding(
            conversationId = "conv_1",
            runId = "run_abc",
            sessionId = "sess_1",
            serverSessionId = "srv_1",
            goal = "costruisci il sito",
            startedAtMs = 1700000000000L
        )
        val decoded = decodeActiveWorkBinding(encodeActiveWorkBinding(binding))
        assertEquals(binding, decoded)
    }

    @Test
    fun decodeRejectsMissingKeys() {
        assertNull(decodeActiveWorkBinding(null))
        assertNull(decodeActiveWorkBinding(JSONObject().put("runId", "r")))
        assertNull(decodeActiveWorkBinding(JSONObject().put("conversationId", "c")))
        assertNull(decodeActiveWorkBinding(JSONObject()))
    }

    @Test
    fun decodeToleratesPartialObjects() {
        val decoded = decodeActiveWorkBinding(
            JSONObject().put("conversationId", "c").put("runId", "r"))
        assertNotNull(decoded)
        assertEquals("c", decoded!!.conversationId)
        assertEquals("r", decoded.runId)
        assertEquals(null, decoded.sessionId)
        assertEquals("", decoded.goal)
    }

    @Test
    fun stateMappingCoversAllRunStatuses() {
        assertEquals(BackgroundWorkState.ACTIVE, backgroundWorkStateFromRun(HermesRunInfo("r", HermesRunStatus.QUEUED), false))
        assertEquals(BackgroundWorkState.ACTIVE, backgroundWorkStateFromRun(HermesRunInfo("r", HermesRunStatus.RUNNING), false))
        assertEquals(BackgroundWorkState.DONE_COMPLETED, backgroundWorkStateFromRun(HermesRunInfo("r", HermesRunStatus.COMPLETED), false))
        assertEquals(BackgroundWorkState.DONE_FAILED, backgroundWorkStateFromRun(HermesRunInfo("r", HermesRunStatus.FAILED), false))
        assertEquals(BackgroundWorkState.DONE_CANCELLED, backgroundWorkStateFromRun(HermesRunInfo("r", HermesRunStatus.CANCELLED), false))
        assertEquals(BackgroundWorkState.UNKNOWN, backgroundWorkStateFromRun(HermesRunInfo("r", HermesRunStatus.UNKNOWN), false))
        assertEquals(BackgroundWorkState.UNKNOWN, backgroundWorkStateFromRun(null, false))
    }

    @Test
    fun approvalPayloadForcesWaitingState() {
        // waiting_for_approval non e' nell'enum wire: il payload decide.
        assertEquals(
            BackgroundWorkState.WAITING_FOR_APPROVAL,
            backgroundWorkStateFromRun(HermesRunInfo("r", HermesRunStatus.UNKNOWN), true)
        )
        assertEquals(
            BackgroundWorkState.WAITING_FOR_APPROVAL,
            backgroundWorkStateFromRun(HermesRunInfo("r", HermesRunStatus.RUNNING), true)
        )
    }

    @Test
    fun parseApprovalFromNestedPayload() {
        val body = JSONObject()
            .put("run_id", "run_1")
            .put("status", "waiting_for_approval")
            .put("approval", JSONObject()
                .put("approval_id", "ap_1")
                .put("request_id", "rq_1")
                .put("tool", "exec_command")
                .put("command", "rm -rf /tmp/x")
                .put("choices", org.json.JSONArray(listOf("once", "deny"))))
            .toString()
        val parsed = parseRunApprovalPayload(body, "run_1")
        assertNotNull(parsed)
        assertEquals("ap_1", parsed!!.approvalId)
        assertEquals("rq_1", parsed.requestId)
        assertEquals("exec_command", parsed.tool)
        assertTrue(parsed.choices.contains("once"))
    }

    @Test
    fun parseApprovalRejectsGarbage() {
        assertNull(parseRunApprovalPayload("not json", "r"))
        assertNull(parseRunApprovalPayload("""{"status":"running"}""", "r"))
        assertNull(parseRunApprovalPayload("""{"approval":{}}""", "r"))
    }

    @Test
    fun summaryUsesGoalOrFallback() {
        assertTrue(backgroundWorkSummary(ActiveWorkBinding("c", "r", goal = "")).contains("background"))
        val withGoal = backgroundWorkSummary(ActiveWorkBinding("c", "r", goal = "fai X"))
        assertTrue(withGoal.contains("fai X"))
        assertFalse(backgroundWorkSummary(ActiveWorkBinding("c", "r", goal = "x".repeat(200))).length > 140)
    }

    @Test
    fun autoApproveOffNeverApproves() {
        assertNull(pickAutoApprovalChoice(listOf("once", "session", "always", "deny"), "off"))
        assertNull(pickAutoApprovalChoice(listOf("once", "session", "always", "deny"), ""))
        assertNull(pickAutoApprovalChoice(listOf("once", "session", "always", "deny"), "banana"))
    }

    @Test
    fun autoApprovePrefersConfiguredLevel() {
        val full = listOf("once", "session", "always", "deny")
        assertEquals("session", pickAutoApprovalChoice(full, "session"))
        assertEquals("always", pickAutoApprovalChoice(full, "always"))
    }

    @Test
    fun autoApproveFallsBackWithoutDeny() {
        assertEquals("once", pickAutoApprovalChoice(listOf("once", "deny"), "always"))
        assertEquals("once", pickAutoApprovalChoice(listOf("once", "deny"), "session"))
        assertNull(pickAutoApprovalChoice(listOf("deny"), "always"))
        assertNull(pickAutoApprovalChoice(emptyList(), "session"))
        assertNull(pickAutoApprovalChoice(listOf("mystery"), "always"))
    }
}
