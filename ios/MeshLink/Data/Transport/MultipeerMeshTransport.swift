import Foundation
#if canImport(MultipeerConnectivity)
import MultipeerConnectivity

public final class MultipeerMeshTransport: NSObject, MeshTransport, @unchecked Sendable {

    public static let serviceType = "meshlink-p2p"

    private let lock = NSLock()
    private var _isRunning: Bool = false
    public var isRunning: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _isRunning
    }

    public var onEvent: (@Sendable (TransportEvent) -> Void)?

    private var localPeerID: MCPeerID?
    private var session: MCSession?
    private var advertiser: MCNearbyServiceAdvertiser?
    private var browser: MCNearbyServiceBrowser?

    private var localNode: Node?
    private var peerToNodeMap: [MCPeerID: Node] = [:]
    private var nodeToPeerMap: [String: MCPeerID] = [:]

    public override init() {
        super.init()
    }

    public func start(localNode: Node) {
        lock.lock()
        self.localNode = localNode
        self._isRunning = true

        let peerID = MCPeerID(displayName: "\(localNode.nodeName)|\(localNode.nodeId)")
        self.localPeerID = peerID

        let session = MCSession(peer: peerID, securityIdentity: nil, encryptionPreference: .none)
        session.delegate = self
        self.session = session

        let discoveryInfo = ["nodeId": localNode.nodeId, "nodeName": localNode.nodeName]
        let advertiser = MCNearbyServiceAdvertiser(peer: peerID, discoveryInfo: discoveryInfo, serviceType: Self.serviceType)
        advertiser.delegate = self
        self.advertiser = advertiser

        let browser = MCNearbyServiceBrowser(peer: peerID, serviceType: Self.serviceType)
        browser.delegate = self
        self.browser = browser

        lock.unlock()

        emitLog(
            tag: "MPC-INIT",
            message: "Starting Multipeer transport for '\(localNode.nodeName)' [\(localNode.nodeId)]",
            level: .info,
            nodeId: localNode.nodeId
        )

        advertiser.startAdvertisingPeer()
        browser.startBrowsingForPeers()
    }

    public func stop() {
        lock.lock()
        _isRunning = false
        advertiser?.stopAdvertisingPeer()
        browser?.stopBrowsingForPeers()
        session?.disconnect()
        peerToNodeMap.removeAll()
        nodeToPeerMap.removeAll()
        lock.unlock()

        emitLog(tag: "MPC-STOP", message: "Multipeer transport stopped.", level: .info)
    }

    public func connectToNode(_ targetNode: Node) {
        lock.lock()
        defer { lock.unlock() }

        guard let peer = peerToNodeMap.first(where: { $0.value.nodeId == targetNode.nodeId })?.key,
              let session = self.session else {
            emitLog(tag: "MPC-CONNECT", message: "Peer for node '\(targetNode.nodeName)' not found", level: .warning)
            return
        }

        emitLog(tag: "MPC-CONNECT", message: "Inviting peer '\(targetNode.nodeName)'...", level: .info, nodeId: targetNode.nodeId)
        browser?.invitePeer(peer, to: session, withContext: nil, timeout: 15)
    }

    public func disconnectNode(nodeId: String) {
        lock.lock()
        defer { lock.unlock() }

        if let peer = nodeToPeerMap[nodeId] {
            session?.cancelConnectPeer(peer)
            peerToNodeMap.removeValue(forKey: peer)
            nodeToPeerMap.removeValue(forKey: nodeId)
            emitLog(tag: "MPC-DISCONNECT", message: "Disconnected from node [\(nodeId)]", level: .info, nodeId: nodeId)
            emitEvent(.nodeDisconnected(nodeId: nodeId, endpointId: peer.displayName))
        }
    }

    @discardableResult
    public func sendPacket(_ packet: MessagePacket, targetEndpointId: String) -> Bool {
        guard let data = PacketSerializer.packetToData(packet) else { return false }

        lock.lock()
        let targetPeer = nodeToPeerMap[targetEndpointId] ?? peerToNodeMap.first(where: { $0.key.displayName == targetEndpointId })?.key
        lock.unlock()

        guard let peer = targetPeer, let session = self.session else {
            emitLog(tag: "MPC-TX", message: "No active peer session for endpoint '\(targetEndpointId)'", level: .error)
            return false
        }

        do {
            try session.send(data, toPeers: [peer], with: .reliable)
            emitEvent(.packetSent(packet: packet, toEndpointId: peer.displayName))
            return true
        } catch {
            emitLog(tag: "MPC-TX", message: "Error sending packet: \(error.localizedDescription)", level: .error)
            return false
        }
    }

    @discardableResult
    public func broadcastPacket(_ packet: MessagePacket, excludeEndpointIds: Set<String> = []) -> Int {
        guard let data = PacketSerializer.packetToData(packet) else { return 0 }

        lock.lock()
        guard let session = self.session else { lock.unlock(); return 0 }
        let connectedPeers = session.connectedPeers.filter { !excludeEndpointIds.contains($0.displayName) }
        lock.unlock()

        if connectedPeers.isEmpty { return 0 }

        do {
            try session.send(data, toPeers: connectedPeers, with: .reliable)
            emitEvent(.packetSent(packet: packet, toEndpointId: "BROADCAST (\(connectedPeers.count) peers)"))
            return connectedPeers.count
        } catch {
            emitLog(tag: "MPC-TX", message: "Error broadcasting packet: \(error.localizedDescription)", level: .error)
            return 0
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

extension MultipeerMeshTransport: MCSessionDelegate {
    public func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
        let parts = peerID.displayName.split(separator: "|")
        let name = parts.first.map(String.init) ?? peerID.displayName
        let nodeId = parts.count > 1 ? String(parts[1]) : "MPC-\(peerID.displayName)"

        switch state {
        case .connected:
            let node = Node(nodeId: nodeId, nodeName: name, status: .connected, endpointId: peerID.displayName)
            lock.lock()
            peerToNodeMap[peerID] = node
            nodeToPeerMap[nodeId] = peerID
            lock.unlock()

            emitLog(tag: "MPC-CONNECT", message: "Connected to peer '\(name)' [\(nodeId)]", level: .success, nodeId: nodeId)
            emitEvent(.nodeConnected(node: node))

        case .connecting:
            emitLog(tag: "MPC-CONNECT", message: "Connecting to peer '\(name)'...", level: .info, nodeId: nodeId)

        case .notConnected:
            lock.lock()
            peerToNodeMap.removeValue(forKey: peerID)
            nodeToPeerMap.removeValue(forKey: nodeId)
            lock.unlock()

            emitLog(tag: "MPC-DISCONNECT", message: "Peer '\(name)' disconnected", level: .warning, nodeId: nodeId)
            emitEvent(.nodeDisconnected(nodeId: nodeId, endpointId: peerID.displayName))

        @unknown default:
            break
        }
    }

    public func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {
        if let packet = PacketSerializer.dataToPacket(data) {
            emitLog(
                tag: "MPC-RX",
                message: "Received packet '\(packet.messageId)' from peer '\(peerID.displayName)'",
                level: .info,
                packetSummary: packet.getSummary(),
                nodeId: packet.sourceId
            )
            emitEvent(.packetReceived(packet: packet, fromEndpointId: peerID.displayName))
        } else {
            emitLog(tag: "MPC-RX", message: "Received malformed byte payload from '\(peerID.displayName)'", level: .warning)
        }
    }

    public func session(_ session: MCSession, didReceive stream: InputStream, withName streamName: String, fromPeer peerID: MCPeerID) {}
    public func session(_ session: MCSession, didStartReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, with progress: Progress) {}
    public func session(_ session: MCSession, didFinishReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, at localURL: URL?, withError error: Error?) {}
}

extension MultipeerMeshTransport: MCNearbyServiceAdvertiserDelegate, MCNearbyServiceBrowserDelegate {
    public func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didReceiveInvitationFromPeer peerID: MCPeerID, withContext context: Data?, invitationHandler: @escaping (Bool, MCSession?) -> Void) {
        emitLog(tag: "MPC-ADVERTISER", message: "Auto-accepting peer invitation from '\(peerID.displayName)'", level: .info)
        invitationHandler(true, self.session)
    }

    public func browser(_ browser: MCNearbyServiceBrowser, foundPeer peerID: MCPeerID, withDiscoveryInfo info: [String : String]?) {
        let nodeId = info?["nodeId"] ?? (peerID.displayName.split(separator: "|").count > 1 ? String(peerID.displayName.split(separator: "|")[1]) : "MPC-\(peerID.displayName)")
        let nodeName = info?["nodeName"] ?? (peerID.displayName.split(separator: "|").first.map(String.init) ?? peerID.displayName)

        let node = Node(nodeId: nodeId, nodeName: nodeName, status: .discovered, endpointId: peerID.displayName)

        lock.lock()
        peerToNodeMap[peerID] = node
        nodeToPeerMap[nodeId] = peerID
        lock.unlock()

        emitLog(tag: "MPC-DISCOVER", message: "Discovered nearby peer: '\(nodeName)' [\(nodeId)]", level: .info, nodeId: nodeId)
        emitEvent(.nodeDiscovered(node: node))
    }

    public func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {
        lock.lock()
        let node = peerToNodeMap.removeValue(forKey: peerID)
        if let nid = node?.nodeId {
            nodeToPeerMap.removeValue(forKey: nid)
        }
        lock.unlock()

        emitLog(tag: "MPC-DISCOVER", message: "Lost discovery of peer '\(node?.nodeName ?? peerID.displayName)'", level: .info)
        emitEvent(.nodeLost(endpointId: peerID.displayName))
    }
}
#endif
