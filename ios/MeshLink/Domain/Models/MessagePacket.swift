import Foundation

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

    public func getSummary() -> String {
        let pathStr = routeHistory.joined(separator: " -> ")
        return "ID: \(messageId) | Src: \(sourceId) -> Dst: \(destinationId) | TTL: \(ttl) | Hops: \(hopCount) | Path: \(pathStr)"
    }
}
