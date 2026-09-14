import Foundation

public enum LogLevel: String, Codable, CaseIterable, Sendable {
    case info = "INFO"
    case success = "SUCCESS"
    case warning = "WARNING"
    case error = "ERROR"
}

public struct NetworkLogEntry: Identifiable, Equatable, Sendable {
    public let id: String
    public let timestamp: Int64
    public let tag: String
    public let message: String
    public let level: LogLevel
    public let packetSummary: String?
    public let relatedNodeId: String?

    public init(
        id: String = UUID().uuidString,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        tag: String,
        message: String,
        level: LogLevel = .info,
        packetSummary: String? = nil,
        relatedNodeId: String? = nil
    ) {
        self.id = id
        self.timestamp = timestamp
        self.tag = tag
        self.message = message
        self.level = level
        self.packetSummary = packetSummary
        self.relatedNodeId = relatedNodeId
    }

    public func getFormattedTime() -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(timestamp) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter.string(from: date)
    }
}
