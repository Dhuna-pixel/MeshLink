package com.meshlink.app.data.transport

import com.meshlink.app.data.transport.framing.MeshLinkFrame
import com.meshlink.app.domain.crc.Crc16Ccitt
import com.meshlink.app.domain.models.ForwardingDecision
import com.meshlink.app.domain.models.LogLevel
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.NetworkLogEntry
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.models.NodeStatus
import com.meshlink.app.domain.routing.RoutingEngine
import com.meshlink.app.utils.JsonSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-fidelity 3-node Simulation Transport for Review III.
 *
 * Simulates:
 *   [Node A (Source)] <---> [Node B (Relay)] <---> [Node C (Destination)]
 *
 * Demonstrates:
 * 1. Binary framing and CRC-16-CCITT integrity verification at every hop.
 * 2. New CRC computation when Relay Node B mutates and forwards the packet.
 * 3. Exact hop progression: TTL: 5 -> 4 -> 3, Hop Count: 0 -> 1 -> 2, Route: [A] -> [A, B] -> [A, B, C].
 * 4. Controlled CRC error injection where Relay Node B detects corruption and drops the frame before routing.
 */
class SimulationTransport : MeshTransport {

    private val simulationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // Virtual Node Definitions
    val simNodeA = Node(nodeId = "NODE-A", nodeName = "Node A (Source)", status = NodeStatus.CONNECTED, endpointId = "EP-A")
    val simNodeB = Node(nodeId = "NODE-B", nodeName = "Node B (Relay)", status = NodeStatus.CONNECTED, endpointId = "EP-B")
    val simNodeC = Node(nodeId = "NODE-C", nodeName = "Node C (Destination)", status = NodeStatus.CONNECTED, endpointId = "EP-C")

    // Independent RoutingEngine instances for each virtual node
    private val engineA = RoutingEngine()
    private val engineB = RoutingEngine()
    private val engineC = RoutingEngine()

    private var localNode: Node = simNodeA
    private val corruptNextSimulationFrame = AtomicBoolean(false)

    fun setCorruptNextFrame(corrupt: Boolean) {
        corruptNextSimulationFrame.set(corrupt)
    }

    override fun start(localNode: Node) {
        this.localNode = localNode
        _isRunning.value = true

        emitLog(
            tag = "SIMULATION",
            message = "3-Node Simulation started. Topology initialized: [Node A] <---> [Node B (Relay)] <---> [Node C]",
            level = LogLevel.SUCCESS
        )

        simulationScope.launch {
            delay(200)
            if (localNode.nodeId == "NODE-A") {
                emitEvent(TransportEvent.NodeConnected(simNodeB))
                emitLog("SIMULATION", "Node A connected to neighbor Node B (Relay)", LogLevel.INFO, nodeId = "NODE-B")
            } else if (localNode.nodeId == "NODE-B") {
                emitEvent(TransportEvent.NodeConnected(simNodeA))
                emitEvent(TransportEvent.NodeConnected(simNodeC))
                emitLog("SIMULATION", "Node B (Relay) connected to Node A and Node C", LogLevel.INFO)
            } else {
                emitEvent(TransportEvent.NodeConnected(simNodeB))
                emitLog("SIMULATION", "Node C connected to neighbor Node B (Relay)", LogLevel.INFO, nodeId = "NODE-B")
            }
        }
    }

    override fun stop() {
        _isRunning.value = false
        engineA.resetSeenCache()
        engineB.resetSeenCache()
        engineC.resetSeenCache()
        emitLog("SIMULATION", "Simulation Mode stopped. Virtual state reset.", LogLevel.INFO)
    }

    override fun connectToNode(targetNode: Node) {
        emitEvent(TransportEvent.NodeConnected(targetNode.copy(status = NodeStatus.CONNECTED)))
    }

    override fun disconnectNode(nodeId: String) {
        emitEvent(TransportEvent.NodeDisconnected(nodeId, "EP-$nodeId"))
    }

    override fun sendPacket(packet: MessagePacket, targetEndpointId: String): Boolean {
        simulationScope.launch {
            executeMultiHopSimulation(packet)
        }
        return true
    }

    override fun broadcastPacket(packet: MessagePacket, excludeEndpointIds: Set<String>): Int {
        simulationScope.launch {
            executeMultiHopSimulation(packet)
        }
        return 1
    }

    /**
     * Executes the complete A -> B -> C simulation pipeline step-by-step with observable delays.
     */
    suspend fun executeMultiHopSimulation(initialPacket: MessagePacket) {
        val shouldCorrupt = corruptNextSimulationFrame.getAndSet(false)

        // =========================================================================
        // STEP 1: NODE A ENCODES FRAME & CALCULATES CRC-16
        // =========================================================================
        val jsonA = JsonSerializer.packetToJson(initialPacket)
        val payloadA = jsonA.toByteArray(StandardCharsets.UTF_8)
        val frameA = MeshLinkFrame.encode(
            frameType = MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET,
            payload = payloadA,
            corruptForTesting = shouldCorrupt
        )

        val crcA = Crc16Ccitt.compute(frameA, 0, frameA.size - MeshLinkFrame.CRC_SIZE)
        val crcHexA = Crc16Ccitt.formatHex(crcA)

        emitLog(
            tag = "SOURCE [A]",
            message = "Node A created packet '${initialPacket.messageId}' for '${initialPacket.destinationId}' (TTL: ${initialPacket.ttl}, Hops: ${initialPacket.hopCount})\nEncoded Frame: ${frameA.size} bytes | CRC-16: $crcHexA | Injected Corruption: $shouldCorrupt",
            level = if (shouldCorrupt) LogLevel.WARNING else LogLevel.INFO,
            packetSummary = initialPacket.getSummary(),
            nodeId = "NODE-A"
        )
        emitEvent(TransportEvent.PacketSent(initialPacket, "EP-B"))

        delay(600)

        // =========================================================================
        // STEP 2: NODE B (RELAY) RECEIVES FRAME & VALIDATES CRC-16
        // =========================================================================
        emitLog(
            tag = "RELAY [B]",
            message = "Node B received ${frameA.size}-byte BLE frame from Node A. Verifying CRC-16 integrity...",
            level = LogLevel.INFO,
            nodeId = "NODE-B"
        )
        delay(400)

        val decodeResultB = MeshLinkFrame.decode(frameA)
        if (decodeResultB is MeshLinkFrame.FrameResult.CrcError) {
            val rxHex = Crc16Ccitt.formatHex(decodeResultB.receivedCrc)
            val calcHex = Crc16Ccitt.formatHex(decodeResultB.calculatedCrc)
            emitLog(
                tag = "CRC-FAIL",
                message = "WARN [CRC][FAIL] Frame Corrupted! received=$rxHex calculated=$calcHex length=${decodeResultB.payloadLength}B peer=NODE-A. Packet DROPPED by Node B before routing.",
                level = LogLevel.ERROR,
                nodeId = "NODE-B"
            )
            return
        }

        if (decodeResultB !is MeshLinkFrame.FrameResult.Success) {
            emitLog(tag = "FRAME-ERROR", message = "Node B failed to decode frame header", level = LogLevel.ERROR)
            return
        }

        emitLog(
            tag = "CRC-PASS",
            message = "INFO [CRC][PASS] messageId=${initialPacket.messageId} received=$crcHexA calculated=$crcHexA at Node B (Relay)",
            level = LogLevel.SUCCESS,
            nodeId = "NODE-B"
        )

        // Decode packet payload
        val parsedPacketAtB = JsonSerializer.jsonToPacket(String(decodeResultB.payload, StandardCharsets.UTF_8))
        if (parsedPacketAtB == null) {
            emitLog(tag = "RELAY [B]", message = "Node B failed to parse packet JSON", level = LogLevel.ERROR)
            return
        }

        // Process through Node B's RoutingEngine
        val neighborsOfB = listOf(simNodeA, simNodeC)
        val decisionB = engineB.processIncomingPacket(parsedPacketAtB, currentNodeId = "NODE-B", connectedNeighbors = neighborsOfB)

        when (decisionB) {
            is ForwardingDecision.Forward -> {
                val forwardedPacket = decisionB.updatedPacket

                // =========================================================================
                // STEP 3: NODE B ENCODES FORWARDED PACKET WITH FRESH CRC-16
                // =========================================================================
                val jsonB = JsonSerializer.packetToJson(forwardedPacket)
                val payloadB = jsonB.toByteArray(StandardCharsets.UTF_8)
                val frameB = MeshLinkFrame.encode(
                    frameType = MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET,
                    payload = payloadB
                )
                val crcB = Crc16Ccitt.compute(frameB, 0, frameB.size - MeshLinkFrame.CRC_SIZE)
                val crcHexB = Crc16Ccitt.formatHex(crcB)

                emitLog(
                    tag = "RELAY [B]",
                    message = "DECISION: FORWARD | TTL: ${parsedPacketAtB.ttl} -> ${forwardedPacket.ttl} | Hops: ${parsedPacketAtB.hopCount} -> ${forwardedPacket.hopCount} | Path: ${forwardedPacket.routeHistory.joinToString(" -> ")}\nGenerated NEW Forwarded Frame: ${frameB.size}B | NEW CRC-16: $crcHexB -> Forwarding to Node C",
                    level = LogLevel.SUCCESS,
                    packetSummary = forwardedPacket.getSummary(),
                    nodeId = "NODE-B"
                )

                delay(600)

                // =========================================================================
                // STEP 4: NODE C (DESTINATION) RECEIVES FRAME & VALIDATES CRC-16
                // =========================================================================
                emitLog(
                    tag = "DEST [C]",
                    message = "Node C received ${frameB.size}-byte BLE frame from Node B. Verifying CRC-16 integrity...",
                    level = LogLevel.INFO,
                    nodeId = "NODE-C"
                )
                delay(400)

                val decodeResultC = MeshLinkFrame.decode(frameB)
                if (decodeResultC is MeshLinkFrame.FrameResult.CrcError) {
                    emitLog(tag = "CRC-FAIL", message = "WARN [CRC][FAIL] at Node C", level = LogLevel.ERROR)
                    return
                }

                if (decodeResultC !is MeshLinkFrame.FrameResult.Success) {
                    emitLog(tag = "FRAME-ERROR", message = "Node C failed to decode frame", level = LogLevel.ERROR)
                    return
                }

                emitLog(
                    tag = "CRC-PASS",
                    message = "INFO [CRC][PASS] messageId=${forwardedPacket.messageId} received=$crcHexB calculated=$crcHexB at Node C (Destination)",
                    level = LogLevel.SUCCESS,
                    nodeId = "NODE-C"
                )

                val parsedPacketAtC = JsonSerializer.jsonToPacket(String(decodeResultC.payload, StandardCharsets.UTF_8)) ?: return
                val neighborsOfC = listOf(simNodeB)
                val decisionC = engineC.processIncomingPacket(parsedPacketAtC, currentNodeId = "NODE-C", connectedNeighbors = neighborsOfC)

                when (decisionC) {
                    is ForwardingDecision.Deliver -> {
                        val delivered = decisionC.packet
                        emitLog(
                            tag = "DEST [C]",
                            message = "DECISION: DELIVER | FINAL DESTINATION REACHED!\nPayload: \"${delivered.payload}\"\nFinal TTL: ${delivered.ttl}\nFinal Hop Count: ${delivered.hopCount}\nComplete Route: ${delivered.routeHistory.joinToString(" -> ")}",
                            level = LogLevel.SUCCESS,
                            packetSummary = delivered.getSummary(),
                            nodeId = "NODE-C"
                        )
                        emitEvent(TransportEvent.PacketReceived(delivered, "EP-B"))
                    }
                    else -> {
                        emitLog(tag = "DEST [C]", message = "Unexpected routing decision at Node C: $decisionC", level = LogLevel.ERROR)
                    }
                }
            }
            is ForwardingDecision.Deliver -> {
                emitLog("RELAY [B]", "Node B delivered packet: ${decisionB.reason}", LogLevel.SUCCESS)
                emitEvent(TransportEvent.PacketReceived(decisionB.packet, "EP-A"))
            }
            is ForwardingDecision.DropDuplicate -> {
                emitLog("RELAY [B]", decisionB.reason, LogLevel.WARNING)
            }
            is ForwardingDecision.DropTtlExpired -> {
                emitLog("RELAY [B]", decisionB.reason, LogLevel.WARNING)
            }
            is ForwardingDecision.DropNoRoute -> {
                emitLog("RELAY [B]", decisionB.reason, LogLevel.ERROR)
            }
        }
    }

    private fun emitLog(
        tag: String,
        message: String,
        level: LogLevel = LogLevel.INFO,
        packetSummary: String? = null,
        nodeId: String? = null
    ) {
        val entry = NetworkLogEntry(
            tag = tag,
            message = message,
            level = level,
            packetSummary = packetSummary,
            relatedNodeId = nodeId
        )
        emitEvent(TransportEvent.LogEmitted(entry))
    }

    private fun emitEvent(event: TransportEvent) {
        simulationScope.launch {
            _events.emit(event)
        }
    }
}
