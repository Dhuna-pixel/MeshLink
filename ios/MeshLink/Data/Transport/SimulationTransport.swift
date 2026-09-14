import Foundation

public final class SimulationTransport: MeshTransport, @unchecked Sendable {

    private let lock = NSLock()
    private var _isRunning: Bool = false
    public var isRunning: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _isRunning
    }

    public var onEvent: (@Sendable (TransportEvent) -> Void)?

    public let simNodeA = Node(nodeId: "NODE-A", nodeName: "Node A (Source)", status: .connected, endpointId: "EP-A")
    public let simNodeB = Node(nodeId: "NODE-B", nodeName: "Node B (Relay)", status: .connected, endpointId: "EP-B")
    public let simNodeC = Node(nodeId: "NODE-C", nodeName: "Node C (Destination)", status: .connected, endpointId: "EP-C")

    private let engineA = RoutingEngine()
    private let engineB = RoutingEngine()
    private let engineC = RoutingEngine()

    private var localNode: Node

    public init() {
        self.localNode = Node(nodeId: "NODE-A", nodeName: "Node A (Source)", status: .connected, endpointId: "EP-A")
    }

    public func start(localNode: Node) {
        lock.lock()
        self.localNode = localNode
        self._isRunning = true
        lock.unlock()

        emitLog(
            tag: "SIMULATION",
            message: "Simulation Mode started. Topology initialized: [Node A] <---> [Node B (Relay)] <---> [Node C]",
            level: .success
        )

        Task {
            try? await Task.sleep(nanoseconds: 200_000_000)
            if localNode.nodeId == "NODE-A" {
                self.emitEvent(.nodeConnected(node: self.simNodeB))
                self.emitLog(tag: "SIMULATION", message: "Node A connected to neighbor Node B (Relay)", level: .info, nodeId: "NODE-B")
            } else if localNode.nodeId == "NODE-B" {
                self.emitEvent(.nodeConnected(node: self.simNodeA))
                self.emitEvent(.nodeConnected(node: self.simNodeC))
                self.emitLog(tag: "SIMULATION", message: "Node B (Relay) connected to Node A and Node C", level: .info)
            } else {
                self.emitEvent(.nodeConnected(node: self.simNodeB))
                self.emitLog(tag: "SIMULATION", message: "Node C connected to neighbor Node B (Relay)", level: .info, nodeId: "NODE-B")
            }
        }
    }

    public func stop() {
        lock.lock()
        _isRunning = false
        lock.unlock()

        engineA.resetSeenCache()
        engineB.resetSeenCache()
        engineC.resetSeenCache()
        emitLog(tag: "SIMULATION", message: "Simulation Mode stopped. Virtual state reset.", level: .info)
    }

    public func connectToNode(_ targetNode: Node) {
        var connected = targetNode
        connected.status = .connected
        emitEvent(.nodeConnected(node: connected))
    }

    public func disconnectNode(nodeId: String) {
        emitEvent(.nodeDisconnected(nodeId: nodeId, endpointId: "EP-\(nodeId)"))
    }

    @discardableResult
    public func sendPacket(_ packet: MessagePacket, targetEndpointId: String) -> Bool {
        Task {
            await executeMultiHopSimulation(initialPacket: packet)
        }
        return true
    }

    @discardableResult
    public func broadcastPacket(_ packet: MessagePacket, excludeEndpointIds: Set<String> = []) -> Int {
        Task {
            await executeMultiHopSimulation(initialPacket: packet)
        }
        return 1
    }

    public func executeMultiHopSimulation(initialPacket: MessagePacket) async {
        emitLog(
            tag: "SOURCE [A]",
            message: "Node A initiated packet '\(initialPacket.messageId)' with destination '\(initialPacket.destinationId)'",
            level: .info,
            packetSummary: initialPacket.getSummary(),
            nodeId: "NODE-A"
        )
        emitEvent(.packetSent(packet: initialPacket, toEndpointId: "EP-B"))

        try? await Task.sleep(nanoseconds: 600_000_000)

        // HOP 1: NODE B (RELAY) RECEIVES AND PROCESSES PACKET
        emitLog(
            tag: "RELAY [B]",
            message: "Node B received packet '\(initialPacket.messageId)' from Node A. Evaluating forwarding rules...",
            level: .info,
            nodeId: "NODE-B"
        )
        try? await Task.sleep(nanoseconds: 400_000_000)

        let neighborsOfB = [simNodeA, simNodeC]
        let decisionB = engineB.processIncomingPacket(
            packet: initialPacket,
            currentNodeId: "NODE-B",
            connectedNeighbors: neighborsOfB
        )

        switch decisionB {
        case .forward(let updatedPacket, let candidateNextHopNodeIds, _):
            emitLog(
                tag: "RELAY [B]",
                message: "DECISION: FORWARD | TTL: \(initialPacket.ttl) -> \(updatedPacket.ttl) | Hops: \(initialPacket.hopCount) -> \(updatedPacket.hopCount) | Next Hop: \(candidateNextHopNodeIds.joined(separator: ", "))",
                level: .success,
                packetSummary: updatedPacket.getSummary(),
                nodeId: "NODE-B"
            )

            try? await Task.sleep(nanoseconds: 600_000_000)

            // HOP 2: NODE C (DESTINATION) RECEIVES PACKET
            emitLog(
                tag: "DEST [C]",
                message: "Node C received packet '\(updatedPacket.messageId)' from Node B. Evaluating destination...",
                level: .info,
                nodeId: "NODE-C"
            )
            try? await Task.sleep(nanoseconds: 400_000_000)

            let neighborsOfC = [simNodeB]
            let decisionC = engineC.processIncomingPacket(
                packet: updatedPacket,
                currentNodeId: "NODE-C",
                connectedNeighbors: neighborsOfC
            )

            switch decisionC {
            case .deliver(let packet, _):
                let routeStr = (packet.routeHistory + ["NODE-C"]).joined(separator: " -> ")
                emitLog(
                    tag: "DEST [C]",
                    message: "DECISION: DELIVER | Final Hops: \(packet.hopCount) | Path: \(routeStr) | Payload: \"\(packet.payload)\"",
                    level: .success,
                    packetSummary: packet.getSummary(),
                    nodeId: "NODE-C"
                )
                emitEvent(.packetReceived(packet: packet, fromEndpointId: "EP-B"))

            default:
                emitLog(
                    tag: "DEST [C]",
                    message: "Unexpected routing decision at Node C: \(decisionC)",
                    level: .error,
                    nodeId: "NODE-C"
                )
            }

        case .deliver(let packet, let reason):
            emitLog(tag: "RELAY [B]", message: "Node B delivered packet locally: \(reason)", level: .success)
            emitEvent(.packetReceived(packet: packet, fromEndpointId: "EP-A"))

        case .dropDuplicate(_, let reason):
            emitLog(tag: "RELAY [B]", message: "Node B dropped duplicate packet: \(reason)", level: .warning)

        case .dropTtlExpired(_, _, let reason):
            emitLog(tag: "RELAY [B]", message: "Node B dropped expired packet: \(reason)", level: .warning)

        case .dropNoRoute(_, _, let reason):
            emitLog(tag: "RELAY [B]", message: "Node B could not route packet: \(reason)", level: .error)
        }
    }

    private func emitLog(
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
        emitEvent(.logEmitted(entry: entry))
    }

    private func emitEvent(_ event: TransportEvent) {
        onEvent?(event)
    }
}
