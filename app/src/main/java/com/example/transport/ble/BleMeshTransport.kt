package com.example.transport.ble

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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import com.example.core.model.BleAdapterStatus
import com.example.core.model.NearbyPeer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull

class BleMeshTransport(
    private val context: Context,
    private val localNodeIdProvider: () -> String
) : MeshTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val framer = BleMeshFramer()

    private val _adapterStatus = MutableStateFlow(BleAdapterStatus())
    override val adapterStatus: StateFlow<BleAdapterStatus> = _adapterStatus.asStateFlow()

    private val _nearbyPeers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    override val nearbyPeers: StateFlow<List<NearbyPeer>> = _nearbyPeers.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<IncomingTransportPacket>(extraBufferCapacity = 64)
    override val incomingPackets: SharedFlow<IncomingTransportPacket> = _incomingPackets.asSharedFlow()

    private val _peerConnectedEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val peerConnectedEvents: SharedFlow<String> = _peerConnectedEvents.asSharedFlow()

    private val discoveredMap = ConcurrentHashMap<String, PeerRecord>()
    private val gattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val serverSubscribers = ConcurrentHashMap<String, BluetoothDevice>()
    private val peerMtu = ConcurrentHashMap<String, Int>()
    private val nodeToAddress = ConcurrentHashMap<String, String>()
    private val writeMutex = Mutex()
    private val pendingWriteAck = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()

    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertisingActive = false
    private var isScanningActive = false
    private var receiverRegistered = false

    private data class PeerRecord(
        val deviceAddress: String,
        val nodeId: String? = null,
        val displayName: String,
        val rssi: Int,
        val isConnected: Boolean,
        val isMeshCapable: Boolean,
        val lastSeenAt: Long,
        val isVerifiedContact: Boolean = false
    )

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    stopMesh()
                } else if (state == BluetoothAdapter.STATE_ON) {
                    refreshStatus()
                }
            }
        }
    }

    init {
        refreshStatus()
        registerAdapterReceiver()
    }

    private fun registerAdapterReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(adapterStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(adapterStateReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun refreshStatus() {
        val hasBleFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val adapter = bluetoothAdapter
        val isSupported = hasBleFeature && adapter != null
        val isEnabled = adapter?.isEnabled == true
        val hasPerms = BleConstants.hasBlePermissions(context)
        val connectedCount = (gattClients.keys + serverSubscribers.keys).distinct().size

        val statusText = when {
            !isSupported -> "BLE hardware unavailable"
            !hasPerms -> "Bluetooth permissions required"
            !isEnabled -> "Bluetooth radio is turned off"
            isAdvertisingActive && isScanningActive -> "Mesh active ($connectedCount linked)"
            isAdvertisingActive || isScanningActive -> "Radio starting ($connectedCount linked)"
            else -> "Radio ready (idle)"
        }

        _adapterStatus.value = BleAdapterStatus(
            isBluetoothSupported = isSupported,
            isBluetoothEnabled = isEnabled,
            hasRequiredPermissions = hasPerms,
            isAdvertising = isAdvertisingActive,
            isScanning = isScanningActive,
            connectedCount = connectedCount,
            statusMessage = statusText
        )
    }

    @SuppressLint("MissingPermission")
    override fun startMesh() {
        refreshStatus()
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || !BleConstants.hasBlePermissions(context)) {
            refreshStatus()
            return
        }

        startGattServer()
        startAdvertising(adapter)
        startScanning(adapter)
        refreshStatus()
    }

    @SuppressLint("MissingPermission")
    override fun stopMesh() {
        if (BleConstants.hasBlePermissions(context)) {
            runCatching { scanner?.stopScan(scanCallback) }
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            gattClients.values.forEach { gatt ->
                runCatching {
                    gatt.disconnect()
                    gatt.close()
                }
            }
            runCatching { gattServer?.close() }
        }
        gattClients.clear()
        serverSubscribers.clear()
        peerMtu.clear()
        gattServer = null
        txCharacteristic = null
        isAdvertisingActive = false
        isScanningActive = false
        refreshStatus()
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        if (gattServer != null || !BleConstants.hasBlePermissions(context)) return
        val manager = bluetoothManager ?: return

        val rxChar = BluetoothGattCharacteristic(
            BleConstants.MESH_RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val txChar = BluetoothGattCharacteristic(
            BleConstants.MESH_TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleConstants.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }
        txCharacteristic = txChar

        val service = BluetoothGattService(
            BleConstants.MESH_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply {
            addCharacteristic(rxChar)
            addCharacteristic(txChar)
        }

        gattServer = manager.openGattServer(context, gattServerCallback)?.also { server ->
            server.addService(service)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: BluetoothAdapter) {
        if (isAdvertisingActive || !BleConstants.hasBlePermissions(context)) return
        val adv = adapter.bluetoothLeAdvertiser ?: return
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val nodeBytes = localNodeIdProvider().take(8).toByteArray(Charsets.UTF_8)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
            .addServiceData(ParcelUuid(BleConstants.MESH_SERVICE_UUID), nodeBytes)
            .build()

        runCatching { adv.startAdvertising(settings, data, advertiseCallback) }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning(adapter: BluetoothAdapter) {
        if (isScanningActive || !BleConstants.hasBlePermissions(context)) return
        val leScanner = adapter.bluetoothLeScanner ?: return
        scanner = leScanner

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching {
            leScanner.startScan(listOf(filter), settings, scanCallback)
            isScanningActive = true
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertisingActive = true
            refreshStatus()
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertisingActive = false
            refreshStatus()
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val serviceData = result.scanRecord?.getServiceData(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
            val advertisedNodeHint = serviceData?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            val deviceName = if (BleConstants.hasBlePermissions(context)) {
                runCatching { device.name }.getOrNull()
            } else null

            val existing = discoveredMap[address]
            val isLinked = gattClients.containsKey(address) || serverSubscribers.containsKey(address)
            val display = existing?.displayName
                ?: deviceName?.takeIf { it.isNotBlank() }
                ?: advertisedNodeHint?.let { "Node $it" }
                ?: "BLE Peer ${address.takeLast(5)}"

            discoveredMap[address] = PeerRecord(
                deviceAddress = address,
                nodeId = existing?.nodeId ?: advertisedNodeHint,
                displayName = display,
                rssi = result.rssi,
                isConnected = isLinked,
                isMeshCapable = true,
                lastSeenAt = System.currentTimeMillis(),
                isVerifiedContact = existing?.isVerifiedContact ?: false
            )
            publishPeers()

            // Auto-connect to discovered mesh nodes if not already linked (tie-breaker by address avoids collision)
            if (!isLinked && address > (bluetoothAdapter?.address ?: "")) {
                connectToPeer(address)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun connectToPeer(deviceAddress: String) {
        if (!BleConstants.hasBlePermissions(context)) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        if (gattClients.containsKey(deviceAddress)) return

        val device = runCatching { adapter.getRemoteDevice(deviceAddress) }.getOrNull() ?: return
        device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    override fun disconnectPeer(deviceAddress: String) {
        if (!BleConstants.hasBlePermissions(context)) return
        gattClients.remove(deviceAddress)?.let { gatt ->
            runCatching {
                gatt.disconnect()
                gatt.close()
            }
        }
        serverSubscribers.remove(deviceAddress)
        framer.clearPeer(deviceAddress)
        discoveredMap[deviceAddress]?.let {
            discoveredMap[deviceAddress] = it.copy(isConnected = false)
        }
        publishPeers()
        refreshStatus()
    }

    override fun associateNodeWithDevice(
        deviceAddress: String,
        nodeId: String,
        displayName: String,
        isVerified: Boolean
    ) {
        nodeToAddress[nodeId] = deviceAddress
        val prev = discoveredMap[deviceAddress]
        discoveredMap[deviceAddress] = PeerRecord(
            deviceAddress = deviceAddress,
            nodeId = nodeId,
            displayName = displayName,
            rssi = prev?.rssi ?: -60,
            isConnected = gattClients.containsKey(deviceAddress) || serverSubscribers.containsKey(deviceAddress),
            isMeshCapable = true,
            lastSeenAt = System.currentTimeMillis(),
            isVerifiedContact = isVerified
        )
        publishPeers()
    }

    override fun getDeviceAddressForNode(nodeId: String): String? = nodeToAddress[nodeId]

    private val gattClientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                gattClients[address] = gatt
                reconnectAttempts.remove(address)
                peerMtu.putIfAbsent(address, BleConstants.DEFAULT_ATT_PAYLOAD_SIZE)
                updateConnectionState(address, true)
                gatt.requestMtu(BleConstants.REQUESTED_MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gattClients.remove(address)
                runCatching { gatt.close() }
                framer.clearPeer(address)
                pendingWriteAck.remove(address)?.complete(false)
                updateConnectionState(address, false)
                scheduleReconnectIfRecent(address)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val address = gatt.device.address ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                peerMtu[address] = (mtu - 3).coerceAtLeast(BleConstants.DEFAULT_ATT_PAYLOAD_SIZE)
            }
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(BleConstants.MESH_SERVICE_UUID) ?: return
            val txChar = service.getCharacteristic(BleConstants.MESH_TX_CHAR_UUID)
            if (txChar != null) {
                gatt.setCharacteristicNotification(txChar, true)
                val cccd = txChar.getDescriptor(BleConstants.CCCD_UUID)
                if (cccd != null) {
                    writeDescriptorCompat(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            }
            scope.launch { _peerConnectedEvents.emit(address) }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val address = gatt.device.address ?: return
            pendingWriteAck.remove(address)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleIncomingFragment(gatt.device.address ?: return, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingFragment(gatt.device.address ?: return, value)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverSubscribers[address] = device
                peerMtu.putIfAbsent(address, BleConstants.DEFAULT_ATT_PAYLOAD_SIZE)
                updateConnectionState(address, true)
                scope.launch { _peerConnectedEvents.emit(address) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverSubscribers.remove(address)
                framer.clearPeer(address)
                updateConnectionState(address, false)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val address = device.address ?: return
            peerMtu[address] = (mtu - 3).coerceAtLeast(BleConstants.DEFAULT_ATT_PAYLOAD_SIZE)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded && BleConstants.hasBlePermissions(context)) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (descriptor.uuid == BleConstants.CCCD_UUID) {
                serverSubscribers[device.address] = device
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
            value: ByteArray
        ) {
            if (responseNeeded && BleConstants.hasBlePermissions(context)) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (characteristic.uuid == BleConstants.MESH_RX_CHAR_UUID) {
                handleIncomingFragment(device.address ?: return, value)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val address = device.address ?: return
            pendingWriteAck.remove(address)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun handleIncomingFragment(address: String, fragment: ByteArray) {
        val full = framer.acceptFragment(address, fragment) ?: return
        scope.launch {
            _incomingPackets.emit(IncomingTransportPacket(address, full))
        }
    }

    override suspend fun broadcastBytes(
        payload: ByteArray,
        excludeDeviceAddresses: Set<String>
    ): Int {
        val targets = (gattClients.keys + serverSubscribers.keys)
            .distinct()
            .filterNot { excludeDeviceAddresses.contains(it) }
        var sentCount = 0
        for (address in targets) {
            if (sendBytesToDevice(address, payload)) {
                sentCount++
            }
        }
        return sentCount
    }

    @SuppressLint("MissingPermission")
    override suspend fun sendBytesToDevice(deviceAddress: String, payload: ByteArray): Boolean {
        if (!BleConstants.hasBlePermissions(context)) return false
        val mtuCap = peerMtu[deviceAddress] ?: BleConstants.DEFAULT_ATT_PAYLOAD_SIZE
        val fragments = framer.fragment(payload, mtuCap)

        return writeMutex.withLock {
            val clientGatt = gattClients[deviceAddress]
            if (clientGatt != null) {
                val rxChar = clientGatt.getService(BleConstants.MESH_SERVICE_UUID)
                    ?.getCharacteristic(BleConstants.MESH_RX_CHAR_UUID)
                    ?: return@withLock false
                for (fragment in fragments) {
                    val ack = CompletableDeferred<Boolean>()
                    pendingWriteAck[deviceAddress] = ack
                    val initiated = writeCharacteristicCompat(clientGatt, rxChar, fragment)
                    if (!initiated) {
                        pendingWriteAck.remove(deviceAddress)
                        return@withLock false
                    }
                    val ok = withTimeoutOrNull(3_000L) { ack.await() } ?: false
                    if (!ok) return@withLock false
                }
                return@withLock true
            }

            val serverDevice = serverSubscribers[deviceAddress]
            val server = gattServer
            val txChar = txCharacteristic
            if (serverDevice != null && server != null && txChar != null) {
                for (fragment in fragments) {
                    val ack = CompletableDeferred<Boolean>()
                    pendingWriteAck[deviceAddress] = ack
                    val okInit = notifyCharacteristicCompat(server, serverDevice, txChar, fragment)
                    if (!okInit) {
                        pendingWriteAck.remove(deviceAddress)
                        return@withLock false
                    }
                    val ok = withTimeoutOrNull(3_000L) { ack.await() } ?: false
                    if (!ok) return@withLock false
                }
                return@withLock true
            }
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyCharacteristicCompat(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, value) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun updateConnectionState(address: String, connected: Boolean) {
        val prev = discoveredMap[address]
        discoveredMap[address] = if (prev != null) {
            prev.copy(isConnected = connected, lastSeenAt = System.currentTimeMillis())
        } else {
            PeerRecord(
                deviceAddress = address,
                nodeId = null,
                displayName = "BLE Peer ${address.takeLast(5)}",
                rssi = -65,
                isConnected = connected,
                isMeshCapable = true,
                lastSeenAt = System.currentTimeMillis()
            )
        }
        publishPeers()
        refreshStatus()
    }

    private fun scheduleReconnectIfRecent(address: String) {
        val attempt = reconnectAttempts.compute(address) { _, v -> (v ?: 0) + 1 } ?: 1
        if (attempt > 3) return
        val record = discoveredMap[address] ?: return
        if (System.currentTimeMillis() - record.lastSeenAt > 60_000L) return

        scope.launch {
            delay(1_500L * attempt)
            if (isScanningActive && !gattClients.containsKey(address)) {
                connectToPeer(address)
            }
        }
    }

    private fun publishPeers() {
        val list = discoveredMap.values
            .sortedWith(compareByDescending<PeerRecord> { it.isConnected }.thenByDescending { it.rssi })
            .map {
                NearbyPeer(
                    deviceAddress = it.deviceAddress,
                    nodeId = it.nodeId,
                    displayName = it.displayName,
                    rssi = it.rssi,
                    isConnected = it.isConnected,
                    isMeshCapable = it.isMeshCapable,
                    hopDistance = if (it.isConnected) 1 else 0,
                    lastSeenAt = it.lastSeenAt,
                    isVerifiedContact = it.isVerifiedContact
                )
            }
        _nearbyPeers.value = list
    }
}
