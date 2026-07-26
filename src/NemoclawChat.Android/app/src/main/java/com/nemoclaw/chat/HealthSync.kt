package com.nemoclaw.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

internal object HealthSync {
    const val UNIQUE_PERIODIC_WORK = "hermes-health-sync-periodic"
    const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

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
        if (Build.VERSION.SDK_INT >= 28 && HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE) == HealthConnectClient.SDK_AVAILABLE) {
            val client = HealthConnectClient.getOrCreate(context)
            if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
                add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            }
        }
    }

    fun permissionRequestContract() = PermissionController.createRequestPermissionResultContract(PROVIDER_PACKAGE)

    fun openSettings(context: Context): Boolean = runCatching {
        context.startActivity(Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"))
        true
    }.getOrDefault(false)

    fun sdkStatus(context: Context): String = when {
        Build.VERSION.SDK_INT < 28 -> "Health Connect richiede Android 9 o successivo."
        HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE) == HealthConnectClient.SDK_AVAILABLE -> "Health Connect disponibile."
        HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE) == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Aggiorna o installa Health Connect."
        else -> "Health Connect non disponibile su questo telefono."
    }

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
        val appContext = context.applicationContext
        val settings = loadSettings(appContext)
        if (!settings.healthSyncEnabled) return@withContext HealthSyncResult.Disabled
        if (!hasConfiguredHermesEndpoint(settings)) return@withContext HealthSyncResult.Permanent("Configura Hermes API URL prima della sincronizzazione salute.")
        if (Build.VERSION.SDK_INT < 28 || HealthConnectClient.getSdkStatus(appContext, PROVIDER_PACKAGE) != HealthConnectClient.SDK_AVAILABLE) {
            return@withContext HealthSyncResult.Permanent(sdkStatus(appContext))
        }

        val client = HealthConnectClient.getOrCreate(appContext)
        val required = requiredPermissions(settings)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(required)) return@withContext HealthSyncResult.Permanent("Permessi Health Connect mancanti.")
        if (requireBackgroundPermission && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted) {
            return@withContext HealthSyncResult.Permanent("Lettura salute in background non autorizzata.")
        }

        try {
            val summary = readDailySummary(client, settings)
            upload(settings, loadGatewaySecret(appContext), summary)
            appContext.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE).edit {
                putLong("last_success_at", System.currentTimeMillis())
                putString("last_date", summary.date)
            }
            HealthSyncResult.Success(summary)
        } catch (error: SecurityException) {
            HealthSyncResult.Permanent(error.message ?: "Permesso Health Connect revocato.")
        } catch (error: IllegalStateException) {
            HealthSyncResult.Permanent(error.message ?: "Gateway rifiuta i dati salute.")
        } catch (error: IOException) {
            HealthSyncResult.Transient(error.message ?: "Rete Hermes non disponibile.")
        } catch (error: Exception) {
            HealthSyncResult.Transient(error.message ?: error.javaClass.simpleName)
        }
    }

    private suspend fun readDailySummary(client: HealthConnectClient, settings: AppSettings): DailyWellbeingSummary {
        val zone = ZoneId.systemDefault()
        val start = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val end = Instant.now()
        val range = TimeRangeFilter.between(start, end)
        var steps: Long? = null
        var calories: Double? = null
        if (settings.healthIncludeSteps) {
            val aggregate = client.aggregate(
                AggregateRequest(setOf(StepsRecord.COUNT_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL), range)
            )
            steps = aggregate[StepsRecord.COUNT_TOTAL]
            calories = aggregate[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
        }
        val sleep = if (settings.healthIncludeSleep) readAll(client, SleepSessionRecord::class, range) else emptyList()
        val workouts = if (settings.healthIncludeWorkouts) readAll(client, ExerciseSessionRecord::class, range) else emptyList()
        val heartRate = if (settings.healthIncludeHeartRate) readAll(client, HeartRateRecord::class, range) else emptyList()
        val sleepMinutes = sleep.sumOf { java.time.Duration.between(it.startTime, it.endTime).toMinutes() }
        val activeMinutes = workouts.sumOf { java.time.Duration.between(it.startTime, it.endTime).toMinutes() }
        val samples = heartRate.flatMap { it.samples }.map { it.beatsPerMinute.toDouble() }
        return DailyWellbeingSummary(
            date = DateTimeFormatter.ISO_LOCAL_DATE.format(java.time.LocalDate.now(zone)),
            zoneId = zone.id,
            collectedAt = Instant.now().toString(),
            steps = steps,
            activeCaloriesKcal = calories,
            sleepMinutes = sleepMinutes.takeIf { settings.healthIncludeSleep },
            workoutMinutes = activeMinutes.takeIf { settings.healthIncludeWorkouts },
            workoutCount = workouts.size.takeIf { settings.healthIncludeWorkouts },
            heartRateAverage = samples.average().takeIf { samples.isNotEmpty() },
            heartRateMin = samples.minOrNull(),
            heartRateMax = samples.maxOrNull()
        )
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
            if (response.code in 400..499) throw IllegalStateException("Gateway rifiuta dati salute: HTTP ${response.code} $body")
            throw IOException("Gateway salute HTTP ${response.code}: $body")
        }
    }

    private fun wellbeingUrl(settings: AppSettings, date: String): String {
        return "${wellbeingCollectionUrl(settings)}/daily/$date"
    }

    private fun wellbeingCollectionUrl(settings: AppSettings): String {
        val gateway = settings.gatewayUrl.trim().trimEnd('/')
        val root = if (gateway.endsWith("/v1", ignoreCase = true)) gateway.removeSuffix("/v1") else gateway
        val uri = URI(root)
        require(uri.scheme == "http" || uri.scheme == "https") { "Endpoint Hermes non valido." }
        return "$root/v1/hub/wellbeing"
    }

    private const val HEALTH_PREFS = "hermes_health_sync"
}

internal sealed interface HealthSyncResult {
    data object Disabled : HealthSyncResult
    data class Success(val summary: DailyWellbeingSummary) : HealthSyncResult
    data class Permanent(val message: String) : HealthSyncResult
    data class Transient(val message: String) : HealthSyncResult
}

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
