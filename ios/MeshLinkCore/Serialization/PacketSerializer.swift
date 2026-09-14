import Foundation

/**
 * Utility for serializing and deserializing domain objects to/from JSON strings and raw byte data.
 *
 * Wire-compatible with Kotlinx Serialization and Gson in Android MeshLink.
 */
public enum PacketSerializer {

    private static let encoder: JSONEncoder = {
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        return enc
    }()

    private static let decoder: JSONDecoder = {
        let dec = JSONDecoder()
        return dec
    }()

    public static func packetToJson(_ packet: MessagePacket) -> String? {
        guard let data = try? encoder.encode(packet) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public static func jsonToPacket(_ jsonStr: String) -> MessagePacket? {
        guard let data = jsonStr.data(using: .utf8) else { return nil }
        return try? decoder.decode(MessagePacket.self, from: data)
    }

    public static func packetToData(_ packet: MessagePacket) -> Data? {
        return try? encoder.encode(packet)
    }

    public static func dataToPacket(_ data: Data) -> MessagePacket? {
        return try? decoder.decode(MessagePacket.self, from: data)
    }

    public static func nodeToJson(_ node: Node) -> String? {
        guard let data = try? encoder.encode(node) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public static func jsonToNode(_ jsonStr: String) -> Node? {
        guard let data = jsonStr.data(using: .utf8) else { return nil }
        return try? decoder.decode(Node.self, from: data)
    }
}
