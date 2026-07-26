package com.nemoclaw.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

internal object HealthSync {
    const val UNIQUE_PERIODIC_WORK = "hermes-health-sync-periodic"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val syncMutex = Mutex()

    fun requiredPermissions(settings: AppSettings): Set<String> = buildSet {
        if (settings.healthIncludeSteps) {
            add(HealthPermission.getReadPermission(StepsRecord::class))
            add(HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class))
        }
        if (settings.healthIncludeSleep) add(HealthPermission.getReadPermission(SleepSessionRecord::class))
        if (settings.healthIncludeWorkouts) add(HealthPermission.getReadPermission(ExerciseSessionRecord::class))
        if (settings.healthIncludeHeartRate) add(HealthPermission.getReadPermission(HeartRateRecord::class))
    }

    fun permissionsForRequest(context: Context, settings: AppSettings): Set<String> = buildSet {
        addAll(requiredPermissions(settings))
        if (Build.VERSION.SDK_INT >= 28 && healthConnectStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            val client = HealthConnectClient.getOrCreate(context)
            if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
                add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            }
        }
    }

    fun permissionRequestContract() = PermissionController.createRequestPermissionResultContract()

    fun openSettings(context: Context): Boolean = runCatching {
        context.startActivity(Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"))
        true
    }.getOrDefault(false)

    fun sdkStatus(context: Context): String = when {
        Build.VERSION.SDK_INT < 28 -> "Health Connect richiede Android 9 o successivo."
        healthConnectStatus(context) == HealthConnectClient.SDK_AVAILABLE -> "Health Connect disponibile."
        healthConnectStatus(context) == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Aggiorna o installa Health Connect."
        else -> "Health Connect non disponibile su questo telefono."
    }

    private fun healthConnectStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

    fun schedule(context: Context, settings: AppSettings) {
        val work = WorkManager.getInstance(context.applicationContext)
        if (!settings.healthSyncEnabled || !context.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE).getBoolean("background_allowed", false)) {
            work.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(UNIQUE_PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun recordBackgroundGrant(context: Context, granted: Set<String>) {
        context.applicationContext.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("background_allowed", HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted)
        }
    }

    suspend fun eraseAllFromHermes(context: Context): HealthEraseResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val settings = loadSettings(appContext)
        if (!hasConfiguredHermesEndpoint(settings)) return@withContext HealthEraseResult.Failed("Configura Hermes API URL prima della cancellazione.")
        try {
            val request = Request.Builder().url(wellbeingCollectionUrl(settings)).delete()
                .header("Accept", "application/json")
                .header("User-Agent", "HermesHub-Android")
                .apply { loadGatewaySecret(appContext)?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val disabled = settings.copy(healthSyncEnabled = false)
                    saveSettings(appContext, disabled)
                    schedule(appContext, disabled)
                    HealthEraseResult.Success
                } else HealthEraseResult.Failed("Cancellazione salute fallita: HTTP ${response.code}.")
            }
        } catch (error: Exception) {
            HealthEraseResult.Failed("Cancellazione salute fallita: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    suspend fun sync(context: Context, requireBackgroundPermission: Boolean = false): HealthSyncResult = withContext(Dispatchers.IO) {
        syncMutex.withLock { syncLocked(context, requireBackgroundPermission) }
    }

    private suspend fun syncLocked(context: Context, requireBackgroundPermission: Boolean): HealthSyncResult {
        val appContext = context.applicationContext
        val settings = loadSettings(appContext)
        if (!settings.healthSyncEnabled) return HealthSyncResult.Disabled
        if (!hasConfiguredHermesEndpoint(settings)) return HealthSyncResult.Permanent("Configura Hermes API URL prima della sincronizzazione salute.")
        if (Build.VERSION.SDK_INT < 28 || healthConnectStatus(appContext) != HealthConnectClient.SDK_AVAILABLE) {
            return HealthSyncResult.Permanent(sdkStatus(appContext))
        }

        val client = HealthConnectClient.getOrCreate(appContext)
        val required = requiredPermissions(settings)
        if (required.isEmpty()) return HealthSyncResult.Permanent("Seleziona almeno una categoria salute prima della sincronizzazione.")
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(required)) return HealthSyncResult.Permanent("Permessi Health Connect mancanti.")
        if (requireBackgroundPermission && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted) {
            return HealthSyncResult.Permanent("Lettura salute in background non autorizzata.")
        }

        val prefs = appContext.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE)
        try {
            val zone = ZoneId.systemDefault()
            val today = java.time.LocalDate.now(zone)
            val cached = cachedToday(prefs, settings, today)
            val retryAfter = prefs.getLong(KEY_QUOTA_RETRY_AFTER, 0L)
            if (cached == null && System.currentTimeMillis() < retryAfter) {
                return HealthSyncResult.Transient(quotaMessage(retryAfter))
            }
            val summary = cached ?: try {
                readDailySummary(client, settings, today, zone).also { saveCachedToday(prefs, settings, it) }
            } catch (error: Exception) {
                if (isHealthConnectRateLimit(error)) {
                    val nextAttempt = System.currentTimeMillis() + HEALTH_QUOTA_COOLDOWN_MS
                    prefs.edit { putLong(KEY_QUOTA_RETRY_AFTER, nextAttempt) }
                    return HealthSyncResult.Transient(quotaMessage(nextAttempt))
                }
                throw error
            }
            upload(settings, loadGatewaySecret(appContext), summary)
            prefs.edit {
                putLong("last_success_at", System.currentTimeMillis())
                putString("last_date", summary.date)
                remove(KEY_QUOTA_RETRY_AFTER)
            }
            return HealthSyncResult.Success(summary)
        } catch (error: WellbeingGatewayException) {
            return when {
                error.code in setOf(404, 405) ||
                    error.body.contains("Rate limited request quota", ignoreCase = true) -> {
                    HealthSyncResult.Permanent("Gateway Hermes senza endpoint Salute. Aggiorna il gateway Linux a Hermes Hub 0.6.176 o successivo, poi riprova.")
                }
                error.code == 429 -> HealthSyncResult.Transient("Gateway Hermes ha applicato un rate limit. Attendi e riprova: ${error.body}")
                error.code in 400..499 -> HealthSyncResult.Permanent("Gateway rifiuta dati salute: HTTP ${error.code} ${error.body}")
                else -> HealthSyncResult.Transient("Gateway salute HTTP ${error.code}: ${error.body}")
            }
        } catch (error: SecurityException) {
            return HealthSyncResult.Permanent(error.message ?: "Permesso Health Connect revocato.")
        } catch (error: IOException) {
            return HealthSyncResult.Transient(error.message ?: "Rete Hermes non disponibile.")
        } catch (error: Exception) {
            return if (isHealthConnectRateLimit(error)) {
                val nextAttempt = System.currentTimeMillis() + HEALTH_QUOTA_COOLDOWN_MS
                prefs.edit { putLong(KEY_QUOTA_RETRY_AFTER, nextAttempt) }
                HealthSyncResult.Transient(quotaMessage(nextAttempt))
            } else {
                HealthSyncResult.Transient("Lettura Health Connect fallita: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    suspend fun readHistory(context: Context, settings: AppSettings, days: Int = 7): HealthHistoryResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT < 28 || healthConnectStatus(appContext) != HealthConnectClient.SDK_AVAILABLE) {
            return@withContext HealthHistoryResult.Unavailable(sdkStatus(appContext))
        }
        val prefs = appContext.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE)
        loadCachedHistory(prefs, settings)?.let { return@withContext HealthHistoryResult.Success(it) }
        val retryAfter = prefs.getLong(KEY_QUOTA_RETRY_AFTER, 0L)
        if (System.currentTimeMillis() < retryAfter) {
            return@withContext HealthHistoryResult.Unavailable(quotaMessage(retryAfter))
        }
        try {
            val client = HealthConnectClient.getOrCreate(appContext)
            val required = requiredPermissions(settings)
            if (required.isEmpty()) {
                return@withContext HealthHistoryResult.Unavailable("Seleziona almeno una categoria salute nelle Impostazioni.")
            }
            if (!client.permissionController.getGrantedPermissions().containsAll(required)) {
                return@withContext HealthHistoryResult.Unavailable("Autorizza Health Connect per visualizzare i dati dell'orologio.")
            }
            val zone = ZoneId.systemDefault()
            val today = java.time.LocalDate.now(zone)
            val history = readDailyHistory(client, settings, today, zone, days.coerceIn(1, 30))
            saveCachedHistory(prefs, settings, history)
            HealthHistoryResult.Success(history)
        } catch (error: SecurityException) {
            HealthHistoryResult.Unavailable(error.message ?: "Permesso Health Connect revocato.")
        } catch (error: IOException) {
            if (isHealthConnectRateLimit(error)) {
                val nextAttempt = System.currentTimeMillis() + HEALTH_QUOTA_COOLDOWN_MS
                prefs.edit { putLong(KEY_QUOTA_RETRY_AFTER, nextAttempt) }
                HealthHistoryResult.Unavailable(quotaMessage(nextAttempt))
            } else {
                HealthHistoryResult.Unavailable("Health Connect non disponibile: ${error.message ?: error.javaClass.simpleName}")
            }
        } catch (error: Exception) {
            if (isHealthConnectRateLimit(error)) {
                val nextAttempt = System.currentTimeMillis() + HEALTH_QUOTA_COOLDOWN_MS
                prefs.edit { putLong(KEY_QUOTA_RETRY_AFTER, nextAttempt) }
                HealthHistoryResult.Unavailable(quotaMessage(nextAttempt))
            } else {
                HealthHistoryResult.Unavailable("Lettura Health Connect fallita: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private suspend fun readDailySummary(
        client: HealthConnectClient,
        settings: AppSettings,
        day: java.time.LocalDate,
        zone: ZoneId
    ): DailyWellbeingSummary {
        val start = day.atStartOfDay(zone).toInstant()
        val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now())
        val range = TimeRangeFilter.between(start, end)
        var steps: Long? = null
        var calories: Double? = null
        val aggregate = client.aggregate(AggregateRequest(selectedMetrics(settings), range))
        if (settings.healthIncludeSteps) {
            steps = aggregate[StepsRecord.COUNT_TOTAL]
            calories = aggregate[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
        }
        val workouts = if (settings.healthIncludeWorkouts) readAll(client, ExerciseSessionRecord::class, range) else emptyList()
        return DailyWellbeingSummary(
            date = DateTimeFormatter.ISO_LOCAL_DATE.format(day),
            zoneId = zone.id,
            collectedAt = Instant.now().toString(),
            steps = steps,
            activeCaloriesKcal = calories,
            sleepMinutes = aggregate[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes().takeIf { settings.healthIncludeSleep },
            workoutMinutes = aggregate[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes().takeIf { settings.healthIncludeWorkouts },
            workoutCount = workouts.size.takeIf { settings.healthIncludeWorkouts },
            heartRateAverage = aggregate[HeartRateRecord.BPM_AVG]?.toDouble().takeIf { settings.healthIncludeHeartRate },
            heartRateMin = aggregate[HeartRateRecord.BPM_MIN]?.toDouble().takeIf { settings.healthIncludeHeartRate },
            heartRateMax = aggregate[HeartRateRecord.BPM_MAX]?.toDouble().takeIf { settings.healthIncludeHeartRate }
        )
    }

    private suspend fun readDailyHistory(
        client: HealthConnectClient,
        settings: AppSettings,
        today: java.time.LocalDate,
        zone: ZoneId,
        days: Int
    ): List<DailyWellbeingSummary> {
        val firstDay = today.minusDays((days - 1).toLong())
        val localRange = TimeRangeFilter.between(firstDay.atStartOfDay(), today.plusDays(1).atStartOfDay())
        val buckets = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(selectedMetrics(settings), localRange, Period.ofDays(1))
        ).associateBy { it.startTime.toLocalDate() }
        val instantRange = TimeRangeFilter.between(firstDay.atStartOfDay(zone).toInstant(), Instant.now())
        val workoutCounts = if (settings.healthIncludeWorkouts) {
            readAll(client, ExerciseSessionRecord::class, instantRange)
                .groupingBy { it.startTime.atZone(zone).toLocalDate() }
                .eachCount()
        } else emptyMap()
        val collectedAt = Instant.now().toString()
        return (0 until days).map { index ->
            val date = firstDay.plusDays(index.toLong())
            val result = buckets[date]?.result
            DailyWellbeingSummary(
                date = DateTimeFormatter.ISO_LOCAL_DATE.format(date),
                zoneId = zone.id,
                collectedAt = collectedAt,
                steps = result?.get(StepsRecord.COUNT_TOTAL).takeIf { settings.healthIncludeSteps },
                activeCaloriesKcal = result?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories.takeIf { settings.healthIncludeSteps },
                sleepMinutes = result?.get(SleepSessionRecord.SLEEP_DURATION_TOTAL)?.toMinutes().takeIf { settings.healthIncludeSleep },
                workoutMinutes = result?.get(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)?.toMinutes().takeIf { settings.healthIncludeWorkouts },
                workoutCount = workoutCounts[date].takeIf { settings.healthIncludeWorkouts },
                heartRateAverage = result?.get(HeartRateRecord.BPM_AVG)?.toDouble().takeIf { settings.healthIncludeHeartRate },
                heartRateMin = result?.get(HeartRateRecord.BPM_MIN)?.toDouble().takeIf { settings.healthIncludeHeartRate },
                heartRateMax = result?.get(HeartRateRecord.BPM_MAX)?.toDouble().takeIf { settings.healthIncludeHeartRate }
            )
        }
    }

    private fun selectedMetrics(settings: AppSettings): Set<AggregateMetric<*>> = buildSet {
        if (settings.healthIncludeSteps) {
            add(StepsRecord.COUNT_TOTAL)
            add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        }
        if (settings.healthIncludeSleep) add(SleepSessionRecord.SLEEP_DURATION_TOTAL)
        if (settings.healthIncludeWorkouts) add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
        if (settings.healthIncludeHeartRate) {
            add(HeartRateRecord.BPM_AVG)
            add(HeartRateRecord.BPM_MIN)
            add(HeartRateRecord.BPM_MAX)
        }
    }

    internal fun isHealthConnectRateLimit(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.contains("rate limit", ignoreCase = true) ||
                message.contains("quota has been exceeded", ignoreCase = true)) return true
            current = current.cause
        }
        return false
    }

    private fun quotaMessage(retryAfter: Long): String {
        val remainingMinutes = ((retryAfter - System.currentTimeMillis()).coerceAtLeast(0L) + 59_999L) / 60_000L
        return "Health Connect ha temporaneamente esaurito la quota di lettura. Hermes non e' stato contattato. Riprova tra circa ${remainingMinutes.coerceAtLeast(1L)} minuti."
    }

    private fun settingsSignature(settings: AppSettings): String = listOf(
        settings.healthIncludeSteps,
        settings.healthIncludeSleep,
        settings.healthIncludeWorkouts,
        settings.healthIncludeHeartRate
    ).joinToString(":")

    private fun cachedToday(
        prefs: android.content.SharedPreferences,
        settings: AppSettings,
        today: java.time.LocalDate
    ): DailyWellbeingSummary? {
        if (prefs.getString(KEY_CACHE_SIGNATURE, null) != settingsSignature(settings)) return null
        if (System.currentTimeMillis() - prefs.getLong(KEY_TODAY_READ_AT, 0L) > TODAY_CACHE_MS) return null
        return prefs.getString(KEY_TODAY_SUMMARY, null)?.let { encoded ->
            runCatching { DailyWellbeingSummary.fromJson(JSONObject(encoded)) }.getOrNull()
        }?.takeIf { it.date == today.toString() }
    }

    private fun saveCachedToday(
        prefs: android.content.SharedPreferences,
        settings: AppSettings,
        summary: DailyWellbeingSummary
    ) {
        prefs.edit {
            putString(KEY_CACHE_SIGNATURE, settingsSignature(settings))
            putLong(KEY_TODAY_READ_AT, System.currentTimeMillis())
            putString(KEY_TODAY_SUMMARY, summary.toJson().toString())
        }
    }

    private fun loadCachedHistory(
        prefs: android.content.SharedPreferences,
        settings: AppSettings
    ): List<DailyWellbeingSummary>? {
        if (prefs.getString(KEY_HISTORY_SIGNATURE, null) != settingsSignature(settings)) return null
        if (System.currentTimeMillis() - prefs.getLong(KEY_HISTORY_READ_AT, 0L) > HISTORY_CACHE_MS) return null
        return prefs.getString(KEY_HISTORY, null)?.let { encoded ->
            runCatching {
                val json = JSONArray(encoded)
                List(json.length()) { index -> DailyWellbeingSummary.fromJson(json.getJSONObject(index)) }
            }.getOrNull()
        }
    }

    private fun saveCachedHistory(
        prefs: android.content.SharedPreferences,
        settings: AppSettings,
        history: List<DailyWellbeingSummary>
    ) {
        prefs.edit {
            putString(KEY_HISTORY_SIGNATURE, settingsSignature(settings))
            putLong(KEY_HISTORY_READ_AT, System.currentTimeMillis())
            putString(KEY_HISTORY, JSONArray(history.map { it.toJson() }).toString())
        }
        history.lastOrNull()?.let { saveCachedToday(prefs, settings, it) }
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> readAll(
        client: HealthConnectClient,
        recordType: kotlin.reflect.KClass<T>,
        range: TimeRangeFilter
    ): List<T> {
        val records = mutableListOf<T>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(recordType, range, pageToken = token, pageSize = 1000))
            records += page.records
            token = page.pageToken
        } while (token != null)
        return records
    }

    private fun upload(settings: AppSettings, apiKey: String?, summary: DailyWellbeingSummary) {
        val url = wellbeingUrl(settings, summary.date)
        val payload = summary.toJson().toString().toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url).put(payload)
            .header("Accept", "application/json")
            .header("User-Agent", "HermesHub-Android")
            .header("Idempotency-Key", "wellbeing:${summary.date}:${summary.collectedAt.substringBefore('T')}")
            .apply { apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) return
            val body = response.body.string().take(500)
            throw WellbeingGatewayException(response.code, body)
        }
    }

    private fun wellbeingUrl(settings: AppSettings, date: String): String {
        return "${wellbeingCollectionUrl(settings)}/daily/$date"
    }

    internal fun wellbeingCollectionUrl(settings: AppSettings): String {
        val uri = URI(settings.gatewayUrl.trim())
        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "Endpoint Hermes non valido."
        }
        require(!uri.host.isNullOrBlank()) { "Endpoint Hermes non valido." }
        val path = uri.path.orEmpty().trimEnd('/')
        val segments = path.split('/').filter { it.isNotBlank() }
        val v1Index = segments.indexOfFirst { it.equals("v1", ignoreCase = true) }
        val prefixSegments = if (v1Index >= 0) segments.take(v1Index) else segments
        val prefix = if (prefixSegments.isEmpty()) "" else "/${prefixSegments.joinToString("/")}"
        return URI(uri.scheme.lowercase(), uri.userInfo, uri.host, uri.port, "$prefix/v1/hub/wellbeing", null, null).toString()
    }

    private const val HEALTH_PREFS = "hermes_health_sync"
    private const val KEY_QUOTA_RETRY_AFTER = "quota_retry_after"
    private const val KEY_CACHE_SIGNATURE = "today_cache_signature"
    private const val KEY_TODAY_READ_AT = "today_read_at"
    private const val KEY_TODAY_SUMMARY = "today_summary"
    private const val KEY_HISTORY_SIGNATURE = "history_cache_signature"
    private const val KEY_HISTORY_READ_AT = "history_read_at"
    private const val KEY_HISTORY = "history"
    private const val TODAY_CACHE_MS = 2 * 60 * 1000L
    private const val HISTORY_CACHE_MS = 5 * 60 * 1000L
    private const val HEALTH_QUOTA_COOLDOWN_MS = 30 * 60 * 1000L
}

internal sealed interface HealthSyncResult {
    data object Disabled : HealthSyncResult
    data class Success(val summary: DailyWellbeingSummary) : HealthSyncResult
    data class Permanent(val message: String) : HealthSyncResult
    data class Transient(val message: String) : HealthSyncResult
}

internal sealed interface HealthHistoryResult {
    data class Success(val items: List<DailyWellbeingSummary>) : HealthHistoryResult
    data class Unavailable(val message: String) : HealthHistoryResult
}

private class WellbeingGatewayException(val code: Int, val body: String) : IOException("Gateway salute HTTP $code: $body")

internal sealed interface HealthEraseResult {
    data object Success : HealthEraseResult
    data class Failed(val message: String) : HealthEraseResult
}

internal data class DailyWellbeingSummary(
    val date: String,
    val zoneId: String,
    val collectedAt: String,
    val steps: Long?,
    val activeCaloriesKcal: Double?,
    val sleepMinutes: Long?,
    val workoutMinutes: Long?,
    val workoutCount: Int?,
    val heartRateAverage: Double?,
    val heartRateMin: Double?,
    val heartRateMax: Double?
) {
    companion object {
        fun fromJson(value: JSONObject): DailyWellbeingSummary {
            val summary = value.getJSONObject("summary")
            val heartRate = summary.optJSONObject("heart_rate_bpm") ?: JSONObject()
            return DailyWellbeingSummary(
                date = value.getString("date"),
                zoneId = value.optString("zone_id", ZoneId.systemDefault().id),
                collectedAt = value.optString("collected_at", Instant.now().toString()),
                steps = summary.optionalLong("steps"),
                activeCaloriesKcal = summary.optionalDouble("active_calories_kcal"),
                sleepMinutes = summary.optionalLong("sleep_minutes"),
                workoutMinutes = summary.optionalLong("workout_minutes"),
                workoutCount = summary.optionalInt("workout_count"),
                heartRateAverage = heartRate.optionalDouble("average"),
                heartRateMin = heartRate.optionalDouble("min"),
                heartRateMax = heartRate.optionalDouble("max")
            )
        }

        private fun JSONObject.optionalLong(name: String): Long? =
            if (has(name) && !isNull(name)) getLong(name) else null

        private fun JSONObject.optionalInt(name: String): Int? =
            if (has(name) && !isNull(name)) getInt(name) else null

        private fun JSONObject.optionalDouble(name: String): Double? =
            if (has(name) && !isNull(name)) getDouble(name) else null
    }

    fun toJson(): JSONObject = JSONObject()
        .put("date", date)
        .put("zone_id", zoneId)
        .put("collected_at", collectedAt)
        .put("source", "health_connect")
        .put("summary", JSONObject()
            .put("steps", steps ?: JSONObject.NULL)
            .put("active_calories_kcal", activeCaloriesKcal ?: JSONObject.NULL)
            .put("sleep_minutes", sleepMinutes ?: JSONObject.NULL)
            .put("workout_minutes", workoutMinutes ?: JSONObject.NULL)
            .put("workout_count", workoutCount ?: JSONObject.NULL)
            .put("heart_rate_bpm", JSONObject()
                .put("average", heartRateAverage ?: JSONObject.NULL)
                .put("min", heartRateMin ?: JSONObject.NULL)
                .put("max", heartRateMax ?: JSONObject.NULL)))
        .put("raw_records", JSONArray())
        .put("wellness_only", true)
}

class HealthSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (val result = HealthSync.sync(applicationContext, requireBackgroundPermission = true)) {
        HealthSyncResult.Disabled, is HealthSyncResult.Success -> Result.success()
        is HealthSyncResult.Permanent -> {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(HealthSync.UNIQUE_PERIODIC_WORK)
            Result.success()
        }
        is HealthSyncResult.Transient -> Result.retry()
    }
}
