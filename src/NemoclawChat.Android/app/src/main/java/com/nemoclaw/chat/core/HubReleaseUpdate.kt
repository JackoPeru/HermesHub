package com.nemoclaw.chat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray

internal fun summarizeReleaseNotes(body: String): String {
    return body.trim().limitText(4000)
}

internal fun findReleaseAsset(assets: JSONArray, suffix: String): String? {
    var fallback: String? = null
    for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val name = asset.optString("name")
        val url = asset.optString("browser_download_url")
        if (name.endsWith(suffix, ignoreCase = true) && url.isNotBlank()) {
            if (fallback == null && !name.contains("debug", ignoreCase = true)) fallback = url
            if (name.startsWith("HermesHub-", ignoreCase = true) &&
                name.contains("android", ignoreCase = true) &&
                !name.contains("debug", ignoreCase = true)
            ) return url
        }
    }
    return fallback
}

internal fun compareVersions(latest: String, local: String): Int {
    val latestParts = parseVersionParts(latest)
    val localParts = parseVersionParts(local)
    for (i in 0 until maxOf(latestParts.size, localParts.size)) {
        val left = latestParts.getOrElse(i) { 0 }
        val right = localParts.getOrElse(i) { 0 }
        if (left != right) return left.compareTo(right)
    }

    return 0
}

internal fun parseVersionParts(value: String): List<Int> {
    return normalizeVersion(value)
        .substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
}

internal fun normalizeVersion(value: String): String {
    return value.trim().trimStart('v', 'V')
}

internal fun downloadProgressLabel(progress: Float?, label: String): String {
    val safe = (progress ?: 0f).coerceIn(0f, 1f)
    val percent = "${(safe * 100).toInt()}%"
    return if (label.isBlank()) percent else "$percent  $label"
}

internal const val MAX_UPDATE_APK_BYTES = 250L * 1024L * 1024L

internal fun isAdvertisedUpdateApkSizeRejected(contentLength: Long): Boolean =
    contentLength > MAX_UPDATE_APK_BYTES

internal fun wouldExceedUpdateApkSizeLimit(downloadedBytes: Long, nextChunkBytes: Int): Boolean =
    nextChunkBytes > 0 && downloadedBytes > MAX_UPDATE_APK_BYTES - nextChunkBytes.toLong()

internal suspend fun downloadUpdateApk(
    context: Context,
    assetUrl: String,
    version: String,
    onProgress: (Float, String, String) -> Unit
): File? = withContext(Dispatchers.IO) {
    val targetDirectory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "exports").apply { mkdirs() }
    val targetFile = File(targetDirectory, "HermesHub-${normalizeVersion(version)}.apk")
    val partialFile = File(targetDirectory, "${targetFile.name}.part")
    partialFile.delete()
    val request = Request.Builder()
        .url(assetUrl)
        .header("Accept", "application/octet-stream")
        .header("User-Agent", "HermesHub-Android")
        .build()

    try {
        updateHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@withContext null
            }

            val body = response.body
            val totalBytes = body.contentLength()
            if (isAdvertisedUpdateApkSizeRejected(totalBytes)) {
                withContext(Dispatchers.Main) {
                    onProgress(0f, "APK rifiutato: dimensione superiore a 250 MiB.", "")
                }
                return@withContext null
            }
            var downloadedBytes = 0L
            var lastPercent = -1
            var exceededSizeLimit = false

            body.byteStream().use { input ->
                FileOutputStream(partialFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        if (wouldExceedUpdateApkSizeLimit(downloadedBytes, read)) {
                            exceededSizeLimit = true
                            break
                        }

                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        if (totalBytes > 0) {
                            val progress = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            val percent = (progress * 100).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                val sizeLabel = if (totalBytes > 0L) {
                                    "${downloadedBytes.toReadableFileSize()} / ${totalBytes.toReadableFileSize()}"
                                } else {
                                    downloadedBytes.toReadableFileSize()
                                }
                                withContext(Dispatchers.Main) {
                                    onProgress(progress, "Scaricamento APK in corso... $percent%", sizeLabel)
                                }
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            if (exceededSizeLimit) {
                partialFile.delete()
                withContext(Dispatchers.Main) {
                    onProgress(0f, "APK rifiutato: download superiore a 250 MiB.", "")
                }
                return@withContext null
            }
            if (totalBytes > 0L && downloadedBytes != totalBytes) {
                partialFile.delete()
                return@withContext null
            }
        }

        val validationError = validateUpdateApk(context, partialFile, version)
        if (validationError != null) {
            partialFile.delete()
            withContext(Dispatchers.Main) { onProgress(0f, "APK rifiutato: $validationError", "") }
            return@withContext null
        }
        if (targetFile.exists() && !targetFile.delete()) {
            partialFile.delete()
            return@withContext null
        }
        if (!partialFile.renameTo(targetFile)) {
            partialFile.delete()
            return@withContext null
        }

        withContext(Dispatchers.Main) {
            onProgress(1f, "Download completato. APK pronto per l'installazione.", targetFile.length().toReadableFileSize())
        }
        targetFile
    } catch (_: Exception) {
        partialFile.delete()
        null
    }
}

internal fun installDownloadedApk(context: Context, apkPath: String): String {
    val apkFile = File(apkPath)
    if (!apkFile.exists()) {
        return "APK non trovato. Riscarica l'aggiornamento."
    }
    val expectedVersion = apkFile.name.removePrefix("HermesHub-").removeSuffix(".apk")
    validateUpdateApk(context, apkFile, expectedVersion)?.let {
        runCatching { apkFile.delete() }
        return "APK non valido: $it. Riscarica l'aggiornamento."
    }

    if (!context.packageManager.canRequestPackageInstalls()) {
        openAndroidIntent(
            context,
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri()
            )
        )
        return "Consenti a Hermes Hub di installare APK sconosciuti, poi premi di nuovo Aggiorna."
    }

    val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
    val installIntent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(contentUri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    return if (openAndroidIntent(context, installIntent)) {
        "Installer Android aperto. Conferma l'aggiornamento."
    } else {
        "Impossibile aprire l'installer Android."
    }
}

internal fun findDownloadedUpdateApk(context: Context, version: String): File? {
    val normalizedVersion = normalizeVersion(version)
    val targetDirectory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "exports")
    val targetFile = File(targetDirectory, "HermesHub-$normalizedVersion.apk")
    if (!targetFile.isFile) return null
    val error = validateUpdateApk(context, targetFile, version)
    if (error != null) {
        runCatching { targetFile.delete() }
        return null
    }
    return targetFile
}

internal fun validateUpdateApk(context: Context, apkFile: File, expectedVersion: String): String? {
    if (!apkFile.isFile || apkFile.length() <= 0L) return "file vuoto"
    if (apkFile.length() > MAX_UPDATE_APK_BYTES) return "file superiore a 250 MiB"
    val packageManager = context.packageManager
    return runCatching {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return "APK non leggibile"
        if (archive.packageName != context.packageName) return "applicationId inatteso: ${archive.packageName}"
        if (normalizeVersion(archive.versionName.orEmpty()) != normalizeVersion(expectedVersion)) {
            return "versione ${archive.versionName.orEmpty()} diversa da ${normalizeVersion(expectedVersion)}"
        }
        @Suppress("DEPRECATION")
        val installed = packageManager.getPackageInfo(context.packageName, flags)
        val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION") archive.signatures.orEmpty()
        }
        val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.signingInfo?.signingCertificateHistory.orEmpty()
        } else {
            @Suppress("DEPRECATION") installed.signatures.orEmpty()
        }
        if (archiveSignatures.isEmpty() || installedSignatures.isEmpty() ||
            archiveSignatures.none { candidate -> installedSignatures.any { it.toByteArray().contentEquals(candidate.toByteArray()) } }
        ) return "firma diversa dall'app installata"
        null
    }.getOrElse { "verifica APK fallita: ${it.message ?: it.javaClass.simpleName}" }
}

internal fun Long.toReadableFileSize(): String {
    if (this <= 0L) {
        return "0 B"
    }

    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var size = this.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024.0
        unitIndex++
    }

    return if (unitIndex == 0) {
        "${size.toLong()} ${units[unitIndex]}"
    } else {
        String.format(java.util.Locale.US, "%.1f %s", size, units[unitIndex])
    }
}

