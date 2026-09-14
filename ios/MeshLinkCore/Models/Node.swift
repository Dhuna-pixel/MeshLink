import Foundation

/**
 * Represents the connection status of a node in the mesh network.
 */
public enum NodeStatus: String, Codable, Sendable {
    case discovered = "DISCOVERED"
    case connecting = "CONNECTING"
    case connected = "CONNECTED"
    case disconnected = "DISCONNECTED"
}

/**
 * Represents a participating node/device in the MeshLink ad-hoc network.
 *
 * @param nodeId Unique persistent identifier for this device/node (e.g. "NODE-A" or UUID)
 * @param nodeName Human-readable name configured by user (e.g. "Node A (iPhone)")
 * @param status Current connection lifecycle status
 * @param endpointId Transport-specific identifier (e.g. BLE Peripheral UUID or Endpoint ID)
 * @param lastSeenTimestamp Epoch milliseconds when this node was last detected or communicated with
 * @param isRelayCapable Indicates if this node is willing to act as an intermediate multi-hop relay
 */
public struct Node: Codable, Identifiable, Equatable, Sendable {
    public var id: String { nodeId }

    public let nodeId: String
    public var nodeName: String
    public var status: NodeStatus
    public var endpointId: String?
    public var lastSeenTimestamp: Int64
    public var isRelayCapable: BooleanLiteralType

    public init(
        nodeId: String,
        nodeName: String,
        status: NodeStatus = .discovered,
        endpointId: String? = nil,
        lastSeenTimestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        isRelayCapable: Bool = true
    ) {
        self.nodeId = nodeId
        self.nodeName = nodeName
        self.status = status
        self.endpointId = endpointId
        self.lastSeenTimestamp = lastSeenTimestamp
        self.isRelayCapable = isRelayCapable
    }
}
