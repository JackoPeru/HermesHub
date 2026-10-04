package com.nemoclaw.chat

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal const val BACKUP_KEYSTORE_ALIAS = "hermes_backup"
internal const val BACKUP_ENVELOPE_VERSION = 1
internal const val BACKUP_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
internal const val BACKUP_GCM_TAG_BITS = 128
internal const val BACKUP_GCM_IV_BYTES = 12
private const val BACKUP_KEYSTORE_PROVIDER = "AndroidKeyStore"
private val backupKeyLock = Any()

/** Errore esplicito di lettura backup: mai crash grezzo, mai dati in chiaro. */
internal class BackupDecryptException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun getOrCreateBackupKey(): SecretKey = synchronized(backupKeyLock) {
    val keyStore = KeyStore.getInstance(BACKUP_KEYSTORE_PROVIDER).apply { load(null) }
    (keyStore.getEntry(BACKUP_KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let {
        return@synchronized it
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, BACKUP_KEYSTORE_PROVIDER)
    generator.init(
        KeyGenParameterSpec.Builder(
            BACKUP_KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()
    )
    generator.generateKey()
}

/**
 * Cifra il payload in envelope versionata {"v":1,"iv":base64,"data":base64}.
 * IV random 12 byte per export (passabile per test deterministici).
 * Base64 java.util: funziona su JVM unit-test e su Android 26+.
 */
internal fun encryptBackupEnvelope(plainBytes: ByteArray, key: SecretKey, iv: ByteArray? = null): String {
    val actualIv = iv ?: ByteArray(BACKUP_GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
    require(actualIv.size == BACKUP_GCM_IV_BYTES) { "IV deve essere di $BACKUP_GCM_IV_BYTES byte" }
    val cipher = Cipher.getInstance(BACKUP_GCM_TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(BACKUP_GCM_TAG_BITS, actualIv))
    val cipherBytes = cipher.doFinal(plainBytes)
    val encoder = java.util.Base64.getEncoder()
    return JSONObject()
        .put("v", BACKUP_ENVELOPE_VERSION)
        .put("iv", encoder.encodeToString(actualIv))
        .put("data", encoder.encodeToString(cipherBytes))
        .toString()
}

/** Decifra un envelope v1; ogni anomalia diventa BackupDecryptException esplicita. */
internal fun decryptBackupEnvelope(envelopeText: String, key: SecretKey): ByteArray {
    val envelope = runCatching { JSONObject(envelopeText) }.getOrElse {
        throw BackupDecryptException("Backup non valido: envelope JSON illeggibile", it)
    }
    val version = envelope.optInt("v", -1)
    if (version != BACKUP_ENVELOPE_VERSION) {
        throw BackupDecryptException(
            "Backup non valido: versione envelope non supportata (v=$version, attesa v=$BACKUP_ENVELOPE_VERSION)"
        )
    }
    val ivB64 = envelope.optString("iv", "")
    val dataB64 = envelope.optString("data", "")
    if (ivB64.isBlank() || dataB64.isBlank()) {
        throw BackupDecryptException("Backup non valido: envelope v1 senza iv/data")
    }
    val decoder = java.util.Base64.getDecoder()
    val iv = runCatching { decoder.decode(ivB64) }.getOrElse {
        throw BackupDecryptException("Backup non valido: iv base64 illeggibile", it)
    }
    val cipherBytes = runCatching { decoder.decode(dataB64) }.getOrElse {
        throw BackupDecryptException("Backup non valido: data base64 illeggibile", it)
    }
    if (iv.size != BACKUP_GCM_IV_BYTES) {
        throw BackupDecryptException("Backup non valido: IV lungo ${iv.size} byte, attesi $BACKUP_GCM_IV_BYTES")
    }
    if (cipherBytes.isEmpty()) {
        throw BackupDecryptException("Backup non valido: payload vuoto")
    }
    return runCatching {
        val cipher = Cipher.getInstance(BACKUP_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(BACKUP_GCM_TAG_BITS, iv))
        cipher.doFinal(cipherBytes)
    }.getOrElse {
        if (it is BackupDecryptException) throw it
        throw BackupDecryptException("Backup non valido: decifratura fallita (chiave errata o dati corrotti)", it)
    }
}

/** Heuristica envelope: JSON con iv + data (qualsiasi v). Tutto il resto = legacy plain. */
internal fun isBackupEnvelope(rawText: String): Boolean {
    val trimmed = rawText.trim()
    if (!trimmed.startsWith("{")) return false
    return runCatching {
        val obj = JSONObject(trimmed)
        obj.has("iv") && obj.has("data")
    }.getOrDefault(false)
}

/**
 * Reader auto-detect: envelope v1 -> decifra, altrimenti fallback legacy plain.
 * Gli errori di envelope corrotta lanciano BackupDecryptException (mai plain, mai crash).
 */
internal fun decodeBackupPayload(rawText: String, key: SecretKey): ByteArray {
    return if (isBackupEnvelope(rawText)) decryptBackupEnvelope(rawText, key)
    else rawText.toByteArray(Charsets.UTF_8)
}

// ---------------------------------------------------------------------------
// Backup con password (envelope v2, ADDITIVO: v1 Keystore e legacy plain restano intatti).
// PBKDF2-HMAC-SHA256 210k iterazioni + AES-256-GCM. Solo javax.crypto, niente nuove dipendenze.
// La password non viene mai salvata: vive solo nel campo UI (remember, non persistito).
// ---------------------------------------------------------------------------
internal const val BACKUP_PASSWORD_ENVELOPE_VERSION = 2
internal const val BACKUP_PASSWORD_ITERATIONS = 210_000
internal const val BACKUP_PASSWORD_SALT_BYTES = 16
internal const val BACKUP_PASSWORD_MIN_SALT_BYTES = 8
internal const val BACKUP_PASSWORD_MAX_SALT_BYTES = 64
internal const val BACKUP_PASSWORD_MIN_ITERATIONS = 10_000
internal const val BACKUP_PASSWORD_MAX_ITERATIONS = 5_000_000

/** Deriva una chiave AES-256 da password via PBKDF2-HMAC-SHA256. Pura/testabile. */
internal fun deriveBackupPasswordKey(
    password: String,
    salt: ByteArray,
    iterations: Int = BACKUP_PASSWORD_ITERATIONS
): SecretKey {
    require(password.isNotEmpty()) { "Password backup vuota" }
    require(salt.size in BACKUP_PASSWORD_MIN_SALT_BYTES..BACKUP_PASSWORD_MAX_SALT_BYTES) {
        "Salt deve essere tra $BACKUP_PASSWORD_MIN_SALT_BYTES e $BACKUP_PASSWORD_MAX_SALT_BYTES byte"
    }
    require(iterations in BACKUP_PASSWORD_MIN_ITERATIONS..BACKUP_PASSWORD_MAX_ITERATIONS) {
        "Iterazioni PBKDF2 fuori range ($BACKUP_PASSWORD_MIN_ITERATIONS..$BACKUP_PASSWORD_MAX_ITERATIONS)"
    }
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
    try {
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    } finally {
        spec.clearPassword()
    }
}

/**
 * Cifra il payload in envelope v2 {"v":2,"salt":b64,"iv":b64,"iter":n,"data":b64}.
 * Salt 16B + IV 12B random (passabili per test deterministici). Pura/testabile.
 */
internal fun encryptBackupEnvelopeWithPassword(
    plainBytes: ByteArray,
    password: String,
    salt: ByteArray? = null,
    iv: ByteArray? = null,
    iterations: Int = BACKUP_PASSWORD_ITERATIONS
): String {
    val actualSalt = salt ?: ByteArray(BACKUP_PASSWORD_SALT_BYTES).also { SecureRandom().nextBytes(it) }
    val actualIv = iv ?: ByteArray(BACKUP_GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
    require(actualSalt.size in BACKUP_PASSWORD_MIN_SALT_BYTES..BACKUP_PASSWORD_MAX_SALT_BYTES) {
        "Salt deve essere tra $BACKUP_PASSWORD_MIN_SALT_BYTES e $BACKUP_PASSWORD_MAX_SALT_BYTES byte"
    }
    require(actualIv.size == BACKUP_GCM_IV_BYTES) { "IV deve essere di $BACKUP_GCM_IV_BYTES byte" }
    val key = deriveBackupPasswordKey(password, actualSalt, iterations)
    val cipher = Cipher.getInstance(BACKUP_GCM_TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(BACKUP_GCM_TAG_BITS, actualIv))
    val cipherBytes = cipher.doFinal(plainBytes)
    val encoder = java.util.Base64.getEncoder()
    return JSONObject()
        .put("v", BACKUP_PASSWORD_ENVELOPE_VERSION)
        .put("salt", encoder.encodeToString(actualSalt))
        .put("iv", encoder.encodeToString(actualIv))
        .put("iter", iterations)
        .put("data", encoder.encodeToString(cipherBytes))
        .toString()
}

/** Decifra un envelope v2 con password; ogni anomalia -> BackupDecryptException. Pura/testabile. */
internal fun decryptBackupEnvelopeWithPassword(envelopeText: String, password: String): ByteArray {
    val envelope = runCatching { JSONObject(envelopeText) }.getOrElse {
        throw BackupDecryptException("Backup non valido: envelope v2 JSON illeggibile", it)
    }
    val version = envelope.optInt("v", -1)
    if (version != BACKUP_PASSWORD_ENVELOPE_VERSION) {
        throw BackupDecryptException(
            "Backup non valido: versione envelope non supportata " +
                "(v=$version, attesa v=$BACKUP_PASSWORD_ENVELOPE_VERSION)"
        )
    }
    if (password.isEmpty()) {
        throw BackupDecryptException("Backup non valido: backup v2 protetto da password, password richiesta")
    }
    val saltB64 = envelope.optString("salt", "")
    val ivB64 = envelope.optString("iv", "")
    val dataB64 = envelope.optString("data", "")
    val iterations = envelope.optInt("iter", -1)
    if (saltB64.isBlank() || ivB64.isBlank() || dataB64.isBlank() ||
        iterations !in BACKUP_PASSWORD_MIN_ITERATIONS..BACKUP_PASSWORD_MAX_ITERATIONS
    ) {
        throw BackupDecryptException("Backup non valido: envelope v2 senza salt/iv/iter/data validi")
    }
    val decoder = java.util.Base64.getDecoder()
    val salt = runCatching { decoder.decode(saltB64) }.getOrElse {
        throw BackupDecryptException("Backup non valido: salt v2 base64 illeggibile", it)
    }
    val iv = runCatching { decoder.decode(ivB64) }.getOrElse {
        throw BackupDecryptException("Backup non valido: iv v2 base64 illeggibile", it)
    }
    val cipherBytes = runCatching { decoder.decode(dataB64) }.getOrElse {
        throw BackupDecryptException("Backup non valido: data v2 base64 illeggibile", it)
    }
    if (salt.size !in BACKUP_PASSWORD_MIN_SALT_BYTES..BACKUP_PASSWORD_MAX_SALT_BYTES) {
        throw BackupDecryptException("Backup non valido: salt v2 di ${salt.size} byte")
    }
    if (iv.size != BACKUP_GCM_IV_BYTES) {
        throw BackupDecryptException("Backup non valido: IV v2 lungo ${iv.size} byte, attesi $BACKUP_GCM_IV_BYTES")
    }
    if (cipherBytes.isEmpty()) {
        throw BackupDecryptException("Backup non valido: payload v2 vuoto")
    }
    val key = runCatching { deriveBackupPasswordKey(password, salt, iterations) }.getOrElse {
        throw BackupDecryptException("Backup non valido: derivazione chiave v2 fallita", it)
    }
    return runCatching {
        val cipher = Cipher.getInstance(BACKUP_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(BACKUP_GCM_TAG_BITS, iv))
        cipher.doFinal(cipherBytes)
    }.getOrElse {
        throw BackupDecryptException("Backup non valido: password errata o dati corrotti", it)
    }
}

/** Heuristica envelope v2: JSON con v=2 + salt/iv/data. */
internal fun isBackupEnvelopeV2(rawText: String): Boolean {
    val trimmed = rawText.trim()
    if (!trimmed.startsWith("{")) return false
    return runCatching {
        val obj = JSONObject(trimmed)
        obj.optInt("v", -1) == BACKUP_PASSWORD_ENVELOPE_VERSION &&
            obj.has("salt") && obj.has("iv") && obj.has("data")
    }.getOrDefault(false)
}

/**
 * Export cifrato con password (contenuto -> envelope v2). Puro/testabile, niente IO.
 * Accetta salt/iv opzionali per test deterministici.
 */
internal fun exportEncryptedWithPassword(
    plainBytes: ByteArray,
    password: String,
    salt: ByteArray? = null,
    iv: ByteArray? = null
): String {
    return encryptBackupEnvelopeWithPassword(plainBytes, password, salt, iv)
}

/** Export cifrato con password su file (ritorna l'envelope scritta). Solo IO separata. */
internal fun exportEncryptedWithPassword(file: File, plainBytes: ByteArray, password: String): String {
    val envelope = exportEncryptedWithPassword(plainBytes, password)
    file.parentFile?.mkdirs()
    file.writeText(envelope, Charsets.UTF_8)
    return envelope
}

/**
 * Reader auto-detect con password: v2 -> password, v1 envelope -> errore esplicito
 * (serve la chiave dispositivo: importare senza password), altrimenti legacy plain.
 * Mai fallback in chiaro su envelope corrotta: BackupDecryptException.
 */
internal fun importWithPassword(json: String, password: String): ByteArray {
    val trimmed = json.trim()
    if (isBackupEnvelopeV2(trimmed)) return decryptBackupEnvelopeWithPassword(trimmed, password)
    if (!isBackupEnvelope(trimmed)) return trimmed.toByteArray(Charsets.UTF_8)
    throw BackupDecryptException(
        "Backup non valido: envelope con chiave dispositivo (v1), importare senza password"
    )
}

/**
 * Reader auto-detect completo v2/v1/plain con chiave dispositivo + password opzionale.
 * v2 senza password -> BackupDecryptException esplicita (mai plain, mai crash).
 */
internal fun decodeBackupPayloadWithPassword(rawText: String, key: SecretKey, password: String?): ByteArray {
    val trimmed = rawText.trim()
    if (isBackupEnvelopeV2(trimmed)) {
        if (password.isNullOrEmpty()) {
            throw BackupDecryptException("Backup non valido: backup v2 protetto da password, password richiesta")
        }
        return decryptBackupEnvelopeWithPassword(trimmed, password)
    }
    if (isBackupEnvelope(trimmed)) return decryptBackupEnvelope(trimmed, key)
    return trimmed.toByteArray(Charsets.UTF_8)
}

internal fun exportLocalBackup(context: Context): String {
    val plain = buildLocalBackupPayload(context)
    val envelope = encryptBackupEnvelope(plain, getOrCreateBackupKey())
    val file = writeAndShareBackupFile(context, envelope, suffix = "")
    return "Backup pronto: ${file.name}"
}

/**
 * Export con password (envelope v2, portabile anche su altro dispositivo:
 * non dipende dall'AndroidKeyStore). La password non viene mai salvata.
 */
internal fun exportLocalBackupWithPassword(context: Context, password: String): String {
    require(password.isNotEmpty()) { "Password backup vuota" }
    val plain = buildLocalBackupPayload(context)
    val envelope = exportEncryptedWithPassword(plain, password)
    val file = writeAndShareBackupFile(context, envelope, suffix = "-pw")
    return "Backup cifrato pronto: ${file.name}"
}

private fun buildLocalBackupPayload(context: Context): ByteArray {
    val archivePrefs = sharedPreferencesJson(context, "chatclaw_archive")
    val conversations = archiveJsonArray(archivePrefs)
    val backup = JSONObject()
        .put("schema", "hermes-hub.local-backup.v1")
        .put("exportedAt", System.currentTimeMillis())
        .put("packageName", context.packageName)
        .put("settings", sharedPreferencesJson(context, "chatclaw_settings"))
        .put("archive", archivePrefs)
        .put("items", conversations)
        .put("conversations", conversations)
        .put("tasks", parseJsonArray(sharedPreferencesJson(context, "chatclaw_tasks").optString("items", "[]")))
        .put("workspace", sharedPreferencesJson(context, "chatclaw_workspace_requests"))
    return backup.toString(2).toByteArray(Charsets.UTF_8)
}

private fun writeAndShareBackupFile(context: Context, envelope: String, suffix: String): File {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    // Prune vecchi export: tieni gli ultimi 3, niente accumulo in cache.
    runCatching {
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(3)
            ?.forEach { it.delete() }
    }
    val file = File(dir, "HermesHub-backup-$timestamp$suffix.json")
    file.writeText(envelope, Charsets.UTF_8)

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val shareIntent = Intent(Intent.ACTION_SEND)
        .setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    context.startActivity(Intent.createChooser(shareIntent, "Backup Hermes Hub"))
    return file
}

private fun parseJsonArray(raw: String): Any {
    return runCatching { org.json.JSONArray(raw) }.getOrElse { raw }
}

private fun archiveJsonArray(archivePrefs: JSONObject): Any {
    val rawItems = archivePrefs.optString("items", "")
    if (rawItems.isNotBlank()) {
        return parseJsonArray(rawItems)
    }
    return parseJsonArray(archivePrefs.optString("conversations", "[]"))
}

private fun sharedPreferencesJson(context: Context, name: String): JSONObject {
    val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val obj = JSONObject()
    prefs.all.toSortedMap().forEach { (key, value) ->
        if (isSensitiveBackupKey(key)) return@forEach
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

private fun isSensitiveBackupKey(key: String): Boolean {
    val normalized = key.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    return listOf("apikey", "token", "secret", "password", "credential", "authorization")
        .any(normalized::contains)
}
