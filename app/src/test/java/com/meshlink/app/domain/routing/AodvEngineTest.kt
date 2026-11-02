package com.meshlink.app.domain.routing

import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.data.transport.framing.MeshLinkFrame
import com.meshlink.app.utils.JsonSerializer
import org.junit.Assert.*
import org.junit.Test

class AodvEngineTest {
    private class Network {
        var time = 1000L
        val nodes = mutableMapOf<String, AodvEngine>()
        val links = mutableSetOf<Set<String>>()
        val queue = ArrayDeque<Triple<String, String, MessagePacket>>()
        val deliveries = mutableListOf<MessagePacket>()
        val confirmedPaths = mutableMapOf<String, List<String>>()
        val statuses = mutableMapOf<String, String>()
        val known = mutableMapOf<String, MutableSet<String>>()
        val traffic = mutableListOf<MessagePacket>()
        var corrupt = false
        fun node(id: String) {
            nodes[id] = AodvEngine(id, id, { to, packet ->
                if (setOf(id, to) in links) { queue.add(Triple(id, to, packet)); traffic.add(packet); true } else false
            }, { deliveries.add(it) }, { key, value -> statuses[key] = value },
                { peer, _ -> known.getOrPut(id) { mutableSetOf() }.add(peer) }, {}, { time },
                { messageId, path -> confirmedPaths[messageId] = path })
        }
        fun link(a: String, b: String) { links.add(setOf(a,b)); nodes.getValue(a).neighborUp(b); nodes.getValue(b).neighborUp(a) }
        fun unlink(a: String, b: String) { links.remove(setOf(a,b)); nodes.getValue(a).neighborDown(b); nodes.getValue(b).neighborDown(a) }
        fun flush() {
            var count = 0
            while (queue.isNotEmpty()) {
                check(count++ < 10000) { "Control storm" }
                val (from,to,p) = queue.removeFirst()
                if (setOf(from,to) !in links) continue
                val frame = MeshLinkFrame.encode(1, JsonSerializer.packetToBytes(p), corrupt && p.control == null)
                val decoded = MeshLinkFrame.decode(frame)
                if (decoded is MeshLinkFrame.FrameResult.Success) nodes.getValue(to).receive(JsonSerializer.bytesToPacket(decoded.payload)!!, from)
            }
        }
        fun send(to: String, ttl: Int = 8, id: String = "message") {
            nodes.getValue("A").send(MessagePacket(id, "A", destinationId = to, payload = "hello", ttl = ttl, routeHistory = listOf("A")))
            flush()
        }
    }
    private fun chain(size: Int): Network = Network().apply {
        (0 until size).forEach { node(('A' + it).toString()) }
        (0 until size - 1).forEach { link(('A' + it).toString(), ('B' + it).toString()) }
        flush()
    }
    @Test fun directDeliveryAndAck() { val n=chain(2); n.send("B"); assertEquals(1,n.deliveries.single().hopCount); assertEquals("Delivered (destination ACK)",n.statuses["message"]) }
    @Test fun threeNodeRelay() { val n=chain(3); n.send("C"); assertEquals(listOf("A","B","C"),n.deliveries.single().routeHistory) }
    @Test fun fourNodeRelay() { val n=chain(4); n.send("D"); assertEquals(3,n.deliveries.single().hopCount); assertEquals(5,n.deliveries.single().ttl); assertEquals(listOf("A","B","C","D"),n.confirmedPaths["message"]); assertEquals("B",n.nodes.getValue("A").snapshot().first { it.destination=="D" }.nextHop) }
    @Test fun shorterEligiblePath() {
        val n=Network(); listOf("A","B","C","D","E").forEach(n::node)
        listOf("A" to "B","B" to "D","A" to "C","C" to "E","E" to "D").forEach { n.link(it.first,it.second) }
        n.flush(); n.send("D"); assertEquals(2,n.deliveries.single().hopCount)
    }
    @Test fun brokenRouteRediscoversAlternative() {
        val n=chain(4); n.link("A","C"); n.flush(); n.send("D")
        n.unlink("A","C"); n.flush(); n.send("D", id="second")
        assertEquals(listOf("A","B","C","D"),n.deliveries.last().routeHistory)
    }
    @Test fun diamondReroutesAfterRelayLeaves() {
        val n=Network(); listOf("A","B","C","D").forEach(n::node)
        listOf("A" to "B","B" to "D","A" to "C","C" to "D").forEach { n.link(it.first,it.second) }
        n.flush();n.send("D");assertEquals(listOf("A","B","D"),n.deliveries.last().routeHistory)
        n.unlink("A","B");n.unlink("B","D");n.flush();n.send("D",id="alternate")
        assertEquals(listOf("A","C","D"),n.deliveries.last().routeHistory)
    }
    @Test fun remoteBrokenLinkPropagatesError() {
        val n=chain(4); n.send("D"); n.unlink("C","D"); n.flush()
        assertFalse(n.nodes.getValue("A").snapshot().first { it.destination=="D" }.valid)
    }
    @Test fun nodeJoinAnnouncementAndRoute() {
        val n=chain(3); n.node("D"); n.link("C","D"); n.flush()
        assertTrue("D" in n.known.getValue("A")); n.send("D"); assertEquals("D",n.deliveries.single().destinationId)
    }
    @Test fun corruptionRejected() { val n=chain(2); n.corrupt=true; n.send("B"); assertTrue(n.deliveries.isEmpty()) }
    @Test fun duplicateDeliveredOnlyOnce() {
        val n=chain(2); n.send("B"); val data=n.traffic.first { it.control==null && it.ackFor==null }
        n.nodes.getValue("B").receive(data,"A"); n.flush(); assertEquals(1,n.deliveries.size)
    }
    @Test fun ttlAndLoopRejection() {
        val n=chain(3); n.send("C",1); assertTrue(n.deliveries.isEmpty())
        n.nodes.getValue("B").receive(MessagePacket("loop","A",destinationId="B",payload="bad",routeHistory=listOf("A","B")),"A")
        assertTrue(n.deliveries.isEmpty())
    }
    @Test fun pendingTimeoutAndExpiration() {
        val n=chain(2); n.send("missing"); repeat(8) { n.time+=5000; n.nodes.getValue("A").tick(); n.flush() }
        assertTrue(n.statuses.getValue("message").startsWith("Failed"))
        n.send("B",id="ok"); n.time+=61000; n.nodes.getValue("A").tick()
        assertFalse(n.nodes.getValue("A").snapshot().first { it.destination=="B" }.valid)
    }
    @Test fun sequenceFreshnessAndShorterHopComparison() {
        val n=chain(3); val a=n.nodes.getValue("A")
        fun reply(seq: Long,hops: Int) { a.receive(MessagePacket("c","B",destinationId="Z",payload="",control=RouteControl("RREP","Q","Z",sequence=seq,hops=hops)),"B") }
        reply(9,3); reply(8,0); assertEquals(4,a.snapshot().first { it.destination=="Z" }.hops)
        reply(9,1); assertEquals(2,a.snapshot().first { it.destination=="Z" }.hops)
        reply(10,4); assertEquals(10L,a.snapshot().first { it.destination=="Z" }.sequence)
    }
    @Test fun duplicateRreqSuppressedAndReverseRouteInstalled() {
        val n=chain(3); val b=n.nodes.getValue("B")
        val p=MessagePacket("control","A",destinationId="C",payload="",control=RouteControl("RREQ","A","C",sequence=100))
        b.receive(p,"A"); val before=n.queue.size; b.receive(p,"A")
        assertEquals(before,n.queue.size); assertEquals("A",b.snapshot().first { it.destination=="A" }.nextHop)
    }
}
