import SwiftUI

public struct MessagingScreen: View {
    @ObservedObject public var viewModel: MeshViewModel

    public init(viewModel: MeshViewModel) {
        self.viewModel = viewModel
    }

    private var destinationPresets: [(id: String, name: String)] {
        var list: [(id: String, name: String)] = []
        let allNodes = viewModel.uiState.connectedNodes + viewModel.uiState.discoveredNodes
        for n in allNodes {
            list.append((id: n.nodeId, name: n.nodeName))
        }
        if !list.contains(where: { $0.id == "NODE-C" }) {
            list.append((id: "NODE-C", name: "Node C (Destination)"))
        }
        if !list.contains(where: { $0.id == "NODE-B" }) {
            list.append((id: "NODE-B", name: "Node B (Relay)"))
        }
        return list
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // =====================================================================
                // MESSAGE COMPOSER CARD
                // =====================================================================
                VStack(alignment: .leading, spacing: 12) {
                    Text("Transmit Mesh Packet")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundColor(.meshCyanPrimary)

                    // Destination Node Selection Chips
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Select Destination Node:")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(.textSecondary)

                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(destinationPresets, id: \.id) { preset in
                                    let isSelected = viewModel.uiState.selectedDestinationNodeId.caseInsensitiveCompare(preset.id) == .orderedSame
                                    Button(action: {
                                        viewModel.updateSelectedDestination(preset.id)
                                    }) {
                                        Text(preset.name)
                                            .font(.system(size: 12, weight: .semibold))
                                            .padding(.horizontal, 12)
                                            .padding(.vertical, 6)
                                            .background(isSelected ? Color.meshCyanPrimary : Color.meshDarkSurfaceVariant)
                                            .foregroundColor(isSelected ? Color.meshDarkBackground : Color.textPrimary)
                                            .cornerRadius(16)
                                    }
                                }
                            }
                        }
                    }

                    // Target Destination Node ID Text Field
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Target Destination Node ID")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.textTertiary)

                        TextField("e.g. NODE-C", text: Binding(
                            get: { viewModel.uiState.selectedDestinationNodeId },
                            set: { viewModel.updateSelectedDestination($0) }
                        ))
                        .font(.system(size: 13, design: .monospaced))
                        .padding(10)
                        .background(Color.meshDarkBackground)
                        .cornerRadius(8)
                        .foregroundColor(.textPrimary)
                    }

                    // TTL Slider
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text("Time-To-Live (Hop Limit):")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.textSecondary)
                            Spacer()
                            Text("\(viewModel.uiState.ttlSliderValue) Hops")
                                .font(.system(size: 13, weight: .bold, design: .monospaced))
                                .foregroundColor(.meshCyanPrimary)
                        }

                        Slider(
                            value: Binding(
                                get: { Double(viewModel.uiState.ttlSliderValue) },
                                set: { viewModel.updateTtl(Int($0)) }
                            ),
                            in: 1...10,
                            step: 1
                        )
                        .tint(.meshCyanPrimary)
                    }

                    // Message Text Field
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Payload / Message Content")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.textTertiary)

                        TextField("e.g. Hello Node C through Node B!", text: Binding(
                            get: { viewModel.uiState.messageInputText },
                            set: { viewModel.updateMessageInput($0) }
                        ))
                        .padding(10)
                        .background(Color.meshDarkBackground)
                        .cornerRadius(8)
                        .foregroundColor(.textPrimary)
                    }

                    // Send Button
                    Button(action: { viewModel.sendMessage() }) {
                        HStack(spacing: 6) {
                            Image(systemName: "paperplane.fill")
                            Text("Send via MeshLink")
                                .font(.system(size: 14, weight: .bold))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(
                            (viewModel.uiState.messageInputText.isEmpty || viewModel.uiState.selectedDestinationNodeId.isEmpty)
                            ? Color.meshDarkSurfaceVariant
                            : Color.meshGreenAccent
                        )
                        .foregroundColor(
                            (viewModel.uiState.messageInputText.isEmpty || viewModel.uiState.selectedDestinationNodeId.isEmpty)
                            ? Color.textTertiary
                            : Color.meshDarkBackground
                        )
                        .cornerRadius(10)
                    }
                    .disabled(viewModel.uiState.messageInputText.isEmpty || viewModel.uiState.selectedDestinationNodeId.isEmpty)
                }
                .padding(16)
                .background(Color.meshDarkSurface)
                .cornerRadius(16)
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(Color.meshDarkSurfaceVariant.opacity(0.5), lineWidth: 1)
                )

                // =====================================================================
                // MESSAGE HISTORY
                // =====================================================================
                HStack {
                    Text("Message Packet History (\(viewModel.uiState.messages.count))")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.textPrimary)
                    Spacer()
                    if !viewModel.uiState.messages.isEmpty {
                        Button("Clear") {
                            viewModel.clearMessages()
                        }
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.textSecondary)
                    }
                }

                if viewModel.uiState.messages.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "tray")
                            .font(.system(size: 32))
                            .foregroundColor(.textTertiary)
                        Text("No messages sent or received yet.")
                            .font(.system(size: 13))
                            .foregroundColor(.textSecondary)
                        Text("Send a message above or run the 3-Node simulation to observe multi-hop routing.")
                            .font(.system(size: 11))
                            .foregroundColor(.textTertiary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 32)
                } else {
                    ForEach(viewModel.uiState.messages) { packet in
                        MessageCardView(
                            packet: packet,
                            currentNodeId: viewModel.uiState.currentNode.nodeId,
                            onInspect: { viewModel.inspectPacket(packet) }
                        )
                    }
                }
            }
            .padding(16)
        }
        .background(Color.meshDarkBackground.ignoresSafeArea())
        .sheet(item: Binding(
            get: { viewModel.uiState.isInspectingPacket },
            set: { viewModel.inspectPacket($0) }
        )) { packet in
            PacketInspectorSheet(packet: packet, onDismiss: {
                viewModel.inspectPacket(nil)
            })
        }
    }
}

private struct MessageCardView: View {
    let packet: MessagePacket
    let currentNodeId: String
    let onInspect: () -> Void

    private var isDeliveredHere: Bool {
        packet.destinationId.caseInsensitiveCompare(currentNodeId) == .orderedSame
    }

    private var isSentFromHere: Bool {
        packet.sourceId.caseInsensitiveCompare(currentNodeId) == .orderedSame
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                HStack(spacing: 6) {
                    Image(systemName: isDeliveredHere ? "arrow.down.left" : (isSentFromHere ? "arrow.up.right" : "arrow.triangle.swap"))
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(isDeliveredHere ? .meshGreenAccent : (isSentFromHere ? .meshCyanPrimary : .meshPurpleAccent))

                    Text(isDeliveredHere ? "DELIVERED HERE" : (isSentFromHere ? "OUTGOING" : "RELAYED"))
                        .font(.system(size: 10, weight: .black, design: .monospaced))
                        .foregroundColor(isDeliveredHere ? .meshGreenAccent : (isSentFromHere ? .meshCyanPrimary : .meshPurpleAccent))
                }

                Spacer()

                Button(action: onInspect) {
                    Image(systemName: "info.circle")
                        .font(.system(size: 14))
                        .foregroundColor(.meshCyanPrimary)
                }
            }

            Text(packet.payload)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(.textPrimary)

            HStack {
                Text("Src: \(packet.sourceId) ➔ Dst: \(packet.destinationId)")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(.textSecondary)
                Spacer()
                Text("Hops: \(packet.hopCount) | TTL: \(packet.ttl)")
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundColor(.meshCyanPrimary)
            }
            .padding(8)
            .background(Color.codeLogBackground)
            .cornerRadius(6)
        }
        .padding(12)
        .background(Color.meshDarkSurface)
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.meshDarkSurfaceVariant.opacity(0.4), lineWidth: 1)
        )
    }
}
