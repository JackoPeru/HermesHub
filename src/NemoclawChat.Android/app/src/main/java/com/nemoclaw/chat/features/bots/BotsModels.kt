package com.nemoclaw.chat.features.bots

import android.os.Parcel
import android.os.Parcelable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal const val CONNECTION_ROSTER_TIMEOUT_MILLIS = 8_000L

internal data class HermesBotItem(
    val profile: String,
    val displayName: String,
    val description: String,
    val hidden: Boolean,
    val chatId: String?,
    val isDefault: Boolean,
    val connectionId: String = "primary",
    val connectionLabel: String = "Gateway principale",
    val identityKey: String = "$connectionId::$profile",
    val handle: String = displayName
)

internal data class HermesBotRoster(
    val items: List<HermesBotItem>,
    val botModeProtocol: Boolean,
    val multiplexEnabled: Boolean,
    val chatSupported: Boolean,
    val status: String,
    val sourceFailures: List<String> = emptyList()
)

internal data class BotChatContext(
    val profile: String,
    val sessionId: String,
    val displayName: String,
    val localConversationId: String,
    val multiplexEnabled: Boolean,
    val connectionId: String = "primary",
    val endpoint: String = ""
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readByte() != 0.toByte(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(profile)
        parcel.writeString(sessionId)
        parcel.writeString(displayName)
        parcel.writeString(localConversationId)
        parcel.writeByte(if (multiplexEnabled) 1 else 0)
        parcel.writeString(connectionId)
        parcel.writeString(endpoint)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<BotChatContext> = object : Parcelable.Creator<BotChatContext> {
            override fun createFromParcel(parcel: Parcel): BotChatContext = BotChatContext(parcel)
            override fun newArray(size: Int): Array<BotChatContext?> = arrayOfNulls(size)
        }
    }
}

/**
 * Azioni della sidebar che richiedono la UI della sezione Bot (editor,
 * conferma eliminazione, gruppi, schermo): la sidebar le accoda, la
 * sezione le consuma. Il resto (chat, pin, hide, sezioni, auto-screen,
 * nuova chat, sessioni recenti) la sidebar lo fa da sola.
 */
internal sealed interface BotSidebarAction {
    data class Edit(val botKey: String) : BotSidebarAction
    data class Duplicate(val botKey: String) : BotSidebarAction
    data class Delete(val botKey: String) : BotSidebarAction
    data object ManageGroups : BotSidebarAction
    data class OpenScreen(val botKey: String) : BotSidebarAction
}

/** Id conversazione locale per una sessione NON canonica: mai collidere con la forever-chat. */
internal fun stableBotSessionConversationId(bot: HermesBotItem, sessionId: String): String {
    val suffix = sessionId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(16)
    return "${stableBotConversationId(bot)}-s-${suffix.ifBlank { "x" }}"
}

/** Titolo per una nuova chat pulita: mai "Bot Chat" (registro canonico). */
internal fun newBotChatTitle(bot: HermesBotItem): String =
    "Chat con ${bot.displayName.trim().take(60).ifBlank { bot.profile }}".take(80)

