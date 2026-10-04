package com.nemoclaw.chat.features.bots

import android.os.Parcel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BotChatContextParcelTest {

    private fun sample(multiplex: Boolean = true) = BotChatContext(
        profile = "helper",
        sessionId = "sess-123",
        displayName = "Helper Bot",
        localConversationId = "bot-primary-helper-sess-123",
        multiplexEnabled = multiplex,
        connectionId = "primary",
        endpoint = "https://gw.example"
    )

    @Test
    fun `round-trip conserva tutti i campi`() {
        val original = sample(multiplex = true)
        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = BotChatContext.CREATOR.createFromParcel(parcel)
            assertEquals(original, restored)
            assertEquals("helper", restored.profile)
            assertEquals("sess-123", restored.sessionId)
            assertEquals("Helper Bot", restored.displayName)
            assertEquals("bot-primary-helper-sess-123", restored.localConversationId)
            assertEquals(true, restored.multiplexEnabled)
            assertEquals("primary", restored.connectionId)
            assertEquals("https://gw.example", restored.endpoint)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `round-trip conserva multiplex false e default`() {
        val original = BotChatContext(
            profile = "p",
            sessionId = "s",
            displayName = "d",
            localConversationId = "l",
            multiplexEnabled = false
        )
        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = BotChatContext.CREATOR.createFromParcel(parcel)
            assertEquals(original, restored)
            assertEquals(false, restored.multiplexEnabled)
            assertEquals("primary", restored.connectionId)
            assertEquals("", restored.endpoint)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `read di String null diventa stringa vuota`() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeString(null)
            parcel.writeString(null)
            parcel.writeString(null)
            parcel.writeString(null)
            parcel.writeByte(1)
            parcel.writeString(null)
            parcel.writeString(null)
            parcel.setDataPosition(0)
            val restored = BotChatContext.CREATOR.createFromParcel(parcel)
            assertEquals("", restored.profile)
            assertEquals("", restored.sessionId)
            assertEquals("", restored.displayName)
            assertEquals("", restored.localConversationId)
            assertEquals(true, restored.multiplexEnabled)
            assertEquals("", restored.connectionId)
            assertEquals("", restored.endpoint)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `marshall-unmarshall via obtain conserva uguaglianza`() {
        val original = sample(multiplex = false).copy(endpoint = "", connectionId = "watch")
        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            assertNotNull(bytes)
            val restoredParcel = Parcel.obtain()
            try {
                restoredParcel.unmarshall(bytes, 0, bytes.size)
                restoredParcel.setDataPosition(0)
                val restored = BotChatContext.CREATOR.createFromParcel(restoredParcel)
                assertEquals(original, restored)
            } finally {
                restoredParcel.recycle()
            }
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `describeContents zero e newArray dimensione`() {
        assertEquals(0, sample().describeContents())
        val array = BotChatContext.CREATOR.newArray(2)
        assertEquals(2, array.size)
        assertArrayEquals(arrayOfNulls(2), array)
    }
}
