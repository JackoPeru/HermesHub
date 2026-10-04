package com.nemoclaw.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

private fun inMemoryBackupKey(): SecretKey {
    val gen = KeyGenerator.getInstance("AES")
    gen.init(256)
    return gen.generateKey()
}

class BackupEnvelopeTest {

    @Test
    fun `round-trip cifratura conserva payload`() {
        val key = inMemoryBackupKey()
        val plain = JSONObject()
            .put("schema", "hermes-hub.local-backup.v1")
            .put("hello", "mondo")
            .toString().toByteArray(Charsets.UTF_8)
        val envelope = encryptBackupEnvelope(plain, key)
        // Formato envelope versionato.
        val parsed = JSONObject(envelope)
        assertEquals(1, parsed.getInt("v"))
        assertTrue(parsed.has("iv"))
        assertTrue(parsed.has("data"))
        // IV 12 byte.
        val iv = java.util.Base64.getDecoder().decode(parsed.getString("iv"))
        assertEquals(BACKUP_GCM_IV_BYTES, iv.size)
        // Niente plaintext in chiaro nell'envelope.
        assertFalse(envelope.contains("mondo"))
        val decoded = decryptBackupEnvelope(envelope, key)
        assertEquals(String(plain, Charsets.UTF_8), String(decoded, Charsets.UTF_8))
    }

    @Test
    fun `iv random per export decifrano entrambi`() {
        val key = inMemoryBackupKey()
        val plain = "stesso-contenuto".toByteArray(Charsets.UTF_8)
        val first = encryptBackupEnvelope(plain, key)
        val second = encryptBackupEnvelope(plain, key)
        assertNotEquals(first, second)
        assertEquals("stesso-contenuto", String(decryptBackupEnvelope(first, key), Charsets.UTF_8))
        assertEquals("stesso-contenuto", String(decryptBackupEnvelope(second, key), Charsets.UTF_8))
    }

    @Test
    fun `fallback legacy plain senza envelope`() {
        val key = inMemoryBackupKey()
        val legacy = JSONObject()
            .put("schema", "hermes-hub.local-backup.v1")
            .put("items", org.json.JSONArray())
            .toString()
        assertFalse(isBackupEnvelope(legacy))
        val decoded = decodeBackupPayload(legacy, key)
        assertEquals(legacy, String(decoded, Charsets.UTF_8))
        // Stringa non-JSON: sempre legacy.
        assertEquals("ciao", String(decodeBackupPayload("ciao", key), Charsets.UTF_8))
    }

    @Test
    fun `envelope corrotta errore esplicito mai plain mai crash`() {
        val key = inMemoryBackupKey()
        val plain = "segreto".toByteArray(Charsets.UTF_8)
        val good = encryptBackupEnvelope(plain, key)
        val parsed = JSONObject(good)
        val goodIv = parsed.getString("iv")
        val goodData = parsed.getString("data")

        // Solo input che sembrano envelope (JSON con iv+data) devono lanciare.
        val corrupted = listOf(
            """{"v":2,"iv":"$goodIv","data":"$goodData"}""",
            """{"v":1,"iv":"","data":"$goodData"}""",
            """{"v":1,"iv":"$goodIv","data":""}""",
            """{"v":1,"iv":"!!!","data":"$goodData"}""",
            """{"v":1,"iv":"$goodIv","data":"!!!"}""",
            """{"v":1,"iv":"${java.util.Base64.getEncoder().encodeToString(ByteArray(5))}","data":"$goodData"}""",
            // Payload manomesso: GCM deve fallire, non ritornare spazzatura.
            run {
                val raw = java.util.Base64.getDecoder().decode(goodData).copyOf()
                raw[0] = (raw[0].toInt() xor 0xFF).toByte()
                """{"v":1,"iv":"$goodIv","data":"${java.util.Base64.getEncoder().encodeToString(raw)}"}"""
            },
            """{"v":99,"iv":"$goodIv","data":"$goodData"}""",
            """{"iv":"$goodIv","data":"$goodData"}"""
        )
        for ((index, bad) in corrupted.withIndex()) {
            assertTrue("case $index deve essere rilevato come envelope", isBackupEnvelope(bad))
            try {
                val out = decodeBackupPayload(bad, key)
                // Se per assurdo non lancia, non deve mai essere il segreto in chiaro.
                assertNotEquals("case $index non deve decifrare in chiaro", "segreto", String(out, Charsets.UTF_8))
                fail("case $index doveva lanciare BackupDecryptException, input=$bad")
            } catch (e: BackupDecryptException) {
                assertTrue("case $index messaggio esplicito", (e.message ?: "").contains("Backup non valido"))
            } catch (e: Exception) {
                fail("case $index deve lanciare BackupDecryptException, non ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        // Input non-envelope: fallback legacy, mai crash, mai segreto.
        for (legacy in listOf("non-json{{{", good.substring(0, good.length / 2))) {
            val out = decodeBackupPayload(legacy, key)
            assertEquals(legacy, String(out, Charsets.UTF_8))
            assertNotEquals("segreto", String(out, Charsets.UTF_8))
        }
    }

    @Test
    fun `chiave errata errore esplicito senza leak`() {
        val key = inMemoryBackupKey()
        val other = inMemoryBackupKey()
        val envelope = encryptBackupEnvelope("dati".toByteArray(Charsets.UTF_8), key)
        try {
            decryptBackupEnvelope(envelope, other)
            fail("chiave errata deve fallire")
        } catch (e: BackupDecryptException) {
            assertTrue((e.message ?: "").contains("Backup non valido"))
        }
    }
}
