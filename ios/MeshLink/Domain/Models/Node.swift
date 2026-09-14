import Foundation

public enum NodeStatus: String, Codable, Sendable {
    case discovered = "DISCOVERED"
    case connecting = "CONNECTING"
    case connected = "CONNECTED"
    case disconnected = "DISCONNECTED"
}

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
