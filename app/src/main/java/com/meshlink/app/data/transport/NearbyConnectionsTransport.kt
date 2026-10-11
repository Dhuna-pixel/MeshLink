package com.meshlink.app.data.transport

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.meshlink.app.data.transport.framing.MeshLinkFrame
import com.meshlink.app.domain.crc.Crc16Ccitt
import com.meshlink.app.domain.models.LogLevel
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.NetworkLogEntry
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.models.NodeStatus
import com.meshlink.app.utils.JsonSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-ready implementation of [MeshTransport] using Google Play Services Nearby Connections API.
 *
 * Utilizes [Strategy.P2P_CLUSTER], enabling an M-to-N decentralized mesh topology where:
 * 1. Every device advertises and discovers peers simultaneously without requiring a central router.
 * 2. Intermediate nodes (e.g. Node B) can maintain simultaneous connections with Node A and Node C.
 * 3. High reliability across Bluetooth, BLE, and Wi-Fi Direct without manual socket management.
 */
class NearbyConnectionsTransport(
    private val context: Context
) : MeshTransport {

    companion object {
        private const val TAG = "NearbyMeshTransport"
        const val SERVICE_ID = "com.meshlink.mesh.review2"
        private val STRATEGY = Strategy.P2P_CLUSTER
    }

    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(context.applicationContext)
    }

    private val transportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var localNode: Node? = null

    // Endpoint ID -> Node mapping for active / discovered connections
    private val endpointToNodeMap = ConcurrentHashMap<String, Node>()
    // Node ID -> Endpoint ID mapping
    private val nodeToEndpointMap = ConcurrentHashMap<String, String>()

    // Test mode: Intentionally corrupt the next outgoing frame's CRC
    private val corruptNextFrame = AtomicBoolean(false)

    fun setCorruptNextFrame(corrupt: Boolean) {
        corruptNextFrame.set(corrupt)
    }

    // =========================================================================
    // PAYLOAD CALLBACK (Receiving data packets from peers)
    // =========================================================================
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                val decodeResult = MeshLinkFrame.decode(bytes)

                when (decodeResult) {
                    is MeshLinkFrame.FrameResult.Success -> {
                        val jsonStr = String(decodeResult.payload, StandardCharsets.UTF_8)
                        val packet = JsonSerializer.jsonToPacket(jsonStr)
                        if (packet != null) {
                            val crcHex = Crc16Ccitt.formatHex(decodeResult.crc)
                            val senderNode = endpointToNodeMap[endpointId]
                            emitLog(
                                tag = "CRC-PASS",
                                message = "INFO [CRC][PASS] messageId=${packet.messageId} received=$crcHex calculated=$crcHex length=${decodeResult.payload.size}B via endpoint '$endpointId' (${senderNode?.nodeName ?: "Unknown"})",
                                level = LogLevel.SUCCESS,
                                packetSummary = packet.getSummary(),
                                nodeId = packet.sourceId
                            )
                            emitEvent(TransportEvent.PacketReceived(packet, endpointId))
                        } else {
                            emitLog(
                                tag = "TRANSPORT",
                                message = "Failed to deserialize JSON packet after CRC pass from endpoint '$endpointId'",
                                level = LogLevel.ERROR
                            )
                        }
                    }

                    is MeshLinkFrame.FrameResult.CrcError -> {
                        val rxHex = Crc16Ccitt.formatHex(decodeResult.receivedCrc)
                        val calcHex = Crc16Ccitt.formatHex(decodeResult.calculatedCrc)
                        val senderNode = endpointToNodeMap[endpointId]

                        // REJECT FRAME: Discard completely and DO NOT forward to RoutingEngine or SeenMessageManager
                        emitLog(
                            tag = "CRC-FAIL",
                            message = "WARN [CRC][FAIL] Frame Corrupted! received=$rxHex calculated=$calcHex length=${decodeResult.payloadLength}B endpoint=$endpointId (${senderNode?.nodeName ?: "Unknown"}). Packet DROPPED before routing.",
                            level = LogLevel.ERROR,
                            nodeId = senderNode?.nodeId
                        )
                    }

                    is MeshLinkFrame.FrameResult.CorruptedHeader -> {
                        // Fallback check for raw un-framed JSON bytes from legacy nodes
                        val fallbackPacket = JsonSerializer.bytesToPacket(bytes)
                        if (fallbackPacket != null) {
                            val senderNode = endpointToNodeMap[endpointId]
                            emitLog(
                                tag = "TRANSPORT",
                                message = "Received un-framed legacy packet '${fallbackPacket.messageId}' via endpoint '$endpointId'",
                                level = LogLevel.INFO,
                                packetSummary = fallbackPacket.getSummary(),
                                nodeId = fallbackPacket.sourceId
                            )
                            emitEvent(TransportEvent.PacketReceived(fallbackPacket, endpointId))
                        } else {
                            emitLog(
                                tag = "FRAME-ERROR",
                                message = "WARN [FRAME][HEADER_ERROR] ${decodeResult.reason} on endpoint '$endpointId'",
                                level = LogLevel.WARNING
                            )
                        }
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Can be used for transfer progress tracking if needed
        }
    }

    // =========================================================================
    // CONNECTION LIFECYCLE CALLBACK
    // =========================================================================
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            emitLog(
                tag = "TRANSPORT",
                message = "Incoming connection request from '${info.endpointName}' ($endpointId). Auto-accepting for mesh.",
                level = LogLevel.INFO
            )
            emitEvent(TransportEvent.ConnectionInitiated(endpointId, info.endpointName))

            // Auto-accept connection to allow rapid ad-hoc mesh formation
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e ->
                    emitLog(
                        tag = "TRANSPORT",
                        message = "Failed to accept connection from '$endpointId': ${e.message}",
                        level = LogLevel.ERROR
                    )
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    // Extract or fallback node info
                    val existingNode = endpointToNodeMap[endpointId]
                    val nodeId = existingNode?.nodeId ?: "PEER-$endpointId"
                    val nodeName = existingNode?.nodeName ?: "Peer ($endpointId)"

                    val connectedNode = Node(
                        nodeId = nodeId,
                        nodeName = nodeName,
                        status = NodeStatus.CONNECTED,
                        endpointId = endpointId,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )

                    endpointToNodeMap[endpointId] = connectedNode
                    nodeToEndpointMap[nodeId] = endpointId

                    emitLog(
                        tag = "TRANSPORT",
                        message = "Connection established with '$nodeName' [$nodeId] (Endpoint: $endpointId)",
                        level = LogLevel.SUCCESS,
                        nodeId = nodeId
                    )
                    emitEvent(TransportEvent.NodeConnected(connectedNode))
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    emitLog(
                        tag = "TRANSPORT",
                        message = "Connection rejected by endpoint '$endpointId'",
                        level = LogLevel.WARNING
                    )
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    emitLog(
                        tag = "TRANSPORT",
                        message = "Connection error with endpoint '$endpointId'",
                        level = LogLevel.ERROR
                    )
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            val node = endpointToNodeMap.remove(endpointId)
            val nodeId = node?.nodeId ?: endpointId
            nodeToEndpointMap.remove(nodeId)

            emitLog(
                tag = "TRANSPORT",
                message = "Disconnected from node '${node?.nodeName ?: endpointId}' [$nodeId]",
                level = LogLevel.WARNING,
                nodeId = nodeId
            )
            emitEvent(TransportEvent.NodeDisconnected(nodeId, endpointId))
        }
    }

    // =========================================================================
    // ENDPOINT DISCOVERY CALLBACK
    // =========================================================================
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Expected format: "NodeName|NodeId" or raw name
            val rawName = info.endpointName
            val parts = rawName.split("|")
            val name = parts.getOrNull(0) ?: rawName
            val parsedNodeId = if (parts.size > 1) parts[1] else "NODE-$endpointId"

            val discoveredNode = Node(
                nodeId = parsedNodeId,
                nodeName = name,
                status = NodeStatus.DISCOVERED,
                endpointId = endpointId,
                lastSeenTimestamp = System.currentTimeMillis()
            )

            endpointToNodeMap[endpointId] = discoveredNode
            nodeToEndpointMap[parsedNodeId] = endpointId

            emitLog(
                tag = "TRANSPORT",
                message = "Discovered nearby mesh node: '$name' [$parsedNodeId] (Endpoint: $endpointId)",
                level = LogLevel.INFO,
                nodeId = parsedNodeId
            )
            emitEvent(TransportEvent.NodeDiscovered(discoveredNode))
        }

        override fun onEndpointLost(endpointId: String) {
            val lostNode = endpointToNodeMap.remove(endpointId)
            lostNode?.let { nodeToEndpointMap.remove(it.nodeId) }

            emitLog(
                tag = "TRANSPORT",
                message = "Lost discovery of node '${lostNode?.nodeName ?: endpointId}'",
                level = LogLevel.INFO
            )
            emitEvent(TransportEvent.NodeLost(endpointId))
        }
    }

    // =========================================================================
    // LIFECYCLE CONTROLS
    // =========================================================================
    override fun start(localNode: Node) {
        this.localNode = localNode
        _isRunning.value = true

        emitLog(
            tag = "TRANSPORT",
            message = "Starting MeshLink transport for local node '${localNode.nodeName}' [${localNode.nodeId}]...",
            level = LogLevel.INFO,
            nodeId = localNode.nodeId
        )

        startAdvertising(localNode)
        startDiscovery()
    }

    private fun startAdvertising(node: Node) {
        // Encode node identity into advertising name
        val advertisingName = "${node.nodeName}|${node.nodeId}"
        val options = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        connectionsClient.startAdvertising(
            advertisingName,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            emitLog(
                tag = "TRANSPORT",
                message = "Advertising started successfully as '$advertisingName'",
                level = LogLevel.SUCCESS
            )
        }.addOnFailureListener { e ->
            emitLog(
                tag = "TRANSPORT",
                message = "Failed to start advertising: ${e.message}",
                level = LogLevel.ERROR
            )
            emitEvent(TransportEvent.ErrorOccurred("Advertising failed: ${e.message}", e))
        }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            emitLog(
                tag = "TRANSPORT",
                message = "Discovery scanning started for service '$SERVICE_ID'",
                level = LogLevel.SUCCESS
            )
        }.addOnFailureListener { e ->
            emitLog(
                tag = "TRANSPORT",
                message = "Failed to start discovery scanning: ${e.message}",
                level = LogLevel.ERROR
            )
            emitEvent(TransportEvent.ErrorOccurred("Discovery failed: ${e.message}", e))
        }
    }

    override fun stop() {
        _isRunning.value = false
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
            endpointToNodeMap.clear()
            nodeToEndpointMap.clear()

            emitLog(
                tag = "TRANSPORT",
                message = "MeshLink transport stopped. All endpoints disconnected.",
                level = LogLevel.INFO
            )
        } catch (e: Exception) {
            emitLog(
                tag = "TRANSPORT",
                message = "Error during transport shutdown: ${e.message}",
                level = LogLevel.WARNING
            )
        }
    }

    override fun connectToNode(targetNode: Node) {
        val endpointId = targetNode.endpointId ?: run {
            emitLog(
                tag = "TRANSPORT",
                message = "Cannot connect to node '${targetNode.nodeName}': Missing endpoint ID",
                level = LogLevel.ERROR
            )
            return
        }

        val local = localNode ?: return
        val localName = "${local.nodeName}|${local.nodeId}"

        emitLog(
            tag = "TRANSPORT",
            message = "Requesting connection to '${targetNode.nodeName}' (Endpoint: $endpointId)...",
            level = LogLevel.INFO,
            nodeId = targetNode.nodeId
        )

        connectionsClient.requestConnection(
            localName,
            endpointId,
            connectionLifecycleCallback
        ).addOnFailureListener { e ->
            emitLog(
                tag = "TRANSPORT",
                message = "Connection request to '$endpointId' failed: ${e.message}",
                level = LogLevel.ERROR
            )
        }
    }

    override fun disconnectNode(nodeId: String) {
        val endpointId = nodeToEndpointMap[nodeId] ?: nodeId
        try {
            connectionsClient.disconnectFromEndpoint(endpointId)
            endpointToNodeMap.remove(endpointId)
            nodeToEndpointMap.remove(nodeId)
            emitLog(
                tag = "TRANSPORT",
                message = "Disconnected from endpoint '$endpointId'",
                level = LogLevel.INFO,
                nodeId = nodeId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting from $endpointId", e)
        }
    }

    override fun sendPacket(packet: MessagePacket, targetEndpointId: String): Boolean {
        // Check if targetEndpointId is a nodeId, and resolve to actual endpointId
        val resolvedEndpointId = nodeToEndpointMap[targetEndpointId] ?: targetEndpointId
        val jsonStr = JsonSerializer.packetToJson(packet)
        val payloadBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)

        val shouldCorrupt = corruptNextFrame.getAndSet(false)
        val framedBytes = MeshLinkFrame.encode(
            frameType = MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET,
            payload = payloadBytes,
            corruptForTesting = shouldCorrupt
        )
        val computedCrc = Crc16Ccitt.compute(framedBytes, 0, framedBytes.size - MeshLinkFrame.CRC_SIZE)
        val payload = Payload.fromBytes(framedBytes)

        return try {
            emitLog(
                tag = "TRANSPORT",
                message = "Sending frame to '$resolvedEndpointId' (CRC: ${Crc16Ccitt.formatHex(computedCrc)}, Size: ${framedBytes.size}B, Corrupted: $shouldCorrupt)",
                level = if (shouldCorrupt) LogLevel.WARNING else LogLevel.INFO,
                packetSummary = packet.getSummary(),
                nodeId = packet.destinationId
            )
            connectionsClient.sendPayload(resolvedEndpointId, payload)
                .addOnSuccessListener {
                    emitEvent(TransportEvent.PacketSent(packet, resolvedEndpointId))
                }
                .addOnFailureListener { e ->
                    emitLog(
                        tag = "TRANSPORT",
                        message = "Failed to send packet '${packet.messageId}' to '$resolvedEndpointId': ${e.message}",
                        level = LogLevel.ERROR
                    )
                }
            true
        } catch (e: Exception) {
            emitLog(
                tag = "TRANSPORT",
                message = "Exception sending packet: ${e.message}",
                level = LogLevel.ERROR
            )
            false
        }
    }

    override fun broadcastPacket(packet: MessagePacket, excludeEndpointIds: Set<String>): Int {
        val activeEndpoints = endpointToNodeMap.filter { it.value.status == NodeStatus.CONNECTED }.keys
        val targets = activeEndpoints.filter { !excludeEndpointIds.contains(it) }

        if (targets.isEmpty()) {
            emitLog(
                tag = "TRANSPORT",
                message = "Broadcast attempted for '${packet.messageId}', but no active connected peers found (excluded: $excludeEndpointIds)",
                level = LogLevel.WARNING
            )
            return 0
        }

        val jsonStr = JsonSerializer.packetToJson(packet)
        val payloadBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)

        val shouldCorrupt = corruptNextFrame.getAndSet(false)
        val framedBytes = MeshLinkFrame.encode(
            frameType = MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET,
            payload = payloadBytes,
            corruptForTesting = shouldCorrupt
        )
        val computedCrc = Crc16Ccitt.compute(framedBytes, 0, framedBytes.size - MeshLinkFrame.CRC_SIZE)
        val payload = Payload.fromBytes(framedBytes)

        try {
            emitLog(
                tag = "TRANSPORT",
                message = "Broadcasting frame to ${targets.size} peer(s) (CRC: ${Crc16Ccitt.formatHex(computedCrc)}, Size: ${framedBytes.size}B, Corrupted: $shouldCorrupt)",
                level = if (shouldCorrupt) LogLevel.WARNING else LogLevel.INFO,
                packetSummary = packet.getSummary()
            )
            connectionsClient.sendPayload(targets.toList(), payload)
                .addOnSuccessListener {
                    emitEvent(TransportEvent.PacketSent(packet, targets.joinToString()))
                }
                .addOnFailureListener { e ->
                    emitLog(
                        tag = "TRANSPORT",
                        message = "Broadcast failed: ${e.message}",
                        level = LogLevel.ERROR
                    )
                }
            return targets.size
        } catch (e: Exception) {
            emitLog(
                tag = "TRANSPORT",
                message = "Exception during broadcast: ${e.message}",
                level = LogLevel.ERROR
            )
            return 0
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
        transportScope.launch {
            _events.emit(event)
        }
    }
}
