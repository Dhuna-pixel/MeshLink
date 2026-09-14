import Foundation

/**
 * Utility to generate clean, readable identifiers for nodes and packets.
 * Matches Android IdGenerator format (e.g. "NODE-A4F2", "MSG-8B12C3").
 */
public enum IdGenerator {

    /**
     * Generates a short, readable node ID, e.g. "NODE-A4F2"
     */
    public static func generateNodeId(prefix: String = "NODE") -> String {
        let uuid = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let suffix = String(uuid.prefix(4)).uppercased()
        return "\(prefix)-\(suffix)"
    }

    /**
     * Generates a unique message ID, e.g. "MSG-8B12C3"
     */
    public static func generateMessageId(prefix: String = "MSG") -> String {
        let uuid = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let suffix = String(uuid.prefix(6)).uppercased()
        return "\(prefix)-\(suffix)"
    }
}
