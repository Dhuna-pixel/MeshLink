package com.meshlink.app.security

import com.meshlink.app.data.preferences.PreferenceStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.PrivateKey
import java.security.Signature
import java.util.UUID

/** A creator-signed, immutable roster. Each member brings their own signed public profile. */
@Serializable
data class GroupChat(val id: String, val name: String, val creator: String,
    val members: List<SignedProfile>, val signature: String = "") {
    companion object { const val MAX_MEMBERS = 8 }
    private fun signingBytes() = ("MeshLink/Group/1\n" + Json.encodeToString(copy(signature = ""))).toByteArray()
    fun sign(key: PrivateKey): GroupChat = copy(signature = Signature.getInstance("SHA256withECDSA").run {
        initSign(key); update(signingBytes()); sign().b64()
    })
    fun member(id: String) = members.find { it.profile.card.id == id }?.profile?.card
    fun verify() {
        require(UUID.fromString(id).toString() == id && name.isNotBlank() && name.length <= 64)
        require(members.size in 2..MAX_MEMBERS && members.map { it.profile.card.id }.distinct().size == members.size)
        require(Json.encodeToString(this).toByteArray().size <= 12_000) { "Group profiles are too large" }
        members.forEach { it.profile.verify(it.signature) }
        val owner = requireNotNull(member(creator))
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(owner.cert().publicKey); update(signingBytes()); verify(signature.bytes64())
        }) { "Invalid group signature" }
    }
}

@Serializable
data class GroupContext(val chat: GroupChat, val messageId: String, val kind: String = "message",
    val exitCount: Int = 0, val exitToken: String = "") {
    fun verify(sender: String, recipient: String) {
        require(UUID.fromString(messageId).toString() == messageId)
        require(kind in setOf("message", "created", "exit", "invite", "joined"))
        if (kind in setOf("exit", "invite", "joined") || (kind == "message" && exitCount > 0)) {
            require(exitCount in 1..3 && UUID.fromString(exitToken).toString() == exitToken)
            require(kind == "exit" || exitCount < 3)
        } else require(exitCount == 0 && exitToken.isEmpty())
        chat.verify()
        require(chat.member(sender) != null && chat.member(recipient) != null && sender != recipient)
        require(kind != "created" || sender == chat.creator)
    }
}

@Serializable
internal data class GroupBody(val context: GroupContext, val text: String)
@Serializable
data class GroupExit(val count: Int = 0, val token: String = "", val left: Boolean = false)
@Serializable
private data class SavedGroups(val chats: List<GroupChat> = emptyList(), val left: Set<String> = emptySet(),
    val exits: Map<String, Map<String, GroupExit>> = emptyMap(), val hidden: Set<String> = emptySet())

/** Stored inside the existing encrypted vault; leave decisions survive restarts and repeated invites. */
class GroupStore(private val storage: PreferenceStorage, private val owner: String) {
    private var state = Json.decodeFromString<SavedGroups>(storage.get(owner) ?: "{}")
    init {
        // Upgrade earlier permanent-left records without forgetting that the person already exited.
        state.left.forEach { id ->
            if (state.exits[id]?.get(owner) == null)
                setExit(id, owner, GroupExit(1, UUID.randomUUID().toString(), true))
        }
    }
    @Synchronized fun chats() = state.chats
    @Synchronized fun hidden() = state.hidden
    @Synchronized fun hide(id: String) { require(state.chats.any { it.id == id }); save(state.copy(hidden = state.hidden + id)) }
    @Synchronized fun show(id: String) { if (id in state.hidden) save(state.copy(hidden = state.hidden - id)) }
    @Synchronized fun left() = state.left
    @Synchronized fun exits() = state.exits
    @Synchronized fun exit(id: String, member: String) = state.exits[id]?.get(member)
        ?: GroupExit(count = if (member == owner && id in state.left) 1 else 0, left = member == owner && id in state.left)
    @Synchronized fun canReceive(context: GroupContext, sender: String, blocked: Set<String>): Boolean {
        val id = context.chat.id
        val known = state.chats.any { it.id == id }
        if (context.kind in setOf("created", "invite") && sender in blocked) return false
        if (!known && (sender in blocked || context.chat.creator in blocked)) return false
        val own = exit(id, owner)
        if (context.kind == "invite") return own.count in 1..2 &&
            context.exitCount == own.count && context.exitToken == own.token && sender !in blocked
        if (own.left) return false
        // A member who has left must rejoin before posting again.
        val senderExit = exit(id, sender)
        if (context.kind == "message") {
            if (context.exitCount == 0) return senderExit.count == 0 && !senderExit.left
            return context.exitCount in 1..2 && context.exitCount >= senderExit.count &&
                (context.exitCount > senderExit.count || context.exitToken == senderExit.token)
        }
        if (context.kind == "joined") return senderExit.count < 3
        return context.kind == "exit" || !senderExit.left
    }
    @Synchronized fun applyEvent(context: GroupContext, sender: String) {
        val member = if (context.kind == "invite") owner else sender
        val old = exit(context.chat.id, member)
        when (context.kind) {
            "exit" -> if (context.exitCount > old.count) setExit(context.chat.id, member, GroupExit(context.exitCount, context.exitToken, true))
            "invite" -> {
                require(old.count < 3 && old.count == context.exitCount && old.token == context.exitToken)
                setExit(context.chat.id, owner, old.copy(left = false))
            }
            "joined", "message" -> if (context.exitCount in 1..2 && context.exitCount >= old.count &&
                (context.exitCount > old.count || context.exitToken == old.token))
                setExit(context.chat.id, member, GroupExit(context.exitCount, context.exitToken, false))
        }
    }
    private fun setExit(id: String, member: String, value: GroupExit) {
        val exits = state.exits + (id to (state.exits[id].orEmpty() + (member to value)))
        val left = if (member != owner) state.left else if (value.left) state.left + id else state.left - id
        save(state.copy(exits = exits, left = left))
    }
    @Synchronized fun remember(chat: GroupChat) {
        val old = state.chats.find { it.id == chat.id }
        if (old != null) { require(old == chat) { "Group membership changed" }; return }
        require(state.chats.size < 64) { "Group limit reached" }
        save(state.copy(chats = state.chats + chat))
    }
    @Synchronized fun leave(id: String): GroupExit {
        require(state.chats.any { it.id == id })
        val old = exit(id, owner)
        if (old.left) return old
        val next = GroupExit((old.count + 1).coerceAtMost(3), UUID.randomUUID().toString(), true)
        setExit(id, owner, next)
        return next
    }
    private fun save(next: SavedGroups) { storage.put(owner, Json.encodeToString(next)); state = next }
}
