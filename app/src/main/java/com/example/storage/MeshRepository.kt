package com.example.storage

import com.example.core.model.AutoDeleteOption
import com.example.core.model.ChatMessage
import com.example.core.model.Contact
import com.example.core.model.ConversationSummary
import com.example.core.model.FileTransferSession
import com.example.core.model.GroupInfo
import com.example.core.model.GroupInvitePolicy
import com.example.core.model.GroupMember
import com.example.core.model.GroupRole
import com.example.core.model.MediaCategory
import com.example.core.model.MessageStatus
import com.example.core.model.TransferDirection
import com.example.core.model.TransferStatus
import com.example.crypto.CryptoEngine
import com.example.crypto.SecretVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class MeshRepository(
    private val db: OfflineMeshDatabase,
    private val crypto: CryptoEngine,
    private val vault: SecretVault
) {
    val contactsDao = db.contactDao()
    val conversationDao = db.conversationDao()
    val messageDao = db.messageDao()
    val groupDao = db.groupDao()
    val fileTransferDao = db.fileTransferDao()
    val routingDao = db.routingDao()

    fun observeContacts(): Flow<List<Contact>> =
        contactsDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeContact(nodeId: String): Flow<Contact?> =
        contactsDao.observeById(nodeId).map { it?.toDomain() }

    suspend fun getContact(nodeId: String): Contact? =
        contactsDao.getById(nodeId)?.toDomain()

    suspend fun upsertContact(contact: Contact) {
        contactsDao.upsert(contact.toEntity())
    }

    fun observeConversations(): Flow<List<ConversationSummary>> =
        combine(
            conversationDao.observeAll(),
            contactsDao.observeAll()
        ) { conversations, contacts ->
            val contactMap = contacts.associateBy { it.nodeId }
            conversations.map { conv ->
                val contact = conv.peerNodeId?.let { contactMap[it] }
                ConversationSummary(
                    conversationId = conv.conversationId,
                    title = contact?.displayName ?: conv.title,
                    isGroup = conv.isGroup,
                    peerNodeId = conv.peerNodeId,
                    groupId = conv.groupId,
                    lastMessagePreview = conv.lastMessagePreview,
                    lastMessageTimestamp = conv.lastMessageTimestamp,
                    unreadCount = conv.unreadCount,
                    isVerified = if (conv.isGroup) true else (contact?.isVerified ?: false),
                    autoDeleteOption = runCatching {
                        AutoDeleteOption.valueOf(conv.autoDeleteName)
                    }.getOrDefault(AutoDeleteOption.OFF)
                )
            }
        }

    fun observeConversation(conversationId: String): Flow<ConversationSummary?> =
        observeConversations().map { list -> list.firstOrNull { it.conversationId == conversationId } }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.observeForConversation(conversationId).map { list -> list.map { it.toDomain() } }

    fun observeGroups(selfNodeId: String): Flow<List<GroupInfo>> =
        groupDao.observeAllGroups().map { groups ->
            groups.map { g ->
                val members = groupDao.getMembers(g.groupId)
                val selfRole = members.firstOrNull { it.nodeId == selfNodeId }?.roleName
                GroupInfo(
                    groupId = g.groupId,
                    name = g.name,
                    createdByNodeId = g.createdByNodeId,
                    invitePolicy = runCatching {
                        GroupInvitePolicy.valueOf(g.invitePolicyName)
                    }.getOrDefault(GroupInvitePolicy.ADMINS_ONLY),
                    keyEpoch = g.keyEpoch,
                    createdAt = g.createdAt,
                    memberCount = members.size,
                    isSelfAdmin = selfRole == GroupRole.ADMIN.name
                )
            }
        }

    fun observeGroup(groupId: String, selfNodeId: String): Flow<GroupInfo?> =
        combine(
            groupDao.observeGroup(groupId),
            groupDao.observeMembers(groupId)
        ) { group, members ->
            group?.let {
                val selfRole = members.firstOrNull { m -> m.nodeId == selfNodeId }?.roleName
                GroupInfo(
                    groupId = it.groupId,
                    name = it.name,
                    createdByNodeId = it.createdByNodeId,
                    invitePolicy = runCatching {
                        GroupInvitePolicy.valueOf(it.invitePolicyName)
                    }.getOrDefault(GroupInvitePolicy.ADMINS_ONLY),
                    keyEpoch = it.keyEpoch,
                    createdAt = it.createdAt,
                    memberCount = members.size,
                    isSelfAdmin = selfRole == GroupRole.ADMIN.name
                )
            }
        }

    fun observeGroupMembers(groupId: String): Flow<List<GroupMember>> =
        combine(
            groupDao.observeMembers(groupId),
            contactsDao.observeAll()
        ) { members, contacts ->
            val verifiedIds = contacts.filter { it.isVerified }.map { it.nodeId }.toSet()
            members.map { m ->
                GroupMember(
                    groupId = m.groupId,
                    nodeId = m.nodeId,
                    displayName = m.displayName,
                    role = runCatching { GroupRole.valueOf(m.roleName) }.getOrDefault(GroupRole.MEMBER),
                    isVerified = verifiedIds.contains(m.nodeId),
                    joinedAt = m.joinedAt
                )
            }
        }

    suspend fun saveGroupKey(groupId: String, epoch: Int, rawKey: ByteArray) {
        val wrapped = vault.wrapWithKeystore(rawKey)
        groupDao.upsertGroupKey(
            GroupKeyEntity(
                groupId = groupId,
                epoch = epoch,
                wrappedKeyBase64 = crypto.toBase64(wrapped),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun loadGroupKey(groupId: String, epoch: Int? = null): ByteArray? {
        val entity = if (epoch != null) {
            groupDao.getGroupKey(groupId, epoch)
        } else {
            groupDao.getLatestGroupKey(groupId)
        } ?: return null
        return runCatching {
            vault.unwrapWithKeystore(crypto.fromBase64(entity.wrappedKeyBase64))
        }.getOrNull()
    }

    fun observeFileTransfers(): Flow<List<FileTransferSession>> =
        fileTransferDao.observeAll().map { transfers ->
            transfers.map { t ->
                val completed = fileTransferDao.getCompletedChunkIndices(t.transferId).size
                FileTransferSession(
                    transferId = t.transferId,
                    conversationId = t.conversationId,
                    peerOrGroupId = t.peerOrGroupId,
                    isGroup = t.isGroup,
                    fileName = t.fileName,
                    mimeType = t.mimeType,
                    mediaCategory = runCatching {
                        MediaCategory.valueOf(t.mediaCategoryName)
                    }.getOrDefault(MediaCategory.DOCUMENT),
                    totalSize = t.totalSize,
                    totalChunks = t.totalChunks,
                    completedChunks = completed,
                    sha256Hex = t.sha256Hex,
                    direction = runCatching {
                        TransferDirection.valueOf(t.directionName)
                    }.getOrDefault(TransferDirection.OUTGOING),
                    status = runCatching {
                        TransferStatus.valueOf(t.statusName)
                    }.getOrDefault(TransferStatus.IN_PROGRESS),
                    localFilePath = t.localFilePath,
                    updatedAt = t.updatedAt
                )
            }
        }

    fun observePendingRelayCount(): Flow<Int> = routingDao.observePendingRelayCount()

    suspend fun purgeExpiredMessages(now: Long = System.currentTimeMillis()): Int =
        messageDao.deleteExpired(now)

    suspend fun clearAllLocalData() {
        messageDao.deleteAll()
        conversationDao.deleteAll()
        groupDao.deleteAllMembers()
        groupDao.deleteAllKeys()
        groupDao.deleteAllGroups()
        fileTransferDao.deleteAllChunks()
        fileTransferDao.deleteAllTransfers()
        contactsDao.deleteAll()
        routingDao.deleteAllSeen()
        routingDao.deleteAllRelays()
    }

    private fun ContactEntity.toDomain() = Contact(
        nodeId = nodeId,
        displayName = displayName,
        signPublicKeyBase64 = signPublicKeyBase64,
        kxPublicKeyBase64 = kxPublicKeyBase64,
        isVerified = isVerified,
        safetyNumber = safetyNumber,
        lastSeenAt = lastSeenAt,
        lastRssi = lastRssi
    )

    private fun Contact.toEntity() = ContactEntity(
        nodeId = nodeId,
        displayName = displayName,
        signPublicKeyBase64 = signPublicKeyBase64,
        kxPublicKeyBase64 = kxPublicKeyBase64,
        isVerified = isVerified,
        safetyNumber = safetyNumber,
        lastSeenAt = lastSeenAt,
        lastRssi = lastRssi
    )

    private fun MessageEntity.toDomain() = ChatMessage(
        messageId = messageId,
        conversationId = conversationId,
        senderNodeId = senderNodeId,
        senderName = senderName,
        isOutgoing = isOutgoing,
        textContent = textContent,
        attachmentTransferId = attachmentTransferId,
        attachmentName = attachmentName,
        attachmentMimeType = attachmentMimeType,
        attachmentLocalPath = attachmentLocalPath,
        mediaCategory = mediaCategoryName?.let {
            runCatching { MediaCategory.valueOf(it) }.getOrNull()
        },
        timestamp = timestamp,
        status = runCatching { MessageStatus.valueOf(statusName) }.getOrDefault(MessageStatus.QUEUED),
        hopCount = hopCount,
        expiresAt = expiresAt
    )
}
