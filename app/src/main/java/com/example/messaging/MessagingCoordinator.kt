package com.example.messaging

import android.net.Uri
import com.example.core.model.AutoDeleteOption
import com.example.core.model.Contact
import com.example.core.model.DirectMessagePayload
import com.example.core.model.FileAckPayload
import com.example.core.model.FileChunkPayload
import com.example.core.model.FileManifestPayload
import com.example.core.model.GroupInvitePayload
import com.example.core.model.GroupKeyRotationPayload
import com.example.core.model.GroupMessagePayload
import com.example.core.model.IdentityBeaconPayload
import com.example.core.model.MediaCategory
import com.example.core.model.MessageStatus
import com.example.core.model.PacketType
import com.example.core.model.ReceiptPayload
import com.example.crypto.CryptoEngine
import com.example.filetransfer.FileTransferManager
import com.example.group.GroupManager
import com.example.identity.IdentityManager
import com.example.routing.InboundLocalPacket
import com.example.routing.MeshRouter
import com.example.storage.ConversationEntity
import com.example.storage.MeshRepository
import com.example.storage.MessageEntity
import com.example.storage.SettingsDataStore
import com.example.transport.ble.MeshTransport
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class MessagingCoordinator(
    private val repository: MeshRepository,
    private val crypto: CryptoEngine,
    private val identityManager: IdentityManager,
    private val transport: MeshTransport,
    private val router: MeshRouter,
    private val groupManager: GroupManager,
    private val fileTransferManager: FileTransferManager,
    private val settingsStore: SettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        scope.launch {
            router.inboundForLocalNode.collect { inbound ->
                handleInboundPacket(inbound)
            }
        }
        scope.launch {
            transport.peerConnectedEvents.collect {
                broadcastIdentityBeacon()
            }
        }
        scope.launch {
            while (true) {
                repository.purgeExpiredMessages()
                delay(15_000L)
            }
        }
    }

    suspend fun broadcastIdentityBeacon() {
        val settings = settingsStore.settingsFlow.first()
        val keys = identityManager.ensureKeyBundle()
        val nodeId = identityManager.deriveNodeId(keys.signPublicKey)
        val beacon = IdentityBeaconPayload(
            nodeId = nodeId,
            displayName = settings.displayName,
            signPublicKeyBase64 = crypto.toBase64(keys.signPublicKey),
            kxPublicKeyBase64 = crypto.toBase64(keys.kxPublicKey),
            timestamp = System.currentTimeMillis()
        )
        router.createAndSendPacket(
            packetType = PacketType.IDENTITY_BEACON,
            targetNodeId = MeshRouter.TARGET_BROADCAST,
            payloadBytes = json.encodeToString(IdentityBeaconPayload.serializer(), beacon)
                .toByteArray(Charsets.UTF_8),
            maxHops = 1
        )
    }

    suspend fun ensureDirectConversation(contact: Contact): String {
        val existing = repository.conversationDao.getById(contact.nodeId)
        if (existing == null) {
            val settings = settingsStore.settingsFlow.first()
            repository.conversationDao.upsert(
                ConversationEntity(
                    conversationId = contact.nodeId,
                    title = contact.displayName,
                    isGroup = false,
                    peerNodeId = contact.nodeId,
                    groupId = null,
                    lastMessagePreview = "Encrypted P2P channel ready",
                    lastMessageTimestamp = System.currentTimeMillis(),
                    unreadCount = 0,
                    autoDeleteName = settings.autoDeleteOption.name
                )
            )
        }
        return contact.nodeId
    }

    suspend fun sendDirectMessage(peerNodeId: String, text: String): Result<Unit> = runCatching {
        val cleanText = text.trim()
        require(cleanText.isNotBlank()) { "Message cannot be blank" }

        val contact = repository.getContact(peerNodeId) ?: error("Peer contact not found")
        val settings = settingsStore.settingsFlow.first()
        val conv = repository.conversationDao.getById(peerNodeId)
        val autoDelete = conv?.let {
            runCatching { AutoDeleteOption.valueOf(it.autoDeleteName) }.getOrNull()
        } ?: settings.autoDeleteOption

        val selfKeys = identityManager.ensureKeyBundle()
        val selfNodeId = identityManager.deriveNodeId(selfKeys.signPublicKey)
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val expiresAt = autoDelete.durationMillis?.let { now + it }

        val sharedKey = crypto.deriveSharedKey(
            selfKeys.kxSecretKey,
            crypto.fromBase64(contact.kxPublicKeyBase64),
            "dm|$selfNodeId|$peerNodeId|$messageId"
        )
        val ad = "$messageId|$selfNodeId|$peerNodeId|$now".toByteArray(Charsets.UTF_8)
        val ciphertext = crypto.encryptAead(sharedKey, cleanText.toByteArray(Charsets.UTF_8), ad)
        sharedKey.fill(0)

        repository.messageDao.upsert(
            MessageEntity(
                messageId = messageId,
                conversationId = peerNodeId,
                senderNodeId = selfNodeId,
                senderName = settings.displayName,
                isOutgoing = true,
                textContent = cleanText,
                timestamp = now,
                statusName = MessageStatus.QUEUED.name,
                hopCount = 0,
                expiresAt = expiresAt
            )
        )
        repository.conversationDao.upsert(
            ConversationEntity(
                conversationId = peerNodeId,
                title = contact.displayName,
                isGroup = false,
                peerNodeId = peerNodeId,
                groupId = null,
                lastMessagePreview = cleanText,
                lastMessageTimestamp = now,
                unreadCount = 0,
                autoDeleteName = autoDelete.name
            )
        )

        val wirePayload = DirectMessagePayload(
            messageId = messageId,
            senderNodeId = selfNodeId,
            senderName = settings.displayName,
            ciphertextBase64 = crypto.toBase64(ciphertext),
            sentAt = now,
            autoDeleteDurationMillis = autoDelete.durationMillis
        )
        router.createAndSendPacket(
            packetType = PacketType.DIRECT_MESSAGE,
            targetNodeId = peerNodeId,
            payloadBytes = json.encodeToString(DirectMessagePayload.serializer(), wirePayload)
                .toByteArray(Charsets.UTF_8)
        )
        val isDirectConnected = transport.getDeviceAddressForNode(peerNodeId) != null
        if (isDirectConnected) {
            repository.messageDao.updateStatus(messageId, MessageStatus.SENT.name)
        }
    }

    suspend fun sendGroupMessage(groupId: String, text: String): Result<Unit> = runCatching {
        val cleanText = text.trim()
        require(cleanText.isNotBlank()) { "Message cannot be blank" }

        val group = repository.groupDao.getGroup(groupId) ?: error("Group not found")
        val groupKey = repository.loadGroupKey(groupId, group.keyEpoch)
            ?: error("Group encryption key missing")
        val settings = settingsStore.settingsFlow.first()
        val conv = repository.conversationDao.getById(groupId)
        val autoDelete = conv?.let {
            runCatching { AutoDeleteOption.valueOf(it.autoDeleteName) }.getOrNull()
        } ?: settings.autoDeleteOption

        val selfKeys = identityManager.ensureKeyBundle()
        val selfNodeId = identityManager.deriveNodeId(selfKeys.signPublicKey)
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val expiresAt = autoDelete.durationMillis?.let { now + it }

        val ad = "$messageId|$groupId|${group.keyEpoch}|$selfNodeId|$now".toByteArray(Charsets.UTF_8)
        val ciphertext = crypto.encryptAead(groupKey, cleanText.toByteArray(Charsets.UTF_8), ad)
        groupKey.fill(0)

        repository.messageDao.upsert(
            MessageEntity(
                messageId = messageId,
                conversationId = groupId,
                senderNodeId = selfNodeId,
                senderName = settings.displayName,
                isOutgoing = true,
                textContent = cleanText,
                timestamp = now,
                statusName = MessageStatus.SENT.name,
                hopCount = 0,
                expiresAt = expiresAt
            )
        )
        repository.conversationDao.upsert(
            ConversationEntity(
                conversationId = groupId,
                title = group.name,
                isGroup = true,
                peerNodeId = null,
                groupId = groupId,
                lastMessagePreview = "${settings.displayName}: $cleanText",
                lastMessageTimestamp = now,
                unreadCount = 0,
                autoDeleteName = autoDelete.name
            )
        )

        val wirePayload = GroupMessagePayload(
            messageId = messageId,
            groupId = groupId,
            keyEpoch = group.keyEpoch,
            senderNodeId = selfNodeId,
            senderName = settings.displayName,
            ciphertextBase64 = crypto.toBase64(ciphertext),
            sentAt = now,
            autoDeleteDurationMillis = autoDelete.durationMillis
        )
        val encoded = json.encodeToString(GroupMessagePayload.serializer(), wirePayload)
            .toByteArray(Charsets.UTF_8)

        val members = repository.groupDao.getMembers(groupId).filter { it.nodeId != selfNodeId }
        for (member in members) {
            router.createAndSendPacket(PacketType.GROUP_MESSAGE, member.nodeId, encoded)
        }
    }

    suspend fun sendMediaAttachment(
        uri: Uri,
        conversationId: String,
        targetPeerOrGroupId: String,
        isGroup: Boolean,
        mediaCategory: MediaCategory
    ): Result<Unit> = runCatching {
        val transfer = fileTransferManager.sendEncryptedFile(
            uri = uri,
            conversationId = conversationId,
            targetPeerOrGroupId = targetPeerOrGroupId,
            isGroup = isGroup,
            mediaCategory = mediaCategory
        ).getOrThrow()

        val settings = settingsStore.settingsFlow.first()
        val selfNodeId = identityManager.deriveNodeId(identityManager.ensureKeyBundle().signPublicKey)
        val now = System.currentTimeMillis()
        val expiresAt = settings.autoDeleteOption.durationMillis?.let { now + it }

        repository.messageDao.upsert(
            MessageEntity(
                messageId = "msg_${transfer.transferId}",
                conversationId = conversationId,
                senderNodeId = selfNodeId,
                senderName = settings.displayName,
                isOutgoing = true,
                textContent = "Encrypted ${mediaCategory.name.lowercase()}: ${transfer.fileName}",
                attachmentTransferId = transfer.transferId,
                attachmentName = transfer.fileName,
                attachmentMimeType = transfer.mimeType,
                attachmentLocalPath = transfer.localFilePath,
                mediaCategoryName = mediaCategory.name,
                timestamp = now,
                statusName = MessageStatus.SENT.name,
                hopCount = 0,
                expiresAt = expiresAt
            )
        )
        val existingConv = repository.conversationDao.getById(conversationId)
        repository.conversationDao.upsert(
            ConversationEntity(
                conversationId = conversationId,
                title = existingConv?.title ?: conversationId,
                isGroup = isGroup,
                peerNodeId = if (isGroup) null else targetPeerOrGroupId,
                groupId = if (isGroup) targetPeerOrGroupId else null,
                lastMessagePreview = "Sent ${transfer.fileName}",
                lastMessageTimestamp = now,
                unreadCount = 0,
                autoDeleteName = existingConv?.autoDeleteName ?: settings.autoDeleteOption.name
            )
        )
    }

    suspend fun markConversationRead(conversationId: String) {
        repository.conversationDao.clearUnread(conversationId)
        val conv = repository.conversationDao.getById(conversationId) ?: return
        if (!conv.isGroup && conv.peerNodeId != null) {
            val messages = repository.messageDao.observeForConversation(conversationId).first()
            val selfNodeId = identityManager.deriveNodeId(identityManager.ensureKeyBundle().signPublicKey)
            messages.filter { !it.isOutgoing && it.statusName != MessageStatus.READ.name }.forEach { msg ->
                repository.messageDao.updateStatus(msg.messageId, MessageStatus.READ.name)
                val receipt = ReceiptPayload(
                    messageId = msg.messageId,
                    conversationId = conversationId,
                    receiverNodeId = selfNodeId,
                    timestamp = System.currentTimeMillis()
                )
                router.createAndSendPacket(
                    PacketType.READ_RECEIPT,
                    conv.peerNodeId,
                    json.encodeToString(ReceiptPayload.serializer(), receipt).toByteArray(Charsets.UTF_8)
                )
            }
        }
    }

    private suspend fun handleInboundPacket(inbound: InboundLocalPacket) {
        val packet = inbound.packet
        val payloadStr = crypto.fromBase64(packet.payloadBase64).toString(Charsets.UTF_8)

        when (packet.packetType) {
            PacketType.IDENTITY_BEACON -> {
                val beacon = runCatching {
                    json.decodeFromString(IdentityBeaconPayload.serializer(), payloadStr)
                }.getOrNull() ?: return
                val existing = repository.getContact(beacon.nodeId)
                val safetyNumber = existing?.safetyNumber
                    ?: identityManager.computeSafetyNumberWith(beacon.signPublicKeyBase64)
                val contact = Contact(
                    nodeId = beacon.nodeId,
                    displayName = beacon.displayName,
                    signPublicKeyBase64 = beacon.signPublicKeyBase64,
                    kxPublicKeyBase64 = beacon.kxPublicKeyBase64,
                    isVerified = existing?.isVerified ?: false,
                    safetyNumber = safetyNumber,
                    lastSeenAt = System.currentTimeMillis(),
                    lastRssi = existing?.lastRssi
                )
                repository.upsertContact(contact)
                transport.associateNodeWithDevice(
                    deviceAddress = inbound.sourceDeviceAddress,
                    nodeId = beacon.nodeId,
                    displayName = beacon.displayName,
                    isVerified = contact.isVerified
                )
            }

            PacketType.DIRECT_MESSAGE -> {
                val dm = runCatching {
                    json.decodeFromString(DirectMessagePayload.serializer(), payloadStr)
                }.getOrNull() ?: return
                val senderContact = repository.getContact(dm.senderNodeId) ?: return
                val selfKeys = identityManager.ensureKeyBundle()
                val selfNodeId = identityManager.deriveNodeId(selfKeys.signPublicKey)

                val sharedKey = crypto.deriveSharedKey(
                    selfKeys.kxSecretKey,
                    crypto.fromBase64(senderContact.kxPublicKeyBase64),
                    "dm|${dm.senderNodeId}|$selfNodeId|${dm.messageId}"
                )
                val ad = "${dm.messageId}|${dm.senderNodeId}|$selfNodeId|${dm.sentAt}".toByteArray(Charsets.UTF_8)
                val plaintext = runCatching {
                    crypto.decryptAead(sharedKey, crypto.fromBase64(dm.ciphertextBase64), ad)
                        .toString(Charsets.UTF_8)
                }.getOrNull() ?: return
                sharedKey.fill(0)

                val now = System.currentTimeMillis()
                val expiresAt = dm.autoDeleteDurationMillis?.let { now + it }

                repository.messageDao.upsert(
                    MessageEntity(
                        messageId = dm.messageId,
                        conversationId = dm.senderNodeId,
                        senderNodeId = dm.senderNodeId,
                        senderName = senderContact.displayName,
                        isOutgoing = false,
                        textContent = plaintext,
                        timestamp = dm.sentAt,
                        statusName = MessageStatus.DELIVERED.name,
                        hopCount = packet.hopCount,
                        expiresAt = expiresAt
                    )
                )
                val existingConv = repository.conversationDao.getById(dm.senderNodeId)
                repository.conversationDao.upsert(
                    ConversationEntity(
                        conversationId = dm.senderNodeId,
                        title = senderContact.displayName,
                        isGroup = false,
                        peerNodeId = dm.senderNodeId,
                        groupId = null,
                        lastMessagePreview = plaintext,
                        lastMessageTimestamp = dm.sentAt,
                        unreadCount = (existingConv?.unreadCount ?: 0) + 1,
                        autoDeleteName = existingConv?.autoDeleteName ?: AutoDeleteOption.OFF.name
                    )
                )

                val ack = ReceiptPayload(dm.messageId, dm.senderNodeId, selfNodeId, now)
                router.createAndSendPacket(
                    PacketType.DELIVERY_RECEIPT,
                    dm.senderNodeId,
                    json.encodeToString(ReceiptPayload.serializer(), ack).toByteArray(Charsets.UTF_8)
                )
            }

            PacketType.GROUP_INVITE -> {
                val invite = runCatching {
                    json.decodeFromString(GroupInvitePayload.serializer(), payloadStr)
                }.getOrNull() ?: return
                groupManager.handleIncomingInvite(packet.originNodeId, invite)
            }

            PacketType.GROUP_KEY_ROTATION -> {
                val rot = runCatching {
                    json.decodeFromString(GroupKeyRotationPayload.serializer(), payloadStr)
                }.getOrNull() ?: return
                groupManager.handleIncomingKeyRotation(packet.originNodeId, rot)
            }

            PacketType.GROUP_MESSAGE -> {
                val gm = runCatching {
                    json.decodeFromString(GroupMessagePayload.serializer(), payloadStr)
                }.getOrNull() ?: return
                val group = repository.groupDao.getGroup(gm.groupId) ?: return
                val groupKey = repository.loadGroupKey(gm.groupId, gm.keyEpoch) ?: return
                val ad = "${gm.messageId}|${gm.groupId}|${gm.keyEpoch}|${gm.senderNodeId}|${gm.sentAt}"
                    .toByteArray(Charsets.UTF_8)
                val plaintext = runCatching {
                    crypto.decryptAead(groupKey, crypto.fromBase64(gm.ciphertextBase64), ad)
                        .toString(Charsets.UTF_8)
                }.getOrNull() ?: return
                groupKey.fill(0)

                val now = System.currentTimeMillis()
                val expiresAt = gm.autoDeleteDurationMillis?.let { now + it }
                repository.messageDao.upsert(
                    MessageEntity(
                        messageId = gm.messageId,
                        conversationId = gm.groupId,
                        senderNodeId = gm.senderNodeId,
                        senderName = gm.senderName,
                        isOutgoing = false,
                        textContent = plaintext,
                        timestamp = gm.sentAt,
                        statusName = MessageStatus.DELIVERED.name,
                        hopCount = packet.hopCount,
                        expiresAt = expiresAt
                    )
                )
                val existingConv = repository.conversationDao.getById(gm.groupId)
                repository.conversationDao.upsert(
                    ConversationEntity(
                        conversationId = gm.groupId,
                        title = group.name,
                        isGroup = true,
                        peerNodeId = null,
                        groupId = gm.groupId,
                        lastMessagePreview = "${gm.senderName}: $plaintext",
                        lastMessageTimestamp = gm.sentAt,
                        unreadCount = (existingConv?.unreadCount ?: 0) + 1,
                        autoDeleteName = existingConv?.autoDeleteName ?: AutoDeleteOption.OFF.name
                    )
                )
            }

            PacketType.FILE_MANIFEST -> {
                val manifest = runCatching {
                    json.decodeFromString(FileManifestPayload.serializer(), payloadStr)
                }.getOrNull() ?: return
                fileTransferManager.handleIncomingManifest(packet.originNodeId, manifest)
                val convId = if (manifest.isGroup && manifest.groupId != null) manifest.groupId else packet.originNodeId
                val senderContact = repository.getContact(packet.originNodeId)
                repository.messageDao.upsert(
                    MessageEntity(
                        messageId = "msg_${manifest.transferId}",
                        conversationId = convId,
                        senderNodeId = packet.originNodeId,
                        senderName = senderContact?.displayName ?: packet.originNodeId.take(6),
                        isOutgoing = false,
                        textContent = "Receiving encrypted ${manifest.mediaCategory.name.lowercase()}: ${manifest.fileName}",
                        attachmentTransferId = manifest.transferId,
                        attachmentName = manifest.fileName,
                        attachmentMimeType = manifest.mimeType,
                        attachmentLocalPath = null,
                        mediaCategoryName = manifest.mediaCategory.name,
                        timestamp = packet.timestamp,
                        statusName = MessageStatus.DELIVERED.name,
                        hopCount = packet.hopCount
                    )
                )
            }

            PacketType.FILE_CHUNK -> {
                val chunk = runCatching {
                    json.decodeFromString(FileChunkPayload.serializer(), payloadStr)
                }.getOrNull() ?: return
                fileTransferManager.handleIncomingChunk(packet.originNodeId, chunk)
            }

            PacketType.FILE_ACK -> {
                val ack = runCatching {
                    json.decodeFromString(FileAckPayload.serializer(), payloadStr)
                }.getOrNull() ?: return
                fileTransferManager.handleIncomingAck(ack)
            }

            PacketType.DELIVERY_RECEIPT -> {
                val receipt = runCatching {
                    json.decodeFromString(ReceiptPayload.serializer(), payloadStr)
                }.getOrNull() ?: return
                repository.messageDao.updateStatus(receipt.messageId, MessageStatus.DELIVERED.name)
            }

            PacketType.READ_RECEIPT -> {
                val receipt = runCatching {
                    json.decodeFromString(ReceiptPayload.serializer(), payloadStr)
                }.getOrNull() ?: return
                repository.messageDao.updateStatus(receipt.messageId, MessageStatus.READ.name)
            }

            PacketType.GROUP_MEMBER_REMOVE -> Unit
        }
    }
}
