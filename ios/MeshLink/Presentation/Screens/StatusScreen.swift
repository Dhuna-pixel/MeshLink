import SwiftUI

public struct StatusScreen: View {
    @ObservedObject public var viewModel: MeshViewModel

    public init(viewModel: MeshViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Header Title
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("MESHLINK")
                            .font(.system(size: 24, weight: .black, design: .rounded))
                            .foregroundColor(.meshCyanPrimary)
                        Text("Decentralized Multi-Hop Mesh Network")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.textSecondary)
                    }
                    Spacer()
                    // Real / Simulation Mode Badge
                    Text(viewModel.uiState.isSimulationMode ? "SIMULATION" : "REAL NETWORK")
                        .font(.system(size: 10, weight: .heavy, design: .monospaced))
                        .foregroundColor(viewModel.uiState.isSimulationMode ? .meshPurpleAccent : .meshGreenAccent)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background((viewModel.uiState.isSimulationMode ? Color.meshPurpleAccent : Color.meshGreenAccent).opacity(0.15))
                        .cornerRadius(6)
                }

                BluetoothPermissionBanner()

                // =====================================================================
                // LOCAL NODE IDENTITY CARD
                // =====================================================================
                VStack(alignment: .leading, spacing: 14) {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("LOCAL NODE IDENTITY")
                                .font(.system(size: 10, weight: .bold, design: .monospaced))
                                .foregroundColor(.textSecondary)

                            Text(viewModel.uiState.currentNode.nodeName)
                                .font(.system(size: 20, weight: .bold))
                                .foregroundColor(.meshCyanPrimary)

                            Text("ID: \(viewModel.uiState.currentNode.nodeId)")
                                .font(.system(size: 13, weight: .medium, design: .monospaced))
                                .foregroundColor(.textSecondary)
                        }

                        Spacer()

                        Button(action: { viewModel.showIdentityDialog(true) }) {
                            Image(systemName: "pencil")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(.meshCyanPrimary)
                                .frame(width: 36, height: 36)
                                .background(Color.meshDarkSurfaceVariant)
                                .clipShape(Circle())
                        }
                    }

                    HStack {
                        NodeStatusBadge(status: viewModel.uiState.currentNode.status)
                        Spacer()
                        Text(viewModel.uiState.isSimulationMode ? "Mode: Simulation (In-Process)" : "Mode: Hardware BLE P2P")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(viewModel.uiState.isSimulationMode ? .meshPurpleAccent : .meshGreenAccent)
                    }

                    // Start/Stop Networking Button
                    Button(action: { viewModel.toggleNetworking() }) {
                        HStack(spacing: 8) {
                            Image(systemName: viewModel.uiState.isNetworkingActive ? "stop.fill" : "play.fill")
                            Text(viewModel.uiState.isNetworkingActive ? "Stop Networking" : "Start Mesh Networking")
                                .font(.system(size: 15, weight: .bold))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(viewModel.uiState.isNetworkingActive ? Color.meshRedError : Color.meshCyanPrimary)
                        .foregroundColor(.meshDarkBackground)
                        .cornerRadius(10)
                    }
                }
                .padding(16)
                .background(Color.meshDarkSurface)
                .cornerRadius(16)
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(Color.meshDarkSurfaceVariant.opacity(0.5), lineWidth: 1)
                )

                // =====================================================================
                // NETWORK STATUS METRICS
                // =====================================================================
                Text("Mesh Network Status")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.textPrimary)

                HStack(spacing: 10) {
                    MetricCard(
                        title: "Connected",
                        value: "\(viewModel.uiState.connectedNodes.count)",
                        subtitle: "Active Neighbors",
                        iconName: "antenna.radiowaves.left.and.right",
                        color: .meshGreenAccent,
                        action: { viewModel.selectTab(.neighbors) }
                    )

                    MetricCard(
                        title: "Discovered",
                        value: "\(viewModel.uiState.discoveredNodes.count)",
                        subtitle: "Nearby Peers",
                        iconName: "network",
                        color: .meshCyanPrimary,
                        action: { viewModel.selectTab(.neighbors) }
                    )

                    MetricCard(
                        title: "Messages",
                        value: "\(viewModel.uiState.messages.count)",
                        subtitle: "Transmitted / RX",
                        iconName: "envelope.fill",
                        color: .meshPurpleAccent,
                        action: { viewModel.selectTab(.messaging) }
                    )
                }

                // =====================================================================
                // QUICK LINKS & VERIFICATION
                // =====================================================================
                VStack(alignment: .leading, spacing: 12) {
                    Text("Multi-Hop Verification Quick Links")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(.textPrimary)

                    Text("Multi-hop topology verification: Node A ➔ Node B (Relay) ➔ Node C (Destination).")
                        .font(.system(size: 12))
                        .foregroundColor(.textSecondary)

                    HStack(spacing: 10) {
                        Button(action: { viewModel.selectTab(.simulation) }) {
                            HStack(spacing: 6) {
                                Image(systemName: "play.circle.fill")
                                Text("3-Node Visualizer")
                            }
                            .font(.system(size: 13, weight: .bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Color.meshDarkSurfaceVariant)
                            .foregroundColor(.meshCyanPrimary)
                            .cornerRadius(8)
                        }

                        Button(action: { viewModel.selectTab(.logs) }) {
                            HStack(spacing: 6) {
                                Image(systemName: "terminal.fill")
                                Text("Live Event Log")
                            }
                            .font(.system(size: 13, weight: .bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Color.meshDarkSurfaceVariant)
                            .foregroundColor(.meshGreenAccent)
                            .cornerRadius(8)
                        }
                    }
                }
                .padding(16)
                .background(Color.meshDarkSurface)
                .cornerRadius(16)
            }
            .padding(16)
        }
        .background(Color.meshDarkBackground.ignoresSafeArea())
        .sheet(isPresented: $viewModel.uiState.isEditingIdentityDialogShown) {
            NodeIdentitySheet(
                isPresented: $viewModel.uiState.isEditingIdentityDialogShown,
                currentNode: viewModel.uiState.currentNode,
                onSave: { newId, newName in
                    viewModel.updateNodeIdentity(nodeId: newId, nodeName: newName)
                }
            )
        }
    }
}
