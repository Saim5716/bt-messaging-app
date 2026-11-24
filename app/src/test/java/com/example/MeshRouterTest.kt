package com.example

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.BleAdapterStatus
import com.example.core.model.Contact
import com.example.core.model.GroupCreationPolicy
import com.example.core.model.GroupInvitePolicy
import com.example.core.model.MeshPacket
import com.example.core.model.NearbyPeer
import com.example.core.model.PacketType
import com.example.crypto.CryptoEngine
import com.example.crypto.IdentityKeyBundle
import com.example.crypto.SecretVault
import com.example.group.GroupManager
import com.example.identity.IdentityManager
import com.example.routing.MeshRouter
import com.example.storage.MeshRepository
import com.example.storage.OfflineMeshDatabase
import com.example.transport.ble.IncomingTransportPacket
import com.example.transport.ble.MeshTransport
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MeshRouterTest {

    private class InMemoryTestVault : SecretVault {
        private var stored: IdentityKeyBundle? = null
        override fun wrapWithKeystore(plaintext: ByteArray): ByteArray = plaintext.copyOf()
        override fun unwrapWithKeystore(wrapped: ByteArray): ByteArray = wrapped.copyOf()
        override fun saveIdentityBundle(bundle: IdentityKeyBundle) {
            stored = bundle
        }

        override fun loadIdentityBundle(): IdentityKeyBundle? = stored
        override fun clearAllSecrets() {
            stored = null
        }
    }

    private class FakeTransport : MeshTransport {
        override val adapterStatus: StateFlow<BleAdapterStatus> = MutableStateFlow(BleAdapterStatus())
        override val nearbyPeers: StateFlow<List<NearbyPeer>> = MutableStateFlow(emptyList())
        private val _incoming = MutableSharedFlow<IncomingTransportPacket>(extraBufferCapacity = 32)
        override val incomingPackets: SharedFlow<IncomingTransportPacket> = _incoming
        private val _connected = MutableSharedFlow<String>(extraBufferCapacity = 16)
        override val peerConnectedEvents: SharedFlow<String> = _connected

        val sentPayloads = mutableListOf<Pair<Set<String>, ByteArray>>()
        var connectedLinkCount = 1

        suspend fun emitIncoming(addr: String, bytes: ByteArray) {
            _incoming.emit(IncomingTransportPacket(addr, bytes))
        }

        override fun refreshStatus() {}
        override fun startMesh() {}
        override fun stopMesh() {}
        override fun connectToPeer(deviceAddress: String) {}
        override fun disconnectPeer(deviceAddress: String) {}
        override fun associateNodeWithDevice(deviceAddress: String, nodeId: String, displayName: String, isVerified: Boolean) {}
        override suspend fun broadcastBytes(payload: ByteArray, excludeDeviceAddresses: Set<String>): Int {
            if (connectedLinkCount == 0) return 0
            sentPayloads.add(excludeDeviceAddresses to payload)
            return connectedLinkCount
        }

        override suspend fun sendBytesToDevice(deviceAddress: String, payload: ByteArray): Boolean = false
        override fun getDeviceAddressForNode(nodeId: String): String? = null
    }

    private class TestCryptoEngine : CryptoEngine {
        private var keySeq = 10
        override fun generateIdentityKeys(): IdentityKeyBundle = IdentityKeyBundle(
            signPublicKey = ByteArray(32) { 1 },
            signSecretKey = ByteArray(64) { 2 },
            kxPublicKey = ByteArray(32) { 3 },
            kxSecretKey = ByteArray(32) { 4 }
        )

        override fun generateSymmetricKey(): ByteArray {
            val v = (keySeq++).toByte()
            return ByteArray(32) { v }
        }

        override fun randomBytes(size: Int): ByteArray = ByteArray(size) { 6 }
        override fun deriveSharedKey(myKxSecretKey: ByteArray, peerKxPublicKey: ByteArray, context: String): ByteArray =
            sha256(myKxSecretKey + peerKxPublicKey + context.toByteArray())

        override fun encryptAead(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray = plaintext
        override fun decryptAead(key: ByteArray, nonceAndCiphertext: ByteArray, associatedData: ByteArray): ByteArray = nonceAndCiphertext
        override fun signDetached(edSecretKey: ByteArray, message: ByteArray): ByteArray = ByteArray(64) { 9 }
        override fun verifyDetached(edPublicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = true
        override fun blake2b256(data: ByteArray): ByteArray = sha256(data)
        override fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
        override fun computeSafetyNumber(publicKeyA: ByteArray, publicKeyB: ByteArray): String = "12345 67890"
        override fun toBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        override fun fromBase64(base64: String): ByteArray = Base64.decode(base64, Base64.NO_WRAP)
        override fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `router deduplicates packets and enforces ttl on multi hop relay`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OfflineMeshDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val crypto = TestCryptoEngine()
            val vault = InMemoryTestVault()
            val identity = IdentityManager(crypto, vault)
            val repo = MeshRepository(db, crypto, vault)
            val transport = FakeTransport()
            MeshRouter(
                transport = transport,
                repository = repo,
                crypto = crypto,
                identityManager = identity,
                allowRelayForwardingProvider = { true },
                defaultTtlProvider = { 5 },
                dispatcher = Dispatchers.IO
            )
            delay(50)

            val json = Json { ignoreUnknownKeys = true }
            val pkt = MeshPacket(
                packetId = "pkt-relay-1",
                packetType = PacketType.DIRECT_MESSAGE,
                originNodeId = "node_origin",
                targetNodeId = "node_other",
                lastHopNodeId = "node_origin",
                ttl = 3,
                hopCount = 0,
                maxHops = 5,
                timestamp = System.currentTimeMillis(),
                payloadBase64 = crypto.toBase64("opaque".toByteArray()),
                signatureBase64 = crypto.toBase64(ByteArray(64))
            )
            val raw = json.encodeToString(MeshPacket.serializer(), pkt).toByteArray()

            transport.emitIncoming("11:22:33:44:55:66", raw)
            repeat(20) {
                if (transport.sentPayloads.isNotEmpty()) return@repeat
                delay(50)
            }
            assertEquals(1, transport.sentPayloads.size)
            assertTrue(transport.sentPayloads[0].first.contains("11:22:33:44:55:66"))
            val relayed = json.decodeFromString(
                MeshPacket.serializer(),
                transport.sentPayloads[0].second.toString(Charsets.UTF_8)
            )
            assertEquals(2, relayed.ttl)
            assertEquals(1, relayed.hopCount)

            // Duplicate reception must be dropped
            transport.emitIncoming("AA:BB:CC:DD:EE:FF", raw)
            delay(150)
            assertEquals(1, transport.sentPayloads.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `router queues packets offline when no ble peers are connected`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OfflineMeshDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val crypto = TestCryptoEngine()
            val vault = InMemoryTestVault()
            val identity = IdentityManager(crypto, vault)
            val repo = MeshRepository(db, crypto, vault)
            val transport = FakeTransport().apply { connectedLinkCount = 0 }
            val router = MeshRouter(
                transport = transport,
                repository = repo,
                crypto = crypto,
                identityManager = identity,
                allowRelayForwardingProvider = { true },
                defaultTtlProvider = { 5 },
                dispatcher = Dispatchers.IO
            )

            router.createAndSendPacket(
                packetType = PacketType.DIRECT_MESSAGE,
                targetNodeId = "peer_offline",
                payloadBytes = "encrypted".toByteArray()
            )
            assertEquals(1, db.routingDao().getActiveRelays(System.currentTimeMillis()).size)

            transport.connectedLinkCount = 1
            router.flushPendingRelays()
            assertEquals(0, db.routingDao().getActiveRelays(System.currentTimeMillis()).size)
            assertEquals(1, transport.sentPayloads.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `group manager rotates key epoch when admin removes a member`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OfflineMeshDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val crypto = TestCryptoEngine()
            val vault = InMemoryTestVault()
            val identity = IdentityManager(crypto, vault)
            val repo = MeshRepository(db, crypto, vault)
            val transport = FakeTransport()
            val router = MeshRouter(
                transport = transport,
                repository = repo,
                crypto = crypto,
                identityManager = identity,
                allowRelayForwardingProvider = { true },
                defaultTtlProvider = { 5 },
                dispatcher = Dispatchers.IO
            )
            val groupManager = GroupManager(repo, crypto, identity, router)

            val bob = Contact(
                nodeId = "bob_node",
                displayName = "Bob",
                signPublicKeyBase64 = crypto.toBase64(ByteArray(32) { 7 }),
                kxPublicKeyBase64 = crypto.toBase64(ByteArray(32) { 8 }),
                isVerified = true,
                safetyNumber = "11111 22222",
                lastSeenAt = System.currentTimeMillis()
            )
            val carol = Contact(
                nodeId = "carol_node",
                displayName = "Carol",
                signPublicKeyBase64 = crypto.toBase64(ByteArray(32) { 9 }),
                kxPublicKeyBase64 = crypto.toBase64(ByteArray(32) { 10 }),
                isVerified = true,
                safetyNumber = "33333 44444",
                lastSeenAt = System.currentTimeMillis()
            )
            repo.upsertContact(bob)
            repo.upsertContact(carol)

            val groupId = groupManager.createGroup(
                name = "Field Rescue",
                initialMembers = listOf(bob, carol),
                invitePolicy = GroupInvitePolicy.ADMINS_ONLY,
                creationPolicy = GroupCreationPolicy.VERIFIED_ONLY,
                selfDisplayName = "Alice"
            ).getOrThrow()

            val epoch1Key = repo.loadGroupKey(groupId, 1)!!
            val newEpoch = groupManager.removeMemberAndRotateKey(groupId, bob.nodeId).getOrThrow()
            assertEquals(2, newEpoch)
            val epoch2Key = repo.loadGroupKey(groupId, 2)!!
            assertFalse(epoch1Key.contentEquals(epoch2Key))

            val membersAfter = db.groupDao().getMembers(groupId)
            assertFalse(membersAfter.any { it.nodeId == bob.nodeId })
            assertTrue(membersAfter.any { it.nodeId == carol.nodeId })
        } finally {
            db.close()
        }
    }
}
