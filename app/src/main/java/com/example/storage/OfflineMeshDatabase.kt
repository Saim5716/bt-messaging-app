package com.example.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ContactEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        GroupKeyEntity::class,
        FileTransferEntity::class,
        FileChunkEntity::class,
        SeenPacketEntity::class,
        PendingRelayEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class OfflineMeshDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun groupDao(): GroupDao
    abstract fun fileTransferDao(): FileTransferDao
    abstract fun routingDao(): RoutingDao

    companion object {
        @Volatile
        private var INSTANCE: OfflineMeshDatabase? = null

        fun getInstance(context: Context): OfflineMeshDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OfflineMeshDatabase::class.java,
                    "offlinemesh.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
