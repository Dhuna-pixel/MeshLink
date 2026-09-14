import Foundation
import Combine

/**
 * Central repository managing the MeshLink node state, network discovery, message routing,
 * and event logging on iOS.
 *
 * Coordinates between the pure domain `RoutingEngine` and the active `MeshTransport`
 * (CoreBluetooth BLE hardware or high-fidelity Simulation).
 */
@MainActor
public final class MeshRepository: ObservableObject {

    @Published public private(set) var currentNode: Node
    @Published public private(set) var discoveredNodes: [Node] = []
    @Published public private(set) var connectedNodes: [Node] = []
    @Published public private(set) var messages: [MessagePacket] = []
    @Published public private(set) var networkLogs: [NetworkLogEntry] = []
    @Published public private(set) var isNetworkingActive: Bool = false
    @Published public private(set) var isSimulationMode: Bool = false

    private let routingEngine: RoutingEngine
    private let bluetoothTransport: CoreBluetoothTransport
    private let simulationTransport: SimulationTransport
    private var activeTransport: MeshTransport

    public init(
        initialNode: Node = Node(
            nodeId: IdGenerator.generateNodeId(prefix: "NODE-A"),
            nodeName: "Node A (iOS)",
            status: .disconnected
        ),
        routingEngine: RoutingEngine = RoutingEngine()
    ) {
        self.currentNode = initialNode
        self.routingEngine = routingEngine
        self.bluetoothTransport = CoreBluetoothTransport()
        self.simulationTransport = SimulationTransport()
        self.activeTransport = self.bluetoothTransport

        setupTransportCallbacks()

        log(
            tag: "INIT",
            message: "MeshLink iOS node initialized with ID '\(initialNode.nodeId)' (\(initialNode.nodeName))",
            level: .info
        )
    }

    private func setupTransportCallbacks() {
        bluetoothTransport.onEvent = { [weak self] event in
            Task { @MainActor [weak self] in
                self?.handleTransportEvent(event)
            }
        }

        simulationTransport.onEvent = { [weak self] event in
            Task { @MainActor [weak self] in
                self?.handleTransportEvent(event)
            }
        }
    }

    // =========================================================================
    // NODE IDENTITY CONFIGURATION
    // =========================================================================
    public func updateNodeIdentity(nodeId: String, nodeName: String) {
        let wasActive = isNetworkingActive
        if wasActive {
            stopMesh()
        }

        let updatedNodeId = nodeId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? IdGenerator.generateNodeId() : nodeId.trimmingCharacters(in: .whitespacesAndNewlines)
        let updatedNodeName = nodeName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Node" : nodeName.trimmingCharacters(in: .whitespacesAndNewlines)

        self.currentNode = Node(
            nodeId: updatedNodeId,
            nodeName: updatedNodeName,
            status: .disconnected
        )

        log(
            tag: "CONFIG",
            message: "Node identity updated to '\(updatedNodeName)' [\(updatedNodeId)]",
            level: .info,
            nodeId: updatedNodeId
        )

        if wasActive {
            startMesh(simulation: isSimulationMode)
        }
    }

    // =========================================================================
    // NETWORKING LIFECYCLE
    // =========================================================================
    public func startMesh(simulation: Bool = false) {
        self.isSimulationMode = simulation
        self.activeTransport = simulation ? simulationTransport : bluetoothTransport

        self.isNetworkingActive = true
        self.currentNode.status = .connected

        log(
            tag: "ENGINE",
            message: "Starting networking in \(simulation ? "SIMULATION" : "HARDWARE BLE") mode...",
            level: .info
        )

        activeTransport.start(localNode: self.currentNode)
    }

    public func stopMesh() {
        activeTransport.stop()
        self.isNetworkingActive = false
        self.currentNode.status = .disconnected
        self.discoveredNodes.removeAll()
        self.connectedNodes.removeAll()

        log(
            tag: "ENGINE",
            message: "Mesh networking stopped. Disconnected from all peers.",
            level: .info
        )
    }

    public func connectToNeighbor(_ targetNode: Node) {
        log(
            tag: "CONNECT",
            message: "Connecting to neighbor '\(targetNode.nodeName)' [\(targetNode.nodeId)]...",
            level: .info,
            nodeId: targetNode.nodeId
        )
        activeTransport.connectToNode(targetNode)
    }

    public func disconnectNeighbor(nodeId: String) {
        activeTransport.disconnectNode(nodeId: nodeId)
    }

    // =========================================================================
    // MESSAGING & ROUTING DISPATCH
    // =========================================================================
    @discardableResult
    public func sendMessage(
        destinationId: String,
        destinationName: String = "",
        payload: String,
        ttl: Int = 5
    ) -> MessagePacket {
        let current = self.currentNode
        let messageId = IdGenerator.generateMessageId()

        let packet = routingEngine.createLocalPacket(
            messageId: messageId,
            sourceId: current.nodeId,
            sourceName: current.nodeName,
            destinationId: destinationId,
            destinationName: destinationName.isEmpty ? destinationId : destinationName,
            payload: payload,
            initialTtl: ttl
        )

        // Store in local message history
        self.messages.insert(packet, at: 0)

        log(
            tag: "SEND [LOCAL]",
            message: "Created message '\(messageId)' for destination '\(destinationId)' with TTL \(ttl)",
            level: .info,
            packetSummary: packet.getSummary(),
            nodeId: destinationId
        )

        // Check if destination is direct neighbor, or broadcast to connected neighbors
        let directNeighbor = connectedNodes.first { $0.nodeId.caseInsensitiveCompare(destinationId) == .orderedSame }
        if let endpoint = directNeighbor?.endpointId {
            log(
                tag: "SEND [DIRECT]",
                message: "Destination '\(destinationId)' is directly connected. Sending packet directly.",
                level: .info
            )
            activeTransport.sendPacket(packet, targetEndpointId: endpoint)
        } else {
            log(
                tag: "SEND [RELAY-OUT]",
                message: "Broadcasting packet '\(messageId)' to \(connectedNodes.count) connected neighbor(s) for mesh forwarding...",
                level: .info
            )
            activeTransport.broadcastPacket(packet, excludeEndpointIds: [])
        }

        return packet
    }

    // =========================================================================
    // INCOMING PACKET ROUTING EVALUATION
    // =========================================================================
    private func handleIncomingPacket(_ packet: MessagePacket, fromEndpointId: String) {
        let current = self.currentNode

        log(
            tag: "ROUTING-IN",
            message: "Processing packet '\(packet.messageId)' (Source: \(packet.sourceId), Dst: \(packet.destinationId), TTL: \(packet.ttl), Hops: \(packet.hopCount))",
            level: .info,
            packetSummary: packet.getSummary()
        )

        let decision = routingEngine.processIncomingPacket(
            packet: packet,
            currentNodeId: current.nodeId,
            connectedNeighbors: self.connectedNodes
        )

        switch decision {
        case .deliver(let deliveredPacket, _):
            // Current node IS the destination
            self.messages.insert(deliveredPacket, at: 0)
            let routeStr = (deliveredPacket.routeHistory + [current.nodeId]).joined(separator: " -> ")
            log(
                tag: "DELIVER [DEST]",
                message: "MESSAGE DELIVERED! Current node '\(current.nodeId)' is the intended destination.\nPayload: \"\(deliveredPacket.payload)\"\nTotal Hops: \(deliveredPacket.hopCount)\nRoute: \(routeStr)",
                level: .success,
                packetSummary: deliveredPacket.getSummary(),
                nodeId: deliveredPacket.sourceId
            )

        case .forward(let updatedPacket, let candidateNextHopNodeIds, _):
            // Current node is intermediate RELAY node (e.g. Node B)
            log(
                tag: "RELAY [FORWARD]",
                message: "ACTING AS RELAY: Node '\(current.nodeId)' is forwarding packet '\(updatedPacket.messageId)'.\nTTL decremented: \(packet.ttl) -> \(updatedPacket.ttl)\nHop count incremented: \(packet.hopCount) -> \(updatedPacket.hopCount)\nRoute history: \(updatedPacket.routeHistory.joined(separator: " -> "))",
                level: .success,
                packetSummary: updatedPacket.getSummary(),
                nodeId: updatedPacket.destinationId
            )

            // Transmit to candidate next hop neighbors
            let candidates = connectedNodes.filter { candidateNextHopNodeIds.contains($0.nodeId) }
            for nextHop in candidates {
                if let ep = nextHop.endpointId {
                    log(
                        tag: "RELAY [TX]",
                        message: "Transmitting forwarded packet to next-hop '\(nextHop.nodeName)' [\(nextHop.nodeId)]",
                        level: .info,
                        nodeId: nextHop.nodeId
                    )
                    activeTransport.sendPacket(updatedPacket, targetEndpointId: ep)
                }
            }

            if candidates.isEmpty {
                var exclude = Set<String>()
                if !fromEndpointId.isEmpty { exclude.insert(fromEndpointId) }
                activeTransport.broadcastPacket(updatedPacket, excludeEndpointIds: exclude)
            }

        case .dropDuplicate(_, let reason):
            log(tag: "DROP [DUPLICATE]", message: reason, level: .warning)

        case .dropTtlExpired(_, _, let reason):
            log(tag: "DROP [TTL-EXPIRED]", message: reason, level: .warning)

        case .dropNoRoute(_, _, let reason):
            log(tag: "DROP [NO-ROUTE]", message: reason, level: .error)
        }
    }

    // =========================================================================
    // TRANSPORT EVENT HANDLING
    // =========================================================================
    private func handleTransportEvent(_ event: TransportEvent) {
        switch event {
        case .nodeDiscovered(let node):
            self.discoveredNodes.removeAll { $0.nodeId == node.nodeId || $0.endpointId == node.endpointId }
            self.discoveredNodes.append(node)

        case .nodeLost(let endpointId):
            self.discoveredNodes.removeAll { $0.endpointId == endpointId }

        case .nodeConnected(let node):
            self.discoveredNodes.removeAll { $0.nodeId == node.nodeId }
            self.connectedNodes.removeAll { $0.nodeId == node.nodeId || $0.endpointId == node.endpointId }
            self.connectedNodes.append(node)

        case .nodeDisconnected(let nodeId, let endpointId):
            self.connectedNodes.removeAll { $0.nodeId == nodeId || $0.endpointId == endpointId }

        case .packetReceived(let packet, let fromEndpointId):
            handleIncomingPacket(packet, fromEndpointId: fromEndpointId)

        case .packetSent(let packet, let toEndpointId):
            log(
                tag: "TX-CONFIRM",
                message: "Packet '\(packet.messageId)' sent out via transport endpoint '\(toEndpointId)'",
                level: .info
            )

        case .logEmitted(let entry):
            appendLog(entry)

        case .errorOccurred(let message):
            log(tag: "ERROR", message: message, level: .error)

        case .connectionInitiated(let endpointId, let nodeName):
            log(tag: "CONNECT", message: "Connection initiated with '\(nodeName)' (\(endpointId))", level: .info)
        }
    }

    // =========================================================================
    // LOGGING & CLEANUP
    // =========================================================================
    public func log(
        tag: String,
        message: String,
        level: LogLevel = .info,
        packetSummary: String? = nil,
        nodeId: String? = nil
    ) {
        let entry = NetworkLogEntry(
            tag: tag,
            message: message,
            level: level,
            packetSummary: packetSummary,
            relatedNodeId: nodeId
        )
        appendLog(entry)
    }

    private func appendLog(_ entry: NetworkLogEntry) {
        self.networkLogs.insert(entry, at: 0)
        if self.networkLogs.count > 250 {
            self.networkLogs.removeLast()
        }
    }

    public func clearLogs() {
        self.networkLogs.removeAll()
        log(tag: "LOG", message: "Network event log cleared", level: .info)
    }

    public func clearMessages() {
        self.messages.removeAll()
    }

    public func resetRoutingState() {
        routingEngine.resetSeenCache()
        log(tag: "ROUTING", message: "Routing engine duplicate cache reset", level: .info)
    }
}
