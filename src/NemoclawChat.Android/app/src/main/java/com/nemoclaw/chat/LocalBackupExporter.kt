package com.nemoclaw.chat

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun exportLocalBackup(context: Context, apiKey: String?): String {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val backup = JSONObject()
        .put("schema", "hermes-hub.local-backup.v1")
        .put("exportedAt", System.currentTimeMillis())
        .put("packageName", context.packageName)
        .put("settings", sharedPreferencesJson(context, "chatclaw_settings"))
        .put("conversations", parseJsonArray(sharedPreferencesJson(context, "chatclaw_archive").optString("conversations", "[]")))
        .put("tasks", parseJsonArray(sharedPreferencesJson(context, "chatclaw_tasks").optString("tasks", "[]")))
        .put("workspace", sharedPreferencesJson(context, "chatclaw_workspace_requests"))

    if (!apiKey.isNullOrBlank()) {
        backup.put("gatewayApiKey", apiKey.trim())
    }

    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "HermesHub-backup-$timestamp.json")
    file.writeText(backup.toString(2), Charsets.UTF_8)

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val shareIntent = Intent(Intent.ACTION_SEND)
        .setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    context.startActivity(Intent.createChooser(shareIntent, "Backup Hermes Hub"))
    return "Backup pronto: ${file.name}"
}

private fun parseJsonArray(raw: String): Any {
    return runCatching { org.json.JSONArray(raw) }.getOrElse { raw }
}

private fun sharedPreferencesJson(context: Context, name: String): JSONObject {
    val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val obj = JSONObject()
    prefs.all.toSortedMap().forEach { (key, value) ->
        when (value) {
            null -> obj.put(key, JSONObject.NULL)
            is String -> obj.put(key, value)
            is Boolean -> obj.put(key, value)
            is Int -> obj.put(key, value)
            is Long -> obj.put(key, value)
            is Float -> obj.put(key, value.toDouble())
            is Set<*> -> obj.put(key, value.joinToString("\n"))
            else -> obj.put(key, value.toString())
        }
    }
    return obj
}
