package com.nemoclaw.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakePrefs : SharedPreferences {
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

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    inner class FakeEditor : SharedPreferences.Editor {
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

private class FakeBlockContext : ContextWrapper(null) {
    private val stores = mutableMapOf<String, FakePrefs>()

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        stores.getOrPut(name ?: "default") { FakePrefs() }

    override fun getPackageName(): String = "com.nemoclaw.chat"
}

class BlockScreenshotsSettingsTest {

    @Before
    fun clearMigrationCache() {
        // PreferencesMigration tiene cache statica: va azzerata per isolare i test JVM.
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
    fun `default resta false per non rompere screenshot utente`() {
        assertFalse(AppSettings().blockScreenshots)
        assertFalse(AppDefaults.blockScreenshots)
        val loaded = loadSettings(FakeBlockContext())
        assertFalse(loaded.blockScreenshots)
    }

    @Test
    fun `flag true persiste dopo save-load`() {
        val context = FakeBlockContext()
        saveSettings(context, AppSettings(blockScreenshots = true))
        assertTrue(loadSettings(context).blockScreenshots)
    }

    @Test
    fun `flag false persiste e toggle true-false`() {
        val context = FakeBlockContext()
        saveSettings(context, AppSettings(blockScreenshots = true))
        assertTrue(loadSettings(context).blockScreenshots)
        saveSettings(context, loadSettings(context).copy(blockScreenshots = false))
        assertFalse(loadSettings(context).blockScreenshots)
    }

    @Test
    fun `copy preserva flag e altri campi`() {
        val base = AppSettings(blockScreenshots = true, model = "m1")
        assertEquals(true, base.copy().blockScreenshots)
        assertEquals("m1", base.copy().model)
        assertEquals(false, base.copy(blockScreenshots = false).blockScreenshots)
    }
}
