package com.meshlink.app.data.repository

import android.content.Context
import com.meshlink.app.data.transport.AndroidBleGattTransport
import com.meshlink.app.data.transport.MeshTransport
import com.meshlink.app.data.transport.NearbyConnectionsTransport
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
    /** Primary Review III Transport: Hardware BLE GATT Dual-Role mesh */
    BLE_GATT,

    /** Google Play Services Nearby Connections */
    NEARBY_CONNECTIONS
}

/**
 * Central repository managing the MeshLink node state, network discovery, message routing,
 * and event logging.
 *
 * Coordinates between the [RoutingEngine] (pure domain logic) and the active [MeshTransport].
 */
class MeshRepository(
    private val context: Context,
    private val routingEngine: RoutingEngine = RoutingEngine()
) {
    val appPreferences = com.meshlink.app.data.preferences.AppPreferencesStore(
        com.meshlink.app.data.preferences.AndroidPreferenceStorage(context,"mesh_settings"))
    val messageNotifier = com.meshlink.app.data.notifications.AndroidMessageNotifier(context)
    private val receivedInbox = com.meshlink.app.domain.notifications.ReceivedMessageStore(
        com.meshlink.app.data.preferences.AndroidPreferenceStorage(context,"mesh_received"))
    private val deliveryNotifications by lazy {
        com.meshlink.app.domain.notifications.DeliveredMessageNotifications(
            localId = { _currentNode.value.nodeId }, preferences = { appPreferences.state.value },
            systemAllowed = messageNotifier::permitted, inbox = receivedInbox,
            senderName = { id -> (_connectedNodes.value + knownNodes.value).find { it.nodeId == id }?.nodeName },
            post = messageNotifier::post)
    }
    fun receivedMessage(target: com.meshlink.app.domain.notifications.NotificationTarget) = receivedInbox.find(_currentNode.value.nodeId,target)
    fun showReceivedMessage(target: com.meshlink.app.domain.notifications.NotificationTarget): MessagePacket? {
        val packet = receivedMessage(target) ?: return null
        _messages.update { (listOf(packet) + it.filterNot { old -> old.messageId == packet.messageId && old.sourceId == packet.sourceId }).take(500) }
        return packet
    }
    private val repositoryScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val preferences = context.getSharedPreferences("mesh_identity", Context.MODE_PRIVATE)
    private val stableId = preferences.getString("id", null) ?: java.util.UUID.randomUUID().toString().also {
        preferences.edit().putString("id", it).apply()
    }
    private var aodv: com.meshlink.app.domain.routing.AodvEngine? = null
    val bluetoothStatus = MutableStateFlow("Checking")
    val knownNodes = MutableStateFlow<List<Node>>(emptyList())
    val routes = MutableStateFlow<List<com.meshlink.app.domain.routing.AodvRoute>>(emptyList())
    val deliveryStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
    // Transports
    val bleTransport: AndroidBleGattTransport by lazy {
        AndroidBleGattTransport(context)
    }
    val nearbyTransport: NearbyConnectionsTransport by lazy {
        NearbyConnectionsTransport(context)
    }

    private var activeTransport: MeshTransport = bleTransport

    // =========================================================================
    // STATE FLOWS
    // =========================================================================
    private val _currentNode = MutableStateFlow(
        Node(
            nodeId = stableId,
            nodeName = preferences.getString("name", null) ?: "Mesh-${stableId.take(6)}",
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

    // Default transport is BLE GATT for Review III
    private val _transportMode = MutableStateFlow(TransportMode.BLE_GATT)
    val transportMode: StateFlow<TransportMode> = _transportMode.asStateFlow()

    val demoTopology = MutableStateFlow(com.meshlink.app.domain.network.DemoTopology())

    @Synchronized fun configureDemo(topology: com.meshlink.app.domain.network.DemoTopology) {
        val wasActive = _isNetworkingActive.value
        if (wasActive) stopNetworking()
        bleTransport.configureDemo(topology)
        demoTopology.value = topology
        log("DEMO", if (topology.enabled) "Controlled BLE Demo Mode: allowed neighbors ${topology.permittedIds.joinToString()}" else "Normal BLE Mode: restrictions disabled")
        if (wasActive) startNetworking(_transportMode.value)
    }

    init {
        repositoryScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                bluetoothStatus.value = try {
                    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
                    if (adapter == null) "Unavailable" else if (adapter.isEnabled) "On" else "Off"
                } catch (_: SecurityException) { "Permission required" }
                aodv?.tick()
                routes.value = aodv?.snapshot() ?: emptyList()
                knownNodes.update { known -> known.filter { node -> _connectedNodes.value.any { it.nodeId == node.nodeId } || System.currentTimeMillis() - node.lastSeenTimestamp < 300_000 } }
                if (_transportMode.value == TransportMode.BLE_GATT) {
                    _isNetworkingActive.value = bleTransport.isRunning.value
                    if (!bleTransport.isRunning.value) { _connectedNodes.value = emptyList(); aodv = null; routes.value = emptyList() }
                }
            }
        }
        // Collect transport events from all transports in background coroutines
        repositoryScope.launch {
            bleTransport.events.collect { if (_transportMode.value == TransportMode.BLE_GATT) handleTransportEvent(it) }
        }
        repositoryScope.launch {
            nearbyTransport.events.collect { if (_transportMode.value == TransportMode.NEARBY_CONNECTIONS) handleTransportEvent(it) }
        }

        log(
            tag = "INIT",
            message = "MeshLink Review III initialized with ID '${_currentNode.value.nodeId}' (${_currentNode.value.nodeName}) | Primary Transport: BLE GATT with CRC-16-CCITT Integrity",
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
            nodeId = nodeId.trim().take(80).ifEmpty { IdGenerator.generateNodeId() },
            nodeName = nodeName.trim().take(64).ifEmpty { "Node" }
        )
        _currentNode.value = updated
        preferences.edit().putString("id", updated.nodeId).putString("name", updated.nodeName).apply()

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
    // CRC ERROR INJECTION TEST CONTROL
    // =========================================================================
    fun triggerCrcErrorSimulation(corrupt: Boolean = true) {
        bleTransport.setCorruptNextCrc(corrupt)
        nearbyTransport.setCorruptNextFrame(corrupt)
        log(
            tag = "TEST-MODE",
            message = if (corrupt) "CRC Error Simulation ARMED: Next frame will have corrupted payload." else "CRC Error Simulation disarmed.",
            level = if (corrupt) LogLevel.WARNING else LogLevel.INFO
        )
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

    fun requestNetworkingStart() {
        androidx.core.content.ContextCompat.startForegroundService(context,
            android.content.Intent(context, com.meshlink.app.service.MeshNetworkingService::class.java))
    }
    fun requestNetworkingStop() {
        context.stopService(android.content.Intent(context, com.meshlink.app.service.MeshNetworkingService::class.java))
    }

    @Synchronized fun startNetworking(mode: TransportMode) {
        if (_isNetworkingActive.value || activeTransport.isRunning.value) return
        _transportMode.value = mode
        activeTransport = when (mode) {
            TransportMode.BLE_GATT -> bleTransport
            TransportMode.NEARBY_CONNECTIONS -> nearbyTransport
        }

        _isNetworkingActive.value = true
        _currentNode.update { it.copy(status = NodeStatus.CONNECTED) }

        log(
            tag = "ENGINE",
            message = "Starting networking in ${getTransportModeDescription(mode)} mode...",
            level = LogLevel.INFO
        )

        if (mode == TransportMode.BLE_GATT) {
            val current = _currentNode.value
            aodv = com.meshlink.app.domain.routing.AodvEngine(current.nodeId, current.nodeName,
                transmit = { nextHop, packet ->
                    _connectedNodes.value.find { it.nodeId == nextHop && demoTopology.value.permits(nextHop) }?.endpointId?.let { bleTransport.sendPacket(packet, it) } ?: false
                },
                delivered = { packet ->
                    _messages.update { (listOf(packet) + it).take(500) }
                    // This callback is reached only after BLE frame validation and final AODV delivery.
                    runCatching { deliveryNotifications.onDelivered(packet) }
                    log("DELIVER", "Message delivered: ${packet.getSummary()}", LogLevel.SUCCESS)
                },
                status = { id, status -> deliveryStatuses.update { (it + (id to status)).entries.toList().takeLast(500).associate { it.toPair() } } },
                learned = { id, name -> knownNodes.update { (it.filter { n -> n.nodeId != id } + Node(id, name, status = NodeStatus.DISCONNECTED)).takeLast(512) } },
                log = { log("AODV", it) },
                acknowledged = { messageId, path ->
                    _messages.update { messages -> messages.map { packet ->
                        if (packet.messageId == messageId) packet.copy(routeHistory = path, hopCount = path.size - 1, ttl = packet.ttl - (path.size - 1)) else packet
                    } }
                })
        }
        activeTransport.start(_currentNode.value)
        _isNetworkingActive.value = activeTransport.isRunning.value
    }

    fun startNetworking() = startNetworking(_transportMode.value)

    @Synchronized fun stopNetworking() {
        aodv = null
        routes.value = emptyList()
        activeTransport.stop()
        _isNetworkingActive.value = false
        _currentNode.update { it.copy(status = NodeStatus.DISCONNECTED) }
        deliveryStatuses.update { statuses -> statuses.mapValues { (_, value) -> if (value.startsWith("Delivered") || value.startsWith("Failed")) value else "Failed: networking stopped" } }
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

    fun startMesh() = requestNetworkingStart()
    fun stopMesh() = requestNetworkingStop()
    fun connectToNeighbor(targetNode: Node) = connectToNode(targetNode)
    fun disconnectNeighbor(nodeId: String) = disconnectNode(nodeId)

    private fun getTransportModeDescription(mode: TransportMode): String {
        return when (mode) {
            TransportMode.BLE_GATT -> "BLE GATT (Dual-Role Android Mesh + CRC-16 Integrity)"
            TransportMode.NEARBY_CONNECTIONS -> "NEARBY CONNECTIONS"
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
        _messages.update { (listOf(packet) + it).take(500) }

        log(
            tag = "SEND [LOCAL]",
            message = "Created message '$messageId' for destination '$destinationId' with initial TTL $ttl (Hops: 0, Route: [${current.nodeId}])",
            level = LogLevel.INFO,
            packetSummary = packet.getSummary(),
            nodeId = destinationId
        )

        if (_transportMode.value == TransportMode.BLE_GATT) {
            if (!_isNetworkingActive.value || payload.toByteArray().size > 2000) {
                deliveryStatuses.update { it + (messageId to "Failed: networking stopped or message exceeds 2000 UTF-8 bytes") }
            } else aodv?.send(packet)
            return packet
        }
        val connectedNeighbors = _connectedNodes.value
        val directNeighbor = connectedNeighbors.find { it.nodeId.equals(destinationId, ignoreCase = true) }
        if (directNeighbor?.endpointId != null) {
            log(
                tag = "SEND [DIRECT]",
                message = "Destination '$destinationId' is directly connected neighbor. Transmitting frame via BLE GATT...",
                level = LogLevel.INFO
            )
            activeTransport.sendPacket(packet, directNeighbor.endpointId)
        } else {
            log(
                tag = "SEND [RELAY-OUT]",
                message = "Broadcasting packet '$messageId' to ${connectedNeighbors.size} connected neighbor(s) for multi-hop mesh forwarding...",
                level = LogLevel.INFO
            )
            activeTransport.broadcastPacket(packet)
        }

        return packet
    }

    // =========================================================================
    // INCOMING PACKET PROCESSING (Pure Domain Routing Execution)
    // =========================================================================
    private fun handleIncomingPacket(packet: MessagePacket, fromEndpointId: String) {
        if (_transportMode.value == TransportMode.BLE_GATT) {
            _connectedNodes.value.find { it.endpointId == fromEndpointId && demoTopology.value.permits(it.nodeId) }?.let { aodv?.receive(packet, it.nodeId) }
            return
        }
        val current = _currentNode.value

        log(
            tag = "ROUTING-IN",
            message = "Processing incoming packet '${packet.messageId}' from [$fromEndpointId] (Src: ${packet.sourceId}, Dst: ${packet.destinationId}, Incoming TTL: ${packet.ttl}, Incoming Hops: ${packet.hopCount})",
            level = LogLevel.INFO,
            packetSummary = packet.getSummary()
        )

        // Evaluate forwarding decision via pure RoutingEngine
        val decision = routingEngine.processIncomingPacket(
            packet = packet,
            currentNodeId = current.nodeId,
            connectedNeighbors = _connectedNodes.value
        )

        when (decision) {
            is ForwardingDecision.Deliver -> {
                // Current node IS the destination!
                val delivered = decision.packet
                _messages.update { listOf(delivered) + it }
                log(
                    tag = "DELIVER [DEST]",
                    message = "Message delivered locally; ${delivered.hopCount} hops, TTL ${delivered.ttl}",
                    level = LogLevel.SUCCESS,
                    packetSummary = delivered.getSummary(),
                    nodeId = delivered.sourceId
                )
            }

            is ForwardingDecision.Forward -> {
                // Current node is an intermediate relay
                val updatedPacket = decision.updatedPacket
                log(
                    tag = "RELAY [FORWARD]",
                    message = "ACTING AS RELAY: Node '${current.nodeId}' forwarding packet '${updatedPacket.messageId}'.\nTTL decremented: ${packet.ttl} -> ${updatedPacket.ttl}\nHop count incremented: ${packet.hopCount} -> ${updatedPacket.hopCount}\nRoute history: ${updatedPacket.routeHistory.joinToString(" -> ")}\nGenerating NEW frame + NEW CRC-16 for next hop...",
                    level = LogLevel.SUCCESS,
                    packetSummary = updatedPacket.getSummary(),
                    nodeId = updatedPacket.destinationId
                )

                // Transmit updated packet with new CRC to candidate neighbors
                val candidates = _connectedNodes.value.filter { decision.candidateNextHopNodeIds.contains(it.nodeId) }
                for (nextHop in candidates) {
                    val targetEndpoint = nextHop.endpointId
                    if (targetEndpoint != null) {
                        log(
                            tag = "RELAY [TX]",
                            message = "Transmitting forwarded frame with new CRC to next-hop '${nextHop.nodeName}' [${nextHop.nodeId}]",
                            level = LogLevel.INFO,
                            nodeId = nextHop.nodeId
                        )
                        activeTransport.sendPacket(updatedPacket, targetEndpoint)
                    }
                }

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
                if (_transportMode.value == TransportMode.BLE_GATT && (!demoTopology.value.permits(node.nodeId) || !bleTransport.isReadyPeer(node.nodeId, node.endpointId))) return
                _discoveredNodes.update { list ->
                    list.filter { it.nodeId != node.nodeId && it.endpointId != node.endpointId }
                }
                _connectedNodes.update { list ->
                    val filtered = list.filter { it.nodeId != node.nodeId && it.endpointId != node.endpointId }
                    filtered + node
                }
                aodv?.neighborUp(node.nodeId)
                knownNodes.update { (it.filter { n -> n.nodeId != node.nodeId } + node).takeLast(512) }
            }
            is TransportEvent.NodeDisconnected -> {
                aodv?.neighborDown(event.nodeId)
                _connectedNodes.update { list ->
                    list.filter { it.nodeId != event.nodeId && it.endpointId != event.endpointId }
                }
            }
            is TransportEvent.PacketReceived -> {
                handleIncomingPacket(event.packet, event.fromEndpointId)
            }
            is TransportEvent.PacketSent -> {
                if (_transportMode.value == TransportMode.BLE_GATT && event.packet.sourceId == _currentNode.value.nodeId && event.packet.control == null && event.packet.ackFor == null) {
                    deliveryStatuses.update { statuses ->
                        if (statuses[event.packet.messageId]?.startsWith("Delivered") == true || statuses[event.packet.messageId]?.startsWith("Failed") == true) statuses
                        else statuses + (event.packet.messageId to "Next-hop ATT receipt; awaiting destination ACK")
                    }
                }
                log(
                    tag = "TX-CONFIRM",
                    message = "Frame containing '${event.packet.messageId}' successfully transmitted to endpoint '${event.toEndpointId}'",
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
                _discoveredNodes.update { nodes -> nodes.map { if (it.endpointId == event.endpointId) it.copy(status = NodeStatus.CONNECTING) else it } }
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
