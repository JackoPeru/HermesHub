package com.nemoclaw.chat

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Chiave dispositivo P-256 in AndroidKeystore + firma richieste sensibili.
 *
 * La chiave privata non lascia mai il Keystore (hardware-backed quando il
 * SoC lo offre). Ogni POST pericoloso (server action/maintenance) porta:
 * X-Hermes-Key-Id, X-Hermes-Timestamp, X-Hermes-Nonce, X-Hermes-Signature
 * su canonico METHOD\npath?query\ntimestamp\nnonce\nsha256hex(body).
 * Il gateway verifica con openssl e rifiuta i replay (nonce visti + finestra
 * 60..3600s). L'uso e' preceduto da BiometricPrompt nella UI: niente firma
 * senza impronta/PIN del proprietario.
 */
internal const val DEVICE_KEY_ALIAS = "hermes_device_sign"
private const val DEVICE_PREFS = "hermes_device_keys"
private const val DEVICE_ID_KEY = "device_id"
private const val PENDING_FCM_TOKEN_KEY = "pending_fcm_token"

internal fun findFragmentActivity(context: Context): FragmentActivity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Impronta/PIN prima di firmare: la chiave firma solo dopo il consenso
 * biometrico dell'utente (niente uso silenzioso da altri componenti).
 */
internal fun authenticateDeviceOwner(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
        onError("Sblocco non disponibile su questo telefono (impronta o PIN).")
        return
    }
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
        }

        override fun onAuthenticationError(code: Int, message: CharSequence) {
            if (code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_CANCELED) {
                onError("Autenticazione annullata.")
            } else {
                onError(message.toString().ifBlank { "Autenticazione fallita." })
            }
        }

        override fun onAuthenticationFailed() {
            onError("Non riconosciuto, riprova.")
        }
    }
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowed)
            .build()
    )
}

internal fun savePendingFcmToken(context: Context, token: String) {
    context.applicationContext
        .getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        .edit { putString(PENDING_FCM_TOKEN_KEY, token) }
}

internal fun loadPendingFcmToken(context: Context): String? {
    return context.applicationContext
        .getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        .getString(PENDING_FCM_TOKEN_KEY, null)?.takeIf { it.isNotBlank() }
}

internal fun deviceSignatureHeaders(): List<String> =
    listOf("X-Hermes-Key-Id", "X-Hermes-Timestamp", "X-Hermes-Nonce", "X-Hermes-Signature")

internal fun canonicalDevicePayload(
    method: String,
    pathAndQuery: String,
    timestampSec: String,
    nonce: String,
    bodySha256Hex: String
): ByteArray {
    return listOf(method.uppercase(), pathAndQuery, timestampSec, nonce, bodySha256Hex)
        .joinToString("\n")
        .toByteArray(Charsets.UTF_8)
}

internal fun sha256Hex(data: ByteArray): String {
    return MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it) }
}

internal fun newDeviceId(): String =
    "and-" + UUID.randomUUID().toString().replace("-", "").take(12)

internal fun loadDeviceId(context: Context): String? {
    return context.applicationContext
        .getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        .getString(DEVICE_ID_KEY, null)?.takeIf { it.isNotBlank() }
}

internal fun saveDeviceId(context: Context, deviceId: String) {
    context.applicationContext
        .getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        .edit { putString(DEVICE_ID_KEY, deviceId) }
}

internal fun clearDeviceId(context: Context) {
    context.applicationContext
        .getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        .edit { remove(DEVICE_ID_KEY) }
}

internal fun ensureDeviceKeyPair(): java.security.PublicKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getCertificate(DEVICE_KEY_ALIAS)?.publicKey)?.let { return it }
    val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
    keyPairGenerator.initialize(
        KeyGenParameterSpec.Builder(DEVICE_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
    )
    return keyPairGenerator.generateKeyPair().public
}

internal fun devicePublicKeyPem(): String {
    val publicKey = ensureDeviceKeyPair()
    val der = publicKey.encoded
    val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
    return "-----BEGIN PUBLIC KEY-----\n" +
        b64.chunked(64).joinToString("\n") +
        "\n-----END PUBLIC KEY-----\n"
}

internal fun devicePublicKeyFingerprint(): String {
    val publicKey = ensureDeviceKeyPair()
    return sha256Hex(publicKey.encoded).take(16)
}

internal data class SignedDeviceRequest(
    val timestampSec: String,
    val nonce: String,
    val signatureB64: String
)

internal fun signDeviceRequest(method: String, pathAndQuery: String, body: ByteArray): SignedDeviceRequest {
    val timestampSec = (System.currentTimeMillis() / 1000L).toString()
    val nonceBytes = ByteArray(16)
    SecureRandom().nextBytes(nonceBytes)
    val nonce = "n" + nonceBytes.joinToString("") { "%02x".format(it) }
    val canonical = canonicalDevicePayload(method, pathAndQuery, timestampSec, nonce, sha256Hex(body))
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val privateKey = (store.getEntry(DEVICE_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry).privateKey
    val signature = Signature.getInstance("SHA256withECDSA").apply {
        initSign(privateKey)
        update(canonical)
    }.sign()
    return SignedDeviceRequest(timestampSec, nonce, Base64.encodeToString(signature, Base64.NO_WRAP))
}
