package com.meshlink.app.domain.models

import com.meshlink.app.domain.routing.RoutingEngine
import com.meshlink.app.utils.JsonSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossPlatformPacketCompatibilityTest {

    @Test
    fun testIosJsonToAndroidPacketDecoding() {
        val iosJson = """
        {
            "messageId": "MSG-IOS-999",
            "sourceId": "NODE-IOS-1",
            "sourceName": "iPhone 15",
            "destinationId": "NODE-ANDROID-2",
            "destinationName": "Android Galaxy",
            "payload": "Cross-platform relay test",
            "ttl": 4,
            "hopCount": 1,
            "timestamp": 1710000000000,
            "routeHistory": ["NODE-ORIGIN", "NODE-IOS-1"],
            "previousHopId": "NODE-IOS-1"
        }
        """.trimIndent()

        val packet = JsonSerializer.jsonToPacket(iosJson)
        assertNotNull("Android should decode iOS JSON packet", packet)

        assertEquals("MSG-IOS-999", packet?.messageId)
        assertEquals("NODE-IOS-1", packet?.sourceId)
        assertEquals("iPhone 15", packet?.sourceName)
        assertEquals("NODE-ANDROID-2", packet?.destinationId)
        assertEquals("Android Galaxy", packet?.destinationName)
        assertEquals("Cross-platform relay test", packet?.payload)
        assertEquals(4, packet?.ttl)
        assertEquals(1, packet?.hopCount)
        assertEquals(1710000000000L, packet?.timestamp)
        assertEquals(listOf("NODE-ORIGIN", "NODE-IOS-1"), packet?.routeHistory)
        assertEquals("NODE-IOS-1", packet?.previousHopId)
    }

    @Test
    fun testAndroidPacketToIosCompatibleJsonEncoding() {
        val packet = MessagePacket(
            messageId = "MSG-A1B2C3",
            sourceId = "NODE-ANDROID-1",
            sourceName = "Android Pixel",
            destinationId = "NODE-IOS-1",
            destinationName = "iPhone 15",
            payload = "Hello from Android to iOS!",
            ttl = 5,
            hopCount = 0,
            timestamp = 1700000000000L,
            routeHistory = listOf("NODE-ANDROID-1"),
            previousHopId = "NODE-ANDROID-1"
        )

        val jsonString = JsonSerializer.packetToJson(packet)

        assertTrue(jsonString.contains("\"messageId\":\"MSG-A1B2C3\""))
        assertTrue(jsonString.contains("\"sourceId\":\"NODE-ANDROID-1\""))
        assertTrue(jsonString.contains("\"destinationId\":\"NODE-IOS-1\""))
        assertTrue(jsonString.contains("\"payload\":\"Hello from Android to iOS!\""))
        assertTrue(jsonString.contains("\"ttl\":5"))
        assertTrue(jsonString.contains("\"hopCount\":0"))
    }

    @Test
    fun testMultiHopRelayForwardingTransformations() {
        val routingEngine = RoutingEngine()

        // 1. Android A creates packet for Android C
        val packetA = routingEngine.createLocalPacket(
            messageId = "MSG-MTR-100",
            sourceId = "NODE-A",
            sourceName = "Android A",
            destinationId = "NODE-C",
            destinationName = "Android C",
            payload = "Relay test across iPhone B",
            initialTtl = 5
        )

        assertEquals(5, packetA.ttl)
        assertEquals(0, packetA.hopCount)
        assertEquals(listOf("NODE-A"), packetA.routeHistory)

        // 2. iPhone B (Relay) processes packet
        val relayEngineB = RoutingEngine()
        val decisionB = relayEngineB.processIncomingPacket(
            packet = packetA,
            currentNodeId = "NODE-B",
            connectedNeighbors = listOf(
                Node(nodeId = "NODE-A", nodeName = "Android A", status = NodeStatus.CONNECTED),
                Node(nodeId = "NODE-C", nodeName = "Android C", status = NodeStatus.CONNECTED)
            )
        )

        assertTrue("Relay node should produce Forward decision", decisionB is ForwardingDecision.Forward)
        val forwardedPacket = (decisionB as ForwardingDecision.Forward).updatedPacket

        assertEquals(4, forwardedPacket.ttl)
        assertEquals(1, forwardedPacket.hopCount)
        assertEquals(listOf("NODE-A", "NODE-B"), forwardedPacket.routeHistory)
        assertEquals("NODE-B", forwardedPacket.previousHopId)
        assertEquals(listOf("NODE-C"), decisionB.candidateNextHopNodeIds)

        // 3. Android C receives packet
        val destinationEngineC = RoutingEngine()
        val decisionC = destinationEngineC.processIncomingPacket(
            packet = forwardedPacket,
            currentNodeId = "NODE-C",
            connectedNeighbors = listOf(
                Node(nodeId = "NODE-B", nodeName = "iPhone B", status = NodeStatus.CONNECTED)
            )
        )

        assertTrue("Destination node should produce Deliver decision", decisionC is ForwardingDecision.Deliver)
        val deliveredPacket = (decisionC as ForwardingDecision.Deliver).packet
        assertEquals("Relay test across iPhone B", deliveredPacket.payload)
        assertEquals(1, deliveredPacket.hopCount)
    }
}
