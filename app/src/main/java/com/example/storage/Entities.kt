package com.example.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val nodeId: String,
    val displayName: String,
    val signPublicKeyBase64: String,
    val kxPublicKeyBase64: String,
    val isVerified: Boolean,
    val safetyNumber: String,
    val lastSeenAt: Long,
    val lastRssi: Int? = null
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val conversationId: String,
    val title: String,
    val isGroup: Boolean,
    val peerNodeId: String?,
    val groupId: String?,
    val lastMessagePreview: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int,
    val autoDeleteName: String = "OFF"
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId"), Index("expiresAt")]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val senderNodeId: String,
    val senderName: String,
    val isOutgoing: Boolean,
    val textContent: String,
    val attachmentTransferId: String? = null,
    val attachmentName: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentLocalPath: String? = null,
    val mediaCategoryName: String? = null,
    val timestamp: Long,
    val statusName: String,
    val hopCount: Int = 0,
    val expiresAt: Long? = null
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val createdByNodeId: String,
    val invitePolicyName: String,
    val keyEpoch: Int,
    val createdAt: Long
)

@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "nodeId"]
)
data class GroupMemberEntity(
    val groupId: String,
    val nodeId: String,
    val displayName: String,
    val roleName: String,
    val joinedAt: Long
)

@Entity(
    tableName = "group_keys",
    primaryKeys = ["groupId", "epoch"]
)
data class GroupKeyEntity(
    val groupId: String,
    val epoch: Int,
    val wrappedKeyBase64: String,
    val createdAt: Long
)

@Entity(tableName = "file_transfers")
data class FileTransferEntity(
    @PrimaryKey val transferId: String,
    val conversationId: String,
    val peerOrGroupId: String,
    val isGroup: Boolean,
    val fileName: String,
    val mimeType: String,
    val mediaCategoryName: String,
    val totalSize: Long,
    val chunkSize: Int,
    val totalChunks: Int,
    val sha256Hex: String,
    val wrappedFileKeyBase64: String,
    val directionName: String,
    val statusName: String,
    val sourceUriString: String?,
    val localFilePath: String?,
    val updatedAt: Long
)

@Entity(
    tableName = "file_chunks",
    primaryKeys = ["transferId", "chunkIndex"]
)
data class FileChunkEntity(
    val transferId: String,
    val chunkIndex: Int,
    val chunkSha256Hex: String,
    val encryptedPayloadBase64: String,
    val isTransferred: Boolean
)

@Entity(tableName = "seen_packets")
data class SeenPacketEntity(
    @PrimaryKey val packetId: String,
    val seenAt: Long,
    val expiresAt: Long
)

@Entity(tableName = "pending_relays")
data class PendingRelayEntity(
    @PrimaryKey val packetId: String,
    val targetNodeId: String,
    val packetJson: String,
    val createdAt: Long,
    val expiresAt: Long,
    val attempts: Int = 0
)
