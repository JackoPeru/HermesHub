package com.nemoclaw.chat.features.bots

import android.content.Context
import com.nemoclaw.chat.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

internal data class HermesBotGroupMemberResult(
    val member: HermesBotGroupMember,
    val reply: String,
    val silent: Boolean
)

internal data class HermesBotGroupTurnResult(
    val groupName: String,
    val outcome: String,
    val reply: String,
    val rounds: Int,
    val botMessages: Int,
    val memberResults: List<HermesBotGroupMemberResult>,
    val failures: List<String>
)

private const val MAX_GROUP_ROUNDS = 3
private const val MAX_GROUP_MESSAGES = 10
private val groupMentionPattern = Pattern.compile("(?<![\\w])@([A-Za-z0-9][A-Za-z0-9_.-]{0,63})(?![\\w])")
private val memberLocks = ConcurrentHashMap<String, Mutex>()
private val activeTurns = ConcurrentHashMap<String, Job>()
private val turnEpochs = ConcurrentHashMap<String, AtomicLong>()
private val turnRegistryLock = Any()

internal suspend fun runHermesBotGroupTurn(
    context: Context,
    settings: AppSettings,
    group: HermesBotGroup,
    userMessage: String
): HermesBotGroupTurnResult = coroutineScope {
    val normalized = group.copy(members = group.members.toList())
    val originalUserMessage = userMessage.trim()
    require(originalUserMessage.isNotBlank() && originalUserMessage.length <= 20_000) { "Il messaggio del gruppo è obbligatorio e limitato a 20.000 caratteri." }
    val key = normalized.name.lowercase()
    val currentJob = coroutineContext[Job] ?: error("Turno gruppo senza job.")
    val epoch = synchronized(turnRegistryLock) {
        activeTurns[key]?.cancel()
        turnEpochs.getOrPut(key) { AtomicLong(0L) }.incrementAndGet().also {
            activeTurns[key] = currentJob
        }
    }
    try {
        val allMembers = normalized.members
        val mentioned = mentionedHandles(originalUserMessage)
        val available = if (mentioned.isEmpty() || mentioned.contains("everyone")) {
            selectMembers(originalUserMessage, allMembers).ifEmpty { allMembers }
        } else {
            selectMembers(originalUserMessage, allMembers)
        }
        var pending = available
        var prompt = originalUserMessage
        var rounds = 0
        var botMessages = 0
        var lastReply = ""
        var outcome = "reply"
        var transcript = emptyList<HermesBotGroupMemberResult>()
        val replies = mutableListOf<HermesBotGroupMemberResult>()
        val failures = mutableListOf<String>()
        val failedIdentities = mutableSetOf<String>()

        while (pending.isNotEmpty() && rounds < MAX_GROUP_ROUNDS && botMessages < MAX_GROUP_MESSAGES) {
            ensureCurrentGroupTurn(key, epoch)
            rounds++
            val current = pending
            pending = emptyList()
            var roundNonPass = 0
            var escalated = false
            val nextTargets = mutableListOf<HermesBotGroupMember>()
            for (member in current) {
                if (member.identityKey.lowercase() in failedIdentities) continue
                ensureCurrentGroupTurn(key, epoch)
                if (botMessages >= MAX_GROUP_MESSAGES) break
                val lock = memberLocks.getOrPut(member.identityKey.lowercase()) { Mutex() }
                try {
                    val result = lock.withLock {
                        ensureCurrentGroupTurn(key, epoch)
                        requestMemberTurn(context, settings, normalized, member, prompt, transcript, originalUserMessage)
                    }
                    replies += result
                    if (!result.silent) {
                        botMessages++
                        roundNonPass++
                        lastReply = result.reply
                        transcript = (transcript + result).takeLast(8)
                        if (containsMention(result.reply, "user")) {
                            outcome = "escalation"
                            escalated = true
                            break
                        }
                        nextTargets += mentionedMembers(result.reply, allMembers)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failedIdentities += member.identityKey.lowercase()
                    failures += "${member.handle}: membro non ha completato il turno."
                }
            }
            if (escalated) break
            if (roundNonPass == 0) {
                outcome = if (failures.isEmpty()) "pass" else "partial"
                break
            }
            if (botMessages >= MAX_GROUP_MESSAGES || rounds >= MAX_GROUP_ROUNDS) {
                outcome = "bounded"
                break
            }
            pending = (nextTargets.ifEmpty { allMembers })
                .filterNot { it.identityKey.lowercase() in failedIdentities }
                .distinctBy { it.identityKey.lowercase() }
            prompt = buildFollowUpPrompt(originalUserMessage)
        }
        if (outcome == "reply" && (rounds >= MAX_GROUP_ROUNDS || botMessages >= MAX_GROUP_MESSAGES)) outcome = "bounded"
        if (failures.isNotEmpty() && replies.isNotEmpty() && outcome !in setOf("pass", "partial", "escalation")) outcome = "partial"
        if (failures.isNotEmpty() && replies.isEmpty()) outcome = "error"
        HermesBotGroupTurnResult(normalized.name, outcome, lastReply, rounds, botMessages, replies, failures)
    } finally {
        synchronized(turnRegistryLock) {
            activeTurns.remove(key, currentJob)
        }
    }
}

private suspend fun requestMemberTurn(
    context: Context,
    settings: AppSettings,
    group: HermesBotGroup,
    member: HermesBotGroupMember,
    prompt: String,
    transcript: List<HermesBotGroupMemberResult>,
    originalUserMessage: String
): HermesBotGroupMemberResult {
    val connection = loadHermesBotConnections(context, settings).items.firstOrNull { it.id.equals(member.connectionId, true) }
        ?: error("Connessione Hermes ${member.connectionId} non trovata.")
    require(connection.enabled && connection.endpoint.isNotBlank()) { "Endpoint connessione Hermes non configurato." }
    val payload = JSONObject()
        .put("group_name", group.name)
        .put("members", JSONArray(group.members.map { descriptor ->
            JSONObject()
                .put("connection_id", descriptor.connectionId)
                .put("profile", descriptor.profile)
                .put("display_name", descriptor.displayName)
                .put("handle", descriptor.handle)
        }))
        .put("user_message", prompt)
        .put("original_user_message", originalUserMessage)
        .put("max_rounds", 1)
        .put("max_messages", 1)
        .put("local_connection_id", member.connectionId)
        .put("target_profiles", JSONArray().put(member.profile))
        .put("transcript", JSONArray(transcript.takeLast(8).map { item ->
            JSONObject().put("handle", item.member.handle).put("reply", item.reply)
        }))
    val response = botPostForConnection(
        settings,
        connection,
        secretForBotConnection(context, connection),
        "/v1/hub/bots/group-turn",
        payload
    )
    if (response.first !in 200..299) error("HTTP ${response.first}: turno gruppo rifiutato.")
    val root = JSONObject(response.second)
    if (root.optJSONArray("member_failures")?.length() ?: 0 > 0) {
        error("Il membro Hermes non ha completato il turno.")
    }
    var reply = root.optString("reply")
    val memberResults = root.optJSONArray("member_results")
    if (memberResults != null && memberResults.length() > 0) {
        val first = memberResults.optJSONObject(0)
        if (first != null) reply = first.optString("reply", reply)
    }
    val silent = isExactPass(reply)
    return HermesBotGroupMemberResult(member, reply, silent)
}

private fun selectMembers(prompt: String, members: List<HermesBotGroupMember>): List<HermesBotGroupMember> {
    if (containsMention(prompt, "everyone")) return members
    val mentioned = mentionedHandles(prompt)
    return members.filter { mentioned.contains(it.handle.lowercase()) }
}

private fun mentionedMembers(text: String, members: List<HermesBotGroupMember>): List<HermesBotGroupMember> {
    if (containsMention(text, "everyone")) return members
    val mentioned = mentionedHandles(text)
    return members.filter { mentioned.contains(it.handle.lowercase()) }
}

private fun mentionedHandles(text: String): Set<String> {
    val matcher = groupMentionPattern.matcher(text)
    val result = mutableSetOf<String>()
    while (matcher.find()) matcher.group(1)?.lowercase()?.let { result += it }
    return result
}

private fun containsMention(text: String, handle: String): Boolean =
    mentionedHandles(text).contains(handle.lowercase())

private fun isExactPass(reply: String): Boolean =
    reply.trim().lowercase() in setOf("", "(pass)", "pass", "pass.")

private fun ensureCurrentGroupTurn(key: String, epoch: Long) {
    val current = synchronized(turnRegistryLock) {
        activeTurns[key]?.isActive == true && turnEpochs[key]?.get() == epoch
    }
    if (!current) {
        throw CancellationException("Turno gruppo annullato o sostituito")
    }
}

private fun buildFollowUpPrompt(originalUserMessage: String): String {
    val prefix = "Richiesta originale dell'utente:\n"
    val suffix = "\n\nContinua il turno del gruppo usando il contesto recente."
    val available = (20_000 - prefix.length - suffix.length).coerceAtLeast(1)
    return prefix + originalUserMessage.take(available) + suffix
}
