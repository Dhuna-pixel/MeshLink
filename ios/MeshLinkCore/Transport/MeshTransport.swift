import Foundation

/**
 * Events emitted by the transport layer to notify repository and routing engine.
 */
public enum TransportEvent: Sendable {
    case nodeDiscovered(node: Node)
    case nodeLost(endpointId: String)
    case connectionInitiated(endpointId: String, nodeName: String)
    case nodeConnected(node: Node)
    case nodeDisconnected(nodeId: String, endpointId: String)
    case packetReceived(packet: MessagePacket, fromEndpointId: String)
    case packetSent(packet: MessagePacket, toEndpointId: String)
    case logEmitted(entry: NetworkLogEntry)
    case errorOccurred(message: String)
}

/**
 * Abstract interface representing a peer-to-peer transport layer for MeshLink.
 *
 * Decouples the physical networking technology (CoreBluetooth BLE, MultipeerConnectivity, or Simulation)
 * from the high-level routing engine and presentation layers.
 */
public protocol MeshTransport: AnyObject, Sendable {

    /**
     * Whether the transport is currently active and scanning/advertising.
     */
    var isRunning: Bool { get }

    /**
     * Transport event handler callback.
     */
    var onEvent: (@Sendable (TransportEvent) -> Void)? { get set }

    /**
     * Starts advertising the local node and scanning for nearby peers.
     */
    func start(localNode: Node)

    /**
     * Stops advertising, scanning, and disconnects all active connections.
     */
    func stop()

    /**
     * Initiates a connection request to a discovered node.
     */
    func connectToNode(_ targetNode: Node)

    /**
     * Disconnects an active connection with a specific node.
     */
    func disconnectNode(nodeId: String)

    /**
     * Transmits a structured message packet to a specific connected peer.
     */
    @discardableResult
    func sendPacket(_ packet: MessagePacket, targetEndpointId: String) -> Bool

    /**
     * Transmits a structured message packet to all connected peers except those listed in `excludeEndpointIds`.
     */
    @discardableResult
    func broadcastPacket(_ packet: MessagePacket, excludeEndpointIds: Set<String>) -> Int
}
