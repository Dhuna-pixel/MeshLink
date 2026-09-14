import Foundation
import SwiftUI
import Combine

/**
 * Main ViewModel for MeshLink iOS UI.
 *
 * Connects SwiftUI screens to `MeshRepository` and orchestrates simulation runs, messaging,
 * identity updates, and event filtering.
 */
@MainActor
public final class MeshViewModel: ObservableObject {

    @Published public var uiState = MeshUiState()

    private let repository: MeshRepository
    private var cancellables = Set<AnyCancellable>()

    public init(repository: MeshRepository? = nil) {
        let repo = repository ?? MeshRepository()
        self.repository = repo
        bindRepository()
    }

    private func bindRepository() {
        repository.$currentNode
            .sink { [weak self] node in self?.uiState.currentNode = node }
            .store(in: &cancellables)

        repository.$isNetworkingActive
            .sink { [weak self] active in self?.uiState.isNetworkingActive = active }
            .store(in: &cancellables)

        repository.$isSimulationMode
            .sink { [weak self] sim in self?.uiState.isSimulationMode = sim }
            .store(in: &cancellables)

        repository.$discoveredNodes
            .sink { [weak self] list in self?.uiState.discoveredNodes = list }
            .store(in: &cancellables)

        repository.$connectedNodes
            .sink { [weak self] list in self?.uiState.connectedNodes = list }
            .store(in: &cancellables)

        repository.$messages
            .sink { [weak self] list in self?.uiState.messages = list }
            .store(in: &cancellables)

        repository.$networkLogs
            .sink { [weak self] list in self?.uiState.networkLogs = list }
            .store(in: &cancellables)
    }

    // =========================================================================
    // UI NAVIGATION & INTERACTION
    // =========================================================================
    public func selectTab(_ tab: MeshScreenTab) {
        uiState.selectedTab = tab
    }

    public func showIdentityDialog(_ show: Bool) {
        uiState.isEditingIdentityDialogShown = show
    }

    public func updateNodeIdentity(nodeId: String, nodeName: String) {
        repository.updateNodeIdentity(nodeId: nodeId, nodeName: nodeName)
        showIdentityDialog(false)
    }

    public func toggleNetworking() {
        if uiState.isNetworkingActive {
            repository.stopMesh()
        } else {
            repository.startMesh(simulation: uiState.isSimulationMode)
        }
    }

    public func setSimulationMode(_ isSim: Bool) {
        if uiState.isNetworkingActive {
            repository.stopMesh()
        }
        uiState.isSimulationMode = isSim
        repository.startMesh(simulation: isSim)
    }

    public func connectToNode(_ node: Node) {
        repository.connectToNeighbor(node)
    }

    public func disconnectNode(nodeId: String) {
        repository.disconnectNeighbor(nodeId: nodeId)
    }

    public func updateSelectedDestination(_ destinationId: String) {
        uiState.selectedDestinationNodeId = destinationId
    }

    public func updateMessageInput(_ text: String) {
        uiState.messageInputText = text
    }

    public func updateTtl(_ ttl: Int) {
        uiState.ttlSliderValue = ttl
    }

    public func setLogFilter(_ level: LogLevel?) {
        uiState.logFilterLevel = level
    }

    public func inspectPacket(_ packet: MessagePacket?) {
        uiState.isInspectingPacket = packet
    }

    public func clearLogs() {
        repository.clearLogs()
    }

    public func clearMessages() {
        repository.clearMessages()
    }

    // =========================================================================
    // SEND MESSAGE DISPATCH
    // =========================================================================
    public func sendMessage() {
        let text = uiState.messageInputText.trimmingCharacters(in: .whitespacesAndNewlines)
        let destinationId = uiState.selectedDestinationNodeId.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !text.isEmpty, !destinationId.isEmpty else { return }

        let allNodes = uiState.connectedNodes + uiState.discoveredNodes
        let destNode = allNodes.first { $0.nodeId == destinationId }
        let destName = destNode?.nodeName ?? destinationId

        repository.sendMessage(
            destinationId: destinationId,
            destinationName: destName,
            payload: text,
            ttl: uiState.ttlSliderValue
        )

        uiState.messageInputText = ""
    }

    // =========================================================================
    // 3-NODE MULTI-HOP SIMULATION DEMONSTRATION
    // =========================================================================
    public func runThreeNodeSimulationDemo() {
        Task {
            uiState.isRunningSimulationAnimation = true
            uiState.simulationStepDescription = "Step 1/3: Node A generating message for Node C (TTL: 5, Hops: 0)..."

            if !uiState.isSimulationMode || !uiState.isNetworkingActive {
                setSimulationMode(true)
            }

            try? await Task.sleep(nanoseconds: 800_000_000)

            uiState.simulationStepDescription = "Step 2/3: Node A transmitting packet to Relay Node B..."

            repository.sendMessage(
                destinationId: "NODE-C",
                destinationName: "Node C (Destination)",
                payload: "Hello Node C! This is a multi-hop test from Node A via relay Node B.",
                ttl: 5
            )

            try? await Task.sleep(nanoseconds: 1_200_000_000)

            uiState.simulationStepDescription = "Step 3/3: Relay Node B processed packet, updated TTL to 4, Hops to 1, forwarded to Node C!"

            try? await Task.sleep(nanoseconds: 1_500_000_000)

            uiState.isRunningSimulationAnimation = false
            uiState.simulationStepDescription = "Demo complete! Node C successfully delivered the packet."
        }
    }
}
