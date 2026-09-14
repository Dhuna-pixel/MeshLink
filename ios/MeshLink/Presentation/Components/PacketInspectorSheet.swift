import SwiftUI

public struct PacketInspectorSheet: View {
    public let packet: MessagePacket
    public let onDismiss: () -> Void

    @State private var copiedWireJson: Bool = false

    public init(packet: MessagePacket, onDismiss: @escaping () -> Void) {
        self.packet = packet
        self.onDismiss = onDismiss
    }

    private var wireJson: String {
        PacketSerializer.packetToJson(packet) ?? "{}"
    }

    private var fullRoute: [String] {
        var r = packet.routeHistory
        if !packet.destinationId.isEmpty && !r.contains(packet.destinationId) {
            r.append(packet.destinationId)
        }
        return r
    }

    public var body: some View {
        NavigationStack {
            ZStack {
                Color.meshDarkBackground.ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        // Header
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("PACKET INSPECTOR")
                                    .font(.system(size: 11, weight: .black, design: .monospaced))
                                    .foregroundColor(.meshCyanPrimary)
                                Text(packet.messageId)
                                    .font(.system(size: 18, weight: .bold, design: .monospaced))
                                    .foregroundColor(.textPrimary)
                            }
                            Spacer()
                            Button("Done", action: onDismiss)
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(.meshCyanPrimary)
                        }
                        .padding(.top, 8)

                        Divider().background(Color.meshDarkSurfaceVariant)

                        // Header Metadata Table
                        VStack(spacing: 8) {
                            FieldRow(label: "Source Node", value: "\(packet.sourceName) (\(packet.sourceId))")
                            FieldRow(label: "Destination", value: "\(packet.destinationName) (\(packet.destinationId))")
                            FieldRow(label: "Time-To-Live (TTL)", value: "\(packet.ttl) hops remaining")
                            FieldRow(label: "Hop Count", value: "\(packet.hopCount) traversed")
                            if let prev = packet.previousHopId {
                                FieldRow(label: "Previous Hop", value: prev)
                            }
                            FieldRow(label: "Timestamp", value: "\(packet.timestamp) ms")
                        }
                        .padding(14)
                        .background(Color.meshDarkSurface)
                        .cornerRadius(12)

                        // Route Traversed Breadcrumb
                        VStack(alignment: .leading, spacing: 8) {
                            Text("ROUTE TRAVERSED")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.textSecondary)

                            HStack(spacing: 6) {
                                ForEach(Array(fullRoute.enumerated()), id: \.offset) { idx, node in
                                    Text(node)
                                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                                        .foregroundColor(idx == fullRoute.count - 1 ? .meshPurpleAccent : .meshCyanPrimary)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 4)
                                        .background(Color.meshDarkSurfaceVariant)
                                        .cornerRadius(6)

                                    if idx < fullRoute.count - 1 {
                                        Image(systemName: "arrow.right")
                                            .font(.system(size: 10, weight: .bold))
                                            .foregroundColor(.textSecondary)
                                    }
                                }
                            }
                        }
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.meshDarkSurface)
                        .cornerRadius(12)

                        // Payload Content
                        VStack(alignment: .leading, spacing: 8) {
                            Text("PAYLOAD CONTENT")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.textSecondary)

                            Text(packet.payload)
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(.meshGreenAccent)
                                .padding(12)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(Color.codeLogBackground)
                                .cornerRadius(8)
                        }
                        .padding(14)
                        .background(Color.meshDarkSurface)
                        .cornerRadius(12)

                        // Wire JSON Representation
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text("RAW WIRE JSON (CROSS-PLATFORM)")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.textSecondary)
                                Spacer()
                                Button(action: {
                                    UIPasteboard.general.string = wireJson
                                    copiedWireJson = true
                                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                                        copiedWireJson = false
                                    }
                                }) {
                                    HStack(spacing: 4) {
                                        Image(systemName: copiedWireJson ? "checkmark" : "doc.on.doc")
                                        Text(copiedWireJson ? "Copied" : "Copy JSON")
                                    }
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundColor(.meshCyanPrimary)
                                }
                            }

                            Text(wireJson)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundColor(.textSecondary)
                                .padding(12)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(Color.codeLogBackground)
                                .cornerRadius(8)
                        }
                        .padding(14)
                        .background(Color.meshDarkSurface)
                        .cornerRadius(12)
                    }
                    .padding(16)
                }
            }
        }
    }
}

private struct FieldRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.system(size: 12))
                .foregroundColor(.textSecondary)
            Spacer()
            Text(value)
                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                .foregroundColor(.textPrimary)
        }
    }
}
