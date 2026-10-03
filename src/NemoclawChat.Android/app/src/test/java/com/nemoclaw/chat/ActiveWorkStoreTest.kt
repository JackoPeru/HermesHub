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

    @Test
    fun resolveAutoApprovePrefersBotOverGlobal() {        val bots = mapOf("coder" to "always")
        assertEquals("always", resolveAutoApproveMode("coder", bots, "off"))
        assertEquals("off", resolveAutoApproveMode("other", bots, "off"))
        assertEquals("session", resolveAutoApproveMode("other", bots, "session"))
        assertEquals("session", resolveAutoApproveMode(null, bots, "session"))
        assertEquals("off", resolveAutoApproveMode("", bots, "off"))
        assertEquals("always", resolveAutoApproveMode("", bots, "always"))
        assertEquals("always", resolveAutoApproveMode("coder", bots, "banana"))
    }

    @Test
    fun screenWsUrlBuildsFromManagerBase() {
        assertEquals(
            "ws://h:8643/display/ws?display_ticket=T",
            buildScreenWsUrl("http://h:8643", "T")
        )
        assertEquals(
            "wss://h:8643/display/ws?display_ticket=T",
            buildScreenWsUrl("https://h:8643/", "T")
        )
        assertNull(buildScreenWsUrl("", "T"))
        assertNull(buildScreenWsUrl("http://h:8643", ""))
        assertNull(buildScreenWsUrl("notaurl", "T"))
    }

    @Test
    fun screenViewerUrlEncodesWs() {
        val url = buildScreenViewerUrl("ws://h:8643/display/ws?display_ticket=T", true)
        assertTrue(url.startsWith("https://appassets.androidplatform.net/assets/novnc/viewer.html?url="))
        assertTrue(url.contains("ws%3A%2F%2Fh%3A8643"))
        assertTrue(url.endsWith("&viewonly=1"))
        assertTrue(buildScreenViewerUrl("ws://h/display/ws?display_ticket=T", false).endsWith("&viewonly=0"))
    }

    @Test
    fun managerStatusErrorsAreExplicit() {
        assertTrue(managerStatusErrorMessage(401, "x").contains("401"))
        assertTrue(managerStatusErrorMessage(403, "x").contains("403"))
        assertTrue(managerStatusErrorMessage(0, "timeout").contains("timeout"))
        val long = "e".repeat(300)
        assertTrue(managerStatusErrorMessage(500, long).contains("500"))
        assertTrue(managerStatusErrorMessage(500, long).length < long.length)
    }

    @Test
    fun managerModeErrorsAreExplicit() {
        assertTrue(managerModeErrorMessage(401, "x").contains("401"))
        assertTrue(managerModeErrorMessage(0, "").contains("non raggiungibile"))
        assertTrue(managerModeErrorMessage(500, "boom").contains("500"))
        assertTrue(managerModeErrorMessage(500, "boom").contains("boom"))
    }
}
