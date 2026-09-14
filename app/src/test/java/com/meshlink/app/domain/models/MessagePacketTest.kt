package com.meshlink.app.domain.models

import com.meshlink.app.utils.JsonSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MessagePacketTest {

    @Test
    fun testPacketForwardingMutation() {
        val original = MessagePacket(
            messageId = "MSG-100",
            sourceId = "NODE-A",
            sourceName = "Node A",
            destinationId = "NODE-C",
            destinationName = "Node C",
            payload = "Test Multi-Hop Forwarding",
            ttl = 5,
            hopCount = 0,
            routeHistory = listOf("NODE-A"),
            previousHopId = "NODE-A"
        )

        val forwardedByB = original.createForwardedPacket("NODE-B")

        assertEquals(4, forwardedByB.ttl)
        assertEquals(1, forwardedByB.hopCount)
        assertEquals(listOf("NODE-A", "NODE-B"), forwardedByB.routeHistory)
        assertEquals("NODE-B", forwardedByB.previousHopId)
        assertEquals("MSG-100", forwardedByB.messageId)
        assertEquals("NODE-A", forwardedByB.sourceId)
        assertEquals("NODE-C", forwardedByB.destinationId)
    }

    @Test
    fun testJsonSerializationRoundtrip() {
        val original = MessagePacket(
            messageId = "MSG-JSON-99",
            sourceId = "NODE-A",
            sourceName = "Alpha",
            destinationId = "NODE-C",
            destinationName = "Charlie",
            payload = "Structured Mesh Payload",
            ttl = 3,
            hopCount = 2,
            timestamp = 1700000000000L,
            routeHistory = listOf("NODE-A", "NODE-B"),
            previousHopId = "NODE-B"
        )

        val json = JsonSerializer.packetToJson(original)
        assertNotNull(json)

        val deserialized = JsonSerializer.jsonToPacket(json)
        assertNotNull(deserialized)
        assertEquals(original.messageId, deserialized?.messageId)
        assertEquals(original.sourceId, deserialized?.sourceId)
        assertEquals(original.destinationId, deserialized?.destinationId)
        assertEquals(original.payload, deserialized?.payload)
        assertEquals(original.ttl, deserialized?.ttl)
        assertEquals(original.hopCount, deserialized?.hopCount)
        assertEquals(original.routeHistory, deserialized?.routeHistory)
    }
}
