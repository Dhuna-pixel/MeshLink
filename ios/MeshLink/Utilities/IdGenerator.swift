import Foundation

public enum IdGenerator {

    public static func generateNodeId(prefix: String = "NODE") -> String {
        let uuid = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let suffix = String(uuid.prefix(4)).uppercased()
        return "\(prefix)-\(suffix)"
    }

    public static func generateMessageId(prefix: String = "MSG") -> String {
        let uuid = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let suffix = String(uuid.prefix(6)).uppercased()
        return "\(prefix)-\(suffix)"
    }
}
