package com.example.transport.ble

import com.example.core.model.BleAdapterStatus
import com.example.core.model.NearbyPeer
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class IncomingTransportPacket(
    val sourceDeviceAddress: String,
    val rawBytes: ByteArray
)

interface MeshTransport {
    val adapterStatus: StateFlow<BleAdapterStatus>
    val nearbyPeers: StateFlow<List<NearbyPeer>>
    val incomingPackets: SharedFlow<IncomingTransportPacket>
    val peerConnectedEvents: SharedFlow<String>

    fun refreshStatus()
    fun startMesh()
    fun stopMesh()
    fun connectToPeer(deviceAddress: String)
    fun disconnectPeer(deviceAddress: String)
    fun associateNodeWithDevice(deviceAddress: String, nodeId: String, displayName: String, isVerified: Boolean)
    suspend fun broadcastBytes(payload: ByteArray, excludeDeviceAddresses: Set<String> = emptySet()): Int
    suspend fun sendBytesToDevice(deviceAddress: String, payload: ByteArray): Boolean
    fun getDeviceAddressForNode(nodeId: String): String?
}
