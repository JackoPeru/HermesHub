package com.nemoclaw.chat

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import org.json.JSONArray
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

internal fun isSensitiveBackupKey(key: String): Boolean {
    val normalized = key.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    return listOf("apikey", "token", "secret", "password", "credential", "authorization")
        .any(normalized::contains)
}

// ---------------------------------------------------------------------------
// RIPRISTINO backup locale (ADDITIVO: export/verify esistenti restano intatti).
// Policy: backup-wins per chiavi non-sensibili, mai sensibili, merge per id
// con last-write-wins su updatedAt, dry-run validante + scrittura atomica.
// ---------------------------------------------------------------------------
internal const val BACKUP_RESTORE_SCHEMA = "hermes-hub.local-backup.v1"
private const val BACKUP_RESTORE_LOG_TAG = "BackupRestore"

/** Errore esplicito di restore: payload malformato -> abort senza scritture. */
internal class BackupRestoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Report finale restore: applicate / saltate-sensibili / merge / errori. */
internal data class RestoreReport(
    val applied: Int,
    val skippedSensitive: Int,
    val conversationsMerged: Int,
    val tasksMerged: Int,
    val workspaceMerged: Int,
    val errors: List<String> = emptyList()
)

private fun logRestoreKey(suffix: String) {
    // Solo nomi chiavi/id, mai valori e mai password. runCatching: Log non mockato nei test JVM.
    runCatching { Log.i(BACKUP_RESTORE_LOG_TAG, suffix) }
}

private fun restoreValueKind(value: Any?): String = when (value) {
    null -> "null"
    is String -> "string"
    is Boolean -> "bool"
    is Number -> "number"
    else -> value.javaClass.simpleName
}

/** Conta le voci ripristinabili (per dialog "sovrascrive N voci"). Puro/testabile. */
internal fun countRestoreEntries(payload: JSONObject): Int {
    var total = 0
    payload.optJSONObject("settings")?.let { total += it.length() }
    total += payload.optJSONArray("items")?.length()
        ?: payload.optJSONArray("conversations")?.length()
        ?: extractArchiveConversationsLenient(payload)
    payload.optJSONArray("tasks")?.let { total += it.length() }
    total += workspaceArrayLenient(payload)?.length() ?: 0
    return total
}

private fun extractArchiveConversationsLenient(payload: JSONObject): Int {
    val archive = payload.optJSONObject("archive") ?: return 0
    archive.optJSONArray("items")?.let { return it.length() }
    archive.optJSONArray("conversations")?.let { return it.length() }
    val itemsRaw = archive.optString("items", "")
    if (itemsRaw.isNotBlank()) {
        return runCatching { JSONArray(itemsRaw).length() }.getOrDefault(0)
    }
    val convRaw = archive.optString("conversations", "")
    if (convRaw.isNotBlank()) {
        return runCatching { JSONArray(convRaw).length() }.getOrDefault(0)
    }
    return 0
}

private fun workspaceArrayLenient(payload: JSONObject): JSONArray? {
    val ws = payload.opt("workspace") ?: return null
    if (ws is JSONArray) return ws
    if (ws is JSONObject) {
        ws.optJSONArray("items")?.let { return it }
        val raw = ws.optString("items", "")
        if (raw.isNotBlank()) {
            return runCatching { JSONArray(raw) }.getOrNull()
        }
        return JSONArray()
    }
    return null
}

private data class ValidatedRestore(
    val settings: Map<String, Any?>,
    val skippedSensitive: Int,
    val conversations: List<JSONObject>,
    val tasks: List<JSONObject>,
    val workspace: List<JSONObject>
)

/** Dry-run puro: valida l'intero payload, una voce malformata -> abort. */
private fun validateRestorePayload(payload: JSONObject): ValidatedRestore {
    if (payload.has("schema")) {
        val schema = payload.optString("schema", "")
        if (schema != BACKUP_RESTORE_SCHEMA) {
            throw BackupRestoreException("Backup non valido: schema non supportato ($schema)")
        }
    }
    // --- settings: deve essere JSONObject con valori primitivi ---
    val settingsMap = linkedMapOf<String, Any?>()
    var skippedSensitive = 0
    if (payload.has("settings")) {
        val settingsObj = payload.optJSONObject("settings")
            ?: throw BackupRestoreException("Backup non valido: sezione settings malformata")
        val keys = settingsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.isBlank()) {
                throw BackupRestoreException("Backup non valido: settings con chiave vuota")
            }
            if (isSensitiveBackupKey(key)) {
                skippedSensitive++
                continue
            }
            if (settingsObj.isNull(key)) {
                settingsMap[key] = null
                continue
            }
            val value: Any? = settingsObj.opt(key)
            when (value) {
                is String, is Boolean, is Number -> settingsMap[key] = value
                null -> settingsMap[key] = null
                else -> throw BackupRestoreException(
                    "Backup non valido: settings chiave malformata (${restoreValueKind(value)})"
                )
            }
        }
    }
    // --- conversazioni: items > conversations > archive ---
    val conversations = extractRestoreConversations(payload)
    val tasks = extractRestoreTasks(payload)
    val workspace = extractRestoreWorkspace(payload)
    return ValidatedRestore(settingsMap, skippedSensitive, conversations, tasks, workspace)
}

private fun requireConversationObjects(array: JSONArray, label: String): List<JSONObject> {
    val out = ArrayList<JSONObject>(array.length())
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i)
            ?: throw BackupRestoreException("Backup non valido: $label voce $i malformata (non oggetto)")
        val id = obj.optString("id", "")
        if (id.isBlank()) {
            throw BackupRestoreException("Backup non valido: $label voce $i senza id")
        }
        out.add(obj)
    }
    return out
}

private fun extractRestoreConversations(payload: JSONObject): List<JSONObject> {
    if (payload.has("items") && payload.optJSONArray("items") == null) {
        throw BackupRestoreException("Backup non valido: sezione items malformata")
    }
    if (!payload.has("items") && payload.has("conversations") && payload.optJSONArray("conversations") == null) {
        throw BackupRestoreException("Backup non valido: sezione conversations malformata")
    }
    payload.optJSONArray("items")?.let { return requireConversationObjects(it, "archivi") }
    payload.optJSONArray("conversations")?.let { return requireConversationObjects(it, "archivi") }
    if (payload.has("archive") && payload.optJSONObject("archive") == null && payload.optJSONArray("archive") == null) {
        throw BackupRestoreException("Backup non valido: sezione archive malformata")
    }
    payload.optJSONArray("archive")?.let { return requireConversationObjects(it, "archivi") }
    val archiveObj = payload.optJSONObject("archive") ?: return emptyList()
    archiveObj.optJSONArray("items")?.let { return requireConversationObjects(it, "archivi") }
    archiveObj.optJSONArray("conversations")?.let { return requireConversationObjects(it, "archivi") }
    val itemsRaw = archiveObj.optString("items", "")
    if (itemsRaw.isNotBlank()) {
        val parsed = runCatching { JSONArray(itemsRaw) }.getOrElse {
            throw BackupRestoreException("Backup non valido: archive.items malformato", it)
        }
        return requireConversationObjects(parsed, "archivi")
    }
    val convRaw = archiveObj.optString("conversations", "")
    if (convRaw.isNotBlank()) {
        val parsed = runCatching { JSONArray(convRaw) }.getOrElse {
            throw BackupRestoreException("Backup non valido: archive.conversations malformato", it)
        }
        return requireConversationObjects(parsed, "archivi")
    }
    return emptyList()
}

private fun extractRestoreTasks(payload: JSONObject): List<JSONObject> {
    if (!payload.has("tasks")) return emptyList()
    val array = payload.optJSONArray("tasks")
        ?: throw BackupRestoreException("Backup non valido: sezione tasks malformata")
    return requireConversationObjects(array, "tasks")
}

private fun extractRestoreWorkspace(payload: JSONObject): List<JSONObject> {
    if (!payload.has("workspace")) return emptyList()
    val raw = payload.opt("workspace")
    when (raw) {
        is JSONArray -> return requireConversationObjects(raw, "workspace")
        is JSONObject -> {
            raw.optJSONArray("items")?.let { return requireConversationObjects(it, "workspace") }
            val itemsRaw = raw.optString("items", "")
            if (itemsRaw.isBlank()) return emptyList()
            val parsed = runCatching { JSONArray(itemsRaw) }.getOrElse {
                throw BackupRestoreException("Backup non valido: workspace.items malformato", it)
            }
            return requireConversationObjects(parsed, "workspace")
        }
        else -> throw BackupRestoreException("Backup non valido: sezione workspace malformata")
    }
}

private fun mergeJsonObjectsById(existing: JSONArray, incoming: List<JSONObject>): Pair<JSONArray, Int> {
    val byId = linkedMapOf<String, JSONObject>()
    for (i in 0 until existing.length()) {
        val obj = existing.optJSONObject(i) ?: continue
        val id = obj.optString("id", "")
        if (id.isBlank()) continue
        byId[id] = obj
    }
    var merged = 0
    for (item in incoming) {
        val id = item.optString("id", "")
        val current = byId[id]
        if (current == null) {
            byId[id] = item
            merged++
        } else {
            val currentUpdated = current.optLong("updatedAt", 0L)
            val incomingUpdated = item.optLong("updatedAt", 0L)
            if (incomingUpdated >= currentUpdated) {
                byId[id] = item
                merged++
            }
        }
    }
    val sorted = byId.values.sortedByDescending { it.optLong("updatedAt", 0L) }
    val array = JSONArray()
    sorted.forEach { array.put(it) }
    return array to merged
}

private fun readCurrentJsonArray(prefsName: String, context: Context): JSONArray {
    val raw = runCatching {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString("items", "[]") ?: "[]"
    }.getOrDefault("[]")
    return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
}

/**
 * Applica le voci del backup a SharedPreferences/archivio.
 * @param password conservato per firma: decode gia avvenuto a monte, mai loggato, mai persistito.
 * Policy: backup-wins per chiavi non-sensibili con Log.i per chiave (mai valori);
 * chiavi sensibili mai ripristinate; merge per id senza duplicati con
 * last-write-wins su updatedAt; dry-run validante con abort senza scritture.
 */
internal fun restoreLocalBackup(context: Context, payload: JSONObject, @Suppress("UNUSED_PARAMETER") password: String? = null): RestoreReport {
    // password: intenzionalmente inutilizzata qui (decode a monte); mai loggare né persistere.
    val validated = validateRestorePayload(payload)
    // --- Fase scrittura: solo dopo validazione completa (atomicita logica) ---
    var settingsApplied = 0
    if (validated.settings.isNotEmpty() || validated.skippedSensitive > 0) {
        val prefs = migratePrefs(context, CURRENT_SETTINGS_PREFS, LEGACY_SETTINGS_PREFS)
        prefs.edit {
            validated.settings.forEach { (key, value) ->
                logRestoreKey("restore settings key=$key")
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Double -> putFloat(key, value.toFloat())
                    is Number -> {
                        // Interi oltre Int -> Long, decimali -> Float.
                        val longValue = value.toLong()
                        if (value.toDouble() % 1.0 == 0.0 && longValue in Int.MIN_VALUE..Int.MAX_VALUE) {
                            putInt(key, longValue.toInt())
                        } else if (value.toDouble() % 1.0 == 0.0) {
                            putLong(key, longValue)
                        } else {
                            putFloat(key, value.toFloat())
                        }
                    }
                    is String -> putString(key, value)
                    else -> throw BackupRestoreException("Backup non valido: settings valore non supportato")
                }
                settingsApplied++
            }
        }
    }
    // --- Conversazioni: upsert per id, last-write-wins su updatedAt ---
    var conversationsMerged = 0
    if (validated.conversations.isNotEmpty()) {
        val incomingArray = JSONArray()
        validated.conversations.forEach { incomingArray.put(it) }
        val backupList = readConversationsFromJsonArray(incomingArray)
        val current = loadConversations(context, includeDeleted = true)
        val byId = current.associateBy { it.id }.toMutableMap()
        for (item in backupList) {
            logRestoreKey("restore conversation id=${item.id}")
            val existing = byId[item.id]
            if (existing == null || item.updatedAt >= existing.updatedAt) {
                byId[item.id] = item
                conversationsMerged++
            }
        }
        saveConversations(context, byId.values.toList())
    }
    // --- Tasks: merge JSON per id (preserva campi extra), cap 200 come saveTasks ---
    var tasksMerged = 0
    if (validated.tasks.isNotEmpty()) {
        val current = readCurrentJsonArray("chatclaw_tasks", context)
        val (merged, count) = mergeJsonObjectsById(current, validated.tasks)
        validated.tasks.forEach { logRestoreKey("restore task id=${it.optString("id")}") }
        val capped = JSONArray()
        for (i in 0 until minOf(merged.length(), 200)) {
            merged.optJSONObject(i)?.let { capped.put(it) }
        }
        context.getSharedPreferences("chatclaw_tasks", Context.MODE_PRIVATE)
            .edit {
                putString("items", capped.toString())
            }
        tasksMerged = count
    }
    // --- Workspace: merge JSON per id, cap 200 ---
    var workspaceMerged = 0
    if (validated.workspace.isNotEmpty()) {
        val current = readCurrentJsonArray("chatclaw_workspace_requests", context)
        val (merged, count) = mergeJsonObjectsById(current, validated.workspace)
        validated.workspace.forEach { logRestoreKey("restore workspace id=${it.optString("id")}") }
        val capped = JSONArray()
        for (i in 0 until minOf(merged.length(), 200)) {
            merged.optJSONObject(i)?.let { capped.put(it) }
        }
        context.getSharedPreferences("chatclaw_workspace_requests", Context.MODE_PRIVATE)
            .edit {
                putString("items", capped.toString())
            }
        workspaceMerged = count
    }
    return RestoreReport(
        applied = settingsApplied,
        skippedSensitive = validated.skippedSensitive,
        conversationsMerged = conversationsMerged,
        tasksMerged = tasksMerged,
        workspaceMerged = workspaceMerged,
        errors = emptyList()
    )
}
