package com.nemoclaw.chat

import com.nemoclaw.chat.features.bots.HermesBotItem
import com.nemoclaw.chat.features.bots.HermesBotRoster
import com.nemoclaw.chat.features.bots.stableBotConversationId

/**
 * Identita della chat normale (selettore bot nel titolo): "" = Hermes
 * default, altrimenti "connessione::profilo" (HermesBotItem.identityKey).
 * Persiste in AppSettings.activeChatBotKey (sopravvive al riavvio);
 * ogni conversazione locale e stampata con LocalConversation.botIdentityKey
 * cosi la sidebar mostra solo le chat dell'identita attiva.
 * Tutto puro e testabile: niente Android, niente rete.
 */

/** Chiave default: profilo Hermes implicito. */
internal const val DEFAULT_CHAT_IDENTITY_KEY = ""

internal fun normalizeChatIdentityKey(key: String?): String = key?.trim().orEmpty()

/** Bot del roster per la chiave (null = default o non trovato: fail-closed sul default). */
internal fun resolveChatIdentity(roster: HermesBotRoster?, key: String): HermesBotItem? {
    val clean = key.trim()
    if (clean.isEmpty()) return null
    return roster?.items?.firstOrNull { it.identityKey == clean }
}

/** Etichetta quando il roster non risolve la chiave (offline): profilo dopo "::". */
internal fun chatIdentityFallbackLabel(key: String): String =
    key.substringAfter("::", key).trim().takeIf { it.isNotBlank() } ?: "Hermes"

/**
 * Chiave identita proprietaria di una conversazione: lo stamp vince; le
 * chat bot legacy senza stamp ("bot-*"/"botchat-*") si attribuiscono via
 * roster (stableBotConversationId); il resto e default. Mai null: le chat
 * senza match restano col default (mai nascondere chat esistenti).
 */
internal fun conversationIdentityKey(conv: LocalConversation, roster: HermesBotRoster?): String {
    val stamped = conv.botIdentityKey.trim()
    if (stamped.isNotEmpty()) return stamped
    if (!isBotConversationId(conv.id)) return DEFAULT_CHAT_IDENTITY_KEY
    val items = roster?.items.orEmpty()
    if (items.isNotEmpty()) {
        val match = items.firstOrNull {
            runCatching { stableBotConversationId(it) }.getOrNull() == conv.id
        }
        if (match != null) return match.identityKey
    }
    return DEFAULT_CHAT_IDENTITY_KEY
}

/** Sottoinsieme delle conversazioni possedute dall'identita (ordine invariato). */
internal fun conversationsForIdentity(
    conversations: List<LocalConversation>,
    key: String,
    roster: HermesBotRoster? = null
): List<LocalConversation> {
    val clean = key.trim()
    return conversations.filter { conversationIdentityKey(it, roster) == clean }
}

/** Voce del menu identita (chiave vuota = Hermes default, aggiunta dalla UI). */
internal data class ChatIdentityOption(
    val key: String,
    val label: String,
    val subtitle: String
)

/** Opzioni dal roster (nascosti esclusi, ordine roster). */
internal fun chatIdentityOptions(roster: HermesBotRoster?): List<ChatIdentityOption> =
    roster?.items.orEmpty()
        .filter { !it.hidden }
        .map {
            ChatIdentityOption(
                key = it.identityKey,
                label = it.displayName.trim().takeIf { name -> name.isNotBlank() } ?: it.profile,
                subtitle = it.connectionLabel.trim().takeIf { label -> label.isNotBlank() } ?: it.profile
            )
        }

/** Picker issato: la TopBar lo renderizza, AppRoot/ChatScreen lo alimentano. */
internal data class ChatIdentityPicker(
    val currentLabel: String,
    val activeKey: String,
    val loading: Boolean,
    val status: String,
    val options: List<ChatIdentityOption>,
    val onPick: (String) -> Unit,
    val onRefresh: () -> Unit = {}
)
