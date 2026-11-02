package com.meshlink.app.data.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.meshlink.app.data.transport.framing.FrameReassembler
import com.meshlink.app.data.transport.framing.MeshLinkFrame
import com.meshlink.app.domain.crc.Crc16Ccitt
import com.meshlink.app.domain.models.BleHandshake
import com.meshlink.app.domain.models.LogLevel
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.NetworkLogEntry
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.models.NodeStatus
import com.meshlink.app.utils.JsonSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import com.meshlink.app.data.transport.framing.BleFragments
import com.meshlink.app.data.transport.framing.FragmentAssembler
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connection states for the explicit BLE Peer Connection State Machine.
 */
enum class BleConnectionState {
    DISCOVERED,
    CONNECTING,
    GATT_CONNECTED,
    SERVICE_DISCOVERY,
    CHARACTERISTICS_READY,
    NOTIFICATIONS_ENABLING,
    HANDSHAKE_SENT,
    HANDSHAKE_RECEIVED,
    READY,
    DISCONNECTING,
    DISCONNECTED,
    ERROR
}

/**
 * Isolated per-peer session state for multi-peer BLE mesh communication.
 */
class BlePeerSession(
    val device: BluetoothDevice,
    val address: String,
    var gatt: BluetoothGatt? = null,
    var isSubscribed: Boolean = false,
    var nodeId: String? = null,
    var displayName: String? = null,
    @Volatile var state: BleConnectionState = BleConnectionState.DISCOVERED,
    var negotiatedMtu: Int = 23,
    val reassembler: FragmentAssembler = FragmentAssembler(),
    @Volatile var completion: CompletableDeferred<Int>? = null,
    @Volatile var helloSent: Boolean = false,
    @Volatile var handshake: BleHandshake? = null,
    var lastSeen: Long = System.currentTimeMillis(),
    val txMutex: Mutex = Mutex()
) {
    val usableChunkSize: Int
        get() = (negotiatedMtu - 3).coerceIn(20, 512)
}

/**
 * Experimental Dual-Role BLE GATT Mesh Transport for Android (Review III).
 *
 * Core Features:
 * 1. Dual-Role simultaneous Bluetooth Low Energy (Server/Peripheral + Client/Central).
 * 2. Binary Framing with CRC-16-CCITT packet integrity verification.
 * 3. Rejection of corrupted frames before reaching RoutingEngine or SeenMessageManager.
 * 4. Explicit 12-state Connection State Machine with application-level HELLO handshake.
 * 5. Dynamic negotiated MTU tracking and MTU-aware chunk fragmentation per peer.
 * 6. Per-peer serialized write queue with mutex to prevent GATT write collisions on relays.
 * 7. Intentional CRC error injection test mode for live demonstration.
 */
class AndroidBleGattTransport(
    private val context: Context,
    private val maxConnections: Int = 4
) : MeshTransport {

    companion object {
        private const val TAG = "AndroidBleTransport"

        // Standard MeshLink Protocol UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("a17e0001-83b8-4f28-9e44-1c9c8de4a630")
        val TX_CHAR_UUID: UUID = UUID.fromString("a17e0002-83b8-4f28-9e44-1c9c8de4a630") // Client Write / Server Receive
        val RX_CHAR_UUID: UUID = UUID.fromString("a17e0003-83b8-4f28-9e44-1c9c8de4a630") // Server Notify / Client Receive
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }

    private val transportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 512)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var localNode: Node? = null
    private var watchdog: kotlinx.coroutines.Job? = null

    // Peripheral (GATT Server) components
    private var gattServer: BluetoothGattServer? = null
    private var leAdvertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false

    // Central (GATT Client) components
    private var leScanner: BluetoothLeScanner? = null
    private var isScanning = false

    // Per-Peer Sessions: [DeviceAddress -> BlePeerSession]
    private val peerSessions = ConcurrentHashMap<String, BlePeerSession>()

    // Node ID <-> Address mappings
    private val endpointToNodeMap = ConcurrentHashMap<String, Node>()
    private val nodeToEndpointMap = ConcurrentHashMap<String, String>()

    // Test mode: Intentionally corrupt the next outgoing frame's CRC
    @Volatile private var demoTopology = com.meshlink.app.domain.network.DemoTopology()
    fun configureDemo(topology: com.meshlink.app.domain.network.DemoTopology) {
        check(!_isRunning.value) { "Stop BLE before changing its permitted neighbors" }
        demoTopology = topology
    }
    fun isReadyPeer(nodeId: String, endpoint: String?): Boolean = peerSessions[endpoint ?: ""]?.let {
        it.state == BleConnectionState.READY && it.nodeId == nodeId && demoTopology.permits(nodeId)
    } == true
    private fun permittedCandidate(nodeId: String): Boolean = !demoTopology.enabled || demoTopology.permittedIds.any {
        it == nodeId || "discovered-${hex(token(it))}" == nodeId
    }
    private val transferId = AtomicInteger()
    private val serverTxMutex = Mutex()
    private val queued = AtomicInteger()
    private val connectionPolicy = BleConnectionPolicy(maxConnections)
    private fun token(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(8).toByteArray()
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private val corruptNextCrc = AtomicBoolean(false)
    private val corruptMessages = ConcurrentHashMap<String, Long>()

    fun setCorruptNextCrc(corrupt: Boolean) {
        corruptNextCrc.set(corrupt)
        if (corrupt) {
            emitLog(
                tag = "TEST-MODE",
                message = "CRC Error Simulation ARMED: Next transmitted frame will contain an intentional payload corruption.",
                level = LogLevel.WARNING
            )
        }
    }

    // =========================================================================
    // PERMISSIONS
    // =========================================================================
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasRequiredPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) ||
                !hasPermission(Manifest.permission.BLUETOOTH_CONNECT) ||
                !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
            ) {
                return false
            }
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                !hasPermission(Manifest.permission.BLUETOOTH) ||
                !hasPermission(Manifest.permission.BLUETOOTH_ADMIN)
            ) {
                return false
            }
        }
        return true
    }

    // =========================================================================
    // LIFECYCLE
    // =========================================================================
    @SuppressLint("MissingPermission")
    override fun start(localNode: Node) {
        if (_isRunning.value) return
        this.localNode = localNode

        emitLog(
            tag = "BLE-INIT",
            message = "Starting BLE GATT Transport for '${localNode.nodeName}' [${localNode.nodeId}]",
            level = LogLevel.INFO,
            nodeId = localNode.nodeId
        )

        if (!hasRequiredPermissions()) {
            emitLog(
                tag = "BLE-ERROR",
                message = "Missing required Bluetooth permissions. Please grant Bluetooth permissions in Android Settings.",
                level = LogLevel.ERROR
            )
            emitEvent(TransportEvent.ErrorOccurred("Bluetooth permissions not granted"))
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            emitLog(
                tag = "BLE-ERROR",
                message = "Bluetooth is disabled or unavailable on this device",
                level = LogLevel.ERROR
            )
            emitEvent(TransportEvent.ErrorOccurred("Bluetooth is disabled"))
            return
        }

        _isRunning.value = true
        try { startGattServer(); startScanning() } catch (e: Exception) {
            emitEvent(TransportEvent.ErrorOccurred("BLE startup failed: ${e.message}")); stop(); return
        }
        watchdog = transportScope.launch {
            while (_isRunning.value) {
                delay(5_000)
                if (!hasRequiredPermissions() || bluetoothAdapter?.isEnabled != true) {
                    emitEvent(TransportEvent.ErrorOccurred("Bluetooth disabled or permission revoked; restart networking after enabling"))
                    stop(); break
                }
                connectionPolicy.stale().filter { !peerSessions.containsKey(it) }.forEach { emitEvent(TransportEvent.NodeLost(it)) }
                peerSessions.values.forEach { session ->
                    session.reassembler.expire()
                    if (session.state != BleConnectionState.READY && System.currentTimeMillis() - session.lastSeen > 30_000) {
                        disconnectNode(session.nodeId ?: session.address)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        _isRunning.value = false
        watchdog?.cancel()
        watchdog = null
        connectionPolicy.reset()

        try {
            stopScanning()
            stopAdvertising()

            for ((_, session) in peerSessions) {
                try {
                    session.gatt?.disconnect()
                    session.gatt?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing client GATT: ${e.message}")
                }
            }
            peerSessions.clear()

            try {
                gattServer?.close()
                gattServer = null
            } catch (e: Exception) {
                Log.w(TAG, "Error closing GATT server: ${e.message}")
            }

            endpointToNodeMap.clear()
            nodeToEndpointMap.clear()

            emitLog(
                tag = "BLE-STOP",
                message = "BLE GATT transport stopped. All peer sessions closed.",
                level = LogLevel.INFO
            )
        } catch (e: Exception) {
            emitLog(
                tag = "BLE-ERROR",
                message = "Error during transport shutdown: ${e.message}",
                level = LogLevel.WARNING
            )
        }
    }

    // =========================================================================
    // GATT SERVER (PERIPHERAL MODE)
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        val manager = bluetoothManager ?: return

        gattServer = (manager.openGattServer(context, gattServerCallback) ?: error("GATT server unavailable")).apply {
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            // RX Characteristic (Server -> Client notification / read)
            val rxChar = BluetoothGattCharacteristic(
                RX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_INDICATE or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            val cccd = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            rxChar.addDescriptor(cccd)
            service.addCharacteristic(rxChar)

            // TX Characteristic (Client -> Server write)
            val txChar = BluetoothGattCharacteristic(
                TX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(txChar)

            check(addService(service)) { "GATT service registration rejected" }
            emitLog(tag = "BLE-PERIPHERAL", message = "GATT Service published [$SERVICE_UUID]", level = LogLevel.SUCCESS)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS && _isRunning.value) try { localNode?.let { startAdvertising(it) } } catch (e: Exception) { emitEvent(TransportEvent.ErrorOccurred("Advertising failed: ${e.message}")); stop() }
            else { emitEvent(TransportEvent.ErrorOccurred("GATT service publication failed: $status")); stop() }
        }
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            peerSessions[device.address]?.completion?.complete(status)
        }
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (!_isRunning.value) return
            if (newState == BluetoothProfile.STATE_CONNECTED && !peerSessions.containsKey(address) && peerSessions.size >= maxConnections) {
                gattServer?.cancelConnection(device); return
            }
            val session = peerSessions[address] ?: if (newState == BluetoothProfile.STATE_CONNECTED) {
                BlePeerSession(device = device, address = address).also { peerSessions[address] = it }
            } else return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                session.state = BleConnectionState.GATT_CONNECTED
                emitLog(
                    tag = "BLE-STATE",
                    message = "Central peer connected to GATT Server: ${device.name ?: "Unknown"} [$address]. State: GATT_CONNECTED",
                    level = LogLevel.INFO
                )
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                session.completion?.complete(BluetoothGatt.GATT_FAILURE)
                session.gatt?.close()
                session.state = BleConnectionState.DISCONNECTED
                peerSessions.remove(address)
                val node = endpointToNodeMap.remove(address)
                val nodeId = node?.nodeId ?: session.nodeId ?: address
                nodeToEndpointMap.remove(nodeId)

                emitLog(
                    tag = "BLE-DISCONNECT",
                    message = "Central peer disconnected from GATT Server: ${node?.nodeName ?: address} [$nodeId]",
                    level = LogLevel.WARNING,
                    nodeId = nodeId
                )
                emitEvent(TransportEvent.NodeDisconnected(nodeId, address))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val session = peerSessions[device.address]
            if (session != null) {
                if (mtu >= 23) session.negotiatedMtu = mtu
                emitLog(
                    tag = "BLE-MTU",
                    message = "Server negotiated MTU $mtu with [${device.address}] (Usable chunk payload: ${session.usableChunkSize} bytes)",
                    level = LogLevel.INFO
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val valid = descriptor.uuid == CCCD_UUID && !preparedWrite && offset == 0 &&
                (value?.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == true || value?.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) == true)
            if (responseNeeded) gattServer?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            if (!valid) return
            if (descriptor.uuid == CCCD_UUID) {
                val isSubscribed = value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                val session = peerSessions[device.address] ?: return
                session.isSubscribed = isSubscribed

                if (isSubscribed) {
                    session.state = BleConnectionState.CHARACTERISTICS_READY
                    emitLog(
                        tag = "BLE-NOTIFY",
                        message = "Central peer [${device.address}] subscribed to mesh notifications. State: CHARACTERISTICS_READY",
                        level = LogLevel.INFO
                    )
                    // Peripheral sends HELLO handshake to Central
                    sendHandshakeToPeer(session)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val valid = !preparedWrite && offset == 0 && characteristic.uuid == TX_CHAR_UUID && value != null
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            }
            if (!valid) return
            if (characteristic.uuid == TX_CHAR_UUID && value != null) {
                val session = peerSessions[device.address] ?: return
                handleIncomingRawChunk(value, session)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val responseData = localNode?.let { "${it.nodeName}|${it.nodeId}".toByteArray(StandardCharsets.UTF_8) } ?: byteArrayOf()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseData)
        }
    }

    // =========================================================================
    // BLE ADVERTISING (PERIPHERAL MODE)
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startAdvertising(node: Node) {
        val adapter = bluetoothAdapter ?: return
        leAdvertiser = adapter.bluetoothLeAdvertiser

        if (leAdvertiser == null) {
            emitLog(
                tag = "BLE-ADVERTISE",
                message = "BLE Advertising not supported on this device hardware",
                level = LogLevel.WARNING
            )
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val advName = token(node.nodeId)
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // Keep within 31-byte advertising packet limit
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanResponseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), advName)
            .build()

        leAdvertiser?.startAdvertising(settings, advertiseData, scanResponseData, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        if (isAdvertising) {
            try {
                leAdvertiser?.stopAdvertising(advertiseCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping advertising: ${e.message}")
            }
            isAdvertising = false
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            emitLog(
                tag = "BLE-ADVERTISE",
                message = "BLE advertising started successfully for Service [$SERVICE_UUID]",
                level = LogLevel.SUCCESS
            )
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            emitEvent(TransportEvent.ErrorOccurred("BLE advertising failed: $errorCode"))
            stop()
            emitLog(
                tag = "BLE-ADVERTISE",
                message = "BLE advertising failed with error code $errorCode",
                level = LogLevel.ERROR
            )
        }
    }

    // =========================================================================
    // BLE SCANNING (CENTRAL MODE)
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val adapter = bluetoothAdapter ?: return
        leScanner = adapter.bluetoothLeScanner

        if (leScanner == null) {
            emitLog(tag = "BLE-SCAN", message = "Bluetooth LE Scanner unavailable", level = LogLevel.ERROR)
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        isScanning = true
        emitLog(tag = "BLE-SCAN", message = "BLE scanning started for Service [$SERVICE_UUID]", level = LogLevel.SUCCESS)
        leScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (isScanning) {
            try {
                leScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping scanner: ${e.message}")
            }
            isScanning = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address

            if (!_isRunning.value) return
            val remoteToken = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            if (remoteToken.size != 8 || !demoTopology.permitsAdvertisement(remoteToken)) return
            val localToken = token(localNode?.nodeId ?: return)
            if (remoteToken.contentEquals(localToken)) return
            if (peerSessions.containsKey(address)) return
            if (peerSessions.size >= maxConnections) return
            if (!connectionPolicy.mayConnect(address, peerSessions.size)) return
            val node = Node(nodeId = "discovered-${hex(remoteToken)}", nodeName = "MeshLink peer", endpointId = address, status = NodeStatus.DISCOVERED)
            if (connectionPolicy.discovered(address)) emitEvent(TransportEvent.NodeDiscovered(node))
            // Stable advertised identity hash selects one central for each pair.
            if (hex(localToken) < hex(remoteToken)) connectToNode(node)

        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            emitEvent(TransportEvent.ErrorOccurred("BLE scanning failed: $errorCode"))
            stop()
            emitLog(tag = "BLE-SCAN", message = "Scan failed with error code $errorCode", level = LogLevel.ERROR)
        }
    }

    // =========================================================================
    // GATT CLIENT CONNECTION (CENTRAL MODE)
    // =========================================================================
    @SuppressLint("MissingPermission")
    @Synchronized override fun connectToNode(targetNode: Node) {
        if (!permittedCandidate(targetNode.nodeId)) {
            emitLog("DEMO", "Forbidden direct connection blocked: ${targetNode.nodeId}", LogLevel.WARNING)
            return
        }
        val address = targetNode.endpointId ?: run {
            emitLog(
                tag = "BLE-CONNECT",
                message = "Cannot connect to '${targetNode.nodeName}': Missing BLE endpoint address",
                level = LogLevel.ERROR
            )
            return
        }

        if (!_isRunning.value || peerSessions.containsKey(address) || peerSessions.size >= maxConnections) return
        if (!connectionPolicy.mayConnect(address, peerSessions.size)) return
        connectionPolicy.attempted(address)
        val session = peerSessions.computeIfAbsent(address) {
            val device = bluetoothAdapter?.getRemoteDevice(address)
                ?: throw IllegalStateException("Device $address not found")
            BlePeerSession(device = device, address = address, nodeId = targetNode.nodeId, displayName = targetNode.nodeName)
        }

        session.state = BleConnectionState.CONNECTING
        emitEvent(TransportEvent.ConnectionInitiated(address, targetNode.nodeName))
        emitLog(
            tag = "BLE-STATE",
            message = "Initiating BLE GATT connection to '${targetNode.nodeName}' [${targetNode.nodeId}] ($address). State: CONNECTING",
            level = LogLevel.INFO,
            nodeId = targetNode.nodeId
        )

        val gatt = try {
            session.device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            peerSessions.remove(address)
            emitEvent(TransportEvent.ErrorOccurred("Connection failed: ${e.message}"))
            return
        }
        session.gatt = gatt
    }

    @SuppressLint("MissingPermission")
    override fun disconnectNode(nodeId: String) {
        val address = nodeToEndpointMap[nodeId] ?: peerSessions.values.find { it.nodeId == nodeId }?.address ?: nodeId
        val session = peerSessions.remove(address)
        if (session != null) {
            session.state = BleConnectionState.DISCONNECTING
            try {
                session.completion?.complete(BluetoothGatt.GATT_FAILURE)
                if (session.gatt == null) gattServer?.cancelConnection(session.device)
                session.gatt?.disconnect()
                session.gatt?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting GATT client: ${e.message}")
            }
        }
        endpointToNodeMap.remove(address)
        nodeToEndpointMap.entries.removeAll { it.value == address }

        emitLog(
            tag = "BLE-DISCONNECT",
            message = "Disconnected from BLE peer [$nodeId]. State: DISCONNECTED",
            level = LogLevel.INFO,
            nodeId = nodeId
        )
        emitEvent(TransportEvent.NodeDisconnected(session?.nodeId ?: nodeId, address))
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            peerSessions[gatt.device.address]?.takeIf { it.gatt === gatt }?.completion?.complete(status)
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val session = peerSessions[gatt.device.address] ?: return
            if (session.gatt !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) sendHandshakeToPeer(session)
            else disconnectNode(session.nodeId ?: session.address)
        }
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            val session = peerSessions[address] ?: run { gatt.close(); return }
            if (session.gatt != null && session.gatt !== gatt) { gatt.close(); return }
            session.gatt = gatt

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                session.state = BleConnectionState.GATT_CONNECTED
                emitLog(
                    tag = "BLE-STATE",
                    message = "GATT connected to peer [$address]. State: GATT_CONNECTED. Requesting MTU 512...",
                    level = LogLevel.SUCCESS
                )
                if (!gatt.requestMtu(512)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                session.completion?.complete(BluetoothGatt.GATT_FAILURE)
                session.gatt?.close()
                session.state = BleConnectionState.DISCONNECTED
                peerSessions.remove(address)
                val node = endpointToNodeMap.remove(address)
                val nodeId = node?.nodeId ?: session.nodeId ?: address
                nodeToEndpointMap.remove(nodeId)

                emitLog(
                    tag = "BLE-DISCONNECT",
                    message = "Disconnected from peer '${node?.nodeName ?: address}' [$nodeId]. State: DISCONNECTED",
                    level = LogLevel.WARNING,
                    nodeId = nodeId
                )
                emitEvent(TransportEvent.NodeDisconnected(nodeId, address))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val session = peerSessions[gatt.device.address]
            if (session != null) {
                if (mtu >= 23) session.negotiatedMtu = mtu
                emitLog(
                    tag = "BLE-MTU",
                    message = "Client negotiated MTU $mtu on [${gatt.device.address}] (Usable chunk payload: ${session.usableChunkSize} bytes)",
                    level = LogLevel.INFO
                )
            }
            session?.state = BleConnectionState.SERVICE_DISCOVERY
            emitLog(tag = "BLE-STATE", message = "Discovering GATT services on [${gatt.device.address}]...", level = LogLevel.INFO)
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            val session = peerSessions[address]?.takeIf { it.gatt === gatt } ?: return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                session.state = BleConnectionState.ERROR
                emitLog(tag = "BLE-ERROR", message = "Service discovery failed on peer [$address]", level = LogLevel.ERROR)
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                session.state = BleConnectionState.ERROR
                emitLog(tag = "BLE-ERROR", message = "MeshLink service [$SERVICE_UUID] not found on peer [$address]", level = LogLevel.WARNING)
                return
            }

            session.state = BleConnectionState.CHARACTERISTICS_READY
            emitLog(tag = "BLE-STATE", message = "Discovered MeshLink Service on [$address]. State: CHARACTERISTICS_READY", level = LogLevel.SUCCESS)

            val tx = service.getCharacteristic(TX_CHAR_UUID)
            val rxChar = service.getCharacteristic(RX_CHAR_UUID)
            if (tx == null || tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 || rxChar == null || rxChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE == 0 || rxChar.getDescriptor(CCCD_UUID) == null) {
                emitEvent(TransportEvent.ErrorOccurred("Peer does not support MeshLink write/indication protocol"))
                disconnectNode(session.address); return
            }
            run {
                session.state = BleConnectionState.NOTIFICATIONS_ENABLING
                if (!gatt.setCharacteristicNotification(rxChar, true)) { disconnectNode(session.address); return }
                val descriptor = rxChar.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                    emitLog(tag = "BLE-NOTIFY", message = "Subscribed to RX notifications on peer [$address]. State: NOTIFICATIONS_ENABLING", level = LogLevel.SUCCESS)
                }
            }

            // HELLO starts only after successful CCCD write callback.
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            val session = peerSessions[gatt.device.address]?.takeIf { it.gatt === gatt } ?: return
            handleIncomingRawChunk(value, session)
        }

        // For Android 13+ (API 33)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val session = peerSessions[gatt.device.address]?.takeIf { it.gatt === gatt } ?: return
            handleIncomingRawChunk(value, session)
        }
    }

    // =========================================================================
    // APPLICATION-LEVEL HELLO HANDSHAKE
    // =========================================================================
    private fun sendHandshakeToPeer(session: BlePeerSession) {
        val node = localNode ?: return
        val handshake = BleHandshake(
            type = "HANDSHAKE",
            protocolVersion = 1,
            nodeId = node.nodeId,
            displayName = node.nodeName,
            isRelayCapable = true
        )
        val jsonStr = BleHandshake.toJson(handshake)
        val payloadBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
        val framedBytes = MeshLinkFrame.encode(MeshLinkFrame.FRAME_TYPE_HELLO_HANDSHAKE, payloadBytes)

        if (session.state != BleConnectionState.READY) session.state = BleConnectionState.HANDSHAKE_SENT
        emitLog(
            tag = "BLE-HANDSHAKE",
            message = "Transmitting HELLO handshake to [${session.address}] (${handshake.displayName} [${handshake.nodeId}]). State: HANDSHAKE_SENT",
            level = LogLevel.INFO
        )

        transportScope.launch {
            if (sendFramedBytesToSession(session, framedBytes)) {
                session.helloSent = true
                markReady(session)
            }
        }
    }

    @Synchronized private fun markReady(session: BlePeerSession) {
        val hello = session.handshake ?: return
        if (!demoTopology.permits(hello.nodeId)) { disconnectNode(session.address); return }
        if (!session.helloSent || session.state == BleConnectionState.READY || peerSessions[session.address] !== session) return
        session.state = BleConnectionState.READY
        val node = Node(hello.nodeId, hello.displayName, status = NodeStatus.CONNECTED, endpointId = session.address)
        endpointToNodeMap[session.address] = node
        nodeToEndpointMap[hello.nodeId] = session.address
        emitEvent(TransportEvent.NodeConnected(node))
    }

    // =========================================================================
    // TRANSMISSION & CRC FRAMING PIPELINE
    // =========================================================================
    override fun sendPacket(packet: MessagePacket, targetEndpointId: String): Boolean {
        val resolvedEndpoint = nodeToEndpointMap[targetEndpointId] ?: targetEndpointId
        val session = peerSessions[resolvedEndpoint]

        if (session == null || session.state != BleConnectionState.READY || !demoTopology.permits(session.nodeId ?: "") || queued.get() >= 64) return false
        val jsonStr = JsonSerializer.packetToJson(packet)
        val payloadBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)

        // Check if intentional CRC corruption is triggered for Review III demo
        corruptMessages.entries.removeAll { it.value < System.currentTimeMillis() }
        if (packet.control == null && packet.ackFor == null && corruptNextCrc.getAndSet(false)) {
            if (corruptMessages.size >= 64) corruptMessages.remove(corruptMessages.keys.first())
            corruptMessages[packet.messageId] = System.currentTimeMillis() + 120_000
        }
        val shouldCorrupt = corruptMessages.containsKey(packet.messageId)

        val framedBytes = try { MeshLinkFrame.encode(
            frameType = MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET,
            payload = payloadBytes,
            corruptForTesting = shouldCorrupt
        ) } catch (e: IllegalArgumentException) { emitEvent(TransportEvent.ErrorOccurred("Frame too large: ${e.message}")); return false }

        val computedCrc = Crc16Ccitt.compute(framedBytes, 0, framedBytes.size - MeshLinkFrame.CRC_SIZE)

        queued.incrementAndGet()
        transportScope.launch {
            try {
                emitLog("BLE-TX", "Sending ${framedBytes.size}B to ${session.address}; CRC ${Crc16Ccitt.formatHex(computedCrc)}; corrupted=$shouldCorrupt")
                if (sendFramedBytesToSession(session, framedBytes)) emitEvent(TransportEvent.PacketSent(packet, resolvedEndpoint))
            } finally { queued.decrementAndGet() }
        }

        return true
    }

    override fun broadcastPacket(packet: MessagePacket, excludeEndpointIds: Set<String>): Int {
        return peerSessions.values.count { it.state == BleConnectionState.READY && it.address !in excludeEndpointIds && sendPacket(packet, it.address) }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun sendFramedBytesToSession(session: BlePeerSession, data: ByteArray): Boolean {
        return try {
            session.txMutex.withLock {
                check(_isRunning.value && peerSessions[session.address] === session) { "Stale connection session" }
                val fragments = BleFragments.split(data, session.usableChunkSize, transferId.incrementAndGet())
                for (chunk in fragments) {
                    suspend fun operation() {
                        val completion = CompletableDeferred<Int>()
                        session.completion = completion
                        try {
                            val gatt = session.gatt
                            val accepted = if (gatt != null) {
                                val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(TX_CHAR_UUID) ?: error("Missing TX characteristic")
                                if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                                else {
                                    characteristic.value = chunk
                                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                    gatt.writeCharacteristic(characteristic)
                                }
                            } else {
                                check(session.isSubscribed) { "Peer has not subscribed" }
                                val server = gattServer ?: error("GATT server closed")
                                val characteristic = server.getService(SERVICE_UUID)?.getCharacteristic(RX_CHAR_UUID) ?: error("Missing RX characteristic")
                                if (Build.VERSION.SDK_INT >= 33) server.notifyCharacteristicChanged(session.device, characteristic, true, chunk) == BluetoothStatusCodes.SUCCESS
                                else { characteristic.value = chunk; server.notifyCharacteristicChanged(session.device, characteristic, true) }
                            }
                            check(accepted) { "GATT operation rejected" }
                            check(withTimeout(8_000) { completion.await() } == BluetoothGatt.GATT_SUCCESS) { "GATT operation failed" }
                        } finally { session.completion = null }
                    }
                    if (session.gatt == null) serverTxMutex.withLock { operation() } else operation()
                }
                emitLog("BLE-TX", "Completed ${fragments.size} fragments; next-hop ATT receipt, not final delivery")
            }
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            emitEvent(TransportEvent.ErrorOccurred("BLE transfer failed: ${e.message}"))
            if (peerSessions[session.address] === session) disconnectNode(session.address)
            false
        }
    }

    // =========================================================================
    // INBOUND REASSEMBLY & CRC VERIFICATION PIPELINE
    // =========================================================================
    private fun handleIncomingRawChunk(chunk: ByteArray, session: BlePeerSession) {
        val bytes = try { session.reassembler.accept(chunk) } catch (e: IllegalArgumentException) {
            emitLog("FRAGMENT", "Rejected fragment: ${e.message}", LogLevel.WARNING); return
        } catch (e: IllegalStateException) {
            emitLog("FRAGMENT", "Rejected transfer: ${e.message}", LogLevel.WARNING); return
        } ?: return
        val frameResults = listOf(MeshLinkFrame.decode(bytes))

        for (result in frameResults) {
            when (result) {
                is MeshLinkFrame.FrameResult.Success -> {
                    val frameType = result.frameType
                    val payloadBytes = result.payload
                    val crcHex = Crc16Ccitt.formatHex(result.crc)

                    when (frameType) {
                        MeshLinkFrame.FRAME_TYPE_HELLO_HANDSHAKE -> {
                            val jsonStr = String(payloadBytes, StandardCharsets.UTF_8)
                            val handshake = BleHandshake.fromJson(jsonStr)
                            if (handshake != null && handshake.protocolVersion == 1 && handshake.nodeId != localNode?.nodeId && handshake.nodeId.length in 1..80 && handshake.displayName.length <= 64) {
                                if (!demoTopology.permits(handshake.nodeId)) {
                                    emitLog("DEMO", "Incoming forbidden peer rejected: ${handshake.nodeId}", LogLevel.WARNING)
                                    disconnectNode(session.address); return
                                }
                                val existing = nodeToEndpointMap[handshake.nodeId]
                                if (existing != null && existing != session.address) { disconnectNode(session.address); return }
                                connectionPolicy.ready(session.address)
                                session.nodeId = handshake.nodeId
                                session.displayName = handshake.displayName
                                session.handshake = handshake

                                emitLog(
                                    tag = "CRC-PASS",
                                    message = "INFO [CRC][PASS] HELLO Frame Valid (CRC: $crcHex, Peer: ${handshake.displayName} [${handshake.nodeId}]). Handshake validated",
                                    level = LogLevel.SUCCESS,
                                    nodeId = handshake.nodeId
                                )

                                markReady(session)
                            } else {
                                emitLog(tag = "BLE-HANDSHAKE", message = "Invalid HELLO payload from [${session.address}]", level = LogLevel.WARNING)
                            }
                        }

                        MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET -> {
                            val jsonStr = String(payloadBytes, StandardCharsets.UTF_8)
                            val packet = JsonSerializer.jsonToPacket(jsonStr)

                            if (packet != null && session.state == BleConnectionState.READY && demoTopology.permits(session.nodeId ?: "")) {
                                emitLog(
                                    tag = "CRC-PASS",
                                    message = "INFO [CRC][PASS] messageId=${packet.messageId} received=$crcHex calculated=$crcHex length=${payloadBytes.size}B from [${session.address}]",
                                    level = LogLevel.SUCCESS,
                                    packetSummary = packet.getSummary(),
                                    nodeId = packet.sourceId
                                )
                                // Pass ONLY CRC-valid packets to upper repository / routing engine
                                emitEvent(TransportEvent.PacketReceived(packet, session.address))
                            } else {
                                emitLog(
                                    tag = "BLE-RX",
                                    message = "Failed to deserialize JSON packet after CRC pass from [${session.address}]",
                                    level = LogLevel.ERROR
                                )
                            }
                        }

                        MeshLinkFrame.FRAME_TYPE_HEARTBEAT -> {
                            emitLog(tag = "BLE-HEARTBEAT", message = "Heartbeat ping received from [${session.address}]", level = LogLevel.INFO)
                        }
                    }
                }

                is MeshLinkFrame.FrameResult.CrcError -> {
                    val rxHex = Crc16Ccitt.formatHex(result.receivedCrc)
                    val calcHex = Crc16Ccitt.formatHex(result.calculatedCrc)

                    // REJECT FRAME: Discard completely and DO NOT forward to RoutingEngine
                    emitLog(
                        tag = "CRC-FAIL",
                        message = "WARN [CRC][FAIL] Frame Corrupted! received=$rxHex calculated=$calcHex length=${result.payloadLength}B peer=${session.displayName ?: session.address} [${session.address}]. Packet DROPPED before routing.",
                        level = LogLevel.ERROR,
                        nodeId = session.nodeId
                    )
                }

                is MeshLinkFrame.FrameResult.CorruptedHeader -> {
                    emitLog(
                        tag = "FRAME-ERROR",
                        message = "WARN [FRAME][HEADER_ERROR] ${result.reason} on peer [${session.address}]",
                        level = LogLevel.WARNING
                    )
                }
            }
        }
    }

    // =========================================================================
    // LOGGING
    // =========================================================================
    private fun emitLog(
        tag: String,
        message: String,
        level: LogLevel = LogLevel.INFO,
        packetSummary: String? = null,
        nodeId: String? = null
    ) {
        val entry = NetworkLogEntry(
            tag = tag,
            message = message,
            level = level,
            packetSummary = packetSummary,
            relatedNodeId = nodeId
        )
        emitEvent(TransportEvent.LogEmitted(entry))
    }

    private fun emitEvent(event: TransportEvent) {
        if (!_events.tryEmit(event)) Log.e(TAG, "Transport event buffer full: ${event.javaClass.simpleName}")
    }
}
