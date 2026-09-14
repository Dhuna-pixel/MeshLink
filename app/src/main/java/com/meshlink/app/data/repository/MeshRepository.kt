package com.meshlink.app.data.repository

import android.content.Context
import com.meshlink.app.data.transport.AndroidBleGattTransport
import com.meshlink.app.data.transport.MeshTransport
import com.meshlink.app.data.transport.NearbyConnectionsTransport
import com.meshlink.app.data.transport.SimulationTransport
import com.meshlink.app.data.transport.TransportEvent
import com.meshlink.app.domain.models.ForwardingDecision
import com.meshlink.app.domain.models.LogLevel
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.NetworkLogEntry
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.models.NodeStatus
import com.meshlink.app.domain.routing.RoutingEngine
import com.meshlink.app.utils.IdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Transport execution modes supported by MeshLink.
 */
enum class TransportMode {
    /** Google Play Services Nearby Connections (Android-to-Android hardware mesh) */
    NEARBY_CONNECTIONS,

    /** Real Bluetooth Low Energy GATT Dual-Role mesh (Android-to-iOS cross-platform hardware) */
    BLE_GATT,

    /** High-fidelity virtual 3-node simulation (In-process testbed) */
    SIMULATION
}

/**
 * Central repository managing the MeshLink node state, network discovery, message routing,
 * and event logging.
 *
 * Coordinates between the [RoutingEngine] (pure domain logic) and the active [MeshTransport]
 * (hardware Nearby Connections, hardware BLE GATT, or high-fidelity Simulation).
 */
class MeshRepository(
    private val context: Context,
    private val routingEngine: RoutingEngine = RoutingEngine()
) {
    private val repositoryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Transports
    private val nearbyTransport: NearbyConnectionsTransport by lazy {
        NearbyConnectionsTransport(context)
    }
    private val bleTransport: AndroidBleGattTransport by lazy {
        AndroidBleGattTransport(context)
    }
    private val simulationTransport: SimulationTransport by lazy {
        SimulationTransport()
    }

    private var activeTransport: MeshTransport = nearbyTransport

    // =========================================================================
    // STATE FLOWS
    // =========================================================================
    private val _currentNode = MutableStateFlow(
        Node(
            nodeId = IdGenerator.generateNodeId("NODE-A"),
            nodeName = "Node A",
            status = NodeStatus.DISCONNECTED
        )
    )
    val currentNode: StateFlow<Node> = _currentNode.asStateFlow()

    private val _discoveredNodes = MutableStateFlow<List<Node>>(emptyList())
    val discoveredNodes: StateFlow<List<Node>> = _discoveredNodes.asStateFlow()

    private val _connectedNodes = MutableStateFlow<List<Node>>(emptyList())
    val connectedNodes: StateFlow<List<Node>> = _connectedNodes.asStateFlow()

    private val _messages = MutableStateFlow<List<MessagePacket>>(emptyList())
    val messages: StateFlow<List<MessagePacket>> = _messages.asStateFlow()

    private val _networkLogs = MutableStateFlow<List<NetworkLogEntry>>(emptyList())
    val networkLogs: StateFlow<List<NetworkLogEntry>> = _networkLogs.asStateFlow()

    private val _isNetworkingActive = MutableStateFlow(false)
    val isNetworkingActive: StateFlow<Boolean> = _isNetworkingActive.asStateFlow()

    private val _transportMode = MutableStateFlow(TransportMode.NEARBY_CONNECTIONS)
    val transportMode: StateFlow<TransportMode> = _transportMode.asStateFlow()

    val isSimulationMode: StateFlow<Boolean> = MutableStateFlow(false).apply {
        repositoryScope.launch {
            _transportMode.collect { mode ->
                value = (mode == TransportMode.SIMULATION)
            }
        }
    }

    init {
        // Collect transport events from all transports in background coroutines
        repositoryScope.launch {
            nearbyTransport.events.collect { handleTransportEvent(it) }
        }
        repositoryScope.launch {
            bleTransport.events.collect { handleTransportEvent(it) }
        }
        repositoryScope.launch {
            simulationTransport.events.collect { handleTransportEvent(it) }
        }

        log(
            tag = "INIT",
            message = "MeshLink node initialized with ID '${_currentNode.value.nodeId}' (${_currentNode.value.nodeName})",
            level = LogLevel.INFO
        )
    }

    // =========================================================================
    // NODE IDENTITY CONFIGURATION
    // =========================================================================
    fun updateNodeIdentity(nodeId: String, nodeName: String) {
        val wasActive = _isNetworkingActive.value
        val currentMode = _transportMode.value
        if (wasActive) {
            stopNetworking()
        }

        val updated = _currentNode.value.copy(
            nodeId = nodeId.trim().ifEmpty { IdGenerator.generateNodeId() },
            nodeName = nodeName.trim().ifEmpty { "Node" }
        )
        _currentNode.value = updated

        log(
            tag = "CONFIG",
            message = "Node identity updated to '${updated.nodeName}' [${updated.nodeId}]",
            level = LogLevel.INFO,
            nodeId = updated.nodeId
        )

        if (wasActive) {
            startNetworking(currentMode)
        }
    }

    // =========================================================================
    // TRANSPORT SELECTION & NETWORKING LIFECYCLE
    // =========================================================================
    fun setTransportMode(mode: TransportMode) {
        val wasActive = _isNetworkingActive.value
        if (wasActive) {
            stopNetworking()
        }
        _transportMode.value = mode
        log(
            tag = "CONFIG",
            message = "Transport mode selected: ${getTransportModeDescription(mode)}",
            level = LogLevel.INFO
        )
        if (wasActive) {
            startNetworking(mode)
        }
    }

    fun startNetworking(mode: TransportMode) {
        _transportMode.value = mode
        activeTransport = when (mode) {
            TransportMode.NEARBY_CONNECTIONS -> nearbyTransport
            TransportMode.BLE_GATT -> bleTransport
            TransportMode.SIMULATION -> simulationTransport
        }

        _isNetworkingActive.value = true
        _currentNode.update { it.copy(status = NodeStatus.CONNECTED) }

        log(
            tag = "ENGINE",
            message = "Starting networking in ${getTransportModeDescription(mode)} mode...",
            level = LogLevel.INFO
        )

        activeTransport.start(_currentNode.value)
    }

    fun startNetworking(simulation: Boolean = false) {
        val mode = if (simulation) TransportMode.SIMULATION else _transportMode.value
        startNetworking(mode)
    }

    fun stopNetworking() {
        activeTransport.stop()
        _isNetworkingActive.value = false
        _currentNode.update { it.copy(status = NodeStatus.DISCONNECTED) }
        _discoveredNodes.value = emptyList()
        _connectedNodes.value = emptyList()

        log(
            tag = "ENGINE",
            message = "Mesh networking stopped. Disconnected from all peers.",
            level = LogLevel.INFO
        )
    }

    fun connectToNode(targetNode: Node) {
        log(
            tag = "CONNECT",
            message = "Connecting to neighbor '${targetNode.nodeName}' [${targetNode.nodeId}]...",
            level = LogLevel.INFO,
            nodeId = targetNode.nodeId
        )
        activeTransport.connectToNode(targetNode)
    }

    fun disconnectNode(nodeId: String) {
        activeTransport.disconnectNode(nodeId)
    }

    fun startMesh(simulation: Boolean = false) = startNetworking(simulation)

    fun stopMesh() = stopNetworking()

    fun connectToNeighbor(targetNode: Node) = connectToNode(targetNode)

    fun disconnectNeighbor(nodeId: String) = disconnectNode(nodeId)

    private fun getTransportModeDescription(mode: TransportMode): String {
        return when (mode) {
            TransportMode.NEARBY_CONNECTIONS -> "NEARBY CONNECTIONS (Android ↔ Android)"
            TransportMode.BLE_GATT -> "BLE GATT (Android ↔ iOS Cross-Platform)"
            TransportMode.SIMULATION -> "3-NODE SIMULATION"
        }
    }

    // =========================================================================
    // MESSAGING & ROUTING ENGINE DISPATCH
    // =========================================================================
    fun sendMessage(
        destinationId: String,
        destinationName: String = "",
        payload: String,
        ttl: Int = 5
    ): MessagePacket {
        val current = _currentNode.value
        val messageId = IdGenerator.generateMessageId()

        val packet = routingEngine.createLocalPacket(
            messageId = messageId,
            sourceId = current.nodeId,
            sourceName = current.nodeName,
            destinationId = destinationId,
            destinationName = destinationName.ifEmpty { destinationId },
            payload = payload,
            initialTtl = ttl
        )

        // Store in local message history
        _messages.update { listOf(packet) + it }

        log(
            tag = "SEND [LOCAL]",
            message = "Created message '$messageId' for destination '$destinationId' with TTL $ttl",
            level = LogLevel.INFO,
            packetSummary = packet.getSummary(),
            nodeId = destinationId
        )

        // Transmit via active transport
        val connectedNeighbors = _connectedNodes.value

        // Check if destination is direct neighbor, or broadcast to connected neighbors
        val directNeighbor = connectedNeighbors.find { it.nodeId.equals(destinationId, ignoreCase = true) }
        if (directNeighbor?.endpointId != null) {
            log(
                tag = "SEND [DIRECT]",
                message = "Destination '$destinationId' is directly connected. Sending packet directly.",
                level = LogLevel.INFO
            )
            activeTransport.sendPacket(packet, directNeighbor.endpointId)
        } else {
            log(
                tag = "SEND [RELAY-OUT]",
                message = "Broadcasting packet '$messageId' to ${connectedNeighbors.size} connected neighbor(s) for mesh forwarding...",
                level = LogLevel.INFO
            )
            activeTransport.broadcastPacket(packet)
        }

        return packet
    }

    // =========================================================================
    // INCOMING PACKET PROCESSING (Core of multi-hop relay logic)
    // =========================================================================
    private fun handleIncomingPacket(packet: MessagePacket, fromEndpointId: String) {
        val current = _currentNode.value

        log(
            tag = "ROUTING-IN",
            message = "Processing packet '${packet.messageId}' (Source: ${packet.sourceId}, Dst: ${packet.destinationId}, TTL: ${packet.ttl}, Hops: ${packet.hopCount})",
            level = LogLevel.INFO,
            packetSummary = packet.getSummary()
        )

        // Evaluate forwarding decision via RoutingEngine
        val decision = routingEngine.processIncomingPacket(
            packet = packet,
            currentNodeId = current.nodeId,
            connectedNeighbors = _connectedNodes.value
        )

        when (decision) {
            is ForwardingDecision.Deliver -> {
                // Current node IS the destination
                _messages.update { listOf(decision.packet) + it }
                log(
                    tag = "DELIVER [DEST]",
                    message = "MESSAGE DELIVERED! Current node '${current.nodeId}' is the intended destination.\nPayload: \"${decision.packet.payload}\"\nTotal Hops: ${decision.packet.hopCount}\nRoute: ${decision.packet.routeHistory.joinToString(" -> ")} -> ${current.nodeId}",
                    level = LogLevel.SUCCESS,
                    packetSummary = decision.packet.getSummary(),
                    nodeId = decision.packet.sourceId
                )
            }

            is ForwardingDecision.Forward -> {
                // Current node is intermediate RELAY node (e.g. Node B)
                val updatedPacket = decision.updatedPacket
                log(
                    tag = "RELAY [FORWARD]",
                    message = "ACTING AS RELAY: Node '${current.nodeId}' is forwarding packet '${updatedPacket.messageId}'.\nTTL decremented: ${packet.ttl} -> ${updatedPacket.ttl}\nHop count incremented: ${packet.hopCount} -> ${updatedPacket.hopCount}\nRoute history: ${updatedPacket.routeHistory.joinToString(" -> ")}",
                    level = LogLevel.SUCCESS,
                    packetSummary = updatedPacket.getSummary(),
                    nodeId = updatedPacket.destinationId
                )

                // Transmit updated packet to next hop candidate neighbors
                val candidates = _connectedNodes.value.filter { decision.candidateNextHopNodeIds.contains(it.nodeId) }
                for (nextHop in candidates) {
                    val targetEndpoint = nextHop.endpointId
                    if (targetEndpoint != null) {
                        log(
                            tag = "RELAY [TX]",
                            message = "Transmitting forwarded packet to next-hop '${nextHop.nodeName}' [${nextHop.nodeId}]",
                            level = LogLevel.INFO,
                            nodeId = nextHop.nodeId
                        )
                        activeTransport.sendPacket(updatedPacket, targetEndpoint)
                    }
                }

                // If no specific candidate had endpoint, broadcast to all non-previous hops
                if (candidates.isEmpty()) {
                    val exclude = mutableSetOf<String>()
                    fromEndpointId.takeIf { it.isNotBlank() }?.let { exclude.add(it) }
                    activeTransport.broadcastPacket(updatedPacket, exclude)
                }
            }

            is ForwardingDecision.DropDuplicate -> {
                log(
                    tag = "DROP [DUPLICATE]",
                    message = decision.reason,
                    level = LogLevel.WARNING
                )
            }

            is ForwardingDecision.DropTtlExpired -> {
                log(
                    tag = "DROP [TTL-EXPIRED]",
                    message = decision.reason,
                    level = LogLevel.WARNING
                )
            }

            is ForwardingDecision.DropNoRoute -> {
                log(
                    tag = "DROP [NO-ROUTE]",
                    message = decision.reason,
                    level = LogLevel.ERROR
                )
            }
        }
    }

    // =========================================================================
    // TRANSPORT EVENT HANDLING
    // =========================================================================
    private fun handleTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.NodeDiscovered -> {
                val node = event.node
                _discoveredNodes.update { list ->
                    val filtered = list.filter { it.nodeId != node.nodeId && it.endpointId != node.endpointId }
                    filtered + node
                }
            }
            is TransportEvent.NodeLost -> {
                _discoveredNodes.update { list ->
                    list.filter { it.endpointId != event.endpointId }
                }
            }
            is TransportEvent.NodeConnected -> {
                val node = event.node
                _discoveredNodes.update { list ->
                    list.filter { it.nodeId != node.nodeId }
                }
                _connectedNodes.update { list ->
                    val filtered = list.filter { it.nodeId != node.nodeId && it.endpointId != node.endpointId }
                    filtered + node
                }
            }
            is TransportEvent.NodeDisconnected -> {
                _connectedNodes.update { list ->
                    list.filter { it.nodeId != event.nodeId && it.endpointId != event.endpointId }
                }
            }
            is TransportEvent.PacketReceived -> {
                handleIncomingPacket(event.packet, event.fromEndpointId)
            }
            is TransportEvent.PacketSent -> {
                log(
                    tag = "TX-CONFIRM",
                    message = "Packet '${event.packet.messageId}' sent out via transport endpoint '${event.toEndpointId}'",
                    level = LogLevel.INFO
                )
            }
            is TransportEvent.LogEmitted -> {
                appendLog(event.entry)
            }
            is TransportEvent.ErrorOccurred -> {
                log(
                    tag = "ERROR",
                    message = event.message,
                    level = LogLevel.ERROR
                )
            }
            is TransportEvent.ConnectionInitiated -> {
                // Handled in transport
            }
        }
    }

    // =========================================================================
    // LOGGING HELPERS
    // =========================================================================
    private fun log(
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
        appendLog(entry)
    }

    private fun appendLog(entry: NetworkLogEntry) {
        _networkLogs.update { (listOf(entry) + it).take(200) }
    }

    fun clearLogs() {
        _networkLogs.value = emptyList()
        log("LOG", "Network event log cleared", LogLevel.INFO)
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

    fun resetRoutingState() {
        routingEngine.resetSeenCache()
        log("ROUTING", "Routing engine duplicate cache reset", LogLevel.INFO)
    }
}
