package com.nemoclaw.chat

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

internal fun parseCronJobs(body: String): List<CronJob> {
    if (body.isBlank()) return emptyList()
    val trimmed = body.trim()
    val array = when {
        trimmed.startsWith("[") -> JSONArray(trimmed)
        else -> {
            val root = JSONObject(trimmed)
            root.optJSONArray("jobs")
                ?: root.optJSONArray("crons")
                ?: root.optJSONArray("items")
                ?: root.optJSONArray("schedules")
                ?: root.optJSONArray("data")
                ?: JSONArray().apply {
                    root.optJSONObject("job")?.let { put(it) }
                    root.optJSONObject("cron")?.let { put(it) }
                    if (length() == 0 && root.has("id")) put(root)
                }
        }
    }
    return buildList {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val state = obj.optString("state", obj.optString("status"))
            val enabled = when {
                obj.has("enabled") -> obj.optBoolean("enabled", true)
                obj.has("active") -> obj.optBoolean("active", true)
                else -> true
            } && !state.equals("paused", true) && !state.equals("disabled", true)
            val id = obj.optString("id", obj.optString("job_id", obj.optString("jobId", obj.optString("name"))))
            val name = obj.optString("name", obj.optString("title", obj.optString("label", id.ifBlank { "Cron" })))
            add(
                CronJob(
                    id = id,
                    name = name,
                    prompt = obj.optString("prompt", obj.optString("instructions", obj.optString("description", obj.optString("input")))),
                    schedule = cronScheduleText(obj),
                    state = state.ifBlank { if (enabled) "attivo" else "pausa" },
                    enabled = enabled,
                    nextRunAt = obj.optString("next_run_at", obj.optString("nextRunAt", obj.optString("next_run", obj.optString("nextRun")))),
                    lastRunAt = obj.optString("last_run_at", obj.optString("lastRunAt", obj.optString("last_run", obj.optString("lastRun")))),
                    lastStatus = obj.optString("last_status", obj.optString("lastStatus", obj.optString("last_result", obj.optString("lastResult")))),
                    deliver = obj.optString("deliver", obj.optString("delivery", obj.optString("target"))),
                    origin = cronOriginText(obj),
                    profile = obj.optString("profile", ""),
                    model = obj.optString("model", ""),
                    provider = obj.optString("provider", ""),
                    reasoningEffort = obj.optString("reasoning_effort", obj.optString("reasoningEffort", "")),
                    workdir = obj.optString("workdir", ""),
                    skills = obj.optJSONArray("skills")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.joinToString(", ")
                    }.orEmpty(),
                    script = obj.optString("script", ""),
                    noAgent = obj.optBoolean("no_agent", obj.optBoolean("noAgent", false)),
                    contextFrom = obj.optJSONArray("context_from")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.joinToString(", ")
                    } ?: obj.optString("context_from", ""),
                    enabledToolsets = obj.optJSONArray("enabled_toolsets")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.joinToString(", ")
                    }.orEmpty()
                )
            )
        }
    }.filter { it.id.isNotBlank() || it.name.isNotBlank() }
}

internal fun cronScheduleText(obj: JSONObject): String {
    val direct = obj.optString("schedule_display",
        obj.optString("scheduleDisplay",
            obj.optString("cron",
                obj.optString("cron_expr", obj.optString("expression")))))
    if (direct.isNotBlank()) return direct
    val value = obj.opt("schedule") ?: return ""
    return when (value) {
        is String -> value
        is JSONObject -> {
            value.optString("expr", value.optString("cron", value.optString("display"))).ifBlank {
                val kind = value.optString("kind")
                val minutes = value.optLong("minutes", 0L)
                val seconds = value.optLong("seconds", 0L)
                when {
                    kind.equals("interval", true) && minutes > 0 -> "ogni $minutes min"
                    kind.equals("interval", true) && seconds > 0 -> "ogni $seconds sec"
                    else -> value.toString()
                }
            }
        }
        else -> value.toString()
    }
}

internal fun cronOriginText(obj: JSONObject): String {
    val origin = obj.opt("origin")
    return when (origin) {
        is String -> origin
        is JSONObject -> origin.optString("client",
            origin.optString("surface",
                origin.optString("host", origin.optString("user", origin.toString()))))
        else -> obj.optString("source", obj.optString("created_by", obj.optString("createdBy")))
    }
}

