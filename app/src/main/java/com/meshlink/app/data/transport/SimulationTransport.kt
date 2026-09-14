package com.meshlink.app.data.transport

import com.meshlink.app.domain.models.ForwardingDecision
import com.meshlink.app.domain.models.LogLevel
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.NetworkLogEntry
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.models.NodeStatus
import com.meshlink.app.domain.routing.RoutingEngine
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

/**
 * High-fidelity in-memory Simulation Transport for testing and demonstrating multi-hop forwarding
 * on a single device or in emulator.
 *
 * Implements a virtual 3-node topology:
 *   [Node A] <---> [Node B (Relay)] <---> [Node C]
 *
 * Each virtual node runs an independent instance of [RoutingEngine].
 * When Node A sends to Node C:
 *   1. Node A transmits packet to Node B.
 *   2. Node B's RoutingEngine processes the packet:
 *      - Checks duplicate (PASSED)
 *      - Checks destination (Node B != Node C -> Relay required)
 *      - Decrements TTL (e.g. 5 -> 4)
 *      - Increments hopCount (0 -> 1)
 *      - Appends Node B to routeHistory: [NODE-A, NODE-B]
 *      - Forwards to Node C
 *   3. Node C's RoutingEngine processes the packet:
 *      - Destination match (Node C == Node C -> DELIVER)
 *      - Emits delivery event and updates received message store
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

    override fun start(localNode: Node) {
        this.localNode = localNode
        _isRunning.value = true

        emitLog(
            tag = "SIMULATION",
            message = "Simulation Mode started. Topology initialized: [Node A] <---> [Node B (Relay)] <---> [Node C]",
            level = LogLevel.SUCCESS
        )

        // Automatically discover and connect neighbors for simulation
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
        emitLog(
            tag = "SOURCE [A]",
            message = "Node A initiated packet '${initialPacket.messageId}' with destination '${initialPacket.destinationId}'",
            level = LogLevel.INFO,
            packetSummary = initialPacket.getSummary(),
            nodeId = "NODE-A"
        )
        emitEvent(TransportEvent.PacketSent(initialPacket, "EP-B"))

        // Simulating wireless air propagation delay
        delay(600)

        // =========================================================================
        // HOP 1: NODE B (RELAY) RECEIVES AND PROCESSES PACKET
        // =========================================================================
        emitLog(
            tag = "RELAY [B]",
            message = "Node B received packet '${initialPacket.messageId}' from Node A. Evaluating forwarding rules...",
            level = LogLevel.INFO,
            nodeId = "NODE-B"
        )
        delay(400)

        // Node B's active neighbors are A and C
        val neighborsOfB = listOf(simNodeA, simNodeC)
        val decisionB = engineB.processIncomingPacket(initialPacket, currentNodeId = "NODE-B", connectedNeighbors = neighborsOfB)

        when (decisionB) {
            is ForwardingDecision.Forward -> {
                val forwardedPacket = decisionB.updatedPacket
                emitLog(
                    tag = "RELAY [B]",
                    message = "DECISION: FORWARD | TTL: ${initialPacket.ttl} -> ${forwardedPacket.ttl} | Hops: ${initialPacket.hopCount} -> ${forwardedPacket.hopCount} | Next Hop: ${decisionB.candidateNextHopNodeIds.joinToString()}",
                    level = LogLevel.SUCCESS,
                    packetSummary = forwardedPacket.getSummary(),
                    nodeId = "NODE-B"
                )

                // Wireless propagation to Node C
                delay(600)

                // =========================================================================
                // HOP 2: NODE C (DESTINATION) RECEIVES PACKET
                // =========================================================================
                emitLog(
                    tag = "DEST [C]",
                    message = "Node C received packet '${forwardedPacket.messageId}' from Node B. Evaluating destination...",
                    level = LogLevel.INFO,
                    nodeId = "NODE-C"
                )
                delay(400)

                val neighborsOfC = listOf(simNodeB)
                val decisionC = engineC.processIncomingPacket(forwardedPacket, currentNodeId = "NODE-C", connectedNeighbors = neighborsOfC)

                when (decisionC) {
                    is ForwardingDecision.Deliver -> {
                        emitLog(
                            tag = "DEST [C]",
                            message = "DECISION: DELIVER | Final Hops: ${forwardedPacket.hopCount} | Path: ${forwardedPacket.routeHistory.joinToString(" -> ")} -> NODE-C | Payload: \"${forwardedPacket.payload}\"",
                            level = LogLevel.SUCCESS,
                            packetSummary = forwardedPacket.getSummary(),
                            nodeId = "NODE-C"
                        )
                        // Deliver packet event so UI displays received message
                        emitEvent(TransportEvent.PacketReceived(decisionC.packet, "EP-B"))
                    }
                    else -> {
                        emitLog(
                            tag = "DEST [C]",
                            message = "Unexpected routing decision at Node C: $decisionC",
                            level = LogLevel.ERROR,
                            nodeId = "NODE-C"
                        )
                    }
                }
            }
            is ForwardingDecision.Deliver -> {
                emitLog("RELAY [B]", "Node B delivered packet locally: ${decisionB.reason}", LogLevel.SUCCESS)
                emitEvent(TransportEvent.PacketReceived(decisionB.packet, "EP-A"))
            }
            is ForwardingDecision.DropDuplicate -> {
                emitLog("RELAY [B]", "Node B dropped duplicate packet: ${decisionB.reason}", LogLevel.WARNING)
            }
            is ForwardingDecision.DropTtlExpired -> {
                emitLog("RELAY [B]", "Node B dropped expired packet: ${decisionB.reason}", LogLevel.WARNING)
            }
            is ForwardingDecision.DropNoRoute -> {
                emitLog("RELAY [B]", "Node B could not route packet: ${decisionB.reason}", LogLevel.ERROR)
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
