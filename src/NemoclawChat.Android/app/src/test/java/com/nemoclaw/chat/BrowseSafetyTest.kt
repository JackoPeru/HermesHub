package com.nemoclaw.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.nemoclaw.chat.features.bots.HermesBotConnection
import com.nemoclaw.chat.features.bots.HermesBotConnectionRegistry
import com.nemoclaw.chat.features.bots.connectionIdsForReset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Fake minimi per loadConversations/saveConversations (stesso pattern di BackupRestoreTest).
private class BrowseFakePrefs : SharedPreferences {
    val data = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = (data[key] as? Set<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = BrowseFakeEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    inner class BrowseFakeEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) { if (value == null) data.remove(key) else data[key] = value }
            return this
        }
        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            if (key != null) { if (values == null) data.remove(key) else data[key] = values }
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor { if (key != null) data[key] = value; return this }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor { if (key != null) data[key] = value; return this }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { if (key != null) data[key] = value; return this }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { if (key != null) data[key] = value; return this }
        override fun remove(key: String?): SharedPreferences.Editor { data.remove(key); return this }
        override fun clear(): SharedPreferences.Editor { data.clear(); return this }
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }
}

private class BrowseFakeContext : ContextWrapper(null) {
    private val stores = mutableMapOf<String, BrowseFakePrefs>()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        stores.getOrPut(name ?: "default") { BrowseFakePrefs() }
    override fun getPackageName(): String = "com.nemoclaw.chat"
    fun prefs(name: String): BrowseFakePrefs =
        getSharedPreferences(name, Context.MODE_PRIVATE) as BrowseFakePrefs
}

/**
 * Test sicurezza/browse: miniature OOM, NULL letterale, backup redact URL,
 * fail-closed bot remoto, reset registry. Commenti italiani, funzioni pure.
 */
class BrowseSafetyTest {

    @Before
    fun clearMigrationCache() {
        runCatching {
            val clazz = Class.forName("com.nemoclaw.chat.PreferencesMigration")
            val instance = clazz.getField("INSTANCE").get(null)
            for (fieldName in listOf("cache", "completed", "locks")) {
                runCatching {
                    val field = clazz.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val value = field.get(instance) ?: return@runCatching
                    value.javaClass.getMethod("clear").invoke(value)
                }
            }
        }
    }

    // FIX 1: sampling potenza di 2 per miniature.
    @Test
    fun `thumbnailSampleSize riduce full-res a 256 con potenza di 2`() {
        // 4000x3000 -> 16 (250x187 dentro 256).
        assertEquals(16, thumbnailSampleSize(4000, 3000, 256))
        // Gia piccola: nessun sampling.
        assertEquals(1, thumbnailSampleSize(200, 200, 256))
        // Al limite: 512 -> 2.
        assertEquals(2, thumbnailSampleSize(512, 512, 256))
        val sample = thumbnailSampleSize(4000, 3000, FEED_THUMBNAIL_MAX_DIM)
        assertTrue(sample >= 2 && sample and (sample - 1) == 0)
        assertTrue(4000 / sample <= 256 && 3000 / sample <= 256)
    }

    @Test
    fun `thumbnailSampleSize con input non validi ritorna 1`() {
        assertEquals(1, thumbnailSampleSize(0, 100, 256))
        assertEquals(1, thumbnailSampleSize(100, -5, 256))
        assertEquals(1, thumbnailSampleSize(100, 100, 0))
    }

    // FIX 2: NULL JSON non diventa letterale "null".
    @Test
    fun `optNullableId mappa NULL e stringa null a null`() {
        val obj = JSONObject()
            .put("a", JSONObject.NULL)
            .put("b", "null")
            .put("c", "NULL")
            .put("d", "  ")
            .put("e", "resp-123")
        assertNull(obj.optNullableId("a"))
        assertNull(obj.optNullableId("b"))
        assertNull(obj.optNullableId("c"))
        assertNull(obj.optNullableId("d"))
        assertEquals("resp-123", obj.optNullableId("e"))
        assertNull(obj.optNullableId("assente"))
    }

    @Test
    fun `round-trip NULL resta null in readConversationsFromJsonArray`() {
        val array = JSONArray().put(
            JSONObject()
                .put("id", "c1")
                .put("title", "T")
                .put("kind", "Chat")
                .put("description", "")
                .put("prompt", "")
                .put("updatedAt", 10L)
                .put("messages", JSONArray())
                .put("previousResponseId", JSONObject.NULL)
                .put("serverConversationId", JSONObject.NULL)
                .put("hermesSessionId", JSONObject.NULL)
        )
        val loaded = readConversationsFromJsonArray(array)
        assertEquals(1, loaded.size)
        assertNull(loaded[0].previousResponseId)
        assertNull(loaded[0].serverConversationId)
        assertNull(loaded[0].hermesSessionId)
    }

    @Test
    fun `round-trip save e loadConversations con NULL resta null`() {
        val context = BrowseFakeContext()
        saveConversations(
            context,
            listOf(
                LocalConversation(
                    id = "c2", title = "T", kind = "Chat",
                    description = "", prompt = "", updatedAt = 10L, messages = emptyList(),
                    previousResponseId = null, serverConversationId = null, hermesSessionId = null
                )
            ),
            syncAfterSave = false
        )
        val loaded = loadConversations(context, includeDeleted = true)
        assertEquals(1, loaded.size)
        assertNull(loaded[0].previousResponseId)
        assertNull(loaded[0].serverConversationId)
        assertNull(loaded[0].hermesSessionId)
    }

    // FIX 5: redact query dai campi URL del backup.
    @Test
    fun `stripUrlQuery rimuove query e fragment`() {
        assertEquals("https://x.test/y", stripUrlQuery("https://x.test/y?key=segreta"))
        assertEquals("https://x.test/y", stripUrlQuery("https://x.test/y?key=1&hub_token=2"))
        assertEquals("https://x.test/y", stripUrlQuery("https://x.test/y#frag"))
        assertEquals("https://x.test/y", stripUrlQuery("https://x.test/y"))
        assertEquals("", stripUrlQuery(""))
        // Contratto: chiamare solo su campi URL (i testi liberi li protegge il sanitizer).
        assertEquals("/v1/media/x", stripUrlQuery("/v1/media/x?token=abc"))
    }

    @Test
    fun `sanitizeBackupUrlsInObject spoglia URL ma conserva prompt`() {
        val obj = JSONObject()
            .put("repositoryUrl", "https://git.test/r?key=segreta")
            .put("artifactUrl", "https://cdn.test/a.mp4?hub_token=abc")
            .put("gatewayUrl", "https://gw.test/v1?token=xyz")
            .put("prompt", "come stai? bene")
            .put("title", "ciao? mondo")
        sanitizeBackupUrlsInObject(obj)
        assertEquals("https://git.test/r", obj.optString("repositoryUrl"))
        assertEquals("https://cdn.test/a.mp4", obj.optString("artifactUrl"))
        assertEquals("https://gw.test/v1", obj.optString("gatewayUrl"))
        assertEquals("come stai? bene", obj.optString("prompt"))
        assertEquals("ciao? mondo", obj.optString("title"))
        assertFalse(obj.toString().contains("key="))
        assertFalse(obj.toString().contains("hub_token"))
    }

    @Test
    fun `sanitizeBackupUrlsInArray gestisce blob items stringificato`() {
        val inner = JSONArray().put(
            JSONObject().put("id", "1").put("repositoryUrl", "https://git.test/r?key=1")
        )
        val obj = JSONObject().put("items", inner.toString())
        sanitizeBackupUrlsInObject(obj)
        assertFalse(obj.optString("items").contains("?key="))
    }

    // FIX 4: fail-closed bot remoto.
    @Test
    fun `isRemoteBotMissingSecret blocca solo remoto senza chiave`() {
        assertTrue(isRemoteBotMissingSecret("connection-abc", null))
        assertTrue(isRemoteBotMissingSecret("connection-abc", ""))
        assertTrue(isRemoteBotMissingSecret("connection-abc", "   "))
        assertFalse(isRemoteBotMissingSecret("connection-abc", "k-segreta"))
        assertFalse(isRemoteBotMissingSecret("primary", null))
        assertFalse(isRemoteBotMissingSecret(null, null))
        assertFalse(isRemoteBotMissingSecret("", null))
        assertTrue(remoteBotMissingSecretMessage().isNotBlank())
    }

    // FIX 3: registry reset esclude primario (puro).
    @Test
    fun `connectionIdsForReset esclude primario`() {
        val registry = HermesBotConnectionRegistry(
            items = listOf(
                HermesBotConnection("primary", "Gateway principale", "https://gw.test", true, true),
                HermesBotConnection("connection-a", "A", "https://a.test", true, false),
                HermesBotConnection("connection-b", "B", "https://b.test", false, false)
            ),
            warnings = emptyList()
        )
        assertEquals(listOf("connection-a", "connection-b"), connectionIdsForReset(registry))
    }
}
