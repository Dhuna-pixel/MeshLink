import SwiftUI

public struct SimulationScreen: View {
    @ObservedObject public var viewModel: MeshViewModel

    public init(viewModel: MeshViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Header
                VStack(alignment: .leading, spacing: 2) {
                    Text("3-Node Multi-Hop Simulation")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundColor(.textPrimary)
                    Text("Demonstrates multi-hop routing (A ➔ B ➔ C) on the same device using genuine RoutingEngine instances.")
                        .font(.system(size: 12))
                        .foregroundColor(.textSecondary)
                }

                // =====================================================================
                // TOPOLOGY VISUALIZER CARD
                // =====================================================================
                VStack(spacing: 16) {
                    Text("VIRTUAL NETWORK TOPOLOGY")
                        .font(.system(size: 11, weight: .black, design: .monospaced))
                        .foregroundColor(.textSecondary)

                    // 3-Node Diagram
                    HStack(spacing: 8) {
                        SimNodeBadge(
                            label: "Node A",
                            sublabel: "SOURCE",
                            color: .meshCyanPrimary,
                            isHighlighted: viewModel.uiState.isRunningSimulationAnimation
                        )

                        Image(systemName: "arrow.right")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.meshCyanPrimary)

                        SimNodeBadge(
                            label: "Node B",
                            sublabel: "RELAY",
                            color: .meshGreenAccent,
                            isHighlighted: viewModel.uiState.isRunningSimulationAnimation
                        )

                        Image(systemName: "arrow.right")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.meshGreenAccent)

                        SimNodeBadge(
                            label: "Node C",
                            sublabel: "DESTINATION",
                            color: .meshPurpleAccent,
                            isHighlighted: viewModel.uiState.isRunningSimulationAnimation
                        )
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)

                    // Live Status Step Description Box
                    Text(viewModel.uiState.simulationStepDescription)
                        .font(.system(size: 12, weight: .medium, design: .monospaced))
                        .foregroundColor(.meshCyanPrimary)
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.codeLogBackground)
                        .cornerRadius(8)

                    // Actions
                    HStack(spacing: 10) {
                        Button(action: { viewModel.runThreeNodeSimulationDemo() }) {
                            HStack(spacing: 6) {
                                Image(systemName: "play.fill")
                                Text("Trigger A ➔ B ➔ C Demo")
                                    .font(.system(size: 13, weight: .bold))
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(viewModel.uiState.isRunningSimulationAnimation ? Color.meshDarkSurfaceVariant : Color.meshGreenAccent)
                            .foregroundColor(viewModel.uiState.isRunningSimulationAnimation ? Color.textTertiary : Color.meshDarkBackground)
                            .cornerRadius(10)
                        }
                        .disabled(viewModel.uiState.isRunningSimulationAnimation)

                        Button(action: {
                            viewModel.setSimulationMode(true)
                            viewModel.uiState.simulationStepDescription = "Simulation topology reset. Ready for test."
                        }) {
                            Image(systemName: "arrow.counterclockwise")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(.textSecondary)
                                .frame(width: 44, height: 44)
                                .background(Color.meshDarkSurfaceVariant)
                                .cornerRadius(10)
                        }
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
                // ROUTING LOGIC SPECIFICATION REFERENCE
                // =====================================================================
                VStack(alignment: .leading, spacing: 12) {
                    Text("Node B (Relay) Internal Execution Logic")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(.meshGreenAccent)

                    LogicStepRow(
                        stepNum: "1",
                        title: "Duplicate Check",
                        desc: "Checks SeenMessageManager. If packetId exists ➔ DROP_DUPLICATE."
                    )
                    LogicStepRow(
                        stepNum: "2",
                        title: "Destination Check",
                        desc: "Checks if destinationId == NODE-B. (Node B != Node C ➔ Relay Required)."
                    )
                    LogicStepRow(
                        stepNum: "3",
                        title: "TTL Decrement & Validation",
                        desc: "If TTL <= 1 ➔ DROP_TTL_EXPIRED. Otherwise TTL = TTL - 1 (5 ➔ 4)."
                    )
                    LogicStepRow(
                        stepNum: "4",
                        title: "Hop Count & Path Mutation",
                        desc: "hopCount = hopCount + 1 (0 ➔ 1). routeHistory += NODE-B."
                    )
                    LogicStepRow(
                        stepNum: "5",
                        title: "Next Hop Forwarding",
                        desc: "Transmits updated packet to connected neighbor Node C (Excludes Node A previous hop)."
                    )
                }
                .padding(16)
                .background(Color.meshDarkSurface)
                .cornerRadius(16)
            }
            .padding(16)
        }
        .background(Color.meshDarkBackground.ignoresSafeArea())
    }
}

private struct SimNodeBadge: View {
    let label: String
    let sublabel: String
    let color: Color
    let isHighlighted: Bool

    var body: some View {
        VStack(spacing: 4) {
            ZStack {
                Circle()
                    .fill(color.opacity(0.2))
                    .frame(width: 48, height: 48)
                Circle()
                    .stroke(color, lineWidth: isHighlighted ? 2.5 : 1.5)
                    .frame(width: 48, height: 48)

                Image(systemName: "network")
                    .font(.system(size: 20))
                    .foregroundColor(color)
            }
            .scaleEffect(isHighlighted ? 1.1 : 1.0)
            .animation(.easeInOut(duration: 0.4), value: isHighlighted)

            Text(label)
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(.textPrimary)

            Text(sublabel)
                .font(.system(size: 8, weight: .heavy, design: .monospaced))
                .foregroundColor(color)
        }
    }
}

private struct LogicStepRow: View {
    let stepNum: String
    let title: String
    let desc: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            ZStack {
                Circle()
                    .fill(Color.meshCyanPrimary.opacity(0.2))
                    .frame(width: 22, height: 22)
                Text(stepNum)
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(.meshCyanPrimary)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(.textPrimary)
                Text(desc)
                    .font(.system(size: 11))
                    .foregroundColor(.textSecondary)
            }
        }
    }
}
