package com.nemoclaw.chat

import org.json.JSONObject

/**
 * Estensioni SSE moderne Hermes Agent (rif. v2026.9.14).
 * - Sessions: assistant.delta, tool.started, tool.completed, terminal run.completed/failed/cancelled.
 * - Runs: lifecycle run.* , tool.started/completed, approval.request/responded, subagent.*.
 * - Responses/Chat: response.*, hermes.tool.progress.
 * - Keepalive ": keepalive" ignorato dal trasporto (mai JSON/evento malformato).
 * - Eventi sconosciuti: tollerati, mai crash, testo finale preservato.
 */

internal fun isHermesKeepaliveLine(line: String): Boolean = line.startsWith(":")

internal fun parseModernSessionRunEvents(eventName: String?, obj: JSONObject): List<ChatStreamEvent> {
    val out = mutableListOf<ChatStreamEvent>()
    val t = (eventName ?: obj.optString("type", "")).lowercase()
    fun runIdOf(): String = obj.optString("run_id", obj.optString("runId", obj.optString("id", "")))
        .takeIf { t.startsWith("run.") } .orEmpty()
    // assistant.delta (Sessions API streaming)
    if (t.contains("assistant.delta") || (t.contains("assistant") && t.contains("delta"))) {
        val delta = obj.optString("delta", obj.optString("text", obj.optString("content", "")))
        if (delta.isNotEmpty()) out += ChatStreamEvent.TextDelta(delta)
        val rid = obj.optString("run_id", "")
        if (rid.isNotBlank()) out += ChatStreamEvent.RunId(rid)
        return out
    }
    // hermes.tool.progress (Chat Completions streaming UX, non persiste nel testo)
    if (t.contains("hermes.tool.progress")) {
        val tool = obj.optString("tool", obj.optString("name", "tool"))
        val preview = obj.optString("preview", obj.optString("label", ""))
        val id = obj.optString("tool_call_id", obj.optString("call_id", obj.optString("id", "tool")))
        out += ChatStreamEvent.ToolCallStart(id, tool.ifBlank { "tool" })
        if (preview.isNotBlank()) out += ChatStreamEvent.ToolCallArgs(id, preview)
        return out
    }
    // tool.started / tool.completed (Sessions + Runs)
    if (t.contains("tool.started") || t == "tool.start" || t.contains("subagent.start")) {
        val id = obj.optString("tool_call_id", obj.optString("call_id", obj.optString("id", obj.optString("delegation_id", "tool"))))
        val name = obj.optString("tool", obj.optString("name", "tool"))
        out += ChatStreamEvent.ToolCallStart(id.ifBlank { "tool" }, name.ifBlank { "tool" })
        val preview = obj.optString("preview", obj.optString("arguments", ""))
        if (preview.isNotBlank()) out += ChatStreamEvent.ToolCallArgs(id.ifBlank { "tool" }, preview)
        return out
    }
    if (t.contains("tool.completed") || t.contains("tool.complete") || t.contains("subagent.complete")) {
        val id = obj.optString("tool_call_id", obj.optString("call_id", obj.optString("id", obj.optString("delegation_id", "tool"))))
        val name = obj.optString("tool", obj.optString("name", ""))
        val result = obj.optString("preview", obj.optString("result", obj.optString("output", obj.optString("summary", ""))))
        if (result.isNotBlank()) out += ChatStreamEvent.ToolResult(id.ifBlank { null }, name.takeIf { it.isNotBlank() }, result)
        out += ChatStreamEvent.ToolCallEnd(id.ifBlank { "tool" })
        return out
    }
    // run.* lifecycle (Runs + session stream terminali)
    if (t.startsWith("run.")) {
        val rid = obj.optString("run_id", obj.optString("runId", obj.optString("id", "")))
        when {
            t.contains("run.started") -> {
                if (rid.isNotBlank()) out += ChatStreamEvent.RunId(rid)
                else out += ChatStreamEvent.Status("Run Hermes avviato.")
            }
            t.contains("run.steered") -> {
                if (rid.isNotBlank()) out += ChatStreamEvent.RunId(rid)
                out += ChatStreamEvent.Status("Guida inviata al run (accodata, consegna al prossimo tool boundary).")
            }
            t.contains("run.completed") || t.contains("run.failed") || t.contains("run.cancelled") -> {
                val status = when {
                    t.contains("run.completed") -> "completed"
                    t.contains("run.failed") -> "failed"
                    else -> "cancelled"
                }
                val output = obj.optString("output", obj.optString("text", ""))
                if (output.isNotBlank()) out += ChatStreamEvent.TextSnapshot(output)
                val pending = obj.optString("pending_steer", "").takeIf { it.isNotBlank() }
                out += ChatStreamEvent.RunStatusChanged(rid, status, pending)
                if (pending != null) out += ChatStreamEvent.Status("Steer non consegnato, riproponilo come turno successivo.")
            }
            else -> {
                // Eventi run futuri/sconosciuti: non crashare, esponi come status minimale.
                out += ChatStreamEvent.RawHermesEvent(t, SAFE_RAW_EVENT_JSON)
            }
        }
        return out
    }
    // approval.* strutturati (server-side, distinti dai Visual Block locali)
    if (t.contains("approval")) {
        val req = parseHermesApprovalRequest(obj)
        val rid = obj.optString("run_id", obj.optString("runId", ""))
        if (t.contains("responded") || t.contains("resolved")) {
            out += ChatStreamEvent.ApprovalResolved(rid, req.approvalId, obj.optString("choice", "resolved"))
            return out
        }
        out += ChatStreamEvent.ApprovalRequest(rid, req.approvalId, req.requestId, req.tool, req.command, req.description, req.choices)
        return out
    }
    // message.started / message.completed wrappers sessioni
    if (t.contains("message.started") || t.contains("message.completed")) {
        val text = obj.optString("text", obj.optString("delta", obj.optString("content", "")))
        if (text.isNotBlank()) {
            if (t.contains("completed")) out += ChatStreamEvent.TextSnapshot(text)
            else out += ChatStreamEvent.TextDelta(text)
        }
        return out
    }
    return out
}
