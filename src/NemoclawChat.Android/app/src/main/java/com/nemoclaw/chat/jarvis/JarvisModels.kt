package com.nemoclaw.chat.jarvis

import org.json.JSONObject

internal enum class JarvisInitiativeMode(val wireValue: String) {
    QUESTIONS_ONLY("questions_only"),
    ASSISTIVE("assistive"),
    PROACTIVE("proactive");

    companion object {
        fun fromWire(value: String?): JarvisInitiativeMode = entries.firstOrNull {
            it.wireValue == value?.trim()?.lowercase()
        } ?: ASSISTIVE
    }
}

internal enum class JarvisPhase {
    IDLE,
    CONNECTING,
    ACTIVE,
    PAUSED,
    STOPPING,
    ERROR
}

internal data class JarvisCapabilities(
    val enabled: Boolean,
    val vision: Boolean,
    val proactiveEvents: Boolean,
    val singleModel: Boolean,
    val fastModelConfigured: Boolean,
    val reasoningModelConfigured: Boolean,
    val maxFrameBytes: Int,
    val maxSessionSeconds: Int
)

internal data class JarvisRemoteSession(
    val id: String,
    val status: String,
    val mode: JarvisInitiativeMode,
    val viewPaused: Boolean
)

internal data class JarvisTurnResult(
    val text: String,
    val route: String,
    val totalLatencyMs: Long?
)

internal data class JarvisEvent(
    val id: String?,
    val type: String,
    val text: String?,
    val route: String?,
    val observation: String?,
    val summary: String?,
    val situation: String?,
    val topic: String?,
    val openLoop: Boolean,
    val autonomous: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
    val totalLatencyMs: Long?
) {
    companion object {
        fun parse(json: String, eventId: String? = null, eventName: String? = null): JarvisEvent {
            val body = JSONObject(json)
            val metrics = body.optJSONObject("metrics")
            return JarvisEvent(
                id = body.optString("id").ifBlank { eventId },
                type = body.optString("type").ifBlank { eventName ?: "message" },
                text = body.optString("text").takeIf { it.isNotBlank() },
                route = body.optString("route").takeIf { it.isNotBlank() },
                observation = body.optString("observation").takeIf { it.isNotBlank() },
                summary = body.optString("summary").takeIf { it.isNotBlank() },
                situation = body.optString("situation").takeIf { it.isNotBlank() },
                topic = body.optString("topic").takeIf { it.isNotBlank() },
                openLoop = body.optBoolean("open_loop", false),
                autonomous = body.optBoolean("autonomous", false),
                errorCode = body.optString("code").takeIf { it.isNotBlank() },
                errorMessage = body.optString("message").takeIf { it.isNotBlank() },
                totalLatencyMs = metrics?.optDouble("total_ms")
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?.toLong()
            )
        }
    }
}

internal data class JarvisUiState(
    val phase: JarvisPhase = JarvisPhase.IDLE,
    val active: Boolean = false,
    val sessionId: String? = null,
    val initiativeMode: JarvisInitiativeMode = JarvisInitiativeMode.ASSISTIVE,
    val objective: String? = null,
    val visionActive: Boolean = false,
    val deviceStatus: String? = null,
    val audioRoute: String? = null,
    val gatewayStatus: String? = null,
    val singleModel: Boolean = true,
    val fastModelAvailable: Boolean = false,
    val reasoningModelAvailable: Boolean = false,
    val currentModel: String? = null,
    val lastObservation: String? = null,
    val shortTermSummary: String? = null,
    val situation: String? = null,
    val conversationTopic: String? = null,
    val awaitingFollowup: Boolean = false,
    val lastInterventionText: String? = null,
    val lastInterventionEventId: String? = null,
    val feedbackStatus: String? = null,
    val lastLatencyMs: Long? = null,
    val transcript: String? = null,
    val error: String? = null
)
