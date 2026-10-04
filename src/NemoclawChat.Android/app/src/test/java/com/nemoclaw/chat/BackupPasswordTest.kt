package com.nemoclaw.chat

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

private fun passwordBackupKey(): SecretKey {
    val gen = KeyGenerator.getInstance("AES")
    gen.init(256)
    return gen.generateKey()
}

private fun fixedSalt(): ByteArray = ByteArray(BACKUP_PASSWORD_SALT_BYTES) { it.toByte() }
private fun fixedIv(): ByteArray = ByteArray(BACKUP_GCM_IV_BYTES) { (it * 7).toByte() }

class BackupPasswordTest {

    @Test
    fun `kdf deterministica con salt fisso`() {
        val salt = fixedSalt()
        val first = deriveBackupPasswordKey("password-segreta", salt).encoded
        val second = deriveBackupPasswordKey("password-segreta", salt).encoded
        assertArrayEquals(first, second)
        assertEquals(32, first.size)
        // Sale diversa -> chiave diversa; password diversa -> chiave diversa.
        val otherSalt = fixedSalt().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        assertFalse(first.contentEquals(deriveBackupPasswordKey("password-segreta", otherSalt).encoded))
        assertFalse(first.contentEquals(deriveBackupPasswordKey("altra-password", salt).encoded))
    }

    @Test
    fun `formato envelope v2 documentato`() {
        val envelope = JSONObject(
            encryptBackupEnvelopeWithPassword(
                "ciao".toByteArray(Charsets.UTF_8),
                "pw",
                salt = fixedSalt(),
                iv = fixedIv()
            )
        )
        assertEquals(2, envelope.getInt("v"))
        assertTrue(envelope.has("salt"))
        assertTrue(envelope.has("iv"))
        assertTrue(envelope.has("data"))
        assertEquals(BACKUP_PASSWORD_ITERATIONS, envelope.getInt("iter"))
        assertEquals(BACKUP_PASSWORD_SALT_BYTES, java.util.Base64.getDecoder().decode(envelope.getString("salt")).size)
        assertEquals(BACKUP_GCM_IV_BYTES, java.util.Base64.getDecoder().decode(envelope.getString("iv")).size)
        assertTrue(isBackupEnvelopeV2(envelope.toString()))
    }

    @Test
    fun `round-trip v2 conserva payload`() {
        val plain = JSONObject()
            .put("schema", "hermes-hub.local-backup.v1")
            .put("hello", "mondo")
            .toString().toByteArray(Charsets.UTF_8)
        // Deterministico con salt/iv fissi.
        val fixed = exportEncryptedWithPassword(plain, "pw-fissa", salt = fixedSalt(), iv = fixedIv())
        assertFalse(fixed.contains("mondo"))
        assertEquals(
            String(plain, Charsets.UTF_8),
            String(importWithPassword(fixed, "pw-fissa"), Charsets.UTF_8)
        )
        // Random di default: due export diversi, entrambi decifrano.
        val first = exportEncryptedWithPassword(plain, "pw")
        val second = exportEncryptedWithPassword(plain, "pw")
        assertNotEquals(first, second)
        assertEquals("mondo", JSONObject(String(importWithPassword(first, "pw"), Charsets.UTF_8)).getString("hello"))
        assertEquals("mondo", JSONObject(String(importWithPassword(second, "pw"), Charsets.UTF_8)).getString("hello"))
    }

    @Test
    fun `tamper e password errata errore esplicito`() {
        val good = exportEncryptedWithPassword("segreto".toByteArray(Charsets.UTF_8), "giusta")
        val parsed = JSONObject(good)
        val salt = parsed.getString("salt")
        val iv = parsed.getString("iv")
        val data = parsed.getString("data")
        val iter = parsed.getInt("iter")

        fun tamperedData(): String {
            val raw = java.util.Base64.getDecoder().decode(data).copyOf()
            raw[0] = (raw[0].toInt() xor 0xFF).toByte()
            return """{"v":2,"salt":"$salt","iv":"$iv","iter":$iter,"data":"${java.util.Base64.getEncoder().encodeToString(raw)}"}"""
        }

        fun truncatedData(): String {
            val raw = java.util.Base64.getDecoder().decode(data).dropLast(1).toByteArray()
            return """{"v":2,"salt":"$salt","iv":"$iv","iter":$iter,"data":"${java.util.Base64.getEncoder().encodeToString(raw)}"}"""
        }

        val badInputs = listOf(
            tamperedData() to "giusta",
            truncatedData() to "giusta",
            // Password errata.
            good to "sbagliata",
            // Envelope troncate/manomesse.
            """{"v":2,"salt":"$salt","iv":"$iv","iter":$iter,"data":"!!!"}""" to "giusta",
            """{"v":2,"salt":"!!!","iv":"$iv","iter":$iter,"data":"$data"}""" to "giusta",
            """{"v":2,"salt":"$salt","iv":"$iv","iter":$iter,"data":""}""" to "giusta",
            """{"v":2,"salt":"$salt","iv":"$iv","data":"$data"}""" to "giusta",
            """{"v":2,"salt":"$salt","iv":"$iv","iter":1,"data":"$data"}""" to "giusta",
            """{"v":1,"salt":"$salt","iv":"$iv","iter":$iter,"data":"$data"}""" to "giusta"
        )
        for ((index, case) in badInputs.withIndex()) {
            val (text, password) = case
            try {
                val out = importWithPassword(text, password)
                assertNotEquals("case $index non deve decifrare in chiaro", "segreto", String(out, Charsets.UTF_8))
                fail("case $index doveva lanciare BackupDecryptException: $text")
            } catch (e: BackupDecryptException) {
                assertTrue("case $index messaggio esplicito", (e.message ?: "").contains("Backup non valido"))
            } catch (e: Exception) {
                fail("case $index deve lanciare BackupDecryptException, non ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        // Password vuota su v2: errore esplicito, mai crash.
        try {
            importWithPassword(good, "")
            fail("password vuota su v2 deve fallire")
        } catch (e: BackupDecryptException) {
            assertTrue((e.message ?: "").contains("Backup non valido"))
        }
    }

    @Test
    fun `v1 e plain ancora leggibili via reader unificato`() {
        val key = passwordBackupKey()
        // v1 Keystore: round-trip invariato + leggibile dal reader unificato.
        val plain = "dati-v1".toByteArray(Charsets.UTF_8)
        val v1 = encryptBackupEnvelope(plain, key)
        assertEquals("dati-v1", String(decryptBackupEnvelope(v1, key), Charsets.UTF_8))
        assertEquals("dati-v1", String(decodeBackupPayloadWithPassword(v1, key, null), Charsets.UTF_8))
        assertEquals("dati-v1", String(decodeBackupPayloadWithPassword(v1, key, "pw-ignorata"), Charsets.UTF_8))
        // Legacy plain: passa invariato con e senza password.
        val legacy = """{"schema":"hermes-hub.local-backup.v1","items":[]}"""
        assertEquals(legacy, String(decodeBackupPayloadWithPassword(legacy, key, null), Charsets.UTF_8))
        assertEquals(legacy, String(importWithPassword(legacy, "qualsiasi"), Charsets.UTF_8))
        // v1 via importWithPassword (senza chiave dispositivo): errore esplicito, mai plain.
        try {
            importWithPassword(v1, "pw")
            fail("v1 via importWithPassword deve fallire con errore esplicito")
        } catch (e: BackupDecryptException) {
            assertTrue((e.message ?: "").contains("Backup non valido"))
        }
        // v2 senza password via reader unificato: errore esplicito, mai plain.
        val v2 = exportEncryptedWithPassword("segreto-v2".toByteArray(Charsets.UTF_8), "pw")
        try {
            decodeBackupPayloadWithPassword(v2, key, null)
            fail("v2 senza password deve fallire")
        } catch (e: BackupDecryptException) {
            assertTrue((e.message ?: "").contains("Backup non valido"))
        }
        assertEquals("segreto-v2", String(decodeBackupPayloadWithPassword(v2, key, "pw"), Charsets.UTF_8))
    }

    @Test
    fun `export su file e reimport con password`() {
        val tmp = Files.createTempFile("backup-v2-", ".json").toFile()
        try {
            val plain = """{"schema":"hermes-hub.local-backup.v1","n":42}""".toByteArray(Charsets.UTF_8)
            val envelope = exportEncryptedWithPassword(tmp, plain, "pw-file")
            assertEquals(envelope, tmp.readText(Charsets.UTF_8))
            assertTrue(isBackupEnvelopeV2(tmp.readText(Charsets.UTF_8)))
            assertEquals(
                String(plain, Charsets.UTF_8),
                String(importWithPassword(tmp.readText(Charsets.UTF_8), "pw-file"), Charsets.UTF_8)
            )
        } finally {
            tmp.delete()
        }
    }
}
