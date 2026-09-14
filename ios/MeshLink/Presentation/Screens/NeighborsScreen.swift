import SwiftUI

public struct NeighborsScreen: View {
    @ObservedObject public var viewModel: MeshViewModel

    public init(viewModel: MeshViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            // Screen Header
            HStack {
                Text("Nearby & Connected Nodes")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundColor(.textPrimary)

                Spacer()

                if viewModel.uiState.isNetworkingActive {
                    HStack(spacing: 6) {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .meshCyanPrimary))
                            .scaleEffect(0.7)
                        Text("Scanning...")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(.meshCyanPrimary)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)

            if !viewModel.uiState.isNetworkingActive {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "wifi.slash")
                        .font(.system(size: 44))
                        .foregroundColor(.textTertiary)

                    Text("Networking is currently stopped.")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.textSecondary)

                    Text("Start Mesh Networking on the Status screen to discover nearby Android and iOS peers.")
                        .font(.system(size: 12))
                        .foregroundColor(.textTertiary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)

                    Button(action: { viewModel.toggleNetworking() }) {
                        Text("Start Mesh Networking")
                            .font(.system(size: 14, weight: .bold))
                            .padding(.horizontal, 20)
                            .padding(.vertical, 10)
                            .background(Color.meshCyanPrimary)
                            .foregroundColor(.meshDarkBackground)
                            .cornerRadius(8)
                    }
                    Spacer()
                }
                .frame(maxWidth: .infinity)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        // Section 1: Active Connected Neighbors
                        VStack(alignment: .leading, spacing: 8) {
                            Text("ACTIVE NEIGHBORS (\(viewModel.uiState.connectedNodes.count))")
                                .font(.system(size: 11, weight: .black, design: .monospaced))
                                .foregroundColor(.meshGreenAccent)

                            if viewModel.uiState.connectedNodes.isEmpty {
                                Text("No active peer connections established yet.")
                                    .font(.system(size: 13))
                                    .foregroundColor(.textSecondary)
                                    .padding(.vertical, 8)
                            } else {
                                ForEach(viewModel.uiState.connectedNodes) { node in
                                    NodeItemCard(
                                        node: node,
                                        isConnected: true,
                                        onConnect: { viewModel.connectToNode(node) },
                                        onDisconnect: { viewModel.disconnectNode(nodeId: node.nodeId) }
                                    )
                                }
                            }
                        }

                        Divider().background(Color.meshDarkSurfaceVariant)

                        // Section 2: Discovered Peers
                        VStack(alignment: .leading, spacing: 8) {
                            Text("DISCOVERED PEERS (\(viewModel.uiState.discoveredNodes.count))")
                                .font(.system(size: 11, weight: .black, design: .monospaced))
                                .foregroundColor(.meshCyanPrimary)

                            if viewModel.uiState.discoveredNodes.isEmpty {
                                Text("Scanning for nearby MeshLink devices over Bluetooth Low Energy...")
                                    .font(.system(size: 13))
                                    .foregroundColor(.textSecondary)
                                    .padding(.vertical, 8)
                            } else {
                                ForEach(viewModel.uiState.discoveredNodes) { node in
                                    NodeItemCard(
                                        node: node,
                                        isConnected: false,
                                        onConnect: { viewModel.connectToNode(node) },
                                        onDisconnect: { viewModel.disconnectNode(nodeId: node.nodeId) }
                                    )
                                }
                            }
                        }
                    }
                    .padding(16)
                }
            }
        }
        .background(Color.meshDarkBackground.ignoresSafeArea())
    }
}

private struct NodeItemCard: View {
    let node: Node
    let isConnected: Bool
    let onConnect: () -> Void
    let onDisconnect: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(node.nodeName)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(.textPrimary)
                    NodeStatusBadge(status: node.status)
                }

                Text("ID: \(node.nodeId)")
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundColor(.textSecondary)

                if let ep = node.endpointId {
                    Text("Endpoint: \(ep) | Relay Capable: \(node.isRelayCapable ? "YES" : "NO")")
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundColor(.textTertiary)
                        .lineLimit(1)
                }
            }

            Spacer()

            if isConnected {
                Button(action: onDisconnect) {
                    HStack(spacing: 4) {
                        Image(systemName: "link.badge.plus")
                        Text("Disconnect")
                    }
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(.meshRedError)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color.meshDarkSurfaceVariant)
                    .cornerRadius(6)
                }
            } else {
                Button(action: onConnect) {
                    HStack(spacing: 4) {
                        Image(systemName: "link")
                        Text("Connect")
                    }
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(.meshDarkBackground)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.meshCyanPrimary)
                    .cornerRadius(6)
                }
            }
        }
        .padding(14)
        .background(Color.meshDarkSurface)
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.meshDarkSurfaceVariant.opacity(0.4), lineWidth: 1)
        )
    }
}
