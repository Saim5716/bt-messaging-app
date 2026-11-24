package com.example.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY isVerified DESC, displayName ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getById(nodeId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE nodeId = :nodeId LIMIT 1")
    fun observeById(nodeId: String): Flow<ContactEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE nodeId = :nodeId")
    suspend fun delete(nodeId: String)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE conversationId = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE conversationId = :id LIMIT 1")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE conversationId = :id")
    suspend fun clearUnread(id: String)

    @Query("UPDATE conversations SET autoDeleteName = :policyName WHERE conversationId = :id")
    suspend fun updateAutoDelete(id: String, policyName: String)

    @Query("DELETE FROM conversations WHERE conversationId = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getById(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("UPDATE messages SET statusName = :statusName WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, statusName: String)

    @Query("UPDATE messages SET attachmentLocalPath = :localPath WHERE attachmentTransferId = :transferId")
    suspend fun updateAttachmentPath(transferId: String, localPath: String)

    @Query("DELETE FROM messages WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun observeAllGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    fun observeGroup(groupId: String): Flow<GroupEntity?>

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroup(groupId: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: GroupEntity)

    @Query("UPDATE groups SET invitePolicyName = :policyName WHERE groupId = :groupId")
    suspend fun updateInvitePolicy(groupId: String, policyName: String)

    @Query("UPDATE groups SET keyEpoch = :epoch WHERE groupId = :groupId")
    suspend fun updateKeyEpoch(groupId: String, epoch: Int)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY roleName ASC, displayName ASC")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getMembers(groupId: String): List<GroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<GroupMemberEntity>)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND nodeId = :nodeId")
    suspend fun removeMember(groupId: String, nodeId: String)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteMembersForGroup(groupId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupKey(key: GroupKeyEntity)

    @Query("SELECT * FROM group_keys WHERE groupId = :groupId AND epoch = :epoch LIMIT 1")
    suspend fun getGroupKey(groupId: String, epoch: Int): GroupKeyEntity?

    @Query("SELECT * FROM group_keys WHERE groupId = :groupId ORDER BY epoch DESC LIMIT 1")
    suspend fun getLatestGroupKey(groupId: String): GroupKeyEntity?

    @Query("DELETE FROM groups WHERE groupId = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("DELETE FROM groups")
    suspend fun deleteAllGroups()

    @Query("DELETE FROM group_members")
    suspend fun deleteAllMembers()

    @Query("DELETE FROM group_keys")
    suspend fun deleteAllKeys()
}

@Dao
interface FileTransferDao {
    @Query("SELECT * FROM file_transfers ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<FileTransferEntity>>

    @Query("SELECT * FROM file_transfers WHERE transferId = :transferId LIMIT 1")
    suspend fun getById(transferId: String): FileTransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transfer: FileTransferEntity)

    @Query("UPDATE file_transfers SET statusName = :statusName, localFilePath = COALESCE(:localPath, localFilePath), updatedAt = :updatedAt WHERE transferId = :transferId")
    suspend fun updateStatus(transferId: String, statusName: String, localPath: String?, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunk(chunk: FileChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunks(chunks: List<FileChunkEntity>)

    @Query("SELECT * FROM file_chunks WHERE transferId = :transferId ORDER BY chunkIndex ASC")
    suspend fun getChunks(transferId: String): List<FileChunkEntity>

    @Query("SELECT COUNT(*) FROM file_chunks WHERE transferId = :transferId AND isTransferred = 1")
    fun observeCompletedChunkCount(transferId: String): Flow<Int>

    @Query("SELECT chunkIndex FROM file_chunks WHERE transferId = :transferId AND isTransferred = 1")
    suspend fun getCompletedChunkIndices(transferId: String): List<Int>

    @Query("UPDATE file_chunks SET isTransferred = 1 WHERE transferId = :transferId AND chunkIndex = :chunkIndex")
    suspend fun markChunkTransferred(transferId: String, chunkIndex: Int)

    @Query("SELECT * FROM file_chunks WHERE transferId = :transferId AND isTransferred = 0 ORDER BY chunkIndex ASC")
    suspend fun getPendingChunks(transferId: String): List<FileChunkEntity>

    @Query("SELECT * FROM file_transfers WHERE statusName IN ('IN_PROGRESS', 'INTERRUPTED', 'PREPARING')")
    suspend fun getResumableTransfers(): List<FileTransferEntity>

    @Query("DELETE FROM file_transfers")
    suspend fun deleteAllTransfers()

    @Query("DELETE FROM file_chunks")
    suspend fun deleteAllChunks()
}

@Dao
interface RoutingDao {
    @Query("SELECT EXISTS(SELECT 1 FROM seen_packets WHERE packetId = :packetId)")
    suspend fun hasSeen(packetId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(entity: SeenPacketEntity)

    @Query("DELETE FROM seen_packets WHERE expiresAt <= :now")
    suspend fun purgeExpiredSeen(now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueRelay(entity: PendingRelayEntity)

    @Query("SELECT * FROM pending_relays WHERE expiresAt > :now ORDER BY createdAt ASC")
    suspend fun getActiveRelays(now: Long): List<PendingRelayEntity>

    @Query("SELECT COUNT(*) FROM pending_relays")
    fun observePendingRelayCount(): Flow<Int>

    @Query("DELETE FROM pending_relays WHERE packetId = :packetId")
    suspend fun removeRelay(packetId: String)

    @Query("UPDATE pending_relays SET attempts = attempts + 1 WHERE packetId = :packetId")
    suspend fun incrementAttempt(packetId: String)

    @Query("DELETE FROM pending_relays WHERE expiresAt <= :now")
    suspend fun purgeExpiredRelays(now: Long)

    @Query("DELETE FROM seen_packets")
    suspend fun deleteAllSeen()

    @Query("DELETE FROM pending_relays")
    suspend fun deleteAllRelays()
}
