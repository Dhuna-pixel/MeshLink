import Foundation
import SwiftUI

/**
 * Screen navigation destinations in the bottom navigation bar.
 */
public enum MeshScreenTab: Int, CaseIterable, Identifiable, Sendable {
    case status = 0
    case neighbors = 1
    case messaging = 2
    case logs = 3
    case simulation = 4

    public var id: Int { rawValue }

    public var title: String {
        switch self {
        case .status: return "Status"
        case .neighbors: return "Neighbors"
        case .messaging: return "Messages"
        case .logs: return "Logs"
        case .simulation: return "3-Node Sim"
        }
    }

    public var iconName: String {
        switch self {
        case .status: return "dot.radiowaves.left.and.right"
        case .neighbors: return "network"
        case .messaging: return "paperplane.fill"
        case .logs: return "terminal.fill"
        case .simulation: return "play.circle.fill"
        }
    }
}

/**
 * Immutable state representing the complete UI state for MeshLink iOS.
 */
public struct MeshUiState: Equatable, Sendable {
    public var currentNode: Node = Node(nodeId: "NODE-A", nodeName: "Node A (iOS)")
    public var isNetworkingActive: Bool = false
    public var isSimulationMode: Bool = false
    public var discoveredNodes: [Node] = []
    public var connectedNodes: [Node] = []
    public var messages: [MessagePacket] = []
    public var networkLogs: [NetworkLogEntry] = []
    public var selectedTab: MeshScreenTab = .status
    public var selectedDestinationNodeId: String = ""
    public var messageInputText: String = ""
    public var ttlSliderValue: Int = 5
    public var logFilterLevel: LogLevel? = nil
    public var isInspectingPacket: MessagePacket? = nil
    public var isEditingIdentityDialogShown: Bool = false
    public var isRunningSimulationAnimation: Bool = false
    public var simulationStepDescription: String = "Ready for test"

    public init() {}
}
