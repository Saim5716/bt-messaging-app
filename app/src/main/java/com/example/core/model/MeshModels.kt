package com.example.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AutoDeleteOption(val durationMillis: Long?, val label: String) {
    OFF(null, "Off"),
    ONE_HOUR(60 * 60 * 1000L, "1 Hour"),
    TWENTY_FOUR_HOURS(24 * 60 * 60 * 1000L, "24 Hours")
}

@Serializable
enum class GroupCreationPolicy(val label: String) {
    ANYONE("Anyone"),
    VERIFIED_ONLY("QR-Verified Contacts Only")
}

@Serializable
enum class GroupInvitePolicy(val label: String) {
    ADMINS_ONLY("Administrators Only"),
    ALL_MEMBERS("All Members")
}

@Serializable
enum class GroupRole {
    ADMIN,
    MEMBER
}

@Serializable
enum class MessageStatus {
    QUEUED,
    SENT,
    RELAYED,
    DELIVERED,
    READ,
    FAILED
}

@Serializable
enum class MediaCategory {
    PHOTO,
    VIDEO,
    DOCUMENT
}

@Serializable
enum class TransferDirection {
    OUTGOING,
    INCOMING
}

@Serializable
enum class TransferStatus {
    PREPARING,
    IN_PROGRESS,
    INTERRUPTED,
    VERIFYING,
    COMPLETED,
    FAILED
}

@Serializable
enum class PacketType {
    IDENTITY_BEACON,
    DIRECT_MESSAGE,
    GROUP_INVITE,
    GROUP_KEY_ROTATION,
    GROUP_MEMBER_REMOVE,
    GROUP_MESSAGE,
    FILE_MANIFEST,
    FILE_CHUNK,
    FILE_ACK,
    DELIVERY_RECEIPT,
    READ_RECEIPT
}

@Serializable
data class LocalIdentity(
    val nodeId: String,
    val displayName: String,
    val signPublicKeyBase64: String,
    val kxPublicKeyBase64: String,
    val safetyFingerprint: String,
    val createdAt: Long
)

@Serializable
data class QrIdentityPayload(
    val version: Int = 1,
    val nodeId: String,
    val displayName: String,
    val signPublicKeyBase64: String,
    val kxPublicKeyBase64: String,
    val timestamp: Long,
    val signatureBase64: String
)

data class Contact(
    val nodeId: String,
    val displayName: String,
    val signPublicKeyBase64: String,
    val kxPublicKeyBase64: String,
    val isVerified: Boolean,
    val safetyNumber: String,
    val lastSeenAt: Long,
    val lastRssi: Int? = null
)

data class ConversationSummary(
    val conversationId: String,
    val title: String,
    val isGroup: Boolean,
    val peerNodeId: String?,
    val groupId: String?,
    val lastMessagePreview: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int,
    val isVerified: Boolean,
    val autoDeleteOption: AutoDeleteOption
)

data class ChatMessage(
    val messageId: String,
    val conversationId: String,
    val senderNodeId: String,
    val senderName: String,
    val isOutgoing: Boolean,
    val textContent: String,
    val attachmentTransferId: String? = null,
    val attachmentName: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentLocalPath: String? = null,
    val mediaCategory: MediaCategory? = null,
    val timestamp: Long,
    val status: MessageStatus,
    val hopCount: Int = 0,
    val expiresAt: Long? = null
)

data class GroupInfo(
    val groupId: String,
    val name: String,
    val createdByNodeId: String,
    val invitePolicy: GroupInvitePolicy,
    val keyEpoch: Int,
    val createdAt: Long,
    val memberCount: Int = 0,
    val isSelfAdmin: Boolean = false
)

data class GroupMember(
    val groupId: String,
    val nodeId: String,
    val displayName: String,
    val role: GroupRole,
    val isVerified: Boolean,
    val joinedAt: Long
)

data class FileTransferSession(
    val transferId: String,
    val conversationId: String,
    val peerOrGroupId: String,
    val isGroup: Boolean,
    val fileName: String,
    val mimeType: String,
    val mediaCategory: MediaCategory,
    val totalSize: Long,
    val totalChunks: Int,
    val completedChunks: Int,
    val sha256Hex: String,
    val direction: TransferDirection,
    val status: TransferStatus,
    val localFilePath: String?,
    val updatedAt: Long
)

data class NearbyPeer(
    val deviceAddress: String,
    val nodeId: String?,
    val displayName: String,
    val rssi: Int,
    val isConnected: Boolean,
    val isMeshCapable: Boolean,
    val hopDistance: Int,
    val lastSeenAt: Long,
    val isVerifiedContact: Boolean
)

data class BleAdapterStatus(
    val isBluetoothSupported: Boolean = true,
    val isBluetoothEnabled: Boolean = false,
    val hasRequiredPermissions: Boolean = false,
    val isAdvertising: Boolean = false,
    val isScanning: Boolean = false,
    val connectedCount: Int = 0,
    val statusMessage: String = "Idle"
)

data class MeshRouteLogEntry(
    val id: String,
    val packetId: String,
    val packetType: PacketType,
    val originNodeId: String,
    val targetNodeId: String,
    val hopCount: Int,
    val ttlRemaining: Int,
    val action: String,
    val timestamp: Long
)

data class MeshSettings(
    val displayName: String = "Mesh Node",
    val autoDeleteOption: AutoDeleteOption = AutoDeleteOption.OFF,
    val groupCreationPolicy: GroupCreationPolicy = GroupCreationPolicy.ANYONE,
    val defaultTtl: Int = 5,
    val backgroundRelayEnabled: Boolean = true,
    val allowRelayForwarding: Boolean = true
)

@Serializable
data class MeshPacket(
    val packetId: String,
    val packetType: PacketType,
    val originNodeId: String,
    val targetNodeId: String,
    val lastHopNodeId: String,
    val ttl: Int,
    val hopCount: Int,
    val maxHops: Int,
    val timestamp: Long,
    val payloadBase64: String,
    val signatureBase64: String
)

@Serializable
data class IdentityBeaconPayload(
    val nodeId: String,
    val displayName: String,
    val signPublicKeyBase64: String,
    val kxPublicKeyBase64: String,
    val timestamp: Long
)

@Serializable
data class DirectMessagePayload(
    val messageId: String,
    val senderNodeId: String,
    val senderName: String,
    val ciphertextBase64: String,
    val sentAt: Long,
    val autoDeleteDurationMillis: Long? = null
)

@Serializable
data class GroupInvitePayload(
    val groupId: String,
    val groupName: String,
    val createdByNodeId: String,
    val invitePolicy: GroupInvitePolicy,
    val keyEpoch: Int,
    val wrappedGroupKeyBase64: String,
    val members: List<GroupMemberWire>
)

@Serializable
data class GroupMemberWire(
    val nodeId: String,
    val displayName: String,
    val signPublicKeyBase64: String,
    val kxPublicKeyBase64: String,
    val role: GroupRole
)

@Serializable
data class GroupKeyRotationPayload(
    val groupId: String,
    val newEpoch: Int,
    val removedNodeId: String?,
    val wrappedGroupKeyBase64: String,
    val updatedMembers: List<GroupMemberWire>
)

@Serializable
data class GroupMessagePayload(
    val messageId: String,
    val groupId: String,
    val keyEpoch: Int,
    val senderNodeId: String,
    val senderName: String,
    val ciphertextBase64: String,
    val sentAt: Long,
    val autoDeleteDurationMillis: Long? = null
)

@Serializable
data class FileManifestPayload(
    val transferId: String,
    val conversationId: String,
    val isGroup: Boolean,
    val groupId: String?,
    val keyEpoch: Int?,
    val senderNodeId: String,
    val fileName: String,
    val mimeType: String,
    val mediaCategory: MediaCategory,
    val totalSize: Long,
    val chunkSize: Int,
    val totalChunks: Int,
    val sha256Hex: String,
    val wrappedFileKeyBase64: String
)

@Serializable
data class FileChunkPayload(
    val transferId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val encryptedChunkBase64: String,
    val chunkSha256Hex: String
)

@Serializable
data class FileAckPayload(
    val transferId: String,
    val receivedIndices: List<Int>,
    val missingIndices: List<Int>,
    val completed: Boolean
)

@Serializable
data class ReceiptPayload(
    val messageId: String,
    val conversationId: String,
    val receiverNodeId: String,
    val timestamp: Long
)
