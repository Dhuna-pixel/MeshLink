package com.meshlink.app.data.transport

import com.meshlink.app.data.transport.framing.encode

import com.meshlink.app.data.transport.framing.BleFragments
import com.meshlink.app.data.transport.framing.FragmentAssembler
import com.meshlink.app.data.transport.framing.MeshLinkFrame
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.routing.AodvEngine
import com.meshlink.app.domain.routing.AodvRoute
import com.meshlink.app.utils.JsonSerializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class VirtualNode(val id: String, val name: String, val enabled: Boolean = true)
data class VirtualLink(val first: String, val second: String) {
    companion object { fun between(a: String, b: String) = if (a < b) VirtualLink(a,b) else VirtualLink(b,a) }
}
data class SimulationEvent(val number: Long, val time: Long, val text: String)
data class SimulationActivity(val from: String, val to: String, val kind: String)
data class SimulationState(
    val nodes: List<VirtualNode> = emptyList(), val links: Set<VirtualLink> = emptySet(),
    val sourceId: String = "", val destinationId: String = "", val events: List<SimulationEvent> = emptyList(),
    val routes: Map<String, List<AodvRoute>> = emptyMap(), val known: Map<String, Set<String>> = emptyMap(),
    val deliveries: List<MessagePacket> = emptyList(), val statuses: Map<String, String> = emptyMap(),
    val lastMessageId: String? = null, val activity: SimulationActivity? = null, val queued: Int = 0,
    val initialTtl: Int? = null, val sentSource: String? = null, val sentDestination: String? = null,
    val clock: Long = 0, val scenario: String = "Linear chain"
) {
    val hasWork get() = queued > 0 || statuses.values.any { !it.startsWith("Delivered") && !it.startsWith("Failed") }
    val lastDelivery get() = deliveries.lastOrNull { it.messageId == lastMessageId }
    fun display(id: String) = nodes.find { it.id == id }?.name ?: id.take(8)
}

/** In-memory link adapter; every virtual participant runs the SAME AodvEngine as physical BLE.
 * No topology search, invented path, or predetermined delivery outcome lives in this adapter.
 * Virtual time only advances when stepped; FIFO link events provide deterministic demonstrations.
 */
class SimulationTransport(private val maxNodes: Int = 32) {
    private val nodes = linkedMapOf<String, VirtualNode>()
    private val engines = linkedMapOf<String, AodvEngine>()
    private val links = linkedSetOf<VirtualLink>()
    private data class Envelope(val from: String, val to: String, val frame: ByteArray, val kind: String, val generation: Long)
    private val queue = ArrayDeque<Envelope>()
    private val generations = mutableMapOf<VirtualLink, Long>()
    private val events = ArrayDeque<SimulationEvent>()
    private val deliveries = mutableListOf<MessagePacket>()
    private val statuses = linkedMapOf<String, String>()
    private val known = mutableMapOf<String, MutableSet<String>>()
    private val corruptIds = mutableSetOf<String>()
    private var clock = 1_000L
    private var eventId = 0L
    private var transferId = 0
    private var source = ""
    private var destination = ""
    private var scenario = "Linear chain"
    private var lastMessageId: String? = null
    private var initialTtl: Int? = null
    private var sentSource: String? = null
    private var sentDestination: String? = null
    private var activity: SimulationActivity? = null
    private val _state = MutableStateFlow(SimulationState())
    val state = _state.asStateFlow()
    init { loadScenario("Linear chain") }

    private fun record(text: String) {
        events.addLast(SimulationEvent(++eventId, clock, text))
        while (events.size > 400) events.removeFirst()
    }
    private fun label(id: String) = nodes[id]?.name ?: id.take(8)
    private fun active(a: String, b: String) = nodes[a]?.enabled == true && nodes[b]?.enabled == true && VirtualLink.between(a,b) in links
    private fun publish() {
        _state.value = SimulationState(nodes.values.toList(), links.toSet(), source, destination, events.toList(),
            engines.mapValues { it.value.snapshot() }, known.mapValues { it.value.toSet() }, deliveries.toList(), statuses.toMap(),
            lastMessageId, activity, queue.size, initialTtl, sentSource, sentDestination, clock, scenario)
    }
    @Synchronized fun addNode(name: String = "Device ${nodes.size + 1}"): String {
        require(nodes.size < maxNodes) { "Simulator limit is $maxNodes nodes." }
        val id = "virtual-${UUID.randomUUID()}"
        nodes[id] = VirtualNode(id, name.trim().take(48).ifBlank { "Device" })
        engines[id] = AodvEngine(id, nodes.getValue(id).name,
            transmit = { to, packet -> enqueue(id, to, packet) },
            delivered = { packet ->
                deliveries.add(packet); if (deliveries.size > 100) deliveries.removeAt(0)
                record("${label(id)}: Destination reached; ${packet.hopCount} hops, TTL ${packet.ttl}")
            },
            status = { messageId, status -> statuses[messageId] = status; record("${label(id)}: $status") },
            learned = { peer, _ -> if (known.getOrPut(id) { mutableSetOf() }.add(peer)) record("${label(id)} discovered ${label(peer)}") },
            log = { record("${label(id)}: $it") }, now = { clock })
        if (source.isBlank()) source = id else if (destination.isBlank()) destination = id
        record("${label(id)} joined (unique ID $id)")
        publish(); return id
    }
    @Synchronized fun rename(id: String, name: String) {
        val node = nodes[id] ?: return
        val clean = name.trim().take(48).ifBlank { node.name }
        nodes[id] = node.copy(name = clean)
        engines.getValue(id).rename(clean)
        record("Node ${id.takeLast(8)} renamed to $clean"); publish()
    }
    @Synchronized fun setEnabled(id: String, enabled: Boolean) {
        val node = nodes[id] ?: return
        if (node.enabled == enabled) return
        val peers = links.filter { it.first == id || it.second == id }.map { if (it.first == id) it.second else it.first }
        nodes[id] = node.copy(enabled = enabled)
        for (peer in peers) {
            val link = VirtualLink.between(id,peer)
            generations[link] = (generations[link] ?: 0) + 1
            if (enabled && nodes[peer]?.enabled == true) { engines.getValue(id).neighborUp(peer); engines.getValue(peer).neighborUp(id) }
            else { engines[id]?.neighborDown(peer); engines[peer]?.neighborDown(id) }
        }
        if (!enabled) engines.getValue(id).cancelPending("Failed: virtual node disabled")
        record("${label(id)} ${if (enabled) "enabled" else "failed / disabled"}"); publish()
    }
    @Synchronized fun removeNode(id: String) {
        setEnabled(id, false)
        nodes.remove(id); engines.remove(id); known.remove(id); known.values.forEach { it.remove(id) }
        links.removeAll { it.first == id || it.second == id }
        generations.keys.removeAll { it.first == id || it.second == id }
        if (source == id) source = nodes.keys.firstOrNull().orEmpty()
        if (destination == id || destination == source) destination = nodes.keys.firstOrNull { it != source }.orEmpty()
        record("Virtual node removed: $id"); publish()
    }
    @Synchronized fun setLink(a: String, b: String, enabled: Boolean) {
        if (a == b || a !in nodes || b !in nodes) return
        val link = VirtualLink.between(a,b)
        if ((link in links) == enabled) return
        generations[link] = (generations[link] ?: 0) + 1
        if (enabled) {
            links.add(link)
            if (active(a,b)) { engines.getValue(a).neighborUp(b); engines.getValue(b).neighborUp(a) }
        } else {
            links.remove(link); engines.getValue(a).neighborDown(b); engines.getValue(b).neighborDown(a)
        }
        record("Link ${label(a)} ↔ ${label(b)} ${if (enabled) "connected" else "disconnected"}"); publish()
    }
    @Synchronized fun selectSource(id: String) { if (id in nodes) source = id; publish() }
    @Synchronized fun selectDestination(id: String) { if (id in nodes) destination = id; publish() }
    @Synchronized fun send(text: String, ttl: Int = 8, corrupt: Boolean = false): String? {
        if (source == destination || nodes[source]?.enabled != true || destination !in nodes || text.isBlank() || text.toByteArray().size > 2000) {
            record("Send rejected: choose distinct nodes, an enabled source, and 1–2000 UTF-8 bytes."); publish(); return null
        }
        if (statuses.size >= 100) {
            val completed = statuses.entries.firstOrNull { it.value.startsWith("Delivered") || it.value.startsWith("Failed") }
            if (completed == null) { record("Send rejected: outgoing history full"); publish(); return null }
            statuses.remove(completed.key); corruptIds.remove(completed.key)
        }
        val id = UUID.randomUUID().toString()
        lastMessageId = id
        initialTtl = ttl.coerceIn(1,16); sentSource = source; sentDestination = destination
        if (corrupt) corruptIds.add(id)
        engines.getValue(source).send(MessagePacket(id, source, label(source), destination, label(destination), text,
            ttl = ttl.coerceIn(1,16), timestamp = clock, routeHistory = listOf(source)))
        publish(); return id
    }
    private fun enqueue(from: String, to: String, packet: MessagePacket): Boolean {
        if (!active(from,to) || queue.size >= 4096) return false
        val kind = packet.control?.kind ?: if (packet.ackFor != null) "ACK" else "DATA"
        val frame = try { MeshLinkFrame.encode(1, JsonSerializer.packetToBytes(packet), packet.messageId in corruptIds) }
            catch (e: IllegalArgumentException) { record("Frame rejected: ${e.message}"); return false }
        val link = VirtualLink.between(from,to)
        queue.addLast(Envelope(from,to,frame,kind,generations[link] ?: 0))
        record("$kind sent: ${label(from)} → ${label(to)}")
        return true
    }
    @Synchronized fun step() {
        clock += if (queue.isEmpty()) 1000 else 25
        val envelope = if (queue.isEmpty()) null else queue.removeFirst()
        if (envelope != null) {
            activity = SimulationActivity(envelope.from,envelope.to,envelope.kind)
            val link = VirtualLink.between(envelope.from,envelope.to)
            if (!active(envelope.from,envelope.to) || generations[link] != envelope.generation) record("${envelope.kind} dropped: link disconnected or session replaced")
            else {
                val assembler = FragmentAssembler { clock }
                var complete: ByteArray? = null
                BleFragments.split(envelope.frame,64,++transferId).forEach { complete = assembler.accept(it) ?: complete }
                val decoded = complete?.let(MeshLinkFrame::decode)
                if (decoded is MeshLinkFrame.FrameResult.Success) {
                    val packet = JsonSerializer.bytesToPacket(decoded.payload)
                    if (packet != null) {
                        record("${envelope.kind} received at ${label(envelope.to)} — CRC PASS")
                        engines.getValue(envelope.to).receive(packet,envelope.from)
                    } else record("Malformed packet rejected")
                } else record("CRC FAIL / invalid frame at ${label(envelope.to)} — dropped before routing")
            }
        } else activity = null
        engines.filterKeys { nodes[it]?.enabled == true }.values.forEach { it.tick() }
        publish()
    }
    @Synchronized fun loadScenario(name: String) {
        nodes.clear(); engines.clear(); links.clear(); generations.clear(); queue.clear(); events.clear(); deliveries.clear(); statuses.clear(); known.clear(); corruptIds.clear()
        initialTtl = null; sentSource = null; sentDestination = null
        clock = 1000; source = ""; destination = ""; lastMessageId = null; activity = null; scenario = name
        val count = when(name) { "Alternative routes" -> 5; "Node joins", "Custom topology" -> 3; else -> 4 }
        val ids = (1..count).map { addNode("Device $it") }
        fun link(a: Int,b: Int) = setLink(ids[a-1],ids[b-1],true)
        when(name) {
            "Alternative routes" -> { link(1,2);link(2,4);link(1,3);link(3,5);link(5,4) }
            "Relay failure" -> { link(1,2);link(2,4);link(1,3);link(3,4) }
            "Custom topology" -> Unit
            else -> (1 until count).forEach { link(it,it+1) }
        }
        source = ids.first(); destination = if (name == "Alternative routes") ids[3] else ids.last()
        record("Simulation — Virtual Nodes: $name loaded"); publish()
    }
    @Synchronized fun reset() { loadScenario(scenario) }
}
