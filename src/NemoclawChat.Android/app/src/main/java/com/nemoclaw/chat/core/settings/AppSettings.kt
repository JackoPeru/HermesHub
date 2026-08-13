package com.nemoclaw.chat

import android.content.Context
import androidx.core.content.edit

data class AppSettings(
    val gatewayUrl: String = AppDefaults.gatewayUrl,
    val gatewayWsUrl: String = AppDefaults.gatewayWsUrl,
    val adminBridgeUrl: String = AppDefaults.adminBridgeUrl,
    val provider: String = AppDefaults.provider,
    val inferenceEndpoint: String = AppDefaults.inferenceEndpoint,
    val preferredApi: String = AppDefaults.preferredApi,
    val model: String = AppDefaults.model,
    val voiceModel: String = AppDefaults.voiceModel,
    val accessMode: String = AppDefaults.accessMode,
    val visualBlocksMode: String = AppDefaults.visualBlocksMode,
    val videoLibraryPath: String = AppDefaults.videoLibraryPath,
    val newsLibraryPath: String = AppDefaults.newsLibraryPath,
    val activeProjectId: String = AppDefaults.activeProjectId,
    val activeProjectName: String = AppDefaults.activeProjectName,
    val activeProjectWorkspacePath: String = "",
    val activeProjectRepositoryUrl: String = "",
    val activeProjectInstructions: String = "",
    val activeProjectMemory: String = "",
    val activeProjectTools: String = "",
    val fontScale: Float = AppDefaults.fontScale,
    val showToolCalls: Boolean = AppDefaults.showToolCalls,
    val showMessageMetrics: Boolean = AppDefaults.showMessageMetrics,
    val metricTtft: Boolean = AppDefaults.metricTtft,
    val metricTokensPerSecond: Boolean = AppDefaults.metricTokensPerSecond,
    val metricOutputTokens: Boolean = AppDefaults.metricOutputTokens,
    val metricPromptTokens: Boolean = AppDefaults.metricPromptTokens,
    val metricContextTokens: Boolean = AppDefaults.metricContextTokens,
    val metricDuration: Boolean = AppDefaults.metricDuration,
    val maxAttachmentMb: Int = AppDefaults.maxAttachmentMb,
    val strictNativeMode: Boolean = AppDefaults.strictNativeMode,
    val demoMode: Boolean = AppDefaults.demoMode,
    val healthSyncEnabled: Boolean = AppDefaults.healthSyncEnabled,
    val healthIncludeSteps: Boolean = AppDefaults.healthIncludeSteps,
    val healthIncludeSleep: Boolean = AppDefaults.healthIncludeSleep,
    val healthIncludeWorkouts: Boolean = AppDefaults.healthIncludeWorkouts,
    val healthIncludeHeartRate: Boolean = AppDefaults.healthIncludeHeartRate
)

internal fun AppSettings.metricFilter(): MetricDisplayFilter = MetricDisplayFilter(
    ttft = metricTtft,
    tokensPerSecond = metricTokensPerSecond,
    outputTokens = metricOutputTokens,
    promptTokens = metricPromptTokens,
    contextTokens = metricContextTokens,
    duration = metricDuration
)

internal fun loadSettings(context: Context): AppSettings {
    val prefs = migratePrefs(context, CURRENT_SETTINGS_PREFS, LEGACY_SETTINGS_PREFS)
    val settings = AppSettings(
        gatewayUrl = prefs.getString("gatewayUrl", AppDefaults.gatewayUrl) ?: AppDefaults.gatewayUrl,
        gatewayWsUrl = prefs.getString("gatewayWsUrl", AppDefaults.gatewayWsUrl) ?: AppDefaults.gatewayWsUrl,
        adminBridgeUrl = prefs.getString("adminBridgeUrl", AppDefaults.adminBridgeUrl) ?: AppDefaults.adminBridgeUrl,
        provider = prefs.getString("provider", AppDefaults.provider) ?: AppDefaults.provider,
        inferenceEndpoint = prefs.getString("inferenceEndpoint", AppDefaults.inferenceEndpoint) ?: AppDefaults.inferenceEndpoint,
        preferredApi = prefs.getString("preferredApi", AppDefaults.preferredApi) ?: AppDefaults.preferredApi,
        model = prefs.getString("model", AppDefaults.model) ?: AppDefaults.model,
        voiceModel = prefs.getString("voiceModel", AppDefaults.voiceModel) ?: AppDefaults.voiceModel,
        accessMode = prefs.getString("accessMode", AppDefaults.accessMode) ?: AppDefaults.accessMode,
        visualBlocksMode = prefs.getString("visualBlocksMode", AppDefaults.visualBlocksMode) ?: AppDefaults.visualBlocksMode,
        videoLibraryPath = prefs.getString("videoLibraryPath", AppDefaults.videoLibraryPath) ?: AppDefaults.videoLibraryPath,
        newsLibraryPath = prefs.getString("newsLibraryPath", AppDefaults.newsLibraryPath) ?: AppDefaults.newsLibraryPath,
        activeProjectId = prefs.getString("activeProjectId", AppDefaults.activeProjectId) ?: AppDefaults.activeProjectId,
        activeProjectName = prefs.getString("activeProjectName", AppDefaults.activeProjectName) ?: AppDefaults.activeProjectName,
        activeProjectWorkspacePath = prefs.getString("activeProjectWorkspacePath", "") ?: "",
        activeProjectRepositoryUrl = prefs.getString("activeProjectRepositoryUrl", "") ?: "",
        activeProjectInstructions = prefs.getString("activeProjectInstructions", "") ?: "",
        activeProjectMemory = prefs.getString("activeProjectMemory", "") ?: "",
        activeProjectTools = prefs.getString("activeProjectTools", "") ?: "",
        fontScale = prefs.getFloat("fontScale", AppDefaults.fontScale).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
        showToolCalls = prefs.getBoolean("showToolCalls", AppDefaults.showToolCalls),
        showMessageMetrics = prefs.getBoolean("showMessageMetrics", AppDefaults.showMessageMetrics),
        metricTtft = prefs.getBoolean("metricTtft", AppDefaults.metricTtft),
        metricTokensPerSecond = prefs.getBoolean("metricTokensPerSecond", AppDefaults.metricTokensPerSecond),
        metricOutputTokens = prefs.getBoolean("metricOutputTokens", AppDefaults.metricOutputTokens),
        metricPromptTokens = prefs.getBoolean("metricPromptTokens", AppDefaults.metricPromptTokens),
        metricContextTokens = prefs.getBoolean("metricContextTokens", AppDefaults.metricContextTokens),
        metricDuration = prefs.getBoolean("metricDuration", AppDefaults.metricDuration),
        maxAttachmentMb = prefs.getInt("maxAttachmentMb", AppDefaults.maxAttachmentMb).let { if (it <= 0 || it == 6) 150 else it }.coerceIn(1, 150),
        strictNativeMode = prefs.getBoolean("strictNativeMode", AppDefaults.strictNativeMode),
        demoMode = prefs.getBoolean("demoMode", AppDefaults.demoMode),
        healthSyncEnabled = prefs.getBoolean("healthSyncEnabled", AppDefaults.healthSyncEnabled),
        healthIncludeSteps = prefs.getBoolean("healthIncludeSteps", AppDefaults.healthIncludeSteps),
        healthIncludeSleep = prefs.getBoolean("healthIncludeSleep", AppDefaults.healthIncludeSleep),
        healthIncludeWorkouts = prefs.getBoolean("healthIncludeWorkouts", AppDefaults.healthIncludeWorkouts),
        healthIncludeHeartRate = prefs.getBoolean("healthIncludeHeartRate", AppDefaults.healthIncludeHeartRate)
    )
    return normalizePlugAndPlaySettings(context, settings)
}

private fun normalizeUrl(value: String): String = value.trim().trimEnd('/')

private fun normalizePlugAndPlaySettings(context: Context, settings: AppSettings): AppSettings {
    var next = settings
    var changed = false

    val gateway = normalizeUrl(next.gatewayUrl)
    if (gateway != next.gatewayUrl) {
        next = next.copy(gatewayUrl = gateway)
        changed = true
    }

    if (next.model.isBlank()) {
        next = next.copy(model = AppDefaults.model)
        changed = true
    }

    if (next.voiceModel.isBlank()) {
        next = next.copy(voiceModel = AppDefaults.voiceModel)
        changed = true
    }

    if (next.provider.isBlank()) {
        next = next.copy(provider = AppDefaults.provider)
        changed = true
    }

    if (next.inferenceEndpoint.isBlank() && next.gatewayUrl.isNotBlank()) {
        next = next.copy(inferenceEndpoint = next.gatewayUrl)
        changed = true
    }
    if (next.adminBridgeUrl.isBlank() && next.gatewayUrl.isNotBlank()) {
        next = next.copy(adminBridgeUrl = next.gatewayUrl.removeSuffix("/v1"))
        changed = true
    }
    if (next.accessMode.isBlank()) {
        next = next.copy(accessMode = AppDefaults.accessMode)
        changed = true
    }

    if (changed) {
        saveSettings(context, next)
    }
    return next
}

internal fun saveSettings(context: Context, settings: AppSettings) {
    context.getSharedPreferences(CURRENT_SETTINGS_PREFS, Context.MODE_PRIVATE).edit {
        putString("gatewayUrl", normalizeUrl(settings.gatewayUrl))
        putString("gatewayWsUrl", normalizeUrl(settings.gatewayWsUrl))
        putString("adminBridgeUrl", normalizeUrl(settings.adminBridgeUrl))
        putString("provider", settings.provider.trim())
        putString("inferenceEndpoint", normalizeUrl(settings.inferenceEndpoint))
        putString("preferredApi", settings.preferredApi.trim())
        putString("model", settings.model.trim())
        putString("voiceModel", settings.voiceModel.trim())
        putString("accessMode", settings.accessMode.trim())
        putString("visualBlocksMode", settings.visualBlocksMode.trim())
        putString("videoLibraryPath", settings.videoLibraryPath.trim())
        putString("newsLibraryPath", settings.newsLibraryPath.trim())
        putString("activeProjectId", settings.activeProjectId.trim())
        putString("activeProjectName", settings.activeProjectName.trim())
        putString("activeProjectWorkspacePath", settings.activeProjectWorkspacePath.trim())
        putString("activeProjectRepositoryUrl", settings.activeProjectRepositoryUrl.trim())
        putString("activeProjectInstructions", settings.activeProjectInstructions.trim())
        putString("activeProjectMemory", settings.activeProjectMemory.trim())
        putString("activeProjectTools", settings.activeProjectTools.trim())
        putFloat("fontScale", settings.fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE))
        putBoolean("showToolCalls", settings.showToolCalls)
        putBoolean("showMessageMetrics", settings.showMessageMetrics)
        putBoolean("metricTtft", settings.metricTtft)
        putBoolean("metricTokensPerSecond", settings.metricTokensPerSecond)
        putBoolean("metricOutputTokens", settings.metricOutputTokens)
        putBoolean("metricPromptTokens", settings.metricPromptTokens)
        putBoolean("metricContextTokens", settings.metricContextTokens)
        putBoolean("metricDuration", settings.metricDuration)
        putInt("maxAttachmentMb", settings.maxAttachmentMb.coerceIn(1, 150))
        putBoolean("strictNativeMode", settings.strictNativeMode)
        putBoolean("demoMode", settings.demoMode)
        putBoolean("healthSyncEnabled", settings.healthSyncEnabled)
        putBoolean("healthIncludeSteps", settings.healthIncludeSteps)
        putBoolean("healthIncludeSleep", settings.healthIncludeSleep)
        putBoolean("healthIncludeWorkouts", settings.healthIncludeWorkouts)
        putBoolean("healthIncludeHeartRate", settings.healthIncludeHeartRate)
    }
}

internal const val MIN_FONT_SCALE = 0.85f
internal const val MAX_FONT_SCALE = 1.25f

internal object AppDefaults {
    const val gatewayUrl = ""
    const val gatewayWsUrl = ""
    const val adminBridgeUrl = ""
    const val provider = "hermes-agent"
    const val inferenceEndpoint = ""
    const val preferredApi = "hermes-native"
    const val model = "hermes-agent"
    const val voiceModel = "hermes-voice"
    const val accessMode = "Tailscale/LAN plug-and-play"
    const val visualBlocksMode = "auto"
    const val videoLibraryPath = ""
    const val newsLibraryPath = ""
    const val activeProjectId = ""
    const val activeProjectName = ""
    const val fontScale = 1.0f
    const val showToolCalls = true
    const val showMessageMetrics = false
    const val metricTtft = true
    const val metricTokensPerSecond = true
    const val metricOutputTokens = true
    const val metricPromptTokens = true
    const val metricContextTokens = true
    const val metricDuration = true
    const val maxAttachmentMb = 150
    const val strictNativeMode = false
    const val demoMode = false
    const val healthSyncEnabled = false
    const val healthIncludeSteps = true
    const val healthIncludeSleep = true
    const val healthIncludeWorkouts = true
    const val healthIncludeHeartRate = false
    const val releasesPage = "https://github.com/JackoPeru/HermesHub/releases"
    const val latestReleaseApi = "https://api.github.com/repos/JackoPeru/HermesHub/releases/latest"
}
