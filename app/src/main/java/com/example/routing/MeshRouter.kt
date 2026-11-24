package com.example.routing

import com.example.core.model.MeshPacket
import com.example.core.model.MeshRouteLogEntry
import com.example.core.model.PacketType
import com.example.crypto.CryptoEngine
import com.example.identity.IdentityManager
import com.example.storage.MeshRepository
import com.example.storage.PendingRelayEntity
import com.example.storage.SeenPacketEntity
import com.example.transport.ble.MeshTransport
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.serialization.json.Json

data class InboundLocalPacket(
    val sourceDeviceAddress: String,
    val packet: MeshPacket
)

class MeshRouter(
    private val transport: MeshTransport,
    private val repository: MeshRepository,
    private val crypto: CryptoEngine,
    private val identityManager: IdentityManager,
    private val allowRelayForwardingProvider: () -> Boolean,
    private val defaultTtlProvider: () -> Int,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val json = Json { ignoreUnknownKeys = true }

    private val _inboundForLocalNode = MutableSharedFlow<InboundLocalPacket>(extraBufferCapacity = 64)
    val inboundForLocalNode: SharedFlow<InboundLocalPacket> = _inboundForLocalNode.asSharedFlow()

    private val _routeLogs = MutableStateFlow<List<MeshRouteLogEntry>>(emptyList())
    val routeLogs: StateFlow<List<MeshRouteLogEntry>> = _routeLogs.asStateFlow()

    init {
        scope.launch {
            transport.incomingPackets.collect { raw ->
                handleIncomingBytes(raw.sourceDeviceAddress, raw.rawBytes)
            }
        }
        scope.launch {
            transport.peerConnectedEvents.collect {
                flushPendingRelays()
            }
        }
    }

    suspend fun createAndSendPacket(
        packetType: PacketType,
        targetNodeId: String,
        payloadBytes: ByteArray,
        maxHops: Int = defaultTtlProvider()
    ): MeshPacket {
        val keys = identityManager.ensureKeyBundle()
        val selfNodeId = identityManager.deriveNodeId(keys.signPublicKey)
        val packetId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val payloadB64 = crypto.toBase64(payloadBytes)
        val clampedHops = maxHops.coerceIn(1, 7)

        val sigContent = canonicalPacketSignatureInput(
            packetId = packetId,
            packetType = packetType,
            originNodeId = selfNodeId,
            targetNodeId = targetNodeId,
            timestamp = timestamp,
            payloadBase64 = payloadB64
        )
        val sig = crypto.signDetached(keys.signSecretKey, sigContent)

        val packet = MeshPacket(
            packetId = packetId,
            packetType = packetType,
            originNodeId = selfNodeId,
            targetNodeId = targetNodeId,
            lastHopNodeId = selfNodeId,
            ttl = clampedHops,
            hopCount = 0,
            maxHops = clampedHops,
            timestamp = timestamp,
            payloadBase64 = payloadB64,
            signatureBase64 = crypto.toBase64(sig)
        )

        val now = System.currentTimeMillis()
        repository.routingDao.markSeen(
            SeenPacketEntity(packetId = packetId, seenAt = now, expiresAt = now + SEEN_TTL_MS)
        )

        dispatchPacket(packet, excludeAddress = null)
        return packet
    }

    private suspend fun dispatchPacket(packet: MeshPacket, excludeAddress: String?) {
        val encoded = json.encodeToString(MeshPacket.serializer(), packet).toByteArray(Charsets.UTF_8)
        val directAddress = if (packet.targetNodeId != TARGET_BROADCAST) {
            transport.getDeviceAddressForNode(packet.targetNodeId)
        } else null

        val sentCount = if (directAddress != null && directAddress != excludeAddress) {
            if (transport.sendBytesToDevice(directAddress, encoded)) 1 else 0
        } else {
            val exclusions = excludeAddress?.let { setOf(it) } ?: emptySet()
            transport.broadcastBytes(encoded, exclusions)
        }

        if (sentCount > 0) {
            appendLog(packet, if (packet.hopCount == 0) "Transmitted ($sentCount link)" else "Relayed ($sentCount link)")
        } else if (packet.packetType != PacketType.IDENTITY_BEACON) {
            val now = System.currentTimeMillis()
            repository.routingDao.enqueueRelay(
                PendingRelayEntity(
                    packetId = packet.packetId,
                    targetNodeId = packet.targetNodeId,
                    packetJson = encoded.toString(Charsets.UTF_8),
                    createdAt = now,
                    expiresAt = now + STORE_FORWARD_TTL_MS
                )
            )
            appendLog(packet, "Queued offline (store & forward)")
        }
    }

    private suspend fun handleIncomingBytes(sourceDeviceAddress: String, rawBytes: ByteArray) {
        val packet = runCatching {
            json.decodeFromString(MeshPacket.serializer(), rawBytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return

        val now = System.currentTimeMillis()
        if (repository.routingDao.hasSeen(packet.packetId)) {
            appendLog(packet, "Duplicate dropped")
            return
        }
        repository.routingDao.markSeen(
            SeenPacketEntity(packetId = packet.packetId, seenAt = now, expiresAt = now + SEEN_TTL_MS)
        )

        val selfNodeId = identityManager.deriveNodeId(identityManager.ensureKeyBundle().signPublicKey)
        if (packet.originNodeId == selfNodeId) return

        // Verify signature if sender key is known
        val originContact = repository.getContact(packet.originNodeId)
        if (originContact != null) {
            val sigBytes = crypto.fromBase64(packet.signatureBase64)
            val sigInput = canonicalPacketSignatureInput(
                packetId = packet.packetId,
                packetType = packet.packetType,
                originNodeId = packet.originNodeId,
                targetNodeId = packet.targetNodeId,
                timestamp = packet.timestamp,
                payloadBase64 = packet.payloadBase64
            )
            val valid = crypto.verifyDetached(
                crypto.fromBase64(originContact.signPublicKeyBase64),
                sigInput,
                sigBytes
            )
            if (!valid) {
                appendLog(packet, "Rejected invalid signature")
                return
            }
        }

        val isForSelf = packet.targetNodeId == selfNodeId || packet.targetNodeId == TARGET_BROADCAST
        if (isForSelf) {
            appendLog(packet, "Delivered locally (hop ${packet.hopCount})")
            _inboundForLocalNode.emit(InboundLocalPacket(sourceDeviceAddress, packet))
        }

        val shouldForward = packet.targetNodeId != selfNodeId &&
            allowRelayForwardingProvider() &&
            packet.ttl > 1 &&
            packet.hopCount < packet.maxHops

        if (shouldForward) {
            val forwarded = packet.copy(
                lastHopNodeId = selfNodeId,
                ttl = packet.ttl - 1,
                hopCount = packet.hopCount + 1
            )
            dispatchPacket(forwarded, excludeAddress = sourceDeviceAddress)
        } else if (!isForSelf && packet.ttl <= 1) {
            appendLog(packet, "Dropped (TTL expired)")
        }
    }

    suspend fun flushPendingRelays() {
        val now = System.currentTimeMillis()
        repository.routingDao.purgeExpiredRelays(now)
        repository.routingDao.purgeExpiredSeen(now)

        val pending = repository.routingDao.getActiveRelays(now)
        for (item in pending) {
            val packet = runCatching {
                json.decodeFromString(MeshPacket.serializer(), item.packetJson)
            }.getOrNull() ?: continue

            val encoded = item.packetJson.toByteArray(Charsets.UTF_8)
            val directAddr = if (packet.targetNodeId != TARGET_BROADCAST) {
                transport.getDeviceAddressForNode(packet.targetNodeId)
            } else null

            val sent = if (directAddr != null) {
                if (transport.sendBytesToDevice(directAddr, encoded)) 1 else 0
            } else {
                transport.broadcastBytes(encoded)
            }

            if (sent > 0) {
                repository.routingDao.removeRelay(item.packetId)
                appendLog(packet, "Flushed from offline queue")
            } else {
                repository.routingDao.incrementAttempt(item.packetId)
            }
        }
    }

    private fun canonicalPacketSignatureInput(
        packetId: String,
        packetType: PacketType,
        originNodeId: String,
        targetNodeId: String,
        timestamp: Long,
        payloadBase64: String
    ): ByteArray {
        return "$packetId|${packetType.name}|$originNodeId|$targetNodeId|$timestamp|$payloadBase64"
            .toByteArray(Charsets.UTF_8)
    }

    private fun appendLog(packet: MeshPacket, action: String) {
        val entry = MeshRouteLogEntry(
            id = UUID.randomUUID().toString(),
            packetId = packet.packetId.take(8),
            packetType = packet.packetType,
            originNodeId = packet.originNodeId,
            targetNodeId = packet.targetNodeId,
            hopCount = packet.hopCount,
            ttlRemaining = packet.ttl,
            action = action,
            timestamp = System.currentTimeMillis()
        )
        _routeLogs.value = (listOf(entry) + _routeLogs.value).take(60)
    }

    companion object {
        const val TARGET_BROADCAST = "BROADCAST"
        private const val SEEN_TTL_MS = 6 * 60 * 60 * 1000L
        private const val STORE_FORWARD_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
