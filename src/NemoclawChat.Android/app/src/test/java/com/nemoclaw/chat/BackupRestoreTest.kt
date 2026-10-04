package com.nemoclaw.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import javax.crypto.KeyGenerator

private class FakeRestorePrefs : SharedPreferences {
    val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()

    @Suppress("UNCHECKED_CAST")
    override fun getString(key: String?, defValue: String?): String? =
        (data[key] as? String) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        (data[key] as? Set<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int =
        (data[key] as? Int) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        (data[key] as? Long) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        (data[key] as? Float) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (data[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeRestoreEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    inner class FakeRestoreEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) {
                if (value == null) data.remove(key) else data[key] = value
            }
            return this
        }

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            if (key != null) {
                if (values == null) data.remove(key) else data[key] = values
            }
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) data[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) data[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) data[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) data[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            data.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            data.clear()
            return this
        }

        override fun commit(): Boolean = true

        override fun apply() = Unit
    }
}

private class FakeRestoreContext : ContextWrapper(null) {
    private val stores = mutableMapOf<String, FakeRestorePrefs>()

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        stores.getOrPut(name ?: "default") { FakeRestorePrefs() }

    override fun getPackageName(): String = "com.nemoclaw.chat"

    fun prefs(name: String): FakeRestorePrefs =
        getSharedPreferences(name, Context.MODE_PRIVATE) as FakeRestorePrefs
}

private fun restoreTestKey(): javax.crypto.SecretKey {
    val gen = KeyGenerator.getInstance("AES")
    gen.init(256)
    return gen.generateKey()
}

private fun restorePayload(
    settings: JSONObject? = null,
    items: JSONArray? = null,
    tasks: JSONArray? = null,
    workspaceItems: JSONArray? = null
): JSONObject {
    val payload = JSONObject()
        .put("schema", "hermes-hub.local-backup.v1")
        .put("exportedAt", 1L)
    if (settings != null) payload.put("settings", settings)
    if (items != null) {
        payload.put("items", items)
        payload.put("conversations", items)
    }
    if (tasks != null) payload.put("tasks", tasks)
    if (workspaceItems != null) {
        payload.put("workspace", JSONObject().put("items", workspaceItems.toString()))
    }
    return payload
}

private fun conversationJson(id: String, title: String, updatedAt: Long): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("title", title)
        .put("kind", "Chat")
        .put("description", "")
        .put("prompt", "")
        .put("updatedAt", updatedAt)
        .put("messages", JSONArray())
}

class BackupRestoreTest {

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

    @Test
    fun `restore applica chiavi non sensibili backup-wins`() {
        val context = FakeRestoreContext()
        val payload = restorePayload(
            settings = JSONObject()
                .put("gatewayUrl", "https://hermes.test")
                .put("model", "hermes-agent")
                .put("maxAttachmentMb", 42)
                .put("showToolCalls", true)
        )
        val report = restoreLocalBackup(context, payload, null)
        assertEquals(4, report.applied)
        assertEquals(0, report.skippedSensitive)
        val settingsPrefs = context.prefs("chatclaw_settings")
        assertEquals("https://hermes.test", settingsPrefs.getString("gatewayUrl", null))
        assertEquals("hermes-agent", settingsPrefs.getString("model", null))
        assertEquals(42, settingsPrefs.getInt("maxAttachmentMb", -1))
        assertTrue(settingsPrefs.getBoolean("showToolCalls", false))
    }

    @Test
    fun `restore salta chiavi sensibili senza scriverle`() {
        val context = FakeRestoreContext()
        val payload = restorePayload(
            settings = JSONObject()
                .put("gatewayUrl", "https://hermes.test")
                .put("apiKey", "SEGRETISSIMA")
                .put("gatewayToken", "TOKEN")
                .put("mySecret", "SECRET")
                .put("dbPassword", "PW")
                .put("myCredential", "CRED")
                .put("authorizationHeader", "AUTH")
        )
        val report = restoreLocalBackup(context, payload, null)
        assertEquals(1, report.applied)
        assertEquals(6, report.skippedSensitive)
        val settingsPrefs = context.prefs("chatclaw_settings")
        assertEquals("https://hermes.test", settingsPrefs.getString("gatewayUrl", null))
        assertFalse(settingsPrefs.contains("apiKey"))
        assertFalse(settingsPrefs.contains("gatewayToken"))
        assertFalse(settingsPrefs.contains("mySecret"))
        assertFalse(settingsPrefs.contains("dbPassword"))
        assertFalse(settingsPrefs.contains("myCredential"))
        assertFalse(settingsPrefs.contains("authorizationHeader"))
    }

    @Test
    fun `restore merge conversazioni per id senza duplicati last-write-wins`() {
        val context = FakeRestoreContext()
        // Stato attuale: id=1 vecchia, id=3 solo locale.
        saveConversations(
            context,
            listOf(
                LocalConversation(
                    id = "1", title = "Vecchia", kind = "Chat",
                    description = "", prompt = "", updatedAt = 100L, messages = emptyList()
                ),
                LocalConversation(
                    id = "3", title = "SoloLocale", kind = "Chat",
                    description = "", prompt = "", updatedAt = 300L, messages = emptyList()
                )
            ),
            syncAfterSave = false
        )
        val backupItems = JSONArray()
            .put(conversationJson("1", "Nuova", 200L))
            .put(conversationJson("2", "Nuova2", 150L))
            // Vecchia: backup piu vecchio del locale -> non deve sovrascrivere.
            .put(conversationJson("3", "Vecchia3", 50L))
        val payload = restorePayload(items = backupItems)
        val report = restoreLocalBackup(context, payload, null)
        assertEquals(2, report.conversationsMerged)
        val merged = loadConversations(context, includeDeleted = true)
        assertEquals(3, merged.size)
        assertEquals(1, merged.count { it.id == "1" })
        assertEquals(1, merged.count { it.id == "2" })
        assertEquals(1, merged.count { it.id == "3" })
        assertEquals("Nuova", merged.first { it.id == "1" }.title)
        assertEquals("SoloLocale", merged.first { it.id == "3" }.title)
    }

    @Test
    fun `restore payload malformato abort senza scritture`() {
        val context = FakeRestoreContext()
        context.prefs("chatclaw_settings").data["gatewayUrl"] = "https://prima.test"
        saveConversations(
            context,
            listOf(
                LocalConversation(
                    id = "keep", title = "Keep", kind = "Chat",
                    description = "", prompt = "", updatedAt = 10L, messages = emptyList()
                )
            ),
            syncAfterSave = false
        )
        // Voce archivi senza id -> malformata: deve abortire tutto.
        val badItems = JSONArray()
            .put(conversationJson("ok", "Ok", 20L))
            .put(JSONObject().put("title", "SenzaId"))
        val payload = restorePayload(
            settings = JSONObject().put("model", "dovrebbe-non-essere-scritto"),
            items = badItems
        )
        try {
            restoreLocalBackup(context, payload, null)
            fail("payload malformato deve lanciare BackupRestoreException")
        } catch (e: BackupRestoreException) {
            assertTrue((e.message ?: "").contains("Backup non valido"))
        }
        // Zero scritture: settings e archivio intatti.
        assertEquals("https://prima.test", context.prefs("chatclaw_settings").getString("gatewayUrl", null))
        assertFalse(context.prefs("chatclaw_settings").contains("model"))
        val current = loadConversations(context, includeDeleted = true)
        assertEquals(1, current.size)
        assertEquals("keep", current[0].id)
    }

    @Test
    fun `restore password errata errore esplicito mai plain`() {
        val key = restoreTestKey()
        val plain = JSONObject()
            .put("schema", "hermes-hub.local-backup.v1")
            .put("hello", "mondo")
            .toString().toByteArray(Charsets.UTF_8)
        val v2 = exportEncryptedWithPassword(plain, "giusta")
        // Password errata -> errore esplicito.
        try {
            decodeBackupPayloadWithPassword(v2, key, "sbagliata")
            fail("password errata deve fallire")
        } catch (e: BackupDecryptException) {
            assertTrue((e.message ?: "").contains("Backup non valido"))
        }
        // Password mancante su v2 -> errore esplicito password richiesta.
        try {
            decodeBackupPayloadWithPassword(v2, key, null)
            fail("password mancante deve fallire")
        } catch (e: BackupDecryptException) {
            assertTrue((e.message ?: "").contains("Backup non valido"))
        }
        // Password giusta -> ok, mai leak in errore.
        val decoded = decodeBackupPayloadWithPassword(v2, key, "giusta")
        assertEquals(String(plain, Charsets.UTF_8), String(decoded, Charsets.UTF_8))
    }
}
