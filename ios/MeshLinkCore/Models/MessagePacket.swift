import Foundation

/**
 * Structured packet exchanged between MeshLink nodes across the peer-to-peer network.
 *
 * Wire-compatible with the Android Kotlin `MessagePacket` data class.
 *
 * Field definitions:
 * - `messageId`: Unique identifier for this message (e.g. "MSG-A1B2C3D4")
 * - `sourceId`: Node ID of the original sender/creator (e.g. "NODE-A")
 * - `sourceName`: Human-readable name of the original sender (e.g. "Node A")
 * - `destinationId`: Node ID of the intended ultimate recipient (e.g. "NODE-C")
 * - `destinationName`: Human-readable name of the intended recipient
 * - `payload`: The textual content / message data
 * - `ttl`: Time-To-Live hop limit to prevent infinite loops (decremented at each relay)
 * - `hopCount`: Number of hops this packet has traversed so far (incremented at each relay)
 * - `timestamp`: Epoch millisecond timestamp when the packet was created
 * - `routeHistory`: Chronological list of node IDs that have handled/relayed this packet
 * - `previousHopId`: Node ID of the immediate previous node that transmitted this packet
 */
public struct MessagePacket: Codable, Identifiable, Equatable, Sendable {
    public var id: String { messageId }

    public let messageId: String
    public let sourceId: String
    public let sourceName: String
    public let destinationId: String
    public let destinationName: String
    public let payload: String
    public let ttl: Int
    public let hopCount: Int
    public let timestamp: Int64
    public let routeHistory: [String]
    public let previousHopId: String?

    public init(
        messageId: String,
        sourceId: String,
        sourceName: String = "",
        destinationId: String,
        destinationName: String = "",
        payload: String,
        ttl: Int = 5,
        hopCount: Int = 0,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        routeHistory: [String] = [],
        previousHopId: String? = nil
    ) {
        self.messageId = messageId
        self.sourceId = sourceId
        self.sourceName = sourceName
        self.destinationId = destinationId
        self.destinationName = destinationName
        self.payload = payload
        self.ttl = ttl
        self.hopCount = hopCount
        self.timestamp = timestamp
        self.routeHistory = routeHistory
        self.previousHopId = previousHopId
    }

    /**
     * Creates an updated clone of this packet when an intermediate relay node forwards it.
     * Decrements TTL by 1, increments hopCount by 1, records current node in route history,
     * and sets previousHopId to current node.
     */
    public func createForwardedPacket(relayNodeId: String) -> MessagePacket {
        let updatedHistory: [String]
        if routeHistory.contains(relayNodeId) {
            updatedHistory = routeHistory
        } else {
            updatedHistory = routeHistory + [relayNodeId]
        }

        return MessagePacket(
            messageId: self.messageId,
            sourceId: self.sourceId,
            sourceName: self.sourceName,
            destinationId: self.destinationId,
            destinationName: self.destinationName,
            payload: self.payload,
            ttl: self.ttl - 1,
            hopCount: self.hopCount + 1,
            timestamp: self.timestamp,
            routeHistory: updatedHistory,
            previousHopId: relayNodeId
        )
    }

    /**
     * Helper to get a concise readable summary of the packet state.
     */
    public func getSummary() -> String {
        let pathStr = routeHistory.joined(separator: " -> ")
        return "ID: \(messageId) | Src: \(sourceId) -> Dst: \(destinationId) | TTL: \(ttl) | Hops: \(hopCount) | Path: \(pathStr)"
    }
}
