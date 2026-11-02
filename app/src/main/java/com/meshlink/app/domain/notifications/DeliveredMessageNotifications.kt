package com.meshlink.app.domain.notifications

import com.meshlink.app.data.preferences.AppPreferences
import com.meshlink.app.data.preferences.PreferenceStorage
import com.meshlink.app.domain.models.MessagePacket
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class NotificationTarget(val messageId: String, val senderId: String)
@Serializable
private data class SavedMessage(val localId: String, val packet: MessagePacket)
@Serializable
private data class SavedInbox(val seen: List<String> = emptyList(), val messages: List<SavedMessage> = emptyList())

/** Bounded persisted inbox supports notification taps after process death and retry deduplication. */
class ReceivedMessageStore(private val storage: PreferenceStorage) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var inbox = try { json.decodeFromString<SavedInbox>(storage.get("received") ?: "{}") } catch (_: Exception) { SavedInbox() }
    private fun key(local: String, packet: MessagePacket) = json.encodeToString(listOf(local,packet.sourceId,packet.messageId))
    @Synchronized fun remember(local: String, packet: MessagePacket): Boolean {
        val key = key(local,packet)
        if (key in inbox.seen) return false
        inbox = SavedInbox((inbox.seen + key).takeLast(2048), (inbox.messages + SavedMessage(local,packet)).takeLast(100))
        storage.put("received",json.encodeToString(inbox))
        return true
    }
    @Synchronized fun find(local: String, target: NotificationTarget): MessagePacket? = inbox.messages.lastOrNull {
        it.localId == local && it.packet.messageId == target.messageId && it.packet.sourceId == target.senderId
    }?.packet
}

data class MessageNotification(val target: NotificationTarget, val sender: String, val text: String, val privatePreview: Boolean)

/** Accepts only the production engine's final-delivery callback, never raw transport frames. */
class DeliveredMessageNotifications(
    private val localId: () -> String,
    private val preferences: () -> AppPreferences,
    private val systemAllowed: () -> Boolean,
    private val inbox: ReceivedMessageStore,
    private val senderName: (String) -> String?,
    private val post: (MessageNotification) -> Unit
) {
    fun onDelivered(packet: MessagePacket) {
        val local = localId()
        // TTL zero AFTER a valid final hop is deliverable; the engine rejects incoming TTL <= 0.
        if (packet.destinationId != local || packet.sourceId == local || packet.control != null || packet.ackFor != null ||
            packet.ttl < 0 || packet.hopCount < 1 || packet.routeHistory.lastOrNull() != local || packet.messageId.isBlank()) return
        if (!inbox.remember(local,packet)) return
        val prefs = preferences()
        if (!prefs.notificationsEnabled || !systemAllowed()) return
        val friendly = safeName(senderName(packet.sourceId).orEmpty()).ifBlank { safeName(packet.sourceName) }
        val sender = friendly.ifBlank { "Node ${safeName(packet.sourceId).take(12).ifBlank { "unknown" }}" }
        try {
            post(MessageNotification(NotificationTarget(packet.messageId,packet.sourceId), sender,
                packet.payload.take(4000),prefs.hideLockScreenPreview))
        } catch (_: Exception) {
            // Notification failure must not prevent message receipt or the destination acknowledgment.
        }
    }
    private fun safeName(value: String) = value.filter { !it.isISOControl() && it !in '\u202a'..'\u202e' && it !in '\u2066'..'\u2069' }.trim().take(64)
}
