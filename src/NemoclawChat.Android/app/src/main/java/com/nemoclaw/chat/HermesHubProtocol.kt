package com.nemoclaw.chat

import okhttp3.Request
import org.json.JSONObject
import java.util.UUID

/** Canonical Hermes Hub Protocol v1 primitives shared by Android transport code. */
internal object HermesHubProtocol {
    const val PROTOCOL_VERSION = 1
    const val REQUEST_ID_HEADER = "X-Hermes-Request-Id"
    const val CORRELATION_ID_HEADER = "X-Hermes-Correlation-Id"
    const val COMPATIBILITY_REQUEST_ID_HEADER = "X-Request-Id"

    fun newCorrelationContext(): HermesRequestContext {
        val token = UUID.randomUUID().toString().replace("-", "")
        return HermesRequestContext("req_$token", "corr_$token")
    }

    fun addCorrelationHeaders(builder: Request.Builder, context: HermesRequestContext) {
        builder.header(REQUEST_ID_HEADER, context.requestId)
            .header(CORRELATION_ID_HEADER, context.correlationId)
            .header(COMPATIBILITY_REQUEST_ID_HEADER, context.requestId)
    }

    fun readEventEnvelope(json: JSONObject): HermesEventEnvelope? {
        if (json.optInt("protocol_version", -1) != PROTOCOL_VERSION || !json.has("payload") || json.opt("type") !is String) {
            return null
        }
        val rawSequence = json.opt("sequence") as? Number ?: return null
        val sequence = rawSequence.toLong()
        if (sequence < 0L || rawSequence.toDouble() != sequence.toDouble()) return null
        if (json.opt("event_id") !is String || json.opt("request_id") !is String || json.opt("correlation_id") !is String) {
            return null
        }
        val eventId = json.optString("event_id").takeIf { it.isNotBlank() } ?: return null
        val requestId = json.optString("request_id").takeIf { it.isNotBlank() } ?: return null
        val correlationId = json.optString("correlation_id").takeIf { it.isNotBlank() } ?: return null
        if (!isWireId(eventId) || !isWireId(requestId) || !isWireId(correlationId)) return null
        val type = json.optString("type").takeIf { it.isNotBlank() } ?: return null
        if (type.length > 160) return null
        val sourceType = json.optString("source_type").takeIf {
            it in setOf("hermes-agent", "hermes-gateway", "hermes-hub", "unknown")
        } ?: return null
        val runId = json.optString("run_id").takeIf { it.isNotBlank() }
        if (runId != null && !isWireId(runId)) return null
        return HermesEventEnvelope(
            protocolVersion = PROTOCOL_VERSION,
            eventId = eventId,
            sequence = sequence,
            requestId = requestId,
            correlationId = correlationId,
            type = type,
            payload = json.optJSONObject("payload"),
            sourceType = sourceType,
            runId = runId
        )
    }

    private fun isWireId(value: String): Boolean {
        return value.length <= 128 &&
            value.firstOrNull()?.isLetterOrDigit() == true &&
            value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == ':' || it == '-' }
    }
}

internal data class HermesRequestContext(
    val requestId: String,
    val correlationId: String
)

internal data class HermesEventEnvelope(
    val protocolVersion: Int,
    val eventId: String,
    val sequence: Long,
    val requestId: String,
    val correlationId: String,
    val type: String,
    val payload: JSONObject?,
    val sourceType: String,
    val runId: String?
)

internal data class HermesProtocolError(
    val code: String,
    val message: String,
    val requestId: String,
    val correlationId: String,
    val retryable: Boolean,
    val runId: String?,
    val protocolVersion: Int = HermesHubProtocol.PROTOCOL_VERSION
)
