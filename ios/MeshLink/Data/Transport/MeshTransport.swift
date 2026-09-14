import Foundation

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

public protocol MeshTransport: AnyObject, Sendable {
    var isRunning: Bool { get }
    var onEvent: (@Sendable (TransportEvent) -> Void)? { get set }

    func start(localNode: Node)
    func stop()
    func connectToNode(_ targetNode: Node)
    func disconnectNode(nodeId: String)

    @discardableResult
    func sendPacket(_ packet: MessagePacket, targetEndpointId: String) -> Bool

    @discardableResult
    func broadcastPacket(_ packet: MessagePacket, excludeEndpointIds: Set<String>) -> Int
}
