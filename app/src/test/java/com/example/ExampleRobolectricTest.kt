package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.storage.MessageEntity
import com.example.storage.OfflineMeshDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("OfflineMesh", appName)
    }

    @Test
    fun `auto delete purges expired messages in room`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OfflineMeshDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val now = 1_700_000_000_000L
            db.messageDao().upsert(
                MessageEntity(
                    messageId = "expired_1",
                    conversationId = "peer_a",
                    senderNodeId = "peer_a",
                    senderName = "Alice",
                    isOutgoing = false,
                    textContent = "Ephemeral secret",
                    timestamp = now - 7_200_000L,
                    statusName = "DELIVERED",
                    expiresAt = now - 1000L
                )
            )
            db.messageDao().upsert(
                MessageEntity(
                    messageId = "active_1",
                    conversationId = "peer_a",
                    senderNodeId = "peer_a",
                    senderName = "Alice",
                    isOutgoing = false,
                    textContent = "Fresh message",
                    timestamp = now,
                    statusName = "DELIVERED",
                    expiresAt = now + 3_600_000L
                )
            )
            val deleted = db.messageDao().deleteExpired(now)
            assertEquals(1, deleted)
            val remaining = db.messageDao().getById("active_1")
            assertEquals("Fresh message", remaining?.textContent)
            assertEquals(null, db.messageDao().getById("expired_1"))
        } finally {
            db.close()
        }
    }
}
