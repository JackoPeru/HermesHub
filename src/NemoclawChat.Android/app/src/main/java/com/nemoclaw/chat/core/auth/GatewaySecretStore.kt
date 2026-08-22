package com.nemoclaw.chat

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val CURRENT_SETTINGS_PREFS = "chatclaw_settings"
internal const val LEGACY_SETTINGS_PREFS = "nemoclaw_settings"
private const val GATEWAY_SECRET_PREF_KEY = "gatewaySecretCiphertext"
private const val GATEWAY_SECRET_KEYSTORE = "AndroidKeyStore"
private const val GATEWAY_SECRET_ALIAS = "HermesHubGatewayApiKey"
private const val GATEWAY_SECRET_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GATEWAY_SECRET_AAD = "HermesHub.ApiKey.v1"
private const val CONNECTION_SECRET_PREFS = "chatclaw_connection_secrets"
private const val CONNECTION_SECRET_ALIAS = "HermesHubConnectionApiKeys"
private const val CONNECTION_SECRET_AAD_PREFIX = "HermesHub.Connection.ApiKey.v1:"
private val gatewaySecretKeyLock = Any()

internal fun migratePrefs(context: Context, currentName: String, legacyName: String) =
    PreferencesMigration.getOrMigrate(context, currentName, legacyName)

internal fun loadGatewaySecret(context: Context): String? {
    val prefs = migratePrefs(context, CURRENT_SETTINGS_PREFS, LEGACY_SETTINGS_PREFS)
    val legacyPrefs = context.applicationContext.getSharedPreferences(LEGACY_SETTINGS_PREFS, Context.MODE_PRIVATE)
    val stored = prefs.getString(GATEWAY_SECRET_PREF_KEY, null)
        ?: legacyPrefs.getString(GATEWAY_SECRET_PREF_KEY, null)?.also { legacyValue ->
            prefs.edit { putString(GATEWAY_SECRET_PREF_KEY, legacyValue) }
        }
        ?: return null

    return runCatching {
        val parts = stored.split(':', limit = 2)
        if (parts.size != 2) {
            val legacyPlaintext = stored.trim().takeIf { it.isNotBlank() } ?: return@runCatching null
            if (saveGatewaySecret(context, legacyPlaintext)) return@runCatching legacyPlaintext
            prefs.edit(commit = true) {
                remove(GATEWAY_SECRET_PREF_KEY)
                remove("gatewaySecret")
            }
            legacyPrefs.edit(commit = true) {
                remove(GATEWAY_SECRET_PREF_KEY)
                remove("gatewaySecret")
            }
            return@runCatching null
        }

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(GATEWAY_SECRET_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateGatewaySecretKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(GATEWAY_SECRET_AAD.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(encrypted), Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
    }.getOrNull()
}

internal fun saveGatewaySecret(context: Context, secret: String?): Boolean {
    val normalized = secret?.trim().takeUnless { it.isNullOrEmpty() }
    if (normalized == null) {
        val currentRemoved = migratePrefs(context, CURRENT_SETTINGS_PREFS, LEGACY_SETTINGS_PREFS)
            .edit()
            .remove(GATEWAY_SECRET_PREF_KEY)
            .remove("gatewaySecret")
            .commit()
        val legacyRemoved = context.applicationContext
            .getSharedPreferences(LEGACY_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(GATEWAY_SECRET_PREF_KEY)
            .remove("gatewaySecret")
            .commit()
        return currentRemoved && legacyRemoved
    }
    val encoded = runCatching {
        val cipher = Cipher.getInstance(GATEWAY_SECRET_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateGatewaySecretKey())
        cipher.updateAAD(GATEWAY_SECRET_AAD.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
    }.getOrNull() ?: return false

    val saved = migratePrefs(context, CURRENT_SETTINGS_PREFS, LEGACY_SETTINGS_PREFS)
        .edit()
        .putString(GATEWAY_SECRET_PREF_KEY, encoded)
        .remove("gatewaySecret")
        .commit()
    if (!saved) return false
    context.applicationContext.getSharedPreferences(LEGACY_SETTINGS_PREFS, Context.MODE_PRIVATE).edit(commit = true) {
        remove(GATEWAY_SECRET_PREF_KEY)
        remove("gatewaySecret")
    }
    return true
}

private fun getOrCreateGatewaySecretKey(): SecretKey = synchronized(gatewaySecretKeyLock) {
    val keyStore = KeyStore.getInstance(GATEWAY_SECRET_KEYSTORE).apply { load(null) }
    val existing = keyStore.getEntry(GATEWAY_SECRET_ALIAS, null) as? KeyStore.SecretKeyEntry
    if (existing != null) {
        return@synchronized existing.secretKey
    }

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, GATEWAY_SECRET_KEYSTORE)
    val spec = KeyGenParameterSpec.Builder(
        GATEWAY_SECRET_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    generator.init(spec)
    generator.generateKey()
}

internal fun loadGatewayConnectionSecret(context: Context, connectionId: String): String? {
    val key = safeConnectionSecretKey(connectionId) ?: return null
    val stored = context.applicationContext
        .getSharedPreferences(CONNECTION_SECRET_PREFS, Context.MODE_PRIVATE)
        .getString(key, null)
        ?: return null
    return decryptConnectionSecret(stored, connectionId)
}

@SuppressLint("UseKtx") // Synchronous commit is required: caller must receive actual persistence success.
internal fun saveGatewayConnectionSecret(context: Context, connectionId: String, secret: String?): Boolean {
    val key = safeConnectionSecretKey(connectionId) ?: return false
    val prefs = context.applicationContext.getSharedPreferences(CONNECTION_SECRET_PREFS, Context.MODE_PRIVATE)
    val normalized = secret?.trim().takeUnless { it.isNullOrEmpty() }
    if (normalized == null) return prefs.edit().remove(key).commit()
    val encoded = encryptConnectionSecret(normalized, connectionId) ?: return false
    return prefs.edit().putString(key, encoded).commit()
}

@SuppressLint("UseKtx") // Synchronous commit is required: caller must receive actual deletion success.
internal fun deleteGatewayConnectionSecret(context: Context, connectionId: String): Boolean {
    val key = safeConnectionSecretKey(connectionId) ?: return false
    return context.applicationContext
        .getSharedPreferences(CONNECTION_SECRET_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(key)
        .commit()
}

private fun safeConnectionSecretKey(connectionId: String): String? {
    val normalized = connectionId.trim()
    return normalized.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) && !it.equals("primary", true) }
        ?.let { "ciphertext_$it" }
}

private fun getOrCreateConnectionSecretKey(): SecretKey = synchronized(gatewaySecretKeyLock) {
    val keyStore = KeyStore.getInstance(GATEWAY_SECRET_KEYSTORE).apply { load(null) }
    val existing = keyStore.getEntry(CONNECTION_SECRET_ALIAS, null) as? KeyStore.SecretKeyEntry
    if (existing != null) return@synchronized existing.secretKey
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, GATEWAY_SECRET_KEYSTORE)
    generator.init(
        KeyGenParameterSpec.Builder(
            CONNECTION_SECRET_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
    )
    generator.generateKey()
}

private fun encryptConnectionSecret(secret: String, connectionId: String): String? = runCatching {
    val cipher = Cipher.getInstance(GATEWAY_SECRET_TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateConnectionSecretKey())
    cipher.updateAAD((CONNECTION_SECRET_AAD_PREFIX + connectionId).toByteArray(Charsets.UTF_8))
    val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
    "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
}.getOrNull()

private fun decryptConnectionSecret(stored: String, connectionId: String): String? = runCatching {
    val parts = stored.split(':', limit = 2)
    if (parts.size != 2) return@runCatching null
    val cipher = Cipher.getInstance(GATEWAY_SECRET_TRANSFORMATION)
    cipher.init(
        Cipher.DECRYPT_MODE,
        getOrCreateConnectionSecretKey(),
        GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
    )
    cipher.updateAAD((CONNECTION_SECRET_AAD_PREFIX + connectionId).toByteArray(Charsets.UTF_8))
    String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
}.getOrNull()
