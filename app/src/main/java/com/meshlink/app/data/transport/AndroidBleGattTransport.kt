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
import com.meshlink.app.domain.models.LogLevel
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.NetworkLogEntry
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.models.NodeStatus
import com.meshlink.app.utils.JsonSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Production-ready cross-platform Peer-to-Peer Transport for Android using BLE GATT.
 *
 * Runs Dual-Role simultaneous Bluetooth Low Energy:
 * 1. BluetoothGattServer + BluetoothLeAdvertiser: Advertises MeshLink GATT service and accepts incoming writes/subscriptions.
 * 2. BluetoothLeScanner + BluetoothGatt: Scans for nearby MeshLink peers, connects, discovers characteristics, and enables notifications.
 *
 * Wire-compatible with iOS CoreBluetoothTransport.
 */
class AndroidBleGattTransport(
    private val context: Context
) : MeshTransport {

    companion object {
        private const val TAG = "AndroidBleTransport"

        // UUIDs matching iOS CoreBluetoothTransport exactly
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Client Write / Server Receive
        val RX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Server Notify / Client Receive
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DEFAULT_CHUNK_SIZE = 180
        private const val DELIMITER_BYTE: Byte = 0x0A // '\n'
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

    // Connected centrals when acting as GATT Server: [DeviceAddress -> BluetoothDevice]
    private val subscribedCentrals = ConcurrentHashMap<String, BluetoothDevice>()

    // Connected peripherals when acting as GATT Client: [DeviceAddress -> BluetoothGatt]
    private val connectedGatts = ConcurrentHashMap<String, BluetoothGatt>()

    // Discovered devices: [DeviceAddress -> BluetoothDevice]
    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()

    // Node & Endpoint mapping: [EndpointId (Address) <-> Node]
    private val endpointToNodeMap = ConcurrentHashMap<String, Node>()
    private val nodeToEndpointMap = ConcurrentHashMap<String, String>()

    // Inbound frame reassembly buffer: [EndpointId -> ByteArrayOutputStream]
    private val receiveBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()

    // =========================================================================
    // PERMISSION CHECK
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
    // LIFECYCLE CONTROLS
    // =========================================================================
    @SuppressLint("MissingPermission")
    override fun start(localNode: Node) {
        this.localNode = localNode
        _isRunning.value = true

        emitLog(
            tag = "BLE-INIT",
            message = "Starting Android BLE GATT mesh transport for local node '${localNode.nodeName}' [${localNode.nodeId}]",
            level = LogLevel.INFO,
            nodeId = localNode.nodeId
        )

        if (!hasRequiredPermissions()) {
            emitLog(
                tag = "BLE-ERROR",
                message = "Missing required Bluetooth permissions. Please grant Bluetooth permissions in Settings.",
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

            for ((_, gatt) in connectedGatts) {
                try {
                    gatt.disconnect()
                    gatt.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing client GATT: ${e.message}")
                }
            }
            connectedGatts.clear()

            try {
                gattServer?.close()
                gattServer = null
            } catch (e: Exception) {
                Log.w(TAG, "Error closing GATT server: ${e.message}")
            }

            discoveredDevices.clear()
            subscribedCentrals.clear()
            endpointToNodeMap.clear()
            nodeToEndpointMap.clear()
            receiveBuffers.clear()

            emitLog(
                tag = "BLE-STOP",
                message = "Android BLE GATT transport stopped. All peer links closed.",
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
            val endpointId = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emitLog(
                    tag = "BLE-PERIPHERAL",
                    message = "Central peer connected to GATT Server: ${device.name ?: "Unknown"} [$endpointId]",
                    level = LogLevel.INFO
                )
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedCentrals.remove(endpointId)
                val node = endpointToNodeMap.remove(endpointId)
                val nodeId = node?.nodeId ?: endpointId
                nodeToEndpointMap.remove(nodeId)

                emitLog(
                    tag = "BLE-DISCONNECT",
                    message = "Central peer disconnected from GATT Server: ${node?.nodeName ?: endpointId} [$nodeId]",
                    level = LogLevel.WARNING,
                    nodeId = nodeId
                )
                emitEvent(TransportEvent.NodeDisconnected(nodeId, endpointId))
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
                if (isSubscribed) {
                    subscribedCentrals[device.address] = device
                    emitLog(
                        tag = "BLE-NOTIFY",
                        message = "Central peer [${device.address}] subscribed to mesh notifications",
                        level = LogLevel.INFO
                    )
                } else {
                    subscribedCentrals.remove(device.address)
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
                handleIncomingRawBytes(value, device.address)
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
            .setIncludeDeviceName(false) // Keep within 31-byte limit
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
            val endpointId = device.address
            discoveredDevices[endpointId] = device

            val record = result.scanRecord
            var rawName: String? = null

            // 1. Try reading from service data
            val serviceData = record?.getServiceData(ParcelUuid(SERVICE_UUID))
            if (serviceData != null && serviceData.isNotEmpty()) {
                rawName = String(serviceData, StandardCharsets.UTF_8)
            }

            // 2. Try device name or advertised local name
            if (rawName.isNullOrBlank()) {
                rawName = record?.deviceName ?: device.name ?: "BLE Node"
            }

            val parts = rawName.split("|")
            val name = parts.getOrNull(0)?.ifBlank { "BLE Node" } ?: "BLE Node"
            val nodeId = if (parts.size > 1 && parts[1].isNotBlank()) parts[1] else "BLE-${endpointId.takeLast(5).replace(":", "")}"

            // Prevent self-discovery
            if (nodeId.equals(localNode?.nodeId, ignoreCase = true)) {
                return
            }

            val discoveredNode = Node(
                nodeId = nodeId,
                nodeName = name,
                status = NodeStatus.DISCOVERED,
                endpointId = endpointId,
                lastSeenTimestamp = System.currentTimeMillis()
            )

            endpointToNodeMap[endpointId] = discoveredNode
            nodeToEndpointMap[nodeId] = endpointId

            emitLog(
                tag = "BLE-DISCOVER",
                message = "Discovered MeshLink peer: '$name' [$nodeId] (RSSI: ${result.rssi} dBm, Addr: $endpointId)",
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
        val endpointId = targetNode.endpointId ?: run {
            emitLog(
                tag = "BLE-CONNECT",
                message = "Cannot connect to '${targetNode.nodeName}': Missing BLE endpoint address",
                level = LogLevel.ERROR
            )
            return
        }

        val device = discoveredDevices[endpointId] ?: bluetoothAdapter?.getRemoteDevice(endpointId) ?: run {
            emitLog(
                tag = "BLE-CONNECT",
                message = "Device for '${targetNode.nodeName}' [$endpointId] not found in discovery cache",
                level = LogLevel.WARNING
            )
            return
        }

        emitLog(
            tag = "BLE-CONNECT",
            message = "Initiating BLE GATT connection to '${targetNode.nodeName}' [${targetNode.nodeId}] (Addr: $endpointId)...",
            level = LogLevel.INFO,
            nodeId = targetNode.nodeId
        )

        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattClientCallback)
        }
        connectedGatts[endpointId] = gatt
    }

    @SuppressLint("MissingPermission")
    override fun disconnectNode(nodeId: String) {
        val endpointId = nodeToEndpointMap[nodeId] ?: nodeId
        val gatt = connectedGatts.remove(endpointId)
        if (gatt != null) {
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting GATT client: ${e.message}")
            }
        }
        endpointToNodeMap.remove(endpointId)
        nodeToEndpointMap.remove(nodeId)
        receiveBuffers.remove(endpointId)

        emitLog(
            tag = "BLE-DISCONNECT",
            message = "Disconnected from BLE peer [$nodeId]",
            level = LogLevel.INFO,
            nodeId = nodeId
        )
        emitEvent(TransportEvent.NodeDisconnected(nodeId, endpointId))
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val endpointId = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emitLog(
                    tag = "BLE-CONNECTED",
                    message = "GATT connected to peer [${gatt.device.address}]. Requesting MTU 512 & discovering services...",
                    level = LogLevel.SUCCESS
                )
                gatt.requestMtu(512)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedGatts.remove(endpointId)
                val node = endpointToNodeMap.remove(endpointId)
                val nodeId = node?.nodeId ?: endpointId
                nodeToEndpointMap.remove(nodeId)
                receiveBuffers.remove(endpointId)

                emitLog(
                    tag = "BLE-DISCONNECT",
                    message = "Disconnected from peer '${node?.nodeName ?: endpointId}' [$nodeId]",
                    level = LogLevel.WARNING,
                    nodeId = nodeId
                )
                emitEvent(TransportEvent.NodeDisconnected(nodeId, endpointId))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitLog(tag = "BLE-GATT", message = "Service discovery failed on peer [${gatt.device.address}]", level = LogLevel.ERROR)
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                emitLog(tag = "BLE-GATT", message = "MeshLink service [$SERVICE_UUID] not found on peer [${gatt.device.address}]", level = LogLevel.WARNING)
                return
            }

            emitLog(tag = "BLE-GATT", message = "Discovered MeshLink Service on [${gatt.device.address}]", level = LogLevel.SUCCESS)

            val rxChar = service.getCharacteristic(RX_CHAR_UUID)
            if (rxChar != null) {
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
                    emitLog(tag = "BLE-NOTIFY", message = "Subscribed to RX notifications on peer [${gatt.device.address}]", level = LogLevel.SUCCESS)
                }
            }

            val endpointId = gatt.device.address
            val existing = endpointToNodeMap[endpointId]
            val nodeId = existing?.nodeId ?: "BLE-${endpointId.takeLast(5).replace(":", "")}"
            val nodeName = existing?.nodeName ?: gatt.device.name ?: "BLE Peer"

            val connectedNode = Node(
                nodeId = nodeId,
                nodeName = nodeName,
                status = NodeStatus.CONNECTED,
                endpointId = endpointId,
                lastSeenTimestamp = System.currentTimeMillis()
            )

            endpointToNodeMap[endpointId] = connectedNode
            nodeToEndpointMap[nodeId] = endpointId

            emitLog(
                tag = "BLE-CONNECT",
                message = "Peer connection fully ready: '$nodeName' [$nodeId] (Addr: $endpointId)",
                level = LogLevel.SUCCESS,
                nodeId = nodeId
            )
            emitEvent(TransportEvent.NodeConnected(connectedNode))
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleIncomingRawBytes(value, gatt.device.address)
        }

        // For Android 13+ (API 33)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncomingRawBytes(value, gatt.device.address)
        }
    }

    // =========================================================================
    // PACKET TRANSMISSION & FRAMING
    // =========================================================================
    @SuppressLint("MissingPermission")
    override fun sendPacket(packet: MessagePacket, targetEndpointId: String): Boolean {
        val jsonStr = JsonSerializer.packetToJson(packet)
        val rawBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)

        // Append newline framing delimiter
        val framedBytes = ByteArray(rawBytes.size + 1)
        System.arraycopy(rawBytes, 0, framedBytes, 0, rawBytes.size)
        framedBytes[rawBytes.size] = DELIMITER_BYTE

        val resolvedEndpoint = nodeToEndpointMap[targetEndpointId] ?: targetEndpointId

        transportScope.launch {
            var sent = false

            // Case A: Target is a connected peripheral (we are Central, write to TX Char)
            val gatt = connectedGatts[resolvedEndpoint]
            if (gatt != null) {
                val service = gatt.getService(SERVICE_UUID)
                val txChar = service?.getCharacteristic(TX_CHAR_UUID)
                if (txChar != null) {
                    transmitChunksToGatt(gatt, txChar, framedBytes)
                    emitLog(
                        tag = "BLE-TX",
                        message = "Sent packet '${packet.messageId}' via BLE GATT Write to [$resolvedEndpoint]",
                        level = LogLevel.INFO,
                        packetSummary = packet.getSummary(),
                        nodeId = packet.destinationId
                    )
                    emitEvent(TransportEvent.PacketSent(packet, resolvedEndpoint))
                    sent = true
                }
            }

            // Case B: Target is a subscribed central (we are Peripheral/Server, notify RX Char)
            if (!sent && gattServer != null) {
                val centralDevice = subscribedCentrals[resolvedEndpoint]
                val service = gattServer?.getService(SERVICE_UUID)
                val rxChar = service?.getCharacteristic(RX_CHAR_UUID)

                if (rxChar != null && centralDevice != null) {
                    notifyChunksToCentral(centralDevice, rxChar, framedBytes)
                    emitLog(
                        tag = "BLE-TX",
                        message = "Sent packet '${packet.messageId}' via BLE GATT Notify to [$resolvedEndpoint]",
                        level = LogLevel.INFO,
                        packetSummary = packet.getSummary(),
                        nodeId = packet.destinationId
                    )
                    emitEvent(TransportEvent.PacketSent(packet, resolvedEndpoint))
                    sent = true
                } else if (rxChar != null && subscribedCentrals.isNotEmpty()) {
                    // Notify all centrals if specific central not found
                    for ((_, device) in subscribedCentrals) {
                        notifyChunksToCentral(device, rxChar, framedBytes)
                    }
                    emitLog(
                        tag = "BLE-TX",
                        message = "Sent packet '${packet.messageId}' via BLE GATT Notify to ${subscribedCentrals.size} central peer(s)",
                        level = LogLevel.INFO,
                        packetSummary = packet.getSummary(),
                        nodeId = packet.destinationId
                    )
                    emitEvent(TransportEvent.PacketSent(packet, "SUBSCRIBED-CENTRALS"))
                    sent = true
                }
            }

            if (!sent) {
                emitLog(
                    tag = "BLE-TX",
                    message = "Cannot send packet '${packet.messageId}': No active BLE GATT connection for '$resolvedEndpoint'",
                    level = LogLevel.WARNING
                )
            }
        }

        return true
    }

    @SuppressLint("MissingPermission")
    override fun broadcastPacket(packet: MessagePacket, excludeEndpointIds: Set<String>): Int {
        val jsonStr = JsonSerializer.packetToJson(packet)
        val rawBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)

        val framedBytes = ByteArray(rawBytes.size + 1)
        System.arraycopy(rawBytes, 0, framedBytes, 0, rawBytes.size)
        framedBytes[rawBytes.size] = DELIMITER_BYTE

        var count = 0

        transportScope.launch {
            // 1. Send to all connected peripherals
            for ((endpointId, gatt) in connectedGatts) {
                if (!excludeEndpointIds.contains(endpointId)) {
                    val service = gatt.getService(SERVICE_UUID)
                    val txChar = service?.getCharacteristic(TX_CHAR_UUID)
                    if (txChar != null) {
                        transmitChunksToGatt(gatt, txChar, framedBytes)
                        count++
                    }
                }
            }

            // 2. Notify all subscribed centrals
            val server = gattServer
            val rxChar = server?.getService(SERVICE_UUID)?.getCharacteristic(RX_CHAR_UUID)
            if (server != null && rxChar != null) {
                for ((endpointId, central) in subscribedCentrals) {
                    if (!excludeEndpointIds.contains(endpointId)) {
                        notifyChunksToCentral(central, rxChar, framedBytes)
                        count++
                    }
                }
            }

            if (count > 0) {
                emitLog(
                    tag = "BLE-TX",
                    message = "Broadcasted packet '${packet.messageId}' to $count BLE peer(s)",
                    level = LogLevel.INFO,
                    packetSummary = packet.getSummary()
                )
                emitEvent(TransportEvent.PacketSent(packet, "BROADCAST ($count peers)"))
            } else {
                emitLog(
                    tag = "BLE-TX",
                    message = "Broadcast attempted for '${packet.messageId}', but no active BLE peers available",
                    level = LogLevel.WARNING
                )
            }
        }

        return count
    }

    @SuppressLint("MissingPermission")
    private fun transmitChunksToGatt(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val chunkLength = minOf(DEFAULT_CHUNK_SIZE, data.size - offset)
            val chunk = ByteArray(chunkLength)
            System.arraycopy(data, offset, chunk, 0, chunkLength)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }

            offset += chunkLength
            try {
                Thread.sleep(15)
            } catch (_: InterruptedException) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyChunksToCentral(central: BluetoothDevice, characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        val server = gattServer ?: return
        var offset = 0
        while (offset < data.size) {
            val chunkLength = minOf(DEFAULT_CHUNK_SIZE, data.size - offset)
            val chunk = ByteArray(chunkLength)
            System.arraycopy(data, offset, chunk, 0, chunkLength)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(central, characteristic, false, chunk)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(central, characteristic, false)
            }

            offset += chunkLength
            try {
                Thread.sleep(15)
            } catch (_: InterruptedException) {}
        }
    }

    // =========================================================================
    // INBOUND DATA PROCESSING & FRAME REASSEMBLY
    // =========================================================================
    private fun handleIncomingRawBytes(incoming: ByteArray, fromEndpointId: String) {
        val stream = receiveBuffers.computeIfAbsent(fromEndpointId) { ByteArrayOutputStream() }
        val currentBytes: ByteArray
        synchronized(stream) {
            stream.write(incoming)
            currentBytes = stream.toByteArray()
        }

        var searchStart = 0
        var lastDelimiterIndex = -1

        for (i in currentBytes.indices) {
            if (currentBytes[i] == DELIMITER_BYTE) {
                val frameLength = i - searchStart
                if (frameLength > 0) {
                    val frameBytes = ByteArray(frameLength)
                    System.arraycopy(currentBytes, searchStart, frameBytes, 0, frameLength)

                    val jsonStr = String(frameBytes, StandardCharsets.UTF_8)
                    val packet = JsonSerializer.jsonToPacket(jsonStr)

                    if (packet != null) {
                        val senderNode = endpointToNodeMap[fromEndpointId]
                        emitLog(
                            tag = "BLE-RX",
                            message = "Received packet '${packet.messageId}' via BLE from [$fromEndpointId] (${senderNode?.nodeName ?: "Peer"})",
                            level = LogLevel.INFO,
                            packetSummary = packet.getSummary(),
                            nodeId = packet.sourceId
                        )
                        emitEvent(TransportEvent.PacketReceived(packet, fromEndpointId))
                    } else {
                        emitLog(
                            tag = "BLE-RX",
                            message = "Received unrecognized/corrupted packet data ($frameLength bytes) from '$fromEndpointId'",
                            level = LogLevel.WARNING
                        )
                    }
                }
                searchStart = i + 1
                lastDelimiterIndex = i
            }
        }

        if (lastDelimiterIndex >= 0) {
            synchronized(stream) {
                stream.reset()
                val remaining = currentBytes.size - (lastDelimiterIndex + 1)
                if (remaining > 0) {
                    stream.write(currentBytes, lastDelimiterIndex + 1, remaining)
                }
            }
        }
    }

    // =========================================================================
    // LOGGING & EVENT EMISSION
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
