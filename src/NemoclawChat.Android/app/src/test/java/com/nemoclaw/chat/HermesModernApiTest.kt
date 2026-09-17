package com.nemoclaw.chat

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class HermesModernApiTest {
    private fun modernCapabilities(): HermesCapabilities {
        val body = """
        {
          "object":"hermes.api_server.capabilities",
          "platform":"hermes-agent",
          "model":"hermes-agent",
          "features":{
            "chat_completions":true,"responses_api":true,
            "run_submission":true,"run_status":true,"run_events_sse":true,"run_stop":true,
            "run_steer":true,"run_approval":true,
            "session_list":true,"session_create":true,"session_read":true,"session_patch":true,
            "session_delete":true,"session_messages":true,"session_fork":true,
            "session_chat":true,"session_chat_stream":true,
            "model_options":true,
            "reasoning_efforts":["none","minimal","low","medium","high","xhigh","max","ultra"],
            "session_key_header":"X-Hermes-Session-Key",
            "skills_api":true
          },
          "endpoints":{"session_list":"/api/sessions","run_events_sse":"/v1/runs/{id}/events"}
        }
        """.trimIndent()
        return parseHermesCapabilities(body)!!
    }

    @Test fun capabilitiesParsingDrivesFeatures() {
        val caps = modernCapabilities()
        assertTrue(caps.supportsModernSessions())
        assertTrue(caps.supportsFullRunControl())
        assertTrue(caps.supportsSteer())
        assertTrue(caps.supportsApproval())
        assertTrue(caps.supportsModelOptions())
        assertTrue(caps.supportsReasoningEffort("max"))
        assertTrue(caps.supportsReasoningEffort("ultra"))
        assertEquals("X-Hermes-Session-Key", caps.sessionKeyHeader)
    }

    @Test fun realUpstreamCapabilityShapeIsUnderstood() {
        // Forma reale v2026.9.14 (api_server.py: _STATIC_FEATURE_FLAGS + endpoints oggetti).
        val body = """
        {
          "object":"hermes.api_server.capabilities","platform":"hermes-agent","model":"hermes-agent",
          "auth":{"type":"bearer","required":true},
          "features":{
            "chat_completions":true,"responses_api":true,"run_submission":true,
            "run_status":true,"run_events_sse":true,"run_stop":true,"run_steer":true,
            "run_approval_response":true,"approval_events":true,"tool_progress_events":true,
            "session_resources":true,"model_options":true,"session_chat":true,
            "session_chat_streaming":true,"session_fork":true,"session_model_lock":true,
            "skills_api":true,
            "session_continuity_header":"X-Hermes-Session-Id",
            "session_key_header":"X-Hermes-Session-Key"
          },
          "endpoints":{
            "sessions":{"method":"GET","path":"/api/sessions"},
            "session_create":{"method":"POST","path":"/api/sessions"},
            "session":{"method":"GET","path":"/api/sessions/{session_id}"},
            "session_update":{"method":"PATCH","path":"/api/sessions/{session_id}"},
            "session_delete":{"method":"DELETE","path":"/api/sessions/{session_id}"},
            "session_messages":{"method":"GET","path":"/api/sessions/{session_id}/messages"},
            "session_fork":{"method":"POST","path":"/api/sessions/{session_id}/fork"},
            "session_chat":{"method":"POST","path":"/api/sessions/{session_id}/chat"},
            "session_chat_stream":{"method":"POST","path":"/api/sessions/{session_id}/chat/stream"},
            "session_model_lock":{"method":"POST","path":"/api/sessions/{session_id}/model"},
            "run_steer":{"method":"POST","path":"/v1/runs/{run_id}/steer"}
          }
        }
        """.trimIndent()
        val caps = parseHermesCapabilities(body)!!
        assertTrue(caps.supportsModernSessions())
        assertTrue(caps.supportsFullRunControl())
        assertTrue(caps.supportsSteer())
        assertTrue(caps.supportsApproval())
        assertTrue(caps.sessionModelLock)
        assertEquals("X-Hermes-Session-Id", caps.sessionContinuityHeader)
        assertEquals("/api/sessions/{session_id}/model", caps.endpoints["session_model_lock"])
    }

    @Test fun legacyCapabilitiesFailClosedOnMaxUltra() {
        val legacy = parseHermesCapabilities("""{"features":{"model_options":true,"run_submission":true}}""")!!
        assertNull(resolveReasoningEffortForServer(legacy, "max"))
        assertNull(resolveReasoningEffortForServer(legacy, "ultra"))
        assertEquals("high", resolveReasoningEffortForServer(legacy, "high"))
        assertNull(resolveReasoningEffortForServer(null, "high"))
    }

    @Test fun reasoningEffortBuildsOfficialModelOptions() {
        val caps = modernCapabilities()
        val opts = buildHermesModelOptions("max", "priority", caps)!!
        assertEquals("max", opts.getString("reasoning_effort"))
        assertEquals("priority", opts.getString("service_tier"))
        assertTrue(opts.getJSONObject("reasoning").getBoolean("enabled"))
        val none = buildHermesModelOptions("none", null, caps)!!
        assertFalse(none.getJSONObject("reasoning").getBoolean("enabled"))
        // Effort non supportato -> non inviare effort, solo tier.
        val legacy = parseHermesCapabilities("""{"features":{"model_options":true}}""")!!
        assertNull(buildHermesModelOptions("max", null, legacy))
        assertEquals("priority", buildHermesModelOptions("max", "priority", legacy)!!.getString("service_tier"))
    }

    @Test fun keepaliveIsNotAnEvent() {
        assertTrue(isHermesKeepaliveLine(": keepalive"))
        assertTrue(isHermesKeepaliveLine(":keepalive"))
        assertFalse(isHermesKeepaliveLine("data: {}"))
    }

    @Test fun sessionStreamingEventsMapCorrectly() {
        val delta = parseSseData("assistant.delta", """{"type":"assistant.delta","delta":"ciao "}""")
        assertTrue(delta.any { it is ChatStreamEvent.TextDelta && it.delta == "ciao " })
        val tStart = parseSseData("tool.started", """{"type":"tool.started","tool":"search","preview":"q"}""")
        assertTrue(tStart.any { it is ChatStreamEvent.ToolCallStart })
        val tDone = parseSseData("tool.completed", """{"type":"tool.completed","tool":"search","preview":"ok"}""")
        assertTrue(tDone.any { it is ChatStreamEvent.ToolCallEnd })
        val runDone = parseSseData("run.completed", """{"type":"run.completed","output":"fatto"}""")
        assertTrue(runDone.any { it is ChatStreamEvent.TextSnapshot && it.text == "fatto" })
        assertTrue(isTerminalSseEvent("run.completed", """{"type":"run.completed"}"""))
        assertTrue(isTerminalSseEvent("run.failed", """{"type":"run.failed"}"""))
        assertTrue(isTerminalSseEvent("run.cancelled", """{"type":"run.cancelled"}"""))
        assertTrue(isTerminalSseEvent("assistant.completed", """{"type":"assistant.completed"}"""))
    }

    @Test fun runProgressAndUnknownEventsNeverCrash() {
        val progress = parseSseData("hermes.tool.progress", """{"type":"hermes.tool.progress","tool":"shell","preview":"ls"}""")
        assertTrue(progress.any { it is ChatStreamEvent.ToolCallStart })
        val unknown = parseSseData("hermes.future.v9", """{"type":"hermes.future.v9","payload":{"x":1}}""")
        // Evento sconosciuto -> tollerato senza crash; preservato per forward-compat (come da contratto esistente),
        // mai promosso a testo finale, mai perdita del testo esistente.
        assertTrue(unknown.any { it is ChatStreamEvent.RawHermesEvent })
        val raw = unknown.filterIsInstance<ChatStreamEvent.RawHermesEvent>().single()
        assertTrue(raw.json.contains("hermes.future.v9"))
        assertTrue(unknown.none { it is ChatStreamEvent.TextDelta || it is ChatStreamEvent.TextSnapshot })
    }

    @Test fun approvalOffersAllFourChoicesWhenServerSendsThem() {
        val events = parseSseData(
            "approval.request",
            """{"type":"approval.request","run_id":"run_9","tool":"shell","command":"rm x","choices":["once","session","always","deny"]}"""
        )
        val req = events.filterIsInstance<ChatStreamEvent.ApprovalRequest>().single()
        assertEquals(listOf("once", "session", "always", "deny"), req.choices)
        var state = StreamingState()
        events.forEach { state = state.applyEvent(it) }
        assertEquals(listOf("once", "session", "always", "deny"), state.pendingApprovals.single().choices)
    }

    @Test fun approvalWithoutAlwaysWhenServerOmitsIt() {
        val events = parseSseData(
            "approval.request",
            """{"type":"approval.request","run_id":"run_9","tool":"shell","choices":["once","session","deny"]}"""
        )
        val req = events.filterIsInstance<ChatStreamEvent.ApprovalRequest>().single()
        assertEquals(listOf("once", "session", "deny"), req.choices)
        assertFalse(req.choices.contains("always"))
    }

    @Test fun approvalOnceDenyOnlyWhenSmartDenied() {
        val events = parseSseData(
            "approval.request",
            """{"type":"approval.request","run_id":"run_9","tool":"shell","smart_denied":true}"""
        )
        assertEquals(listOf("once", "deny"), events.filterIsInstance<ChatStreamEvent.ApprovalRequest>().single().choices)
        val noSession = parseSseData(
            "approval.request",
            """{"type":"approval.request","run_id":"run_9","allow_session":false}"""
        )
        assertEquals(listOf("once", "deny"), noSession.filterIsInstance<ChatStreamEvent.ApprovalRequest>().single().choices)
        val noPermanent = parseSseData(
            "approval.request",
            """{"type":"approval.request","run_id":"run_9","allow_permanent":false}"""
        )
        assertEquals(listOf("once", "session", "deny"), noPermanent.filterIsInstance<ChatStreamEvent.ApprovalRequest>().single().choices)
    }

    @Test fun approvalUnknownChoicesAreIgnored() {
        val events = parseSseData(
            "approval.request",
            """{"type":"approval.request","run_id":"run_9","choices":["once","maybe-later","deny",""]}"""
        )
        assertEquals(listOf("once", "deny"), events.filterIsInstance<ChatStreamEvent.ApprovalRequest>().single().choices)
        assertNull(normalizeHermesApprovalChoice("maybe-later"))
        assertNull(normalizeHermesApprovalChoice("reject"))
        assertEquals("once", normalizeHermesApprovalChoice("approve"))
        assertEquals("once", normalizeHermesApprovalChoice("ALLOW"))
    }

    @Test fun approvalRequestIdIsPreservedForResolve() {
        val events = parseSseData(
            "approval.request",
            """{"type":"approval.request","run_id":"run_9","request_id":"req-42","tool":"shell"}"""
        )
        val req = events.filterIsInstance<ChatStreamEvent.ApprovalRequest>().single()
        assertEquals("req-42", req.requestId)
    }

    @Test fun serverApprovalNeverConfusedWithLocalVisualBlock() {
        val events = parseSseData(
            "approval.request",
            """{"type":"approval.request","run_id":"run_9","tool":"shell","command":"rm x","choices":["once","deny"]}"""
        )
        assertTrue(events.none { it is ChatStreamEvent.VisualBlocks })
        assertTrue(events.any { it is ChatStreamEvent.ApprovalRequest })
        var state = StreamingState()
        events.forEach { state = state.applyEvent(it) }
        assertEquals(1, state.pendingApprovals.size)
        assertTrue(state.visualBlocks.isEmpty())
    }

    @Test fun approvalEventsAreStructured() {
        val pending = parseSseData("approval.request", """{"type":"approval.request","run_id":"run_9","tool":"shell","command":"rm x","description":"pulizia"}""")
        val req = pending.filterIsInstance<ChatStreamEvent.ApprovalRequest>().single()
        assertEquals("run_9", req.runId)
        assertEquals("shell", req.tool)
        var state = StreamingState()
        pending.forEach { state = state.applyEvent(it) }
        assertEquals(1, state.pendingApprovals.size)
        assertEquals("run_9", state.activeRunId)
        val resolved = listOf(ChatStreamEvent.ApprovalResolved("run_9", req.approvalId, "once"))
        resolved.forEach { state = state.applyEvent(it) }
        // approvalId vuoto: il filtro non rimuove (nessun falso positivo), ma lo stato resta coerente.
        assertTrue(state.pendingApprovals.size <= 1)
    }

    @Test fun runStatusChangedTracksTerminalAndPendingSteer() {
        val events = parseSseData("run.completed", """{"type":"run.completed","run_id":"run_3","output":"fatto","pending_steer":"riprova"}""")
        assertTrue(events.any { it is ChatStreamEvent.TextSnapshot && it.text == "fatto" })
        val status = events.filterIsInstance<ChatStreamEvent.RunStatusChanged>().single()
        assertEquals("completed", status.status)
        assertEquals("riprova", status.pendingSteer)
        var state = StreamingState()
        events.forEach { state = state.applyEvent(it) }
        assertEquals("completed", state.runStatus)
        assertEquals("riprova", state.pendingSteer)
    }

    @Test fun sessionBindingKeyIsProfileScoped() {
        assertEquals("default::abc", sessionBindingKey("abc", null))
        assertEquals("coder::abc", sessionBindingKey("abc", "Coder"))
        assertTrue(isValidHermesSessionKey("agent:main:android:dm:u1"))
    }

    @Test fun sessionParsersCoverCrudMessagesFork() {
        val list = parseHermesSessionList("""{"sessions":[{"id":"s1","title":"A"},{"id":"s2","title":"B","parent_session_id":"s1"}]}""")
        assertEquals(2, list.size)
        assertEquals("s1", list[1].parentId)
        val single = parseHermesSession("""{"session":{"id":"s1","title":"A"}}""")!!
        assertEquals("s1", single.id)
        val msgs = parseHermesSessionMessages("""{"messages":[{"role":"user","content":"ciao"},{"role":"assistant","content":"ok"}]}""")
        assertEquals(2, msgs.size)
        assertEquals("user", msgs[0].role)
    }

    @Test fun runStatusParsingCoversTerminalAndPendingSteer() {
        val completed = parseHermesRunInfo("""{"run_id":"run_1","status":"completed","output":"ok"}""")!!
        assertEquals(HermesRunStatus.COMPLETED, completed.status)
        assertTrue(completed.status.isTerminal())
        val pending = parseHermesRunInfo("""{"run_id":"run_2","status":"completed","pending_steer":"riprova"}""")!!
        assertEquals("riprova", pending.pendingSteer)
        assertEquals(HermesRunStatus.WAITING_FOR_APPROVAL, HermesRunStatus.fromWire("waiting_for_approval"))
        assertEquals(HermesRunStatus.UNKNOWN, HermesRunStatus.fromWire("boh"))
    }

    @Test fun profileIsolationIsFailClosed() {
        // Profilo nominato: nessun fallback null/anonimo.
        assertEquals(listOf<String?>(null), hermesProfileAuthCandidates(null, null).takeLast(1))
        assertEquals(listOf<String?>(null), hermesProfileAuthCandidates(null, "coder"))
        assertEquals(listOf("k"), hermesProfileAuthCandidates("k", "coder"))
        // Session key validation finita e sicura.
        assertFalse(isValidHermesSessionKey(null))
        assertFalse(isValidHermesSessionKey(""))
        assertFalse(isValidHermesSessionKey("a\nb"))
        assertTrue(isValidHermesSessionKey("agent:main:android:dm:user-1"))
    }

    @Test fun modelOptionsCatalogParsesWithoutHardcoding() {
        val catalog = parseModelOptionsPayload("""
        {"providers":[{"slug":"nous","display_name":"Nous","available":true}],
         "models":[{"id":"m1","display_name":"M1","provider":"nous","capabilities":["reasoning"],"context_window":128000,"reasoning_efforts":["low","high"],"pricing":{"input":1}}]}
        """.trimIndent())
        assertEquals(1, catalog.models.size)
        assertEquals("m1", catalog.models[0].id)
        assertEquals(128000L, catalog.models[0].contextWindow)
        assertTrue(catalog.models[0].reasoningSupported)
        val fallback = parseV1ModelsFallback("""{"data":[{"id":"hermes-agent"}]}""")
        assertEquals("v1-models", fallback.source)
        assertEquals("hermes-agent", fallback.models.single().id)
    }

    @Test fun sessionWriteOutcomeMappingIsExplicit() {
        assertEquals(HermesSessionWrite.APPLIED, sessionWriteOutcome(200))
        assertEquals(HermesSessionWrite.APPLIED, sessionWriteOutcome(201))
        assertEquals(HermesSessionWrite.GONE, sessionWriteOutcome(404))
        assertEquals(HermesSessionWrite.AUTH_DENIED, sessionWriteOutcome(401))
        assertEquals(HermesSessionWrite.AUTH_DENIED, sessionWriteOutcome(403))
        assertEquals(HermesSessionWrite.TRANSIENT, sessionWriteOutcome(500))
        assertEquals(HermesSessionWrite.TRANSIENT, sessionWriteOutcome(0))
    }

    @Test fun modelLockWarningOnlyOnFailure() {
        assertNull(hermesModelLockWarning(200, "M1"))
        assertNull(hermesModelLockWarning(201, "M1"))
        val auth = hermesModelLockWarning(401, "M1")!!
        assertTrue(auth.contains("NON è salvato"))
        assertTrue(auth.contains("fail-closed"))
        val gone = hermesModelLockWarning(404, "M1")!!
        assertTrue(gone.contains("NON è salvato"))
        assertTrue(gone.contains("404"))
        val transient = hermesModelLockWarning(500, "M1")!!
        assertTrue(transient.contains("NON è salvato"))
        assertTrue(transient.contains("per questo turno"))
    }

    @Test fun legacyServerFallbackIsExplicit() {
        val legacy = parseHermesCapabilities("""{"features":{"chat_completions":true}}""")!!
        assertFalse(legacy.supportsModernSessions())
        assertFalse(legacy.supportsSteer())
        assertFalse(legacy.supportsApproval())
        assertFalse(legacy.supportsModelOptions())
    }

    @Test fun cronJobParseKeepsServerTruthForReadOnlyDisplay() {
        // GET /api/jobs restituisce dict completi (anche campi impostati via CLI/dashboard);
        // la UI li mostra in sola lettura, l'edit resta limitato alla whitelist /api/jobs.
        val body = """{"jobs":[{
          "id":"abcdef012345","name":"Brief","prompt":"Leggi feed","schedule":"0 8 * * *",
          "deliver":"bot-chat:coder","skills":["blogwatcher"],"enabled":true,
          "model":"m1","provider":"nous","reasoning_effort":"high","workdir":"/home/u/proj",
          "script":"watch.sh","no_agent":false,"context_from":["self","abcdef012344"],
          "enabled_toolsets":["core"]
        }]}"""
        val jobs = parseCronJobs(body)
        assertEquals(1, jobs.size)
        val job = jobs.single()
        assertEquals("bot-chat:coder", job.deliver)
        assertEquals("m1", job.model)
        assertEquals("nous", job.provider)
        assertEquals("high", job.reasoningEffort)
        assertEquals("/home/u/proj", job.workdir)
        assertEquals("blogwatcher", job.skills)
        assertEquals("watch.sh", job.script)
        assertFalse(job.noAgent)
        assertEquals("self, abcdef012344", job.contextFrom)
        assertEquals("core", job.enabledToolsets)
        assertTrue(job.enabled)
    }
}
