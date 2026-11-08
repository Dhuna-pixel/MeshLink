package com.meshlink.app

import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.ui.screens.*
import org.junit.Assert.*
import org.junit.Test

class ConversationsTest {
    @Test fun groupsBothDirectionsAndExcludesRelayTraffic() {
        val first = MessagePacket("1","me",destinationId="peer",payload="one",timestamp=1)
        val second = first.copy(messageId="2",sourceId="peer",destinationId="me",timestamp=2)
        val relay = first.copy(sourceId="other")
        assertEquals(mapOf("peer" to listOf(first,second)), conversations("me",listOf(second,relay,first)))
    }
    @Test fun groupCopiesDoNotLeakIntoDirectConversations() {
        val chat = com.meshlink.app.security.GroupChat("group", "Rescue team", "me", emptyList())
        val packet = MessagePacket("copy", "me", destinationId = "peer", payload = "group text",
            group = com.meshlink.app.security.GroupContext(chat, "logical-message"))
        assertTrue(conversations("me", listOf(packet)).isEmpty())
    }
    @Test fun bubblesDistinguishLinkReceiptFromDestinationAck() {
        listOf("Queued" to "Queued", "Discovering route" to "Discovering route", "Encrypting" to "Sending",
            "Queued for next hop; awaiting destination ACK" to "Sending",
            "Next-hop ATT receipt; awaiting destination ACK" to "Sent to next hop",
            "Delivered (destination ACK)" to "Delivered", "Failed: timeout" to "Failed"
        ).forEach { (status,label) -> assertEquals(label,deliveryLabel(status)) }
    }
}
