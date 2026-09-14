import Foundation
import CoreBluetooth

/**
 * Production-ready cross-platform Peer-to-Peer Transport for iOS using CoreBluetooth (BLE).
 *
 * Runs Dual-Role simultaneous Bluetooth Low Energy:
 * 1. CBPeripheralManager: Advertises the MeshLink Service UUID and accepts incoming peer connections, writes, and subscriptions.
 * 2. CBCentralManager: Scans for nearby MeshLink peers, connects to them, and discovers TX/RX GATT characteristics.
 *
 * Wire-compatible with Android AndroidBleGattTransport.
 */
public final class CoreBluetoothTransport: NSObject, MeshTransport, @unchecked Sendable {

    public static let serviceUUID = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    public static let txCharUUID  = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Client Write / Server Receive
    public static let rxCharUUID  = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Server Notify / Client Receive

    private let queue = DispatchQueue(label: "com.meshlink.ble.transport", qos: .userInitiated)
    private let lock = NSLock()

    private var _isRunning: Bool = false
    public var isRunning: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _isRunning
    }

    public var onEvent: (@Sendable (TransportEvent) -> Void)?

    private var centralManager: CBCentralManager?
    private var peripheralManager: CBPeripheralManager?

    private var localNode: Node?
    private var rxCharacteristic: CBMutableCharacteristic?

    // Discovered & Connected Peripherals
    private var discoveredPeripherals: [UUID: CBPeripheral] = [:]
    private var connectedPeripherals: [UUID: CBPeripheral] = [:]
    private var peripheralTxCharacteristics: [UUID: CBCharacteristic] = [:]

    // Subscribed Centrals (when acting as peripheral)
    private var subscribedCentrals: [CBCentral] = []

    // Endpoint ID <-> Node mappings
    private var endpointToNodeMap: [String: Node] = [:]
    private var nodeToEndpointMap: [String: String] = [:]

    // Inbound frame buffers for reassembly: [EndpointId: Data]
    private var receiveBuffers: [String: Data] = [:]

    public override init() {
        super.init()
    }

    // =========================================================================
    // LIFECYCLE CONTROLS
    // =========================================================================
    public func start(localNode: Node) {
        lock.lock()
        self.localNode = localNode
        self._isRunning = true
        lock.unlock()

        emitLog(
            tag: "BLE-INIT",
            message: "Starting CoreBluetooth BLE mesh transport for local node '\(localNode.nodeName)' [\(localNode.nodeId)]",
            level: .info,
            nodeId: localNode.nodeId
        )

        queue.async { [weak self] in
            guard let self = self else { return }
            self.centralManager = CBCentralManager(delegate: self, queue: self.queue)
            self.peripheralManager = CBPeripheralManager(delegate: self, queue: self.queue)
        }
    }

    public func stop() {
        lock.lock()
        _isRunning = false
        lock.unlock()

        queue.async { [weak self] in
            guard let self = self else { return }
            self.centralManager?.stopScan()
            for (_, peripheral) in self.connectedPeripherals {
                self.centralManager?.cancelPeripheralConnection(peripheral)
            }
            self.peripheralManager?.stopAdvertising()
            self.peripheralManager?.removeAllServices()

            self.discoveredPeripherals.removeAll()
            self.connectedPeripherals.removeAll()
            self.peripheralTxCharacteristics.removeAll()
            self.subscribedCentrals.removeAll()
            self.endpointToNodeMap.removeAll()
            self.nodeToEndpointMap.removeAll()
            self.receiveBuffers.removeAll()

            self.emitLog(tag: "BLE-STOP", message: "CoreBluetooth BLE transport stopped. All peer links closed.", level: .info)
        }
    }

    public func connectToNode(_ targetNode: Node) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard let endpointId = targetNode.endpointId, let uuid = UUID(uuidString: endpointId) else {
                self.emitLog(
                    tag: "BLE-CONNECT",
                    message: "Cannot connect to '\(targetNode.nodeName)': Missing valid BLE endpoint UUID",
                    level: .error
                )
                return
            }

            guard let peripheral = self.discoveredPeripherals[uuid] else {
                self.emitLog(
                    tag: "BLE-CONNECT",
                    message: "Peripheral for '\(targetNode.nodeName)' [\(endpointId)] not in discovery cache",
                    level: .warning
                )
                return
            }

            self.emitLog(
                tag: "BLE-CONNECT",
                message: "Initiating BLE connection to '\(targetNode.nodeName)' [\(targetNode.nodeId)]...",
                level: .info,
                nodeId: targetNode.nodeId
            )
            self.centralManager?.connect(peripheral, options: nil)
        }
    }

    public func disconnectNode(nodeId: String) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard let endpointId = self.nodeToEndpointMap[nodeId], let uuid = UUID(uuidString: endpointId) else {
                return
            }
            if let peripheral = self.connectedPeripherals[uuid] {
                self.centralManager?.cancelPeripheralConnection(peripheral)
            }
            self.endpointToNodeMap.removeValue(forKey: endpointId)
            self.nodeToEndpointMap.removeValue(forKey: nodeId)
            self.receiveBuffers.removeValue(forKey: endpointId)
            self.emitLog(tag: "BLE-DISCONNECT", message: "Disconnected from node ID [\(nodeId)]", level: .info, nodeId: nodeId)
            self.emitEvent(.nodeDisconnected(nodeId: nodeId, endpointId: endpointId))
        }
    }

    // =========================================================================
    // PACKET TRANSMISSION & FRAMING
    // =========================================================================
    @discardableResult
    public func sendPacket(_ packet: MessagePacket, targetEndpointId: String) -> Bool {
        guard let data = PacketSerializer.packetToData(packet) else {
            emitLog(tag: "BLE-TX", message: "Failed to encode packet '\(packet.messageId)' to JSON bytes", level: .error)
            return false
        }

        // Frame data with newline delimiter for stream boundary
        var framedData = data
        framedData.append(contentsOf: [0x0A]) // \n delimiter

        queue.async { [weak self] in
            guard let self = self else { return }
            let resolvedEndpoint = self.nodeToEndpointMap[targetEndpointId] ?? targetEndpointId

            // Case A: Target is connected peripheral (we act as central)
            if let uuid = UUID(uuidString: resolvedEndpoint),
               let peripheral = self.connectedPeripherals[uuid],
               let txChar = self.peripheralTxCharacteristics[uuid] {
                self.transmitDataInChunks(framedData, to: peripheral, characteristic: txChar)
                self.emitLog(
                    tag: "BLE-TX",
                    message: "Sent packet '\(packet.messageId)' via BLE GATT Write to [\(resolvedEndpoint)]",
                    level: .info,
                    packetSummary: packet.getSummary(),
                    nodeId: packet.destinationId
                )
                self.emitEvent(.packetSent(packet: packet, toEndpointId: resolvedEndpoint))
                return
            }

            // Case B: Target is subscribed central (we act as peripheral notifying centrals)
            if let rxChar = self.rxCharacteristic, !self.subscribedCentrals.isEmpty {
                self.notifyDataInChunks(framedData, to: nil, characteristic: rxChar)
                self.emitLog(
                    tag: "BLE-TX",
                    message: "Sent packet '\(packet.messageId)' via BLE GATT Notify to subscribed centrals",
                    level: .info,
                    packetSummary: packet.getSummary(),
                    nodeId: packet.destinationId
                )
                self.emitEvent(.packetSent(packet: packet, toEndpointId: "SUBSCRIBED-CENTRALS"))
                return
            }

            self.emitLog(
                tag: "BLE-TX",
                message: "Target endpoint '\(resolvedEndpoint)' has no active BLE TX characteristic",
                level: .warning
            )
        }

        return true
    }

    @discardableResult
    public func broadcastPacket(_ packet: MessagePacket, excludeEndpointIds: Set<String> = []) -> Int {
        guard let data = PacketSerializer.packetToData(packet) else { return 0 }

        var framedData = data
        framedData.append(contentsOf: [0x0A])

        var sentCount = 0

        queue.async { [weak self] in
            guard let self = self else { return }
            // 1. Send to all connected peripherals
            for (uuid, peripheral) in self.connectedPeripherals {
                let epId = uuid.uuidString
                if !excludeEndpointIds.contains(epId), let txChar = self.peripheralTxCharacteristics[uuid] {
                    self.transmitDataInChunks(framedData, to: peripheral, characteristic: txChar)
                    sentCount += 1
                }
            }

            // 2. Notify all subscribed centrals
            if let rxChar = self.rxCharacteristic, !self.subscribedCentrals.isEmpty {
                self.notifyDataInChunks(framedData, to: nil, characteristic: rxChar)
                sentCount += self.subscribedCentrals.count
            }

            if sentCount > 0 {
                self.emitLog(
                    tag: "BLE-TX",
                    message: "Broadcasted packet '\(packet.messageId)' to \(sentCount) BLE peer(s)",
                    level: .info,
                    packetSummary: packet.getSummary()
                )
                self.emitEvent(.packetSent(packet: packet, toEndpointId: "BROADCAST (\(sentCount) peers)"))
            }
        }

        return sentCount
    }

    private func transmitDataInChunks(_ data: Data, to peripheral: CBPeripheral, characteristic: CBCharacteristic) {
        let maxChunkSize = peripheral.maximumWriteValueLength(for: .withoutResponse)
        let chunkSize = max(20, min(maxChunkSize, 180))

        var offset = 0
        while offset < data.count {
            let chunkLength = min(chunkSize, data.count - offset)
            let chunk = data.subdata(in: offset..<(offset + chunkLength))
            let writeType: CBCharacteristicWriteType = characteristic.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
            peripheral.writeValue(chunk, for: characteristic, type: writeType)
            offset += chunkLength
            usleep(15000) // 15ms pause between BLE write packets
        }
    }

    private func notifyDataInChunks(_ data: Data, to centrals: [CBCentral]?, characteristic: CBMutableCharacteristic) {
        guard let pm = self.peripheralManager else { return }
        let chunkSize = 180
        var offset = 0
        while offset < data.count {
            let chunkLength = min(chunkSize, data.count - offset)
            let chunk = data.subdata(in: offset..<(offset + chunkLength))
            pm.updateValue(chunk, for: characteristic, onSubscribedCentrals: centrals)
            offset += chunkLength
            usleep(15000) // 15ms pause between BLE notification packets
        }
    }

    // =========================================================================
    // INBOUND DATA PROCESSING & FRAME REASSEMBLY
    // =========================================================================
    private func handleIncomingRawBytes(_ incomingData: Data, fromEndpointId: String) {
        var buffer = receiveBuffers[fromEndpointId] ?? Data()
        buffer.append(incomingData)

        while let delimiterRange = buffer.range(of: Data([0x0A])) {
            let frameData = buffer.subdata(in: 0..<delimiterRange.lowerBound)
            buffer.removeSubrange(0..<delimiterRange.upperBound)

            if !frameData.isEmpty {
                if let packet = PacketSerializer.dataToPacket(frameData) {
                    let senderNode = endpointToNodeMap[fromEndpointId]
                    emitLog(
                        tag: "BLE-RX",
                        message: "Received packet '\(packet.messageId)' from endpoint '\(fromEndpointId)' (\(senderNode?.nodeName ?? "Peer"))",
                        level: .info,
                        packetSummary: packet.getSummary(),
                        nodeId: packet.sourceId
                    )
                    emitEvent(.packetReceived(packet: packet, fromEndpointId: fromEndpointId))
                } else {
                    emitLog(
                        tag: "BLE-RX",
                        message: "Received unrecognized packet data (\(frameData.count) bytes) from '\(fromEndpointId)'",
                        level: .warning
                    )
                }
            }
        }

        receiveBuffers[fromEndpointId] = buffer
    }

    // =========================================================================
    // LOGGING & EVENTS
    // =========================================================================
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

// =============================================================================
// CBCENTRALMANAGER DELEGATE
// =============================================================================
extension CoreBluetoothTransport: CBCentralManagerDelegate {

    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            emitLog(tag: "BLE-CENTRAL", message: "Bluetooth Central is powered ON. Starting scan...", level: .success)
            central.scanForPeripherals(withServices: [Self.serviceUUID], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        case .poweredOff:
            emitLog(tag: "BLE-CENTRAL", message: "Bluetooth is Powered OFF on device", level: .warning)
        case .unauthorized:
            emitLog(tag: "BLE-CENTRAL", message: "Bluetooth permission is UNAUTHORIZED by user", level: .error)
        case .unsupported:
            emitLog(tag: "BLE-CENTRAL", message: "Bluetooth Low Energy is unsupported on this hardware", level: .error)
        default:
            break
        }
    }

    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let endpointId = peripheral.identifier.uuidString
        discoveredPeripherals[peripheral.identifier] = peripheral

        var advName = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name ?? "Nearby BLE Peer"
        if let serviceDataDict = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
           let serviceData = serviceDataDict[Self.serviceUUID],
           let str = String(data: serviceData, encoding: .utf8) {
            advName = str
        }

        let parts = advName.split(separator: "|")
        let name = parts.first.map(String.init) ?? advName
        let nodeId = parts.count > 1 ? String(parts[1]) : "BLE-\(String(endpointId.prefix(4)))"

        if nodeId.caseInsensitiveCompare(localNode?.nodeId ?? "") == .orderedSame {
            return
        }

        let discoveredNode = Node(
            nodeId: nodeId,
            nodeName: name,
            status: .discovered,
            endpointId: endpointId,
            lastSeenTimestamp: Int64(Date().timeIntervalSince1970 * 1000)
        )

        endpointToNodeMap[endpointId] = discoveredNode
        nodeToEndpointMap[nodeId] = endpointId

        emitLog(
            tag: "BLE-DISCOVER",
            message: "Discovered MeshLink peer: '\(name)' [\(nodeId)] (RSSI: \(RSSI) dBm)",
            level: .info,
            nodeId: nodeId
        )
        emitEvent(.nodeDiscovered(node: discoveredNode))
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        let endpointId = peripheral.identifier.uuidString
        connectedPeripherals[peripheral.identifier] = peripheral
        peripheral.delegate = self
        peripheral.discoverServices([Self.serviceUUID])

        let existing = endpointToNodeMap[endpointId]
        let nodeId = existing?.nodeId ?? "BLE-\(String(endpointId.prefix(4)))"
        let name = existing?.nodeName ?? peripheral.name ?? "BLE Peer"

        let connectedNode = Node(
            nodeId: nodeId,
            nodeName: name,
            status: .connected,
            endpointId: endpointId,
            lastSeenTimestamp: Int64(Date().timeIntervalSince1970 * 1000)
        )

        endpointToNodeMap[endpointId] = connectedNode
        nodeToEndpointMap[nodeId] = endpointId

        emitLog(
            tag: "BLE-CONNECT",
            message: "Connected to BLE peer '\(name)' [\(nodeId)] (Endpoint: \(endpointId))",
            level: .success,
            nodeId: nodeId
        )
        emitEvent(.nodeConnected(node: connectedNode))
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let endpointId = peripheral.identifier.uuidString
        emitLog(
            tag: "BLE-CONNECT",
            message: "Failed to connect to peripheral [\(endpointId)]: \(error?.localizedDescription ?? "Unknown error")",
            level: .error
        )
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let endpointId = peripheral.identifier.uuidString
        connectedPeripherals.removeValue(forKey: peripheral.identifier)
        peripheralTxCharacteristics.removeValue(forKey: peripheral.identifier)

        let node = endpointToNodeMap.removeValue(forKey: endpointId)
        let nodeId = node?.nodeId ?? endpointId
        nodeToEndpointMap.removeValue(forKey: nodeId)
        receiveBuffers.removeValue(forKey: endpointId)

        emitLog(
            tag: "BLE-DISCONNECT",
            message: "Peripheral '\(node?.nodeName ?? endpointId)' [\(nodeId)] disconnected",
            level: .warning,
            nodeId: nodeId
        )
        emitEvent(.nodeDisconnected(nodeId: nodeId, endpointId: endpointId))
    }
}

// =============================================================================
// CBPERIPHERAL DELEGATE
// =============================================================================
extension CoreBluetoothTransport: CBPeripheralDelegate {

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == Self.serviceUUID {
            peripheral.discoverCharacteristics([Self.txCharUUID, Self.rxCharUUID], for: service)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }
        for char in characteristics {
            if char.uuid == Self.txCharUUID {
                peripheralTxCharacteristics[peripheral.identifier] = char
            } else if char.uuid == Self.rxCharUUID {
                peripheral.setNotifyValue(true, for: char)
            }
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        handleIncomingRawBytes(data, fromEndpointId: peripheral.identifier.uuidString)
    }
}

// =============================================================================
// CBPERIPHERALMANAGER DELEGATE (Peripheral Mode)
// =============================================================================
extension CoreBluetoothTransport: CBPeripheralManagerDelegate {

    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn {
            emitLog(tag: "BLE-PERIPHERAL", message: "Bluetooth Peripheral powered ON. Publishing GATT Service...", level: .success)

            let rxChar = CBMutableCharacteristic(
                type: Self.rxCharUUID,
                properties: [.notify, .read],
                value: nil,
                permissions: [.readable]
            )
            self.rxCharacteristic = rxChar

            let txChar = CBMutableCharacteristic(
                type: Self.txCharUUID,
                properties: [.write, .writeWithoutResponse],
                value: nil,
                permissions: [.writeable]
            )

            let service = CBMutableService(type: Self.serviceUUID, primary: true)
            service.characteristics = [rxChar, txChar]

            peripheral.add(service)

            let local = self.localNode
            let advName = "\(local?.nodeName ?? "MeshNode")|\(local?.nodeId ?? "NODE-A")"
            peripheral.startAdvertising([
                CBAdvertisementDataServiceUUIDsKey: [Self.serviceUUID],
                CBAdvertisementDataLocalNameKey: advName
            ])
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        subscribedCentrals.append(central)
        emitLog(tag: "BLE-PERIPHERAL", message: "Central [\(central.identifier.uuidString)] subscribed to mesh notifications", level: .info)
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribedCentrals.removeAll { $0.identifier == central.identifier }
        emitLog(tag: "BLE-PERIPHERAL", message: "Central [\(central.identifier.uuidString)] unsubscribed", level: .info)
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for req in requests {
            if let data = req.value {
                handleIncomingRawBytes(data, fromEndpointId: req.central.identifier.uuidString)
            }
            peripheral.respond(to: req, withResult: .success)
        }
    }
}
