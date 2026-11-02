package com.meshlink.app.data.transport

import org.junit.Assert.*
import org.junit.Test

class DynamicSimulationTest {
    private fun drain(sim: SimulationTransport) {
        var iterations=0
        while (sim.state.value.hasWork) { check(iterations++ < 5000) { "Simulator did not settle" };sim.step() }
    }
    @Test fun fourNodeChainUsesActualProtocolAndAcknowledgment() {
        val sim=SimulationTransport();drain(sim);sim.send("hello",8);drain(sim)
        val state=sim.state.value;val packet=state.lastDelivery!!
        assertEquals(state.nodes.map { it.id },packet.routeHistory)
        assertEquals(3,packet.hopCount);assertEquals(5,packet.ttl)
        assertEquals("Delivered (destination ACK)",state.statuses[state.lastMessageId])
        listOf("RREQ sent","RREQ received","RREP sent","RREP received","Route installed","DATA received","ACK received").forEach { text -> assertTrue(text,state.events.any { text in it.text }) }
    }
    @Test fun shorterEligiblePath() {
        val sim=SimulationTransport();sim.loadScenario("Alternative routes");drain(sim);sim.send("short");drain(sim)
        assertEquals(2,sim.state.value.lastDelivery!!.hopCount)
    }
    @Test fun failedRelayProcessesRerrAndRediscovers() {
        val sim=SimulationTransport();sim.loadScenario("Relay failure");drain(sim);sim.send("first");drain(sim)
        val failed=sim.state.value.lastDelivery!!.routeHistory[1]
        sim.setEnabled(failed,false);drain(sim)
        assertTrue(sim.state.value.events.any { "RERR" in it.text })
        sim.send("second");drain(sim)
        assertFalse(failed in sim.state.value.lastDelivery!!.routeHistory)
        assertEquals("Delivered (destination ACK)",sim.state.value.statuses[sim.state.value.lastMessageId])
    }
    @Test fun dynamicJoinNameChangeAndRemoval() {
        val sim=SimulationTransport();sim.loadScenario("Node joins");drain(sim)
        val last=sim.state.value.nodes.last().id;val added=sim.addNode("Duplicate name")
        sim.rename(sim.state.value.nodes.first().id,"Duplicate name")
        sim.setLink(last,added,true);drain(sim)
        assertTrue(added in sim.state.value.known.getValue(sim.state.value.sourceId))
        sim.selectDestination(added);sim.send("joined");drain(sim)
        assertEquals(3,sim.state.value.lastDelivery!!.hopCount)
        sim.removeNode(added);drain(sim)
        assertFalse(sim.state.value.nodes.any { it.id==added });assertFalse(sim.state.value.links.any { it.first==added || it.second==added })
    }
    @Test fun tenNodeTopologyAndArbitrarySource() {
        val sim=SimulationTransport();sim.loadScenario("Custom topology")
        repeat(7) { sim.addNode() };val ids=sim.state.value.nodes.map { it.id }
        ids.zipWithNext().forEach { sim.setLink(it.first,it.second,true) };drain(sim)
        sim.selectSource(ids.last());sim.selectDestination(ids.first());sim.send("ten nodes",16);drain(sim)
        assertEquals(9,sim.state.value.lastDelivery!!.hopCount)
        assertEquals(ids.reversed(),sim.state.value.lastDelivery!!.routeHistory)
    }
    @Test fun corruptFrameNeverRoutesOrDeliversAndTimesOut() {
        val sim=SimulationTransport();drain(sim);sim.send("corrupt",corrupt=true);drain(sim)
        assertNull(sim.state.value.lastDelivery)
        assertTrue(sim.state.value.events.any { "CRC FAIL" in it.text })
        assertTrue(sim.state.value.statuses.values.single().startsWith("Failed"))
    }
    @Test fun ttlExpiresAndResetClearsState() {
        val sim=SimulationTransport();drain(sim);sim.send("short TTL",1);drain(sim)
        assertNull(sim.state.value.lastDelivery);assertTrue(sim.state.value.events.any { "TTL expired" in it.text })
        val old=sim.state.value.nodes.map { it.id };sim.reset()
        assertTrue(sim.state.value.statuses.isEmpty());assertTrue(sim.state.value.nodes.none { it.id in old })
    }
    @Test fun removedLinkDropsQueuedFramesEvenIfReconnected() {
        val sim=SimulationTransport();drain(sim);val ids=sim.state.value.nodes.map { it.id };sim.send("race")
        sim.setLink(ids[0],ids[1],false);sim.setLink(ids[0],ids[1],true);drain(sim)
        assertTrue(sim.state.value.events.any { "session replaced" in it.text })
    }
    @Test fun simulatorStateIsIndependentBetweenInstances() {
        val first=SimulationTransport();val second=SimulationTransport()
        first.addNode("extra");assertEquals(4,second.state.value.nodes.size)
        assertTrue(first.state.value.nodes.none { a -> second.state.value.nodes.any { it.id == a.id } })
    }
    @Test fun cycleSuppressesDuplicateRequestsAndInstallsReverseRoute() {
        val sim = SimulationTransport(); val ids = sim.state.value.nodes.map { it.id }
        sim.setLink(ids[0], ids[2], true); drain(sim)
        sim.send("cycle"); drain(sim)
        assertTrue(sim.state.value.events.any { "Duplicate RREQ rejected" in it.text })
        assertTrue(sim.state.value.routes.getValue(ids.last()).any { it.destination == ids.first() && it.valid })
        assertEquals(1, sim.state.value.deliveries.size)
    }

}
