package com.nemoclaw.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Copies each legacy store once, atomically from callers' point of view.
 * Completion is recorded only after the destination already had data or a
 * synchronous copy completed successfully, so a failed write can be retried.
 */
internal object PreferencesMigration {
    private val cache = ConcurrentHashMap<String, SharedPreferences>()
    private val completed = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val locks = ConcurrentHashMap<String, Any>()

    fun getOrMigrate(context: Context, currentName: String, legacyName: String): SharedPreferences {
        val appContext = context.applicationContext
        val current = cache.getOrPut(currentName) {
            appContext.getSharedPreferences(currentName, Context.MODE_PRIVATE)
        }
        if (completed.contains(currentName)) return current

        synchronized(locks.getOrPut(currentName) { Any() }) {
            if (completed.contains(currentName)) return current
            if (current.all.isNotEmpty()) {
                completed.add(currentName)
                return current
            }

            val legacy = cache.getOrPut(legacyName) {
                appContext.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            }
            val legacyValues = legacy.all
            if (legacyValues.isEmpty() || copyValues(current, legacyValues)) {
                completed.add(currentName)
            }
            return current
        }
    }

    private fun copyValues(destination: SharedPreferences, values: Map<String, *>): Boolean {
        destination.edit(commit = true) {
            values.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
        return values.all { (key, expected) ->
            when (expected) {
                is Set<*> -> destination.getStringSet(key, emptySet()) == expected.filterIsInstance<String>().toSet()
                else -> destination.all[key] == expected
            }
        }
    }
}
