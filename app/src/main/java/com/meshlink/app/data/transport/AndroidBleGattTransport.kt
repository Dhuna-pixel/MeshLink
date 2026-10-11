package com.meshlink.app.data.transport

import android.Manifest
import android.annotation.SuppressLint
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
import kotlinx.coroutines.delay
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
    var state: BleConnectionState = BleConnectionState.DISCOVERED,
    var negotiatedMtu: Int = 23,
    val reassembler: FrameReassembler = FrameReassembler(),
    val txMutex: Mutex = Mutex()
) {
    val usableChunkSize: Int
        get() = maxOf(20, negotiatedMtu - 3)
}

/**
 * Production-ready Dual-Role BLE GATT Mesh Transport for Android (Review III).
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
    private val context: Context
) : MeshTransport {

    companion object {
        private const val TAG = "AndroidBleTransport"

        // Standard MeshLink Protocol UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Client Write / Server Receive
        val RX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Server Notify / Client Receive
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }

    private val transportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var localNode: Node? = null

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
    private val corruptNextCrc = AtomicBoolean(false)

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
            if (!hasPermission(Manifest.permission.BLUETOOTH) ||
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
        this.localNode = localNode
        _isRunning.value = true

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

        startGattServer()
        startAdvertising(localNode)
        startScanning()
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        _isRunning.value = false

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

        gattServer = manager.openGattServer(context, gattServerCallback)?.apply {
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            // RX Characteristic (Server -> Client notification / read)
            val rxChar = BluetoothGattCharacteristic(
                RX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
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

            addService(service)
            emitLog(tag = "BLE-PERIPHERAL", message = "GATT Service published [$SERVICE_UUID]", level = LogLevel.SUCCESS)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            val session = peerSessions.computeIfAbsent(address) {
                BlePeerSession(device = device, address = address)
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                session.state = BleConnectionState.GATT_CONNECTED
                emitLog(
                    tag = "BLE-STATE",
                    message = "Central peer connected to GATT Server: ${device.name ?: "Unknown"} [$address]. State: GATT_CONNECTED",
                    level = LogLevel.INFO
                )
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
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
                session.negotiatedMtu = mtu
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
            if (descriptor.uuid == CCCD_UUID) {
                val isSubscribed = value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val session = peerSessions.computeIfAbsent(device.address) {
                    BlePeerSession(device = device, address = device.address)
                }
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
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
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
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }

            if (characteristic.uuid == TX_CHAR_UUID && value != null) {
                val session = peerSessions.computeIfAbsent(device.address) {
                    BlePeerSession(device = device, address = device.address)
                }
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

        val advName = "${node.nodeName}|${node.nodeId}"
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // Keep within 31-byte advertising packet limit
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanResponseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), advName.toByteArray(StandardCharsets.UTF_8))
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
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
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

            val session = peerSessions.computeIfAbsent(address) {
                BlePeerSession(device = device, address = address)
            }

            val record = result.scanRecord
            var rawName: String? = null

            val serviceData = record?.getServiceData(ParcelUuid(SERVICE_UUID))
            if (serviceData != null && serviceData.isNotEmpty()) {
                rawName = String(serviceData, StandardCharsets.UTF_8)
            }

            if (rawName.isNullOrBlank()) {
                rawName = record?.deviceName ?: device.name ?: "BLE Node"
            }

            val parts = rawName.split("|")
            val name = parts.getOrNull(0)?.ifBlank { "BLE Node" } ?: "BLE Node"
            val nodeId = if (parts.size > 1 && parts[1].isNotBlank()) parts[1] else "BLE-${address.takeLast(5).replace(":", "")}"

            // Prevent self-discovery
            if (nodeId.equals(localNode?.nodeId, ignoreCase = true)) {
                return
            }

            session.nodeId = nodeId
            session.displayName = name

            val discoveredNode = Node(
                nodeId = nodeId,
                nodeName = name,
                status = NodeStatus.DISCOVERED,
                endpointId = address,
                lastSeenTimestamp = System.currentTimeMillis()
            )

            endpointToNodeMap[address] = discoveredNode
            nodeToEndpointMap[nodeId] = address

            emitLog(
                tag = "BLE-DISCOVER",
                message = "Discovered MeshLink peer: '$name' [$nodeId] (RSSI: ${result.rssi} dBm, Addr: $address). State: DISCOVERED",
                level = LogLevel.INFO,
                nodeId = nodeId
            )
            emitEvent(TransportEvent.NodeDiscovered(discoveredNode))
        }

        override fun onScanFailed(errorCode: Int) {
            emitLog(tag = "BLE-SCAN", message = "Scan failed with error code $errorCode", level = LogLevel.ERROR)
        }
    }

    // =========================================================================
    // GATT CLIENT CONNECTION (CENTRAL MODE)
    // =========================================================================
    @SuppressLint("MissingPermission")
    override fun connectToNode(targetNode: Node) {
        val address = targetNode.endpointId ?: run {
            emitLog(
                tag = "BLE-CONNECT",
                message = "Cannot connect to '${targetNode.nodeName}': Missing BLE endpoint address",
                level = LogLevel.ERROR
            )
            return
        }

        val session = peerSessions.computeIfAbsent(address) {
            val device = bluetoothAdapter?.getRemoteDevice(address)
                ?: throw IllegalStateException("Device $address not found")
            BlePeerSession(device = device, address = address, nodeId = targetNode.nodeId, displayName = targetNode.nodeName)
        }

        session.state = BleConnectionState.CONNECTING
        emitLog(
            tag = "BLE-STATE",
            message = "Initiating BLE GATT connection to '${targetNode.nodeName}' [${targetNode.nodeId}] ($address). State: CONNECTING",
            level = LogLevel.INFO,
            nodeId = targetNode.nodeId
        )

        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            session.device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            session.device.connectGatt(context, false, gattClientCallback)
        }
        session.gatt = gatt
    }

    @SuppressLint("MissingPermission")
    override fun disconnectNode(nodeId: String) {
        val address = nodeToEndpointMap[nodeId] ?: nodeId
        val session = peerSessions.remove(address)
        if (session != null) {
            session.state = BleConnectionState.DISCONNECTING
            try {
                session.gatt?.disconnect()
                session.gatt?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting GATT client: ${e.message}")
            }
        }
        endpointToNodeMap.remove(address)
        nodeToEndpointMap.remove(nodeId)

        emitLog(
            tag = "BLE-DISCONNECT",
            message = "Disconnected from BLE peer [$nodeId]. State: DISCONNECTED",
            level = LogLevel.INFO,
            nodeId = nodeId
        )
        emitEvent(TransportEvent.NodeDisconnected(nodeId, address))
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            val session = peerSessions.computeIfAbsent(address) {
                BlePeerSession(device = gatt.device, address = address, gatt = gatt)
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                session.state = BleConnectionState.GATT_CONNECTED
                emitLog(
                    tag = "BLE-STATE",
                    message = "GATT connected to peer [$address]. State: GATT_CONNECTED. Requesting MTU 512...",
                    level = LogLevel.SUCCESS
                )
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
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
                session.negotiatedMtu = mtu
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
            val session = peerSessions[address] ?: return

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

            val rxChar = service.getCharacteristic(RX_CHAR_UUID)
            if (rxChar != null) {
                session.state = BleConnectionState.NOTIFICATIONS_ENABLING
                gatt.setCharacteristicNotification(rxChar, true)
                val descriptor = rxChar.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                    emitLog(tag = "BLE-NOTIFY", message = "Subscribed to RX notifications on peer [$address]. State: NOTIFICATIONS_ENABLING", level = LogLevel.SUCCESS)
                }
            }

            // Central sends HELLO handshake to Peripheral
            sendHandshakeToPeer(session)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            val session = peerSessions[gatt.device.address] ?: return
            handleIncomingRawChunk(value, session)
        }

        // For Android 13+ (API 33)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val session = peerSessions[gatt.device.address] ?: return
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

        session.state = BleConnectionState.HANDSHAKE_SENT
        emitLog(
            tag = "BLE-HANDSHAKE",
            message = "Transmitting HELLO handshake to [${session.address}] (${handshake.displayName} [${handshake.nodeId}]). State: HANDSHAKE_SENT",
            level = LogLevel.INFO
        )

        transportScope.launch {
            sendFramedBytesToSession(session, framedBytes)
        }
    }

    // =========================================================================
    // TRANSMISSION & CRC FRAMING PIPELINE
    // =========================================================================
    override fun sendPacket(packet: MessagePacket, targetEndpointId: String): Boolean {
        val resolvedEndpoint = nodeToEndpointMap[targetEndpointId] ?: targetEndpointId
        val session = peerSessions[resolvedEndpoint]

        val jsonStr = JsonSerializer.packetToJson(packet)
        val payloadBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)

        // Check if intentional CRC corruption is triggered for Review III demo
        val shouldCorrupt = corruptNextCrc.getAndSet(false)

        val framedBytes = MeshLinkFrame.encode(
            frameType = MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET,
            payload = payloadBytes,
            corruptForTesting = shouldCorrupt
        )

        val computedCrc = Crc16Ccitt.compute(framedBytes, 0, framedBytes.size - MeshLinkFrame.CRC_SIZE)

        transportScope.launch {
            if (session != null) {
                emitLog(
                    tag = "BLE-TX",
                    message = "Sending frame to [${session.address}] (CRC: ${Crc16Ccitt.formatHex(computedCrc)}, Size: ${framedBytes.size}B, Corrupted: $shouldCorrupt)",
                    level = if (shouldCorrupt) LogLevel.WARNING else LogLevel.INFO,
                    packetSummary = packet.getSummary(),
                    nodeId = packet.destinationId
                )
                sendFramedBytesToSession(session, framedBytes)
                emitEvent(TransportEvent.PacketSent(packet, resolvedEndpoint))
            } else {
                emitLog(
                    tag = "BLE-TX",
                    message = "Cannot send packet '${packet.messageId}': No active BLE peer session for '$resolvedEndpoint'",
                    level = LogLevel.WARNING
                )
            }
        }

        return true
    }

    override fun broadcastPacket(packet: MessagePacket, excludeEndpointIds: Set<String>): Int {
        val jsonStr = JsonSerializer.packetToJson(packet)
        val payloadBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)

        val shouldCorrupt = corruptNextCrc.getAndSet(false)
        val framedBytes = MeshLinkFrame.encode(
            frameType = MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET,
            payload = payloadBytes,
            corruptForTesting = shouldCorrupt
        )

        val computedCrc = Crc16Ccitt.compute(framedBytes, 0, framedBytes.size - MeshLinkFrame.CRC_SIZE)
        var count = 0

        transportScope.launch {
            for ((address, session) in peerSessions) {
                if (!excludeEndpointIds.contains(address) && session.state == BleConnectionState.READY) {
                    sendFramedBytesToSession(session, framedBytes)
                    count++
                }
            }

            if (count > 0) {
                emitLog(
                    tag = "BLE-TX",
                    message = "Broadcasted frame to $count peer(s) (CRC: ${Crc16Ccitt.formatHex(computedCrc)}, Size: ${framedBytes.size}B, Corrupted: $shouldCorrupt)",
                    level = if (shouldCorrupt) LogLevel.WARNING else LogLevel.INFO,
                    packetSummary = packet.getSummary()
                )
                emitEvent(TransportEvent.PacketSent(packet, "BROADCAST ($count peers)"))
            } else {
                emitLog(
                    tag = "BLE-TX",
                    message = "Broadcast attempted for '${packet.messageId}', but no READY BLE peers available",
                    level = LogLevel.WARNING
                )
            }
        }

        return count
    }

    /**
     * Serialized MTU-aware chunk transmission per peer session.
     */
    @SuppressLint("MissingPermission")
    private suspend fun sendFramedBytesToSession(session: BlePeerSession, data: ByteArray) {
        session.txMutex.withLock {
            val chunkSize = session.usableChunkSize
            var offset = 0

            // Case A: We are Central, write to Peripheral's TX Characteristic
            val gatt = session.gatt
            if (gatt != null) {
                val service = gatt.getService(SERVICE_UUID)
                val txChar = service?.getCharacteristic(TX_CHAR_UUID)
                if (txChar != null) {
                    while (offset < data.size) {
                        val length = minOf(chunkSize, data.size - offset)
                        val chunk = ByteArray(length)
                        System.arraycopy(data, offset, chunk, 0, length)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(txChar, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        } else {
                            @Suppress("DEPRECATION")
                            txChar.value = chunk
                            @Suppress("DEPRECATION")
                            txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(txChar)
                        }
                        offset += length
                        delay(15) // Safe BLE GATT inter-write packet delay
                    }
                    return@withLock
                }
            }

            // Case B: We are Peripheral, notify Central's RX Characteristic
            val server = gattServer
            if (server != null && session.isSubscribed) {
                val service = server.getService(SERVICE_UUID)
                val rxChar = service?.getCharacteristic(RX_CHAR_UUID)
                if (rxChar != null) {
                    while (offset < data.size) {
                        val length = minOf(chunkSize, data.size - offset)
                        val chunk = ByteArray(length)
                        System.arraycopy(data, offset, chunk, 0, length)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            server.notifyCharacteristicChanged(session.device, rxChar, false, chunk)
                        } else {
                            @Suppress("DEPRECATION")
                            rxChar.value = chunk
                            @Suppress("DEPRECATION")
                            server.notifyCharacteristicChanged(session.device, rxChar, false)
                        }
                        offset += length
                        delay(15)
                    }
                }
            }
        }
    }

    // =========================================================================
    // INBOUND REASSEMBLY & CRC VERIFICATION PIPELINE
    // =========================================================================
    private fun handleIncomingRawChunk(chunk: ByteArray, session: BlePeerSession) {
        val frameResults = session.reassembler.processChunk(chunk)

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
                            if (handshake != null) {
                                session.nodeId = handshake.nodeId
                                session.displayName = handshake.displayName
                                session.state = BleConnectionState.READY

                                emitLog(
                                    tag = "CRC-PASS",
                                    message = "INFO [CRC][PASS] HELLO Frame Valid (CRC: $crcHex, Peer: ${handshake.displayName} [${handshake.nodeId}]). State: READY",
                                    level = LogLevel.SUCCESS,
                                    nodeId = handshake.nodeId
                                )

                                val connectedNode = Node(
                                    nodeId = handshake.nodeId,
                                    nodeName = handshake.displayName,
                                    status = NodeStatus.CONNECTED,
                                    endpointId = session.address,
                                    lastSeenTimestamp = System.currentTimeMillis()
                                )
                                endpointToNodeMap[session.address] = connectedNode
                                nodeToEndpointMap[handshake.nodeId] = session.address

                                emitEvent(TransportEvent.NodeConnected(connectedNode))
                            } else {
                                emitLog(tag = "BLE-HANDSHAKE", message = "Invalid HELLO payload from [${session.address}]", level = LogLevel.WARNING)
                            }
                        }

                        MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET -> {
                            val jsonStr = String(payloadBytes, StandardCharsets.UTF_8)
                            val packet = JsonSerializer.jsonToPacket(jsonStr)

                            if (packet != null) {
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
        transportScope.launch {
            _events.emit(event)
        }
    }
}
