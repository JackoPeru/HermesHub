package com.nemoclaw.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeCachePrefs : SharedPreferences {
    val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()

    @Suppress("UNCHECKED_CAST")
    override fun getString(key: String?, defValue: String?): String? =
        (data[key] as? String) ?: defValue

    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeCacheEditor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    inner class FakeCacheEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) {
                if (value == null) data.remove(key) else data[key] = value
            }
            return this
        }

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) data.remove(key)
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

private class FakeCacheContext : ContextWrapper(null) {
    private val stores = mutableMapOf<String, FakeCachePrefs>()

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        stores.getOrPut(name ?: "default") { FakeCachePrefs() }

    override fun getPackageName(): String = "com.nemoclaw.chat"

    fun prefs(name: String): FakeCachePrefs =
        getSharedPreferences(name, Context.MODE_PRIVATE) as FakeCachePrefs
}

class ArchiveCacheTest {
    /** Isolamento: PreferencesMigration e la cache parse sono globali di
     *  processo; senza reset i test si vedono a vicenda. */
    @Before fun resetGlobalCaches() {
        val migration = PreferencesMigration::class.java
        val cacheField = migration.getDeclaredField("cache").apply { isAccessible = true }
        (cacheField.get(PreferencesMigration) as java.util.concurrent.ConcurrentHashMap<*, *>).clear()
        val completedField = migration.getDeclaredField("completed").apply { isAccessible = true }
        (completedField.get(PreferencesMigration) as MutableSet<*>).clear()
        val repo = Class.forName("com.nemoclaw.chat.ConversationArchiveRepositoryKt")
        val parseField = repo.getDeclaredField("archiveParseCache").apply { isAccessible = true }
        parseField.set(null, null)
    }

    private fun seed(items: JSONArray): FakeCacheContext {
        val context = FakeCacheContext()
        context.prefs(CURRENT_ARCHIVE_PREFS).data["items"] = items.toString()
        return context
    }

    private fun convo(id: String, title: String, updatedAt: Long): JSONObject =
        JSONObject().put("id", id).put("title", title).put("updatedAt", updatedAt)
            .put("messages", JSONArray())

    @Test fun repeatedReadsReturnSameContent() {
        val context = seed(JSONArray().put(convo("a", "Chat A", 3L)).put(convo("b", "Chat B", 5L)))
        val first = loadConversations(context)
        val second = loadConversations(context)
        assertEquals(listOf("b", "a"), first.map { it.id })
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(first.map { it.title }, second.map { it.title })
    }

    @Test fun externalWriteInvalidatesCache() {
        val context = seed(JSONArray().put(convo("a", "Chat A", 1L)))
        assertEquals(listOf("a"), loadConversations(context).map { it.id })
        context.prefs(CURRENT_ARCHIVE_PREFS).data["items"] =
            JSONArray().put(convo("a", "Chat A", 1L)).put(convo("z", "Chat Z", 9L)).toString()
        assertEquals(listOf("z", "a"), loadConversations(context).map { it.id })
    }

    @Test fun saveInvalidatesCache() {
        val context = seed(JSONArray().put(convo("a", "Chat A", 1L)))
        assertEquals(1, loadConversations(context).size)
        val current = loadConversations(context, includeDeleted = true).toMutableList()
        saveConversations(context, current, syncAfterSave = false)
        assertEquals(1, loadConversations(context).size)
    }

    @Test fun cacheKeyDistinguishesLengthAndHash() {
        assertTrue(archiveCacheKey("abc") != archiveCacheKey("abcd"))
        assertTrue(archiveCacheKey("abc") != archiveCacheKey("abd"))
        assertEquals(archiveCacheKey("abc"), archiveCacheKey("abc"))
    }

    @Test fun clearEmptiesCache() {
        val context = seed(JSONArray().put(convo("a", "Chat A", 1L)))
        assertEquals(1, loadConversations(context).size)
        clearLocalArchiveAndTasks(context)
        assertTrue(loadConversations(context).isEmpty())
    }
}
