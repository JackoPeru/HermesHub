package com.nemoclaw.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private class FakeQueuePrefs : SharedPreferences {
    val data = mutableMapOf<String, String?>()
    override fun getAll(): Map<String, *> = data.toMap()
    override fun getString(key: String?, defValue: String?): String? = data[key] ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeQueueEditor(data)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private class FakeQueueEditor(private val data: MutableMap<String, String?>) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { data[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor = apply { data.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { data.clear() }
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }
}

private class FakeQueueContext : ContextWrapper(null) {
    private val stores = mutableMapOf<String, FakeQueuePrefs>()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        stores.getOrPut(name ?: "default") { FakeQueuePrefs() }
    override fun getPackageName(): String = "com.nemoclaw.chat"
}

class QueuedPromptsTest {

    @Test
    fun roundTripPreservesQueue() {
        val context = FakeQueueContext()
        val file = File.createTempFile("queue-", ".jpg")
        try {
            val queue = listOf(
                QueuedPrompt("a", "primo", listOf(ChatInputAttachment(filename = "f.jpg", mimeType = "image/jpeg", sizeBytes = 10, localFilePath = file.absolutePath)), 1000L),
                QueuedPrompt("b", "secondo", emptyList(), 2000L)
            )
            saveQueuedPrompts(context, queue)
            val loaded = loadQueuedPrompts(context)
            assertEquals(2, loaded.size)
            assertEquals("a", loaded[0].conversationId)
            assertEquals("primo", loaded[0].text)
            assertEquals(1000L, loaded[0].atMs)
            assertEquals(1, loaded[0].attachments.size)
            assertEquals(file.absolutePath, loaded[0].attachments[0].localFilePath)
            assertEquals("b", loaded[1].conversationId)
            assertTrue(loaded[1].attachments.isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingFilesAndEmptyEntriesAreDropped() {
        val context = FakeQueueContext()
        val queue = listOf(
            QueuedPrompt("a", "", listOf(ChatInputAttachment(filename = "x.jpg", mimeType = "image/jpeg", sizeBytes = 10, localFilePath = "/non/esiste.jpg"))),
            QueuedPrompt("", "no-cid"),
            QueuedPrompt("b", "ok")
        )
        saveQueuedPrompts(context, queue)
        val loaded = loadQueuedPrompts(context)
        assertEquals(1, loaded.size)
        assertEquals("b", loaded[0].conversationId)
    }

    @Test
    fun emptyQueueRoundTrips() {
        val context = FakeQueueContext()
        saveQueuedPrompts(context, emptyList())
        assertTrue(loadQueuedPrompts(context).isEmpty())
        assertTrue(loadQueuedPrompts(FakeQueueContext()).isEmpty())
    }

    @Test
    fun capIsTenPerChat() {
        assertEquals(10, MAX_QUEUED_PROMPTS_PER_CHAT)
        val full = (1..10).map { QueuedPrompt("a", "p$it") }
        assertTrue(!canEnqueuePrompt(full, "a"))
        assertTrue(canEnqueuePrompt(full.dropLast(1), "a"))
    }

    @Test
    fun attachmentCountSurvivesRoundTrip() {
        val context = FakeQueueContext()
        val file = File.createTempFile("queue-", ".jpg")
        try {
            val queue = listOf(
                QueuedPrompt(
                    "a", "testo",
                    listOf(ChatInputAttachment(filename = "f.jpg", mimeType = "image/jpeg", sizeBytes = 10, localFilePath = file.absolutePath)),
                    attachmentCount = 3
                )
            )
            saveQueuedPrompts(context, queue)
            val loaded = loadQueuedPrompts(context)
            assertEquals(1, loaded.size)
            assertEquals(1, loaded[0].attachments.size)
            assertEquals(3, loaded[0].attachmentCount)
        } finally {
            file.delete()
        }
    }

    @Test
    fun shouldCancelManagerJobOnlyForTurnYoungActiveJobs() {
        val nowMs = System.currentTimeMillis()
        val sinceMs = nowMs - 60_000
        val createdSec = nowMs / 1000.0
        assertTrue(shouldCancelManagerJob("queued", createdSec, sinceMs))
        assertTrue(shouldCancelManagerJob("running", createdSec, sinceMs))
        assertFalse(shouldCancelManagerJob("done", createdSec, sinceMs))
        assertFalse(shouldCancelManagerJob("failed", createdSec, sinceMs))
        assertFalse(shouldCancelManagerJob("cancelled", createdSec, sinceMs))
        assertFalse(shouldCancelManagerJob("queued", createdSec - 3600, sinceMs))
        assertFalse(shouldCancelManagerJob("", createdSec, sinceMs))
    }
}
