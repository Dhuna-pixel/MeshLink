package com.meshlink.app.data.transport

import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.NetworkLogEntry
import com.meshlink.app.domain.models.Node
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Events emitted by the transport layer to notify repository and routing engine.
 */
sealed class TransportEvent {
    data class NodeDiscovered(val node: Node) : TransportEvent()
    data class NodeLost(val endpointId: String) : TransportEvent()
    data class ConnectionInitiated(val endpointId: String, val nodeName: String) : TransportEvent()
    data class NodeConnected(val node: Node) : TransportEvent()
    data class NodeDisconnected(val nodeId: String, val endpointId: String) : TransportEvent()
    data class PacketReceived(val packet: MessagePacket, val fromEndpointId: String) : TransportEvent()
    data class PacketSent(val packet: MessagePacket, val toEndpointId: String) : TransportEvent()
    data class LogEmitted(val entry: NetworkLogEntry) : TransportEvent()
    data class ErrorOccurred(val message: String, val exception: Throwable? = null) : TransportEvent()
}

/**
 * Abstract interface representing a peer-to-peer transport layer for MeshLink.
 *
 * Decouples the physical networking technology (Nearby Connections, BLE, or Wi-Fi Direct)
 * from the high-level routing engine and presentation layers.
 */
interface MeshTransport {

    /**
     * Observable stream of transport events (discoveries, connections, packet arrivals, logs).
     */
    val events: SharedFlow<TransportEvent>

    /**
     * Whether the transport is currently active and scanning/advertising.
     */
    val isRunning: StateFlow<Boolean>

    /**
     * Starts advertising the local node and scanning for nearby peers.
     */
    fun start(localNode: Node)

    /**
     * Stops advertising, scanning, and disconnects all active connections.
     */
    fun stop()

    /**
     * Initiates a connection request to a discovered node.
     */
    fun connectToNode(targetNode: Node)

    /**
     * Disconnects an active connection with a specific node.
     */
    fun disconnectNode(nodeId: String)

    /**
     * Transmits a structured message packet to a specific connected peer.
     */
    fun sendPacket(packet: MessagePacket, targetEndpointId: String): Boolean

    /**
     * Transmits a structured message packet to all connected peers except those listed in [excludeEndpointIds].
     */
    fun broadcastPacket(packet: MessagePacket, excludeEndpointIds: Set<String> = emptySet()): Int
}
