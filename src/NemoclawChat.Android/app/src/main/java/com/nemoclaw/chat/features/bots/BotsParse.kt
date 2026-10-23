package com.nemoclaw.chat.features.bots

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.normalizeHermesProfileName
import com.nemoclaw.chat.postJson
import com.nemoclaw.chat.resolveHermesUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

internal fun safeBotError(status: Int, body: String, fallback: String): String {
    val message = runCatching {
        val root = JSONObject(body)
        val error = root.optJSONObject("error")
        (error?.optString("message") ?: root.optString("message")).trim()
    }.getOrDefault("")
    return if (message.isBlank()) "$fallback (HTTP $status)." else "$fallback (HTTP $status): ${message.take(320)}"
}

private fun parseBotItem(root: JSONObject, fallbackProfile: String = "", connection: HermesBotConnection? = null): HermesBotItem {
    val item = root.optJSONObject("bot") ?: root
    val profile = item.optString("profile", item.optString("name", fallbackProfile)).trim()
    return HermesBotItem(
        profile = profile,
        displayName = item.optString("display_name", item.optString("displayName", item.optString("title", profile))),
        description = item.optString("description"),
        hidden = item.optBoolean("hidden", false),
        chatId = item.optString("chat_id", item.optString("chatId")).takeIf { it.isNotBlank() },
        isDefault = item.optBoolean("is_default", item.optBoolean("isDefault", profile.equals("default", true))),
        connectionId = connection?.id ?: "primary",
        connectionLabel = connection?.label ?: "Gateway principale",
        identityKey = "${connection?.id ?: "primary"}::$profile"
    )
}

internal suspend fun createHermesBot(
    settings: AppSettings,
    apiKey: String?,
    profile: String,
    displayName: String,
    description: String,
    soul: String,
    connection: HermesBotConnection? = null
): Result<HermesBotItem> = withContext(Dispatchers.IO) {
    runCatching {
        val normalized = normalizeHermesProfileName(profile)
        val payload = JSONObject()
            .put("name", normalized)
            .put("profile", normalized)
            .put("display_name", displayName.trim())
            .put("description", description.trim())
            .put("no_skills", false)
        if (soul.isNotBlank()) payload.put("soul", soul)
        val response = if (connection == null) {
            postJson(resolveHermesUrl(settings, "/v1/hub/bots"), payload, apiKey)
        } else {
            botPostForConnection(settings, connection, apiKey, "/v1/hub/bots", payload)
        }
        if (response.first !in 200..299) error(safeBotError(response.first, response.second, "Bot non creato"))
        parseBotItem(JSONObject(response.second), normalized, connection)
    }
}

internal suspend fun updateHermesBot(
    settings: AppSettings,
    apiKey: String?,
    bot: HermesBotItem,
    displayName: String,
    description: String,
    soul: String?,
    connection: HermesBotConnection? = null
): Result<HermesBotItem> = withContext(Dispatchers.IO) {
    runCatching {
        val profile = normalizeHermesProfileName(bot.profile)
        val payload = JSONObject()
            .put("display_name", displayName.trim())
            .put("description", description.trim())
        if (!soul.isNullOrBlank()) payload.put("soul", soul)
        val encoded = URLEncoder.encode(profile, "UTF-8")
        val response = if (connection == null) {
            postJson(
                resolveHermesUrl(settings, "/v1/hub/bots/$encoded"),
                payload,
                apiKey,
                method = "PATCH"
            )
        } else {
            botPostForConnection(
                settings,
                connection,
                apiKey,
                "/v1/hub/bots/$encoded",
                payload,
                method = "PATCH"
            )
        }
        if (response.first !in 200..299) error(safeBotError(response.first, response.second, "Modifiche bot non salvate"))
        parseBotItem(JSONObject(response.second), profile, connection)
    }
}

internal suspend fun deleteHermesBot(
    context: Context,
    settings: AppSettings,
    apiKey: String?,
    bot: HermesBotItem,
    confirmation: String
): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val profile = normalizeHermesProfileName(bot.profile)
        check(!bot.isDefault && !profile.equals("default", true)) { "Il profilo predefinito non può essere eliminato." }
        check(confirmation == profile) { "La conferma deve corrispondere esattamente al nome del bot." }
        val encoded = URLEncoder.encode(profile, "UTF-8")
        val response = if (bot.connectionId.equals("primary", true)) {
            postJson(
                resolveHermesUrl(settings, "/v1/hub/bots/$encoded"),
                JSONObject().put("confirm_name", confirmation),
                apiKey,
                method = "DELETE"
            )
        } else {
            val connection = connectionForBot(context, settings, bot)
            botPostForConnection(
                settings,
                connection,
                secretForBotConnection(context, connection),
                "/v1/hub/bots/$encoded",
                JSONObject().put("confirm_name", confirmation),
                method = "DELETE"
            )
        }
        if (response.first !in 200..299) error(safeBotError(response.first, response.second, "Bot non eliminato"))
    }
}

