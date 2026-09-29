package com.nemoclaw.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveRepositoryContractTest {
    @Test fun nullOverridesReadBackAsBlankInsteadOfNullString() {
        val array = JSONArray()
        val item = JSONObject()
        item.put("id", "x")
        item.put("modelOverride", JSONObject.NULL)
        item.put("providerOverride", JSONObject.NULL)
        item.put("reasoningEffort", JSONObject.NULL)
        array.put(item)
        val loaded = readConversationsFromJsonArray(array)
        assertEquals(1, loaded.size)
        assertEquals("", loaded[0].modelOverride)
        assertEquals("", loaded[0].providerOverride)
        assertEquals("", loaded[0].reasoningEffort)
    }

    @Test fun blankOverridesStayBlank() {
        val array = JSONArray()
        val item = JSONObject()
        item.put("id", "y")
        item.put("modelOverride", "")
        item.put("providerOverride", "")
        array.put(item)
        val loaded = readConversationsFromJsonArray(array)
        assertEquals(1, loaded.size)
        assertEquals("", loaded[0].modelOverride)
        assertEquals("", loaded[0].providerOverride)
    }
}
