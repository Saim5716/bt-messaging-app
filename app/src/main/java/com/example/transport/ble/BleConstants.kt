package com.example.transport.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object BleConstants {
    val MESH_SERVICE_UUID: UUID = UUID.fromString("8f4e2a10-6c3b-4d9a-9e12-47f8c31b0001")
    val MESH_RX_CHAR_UUID: UUID = UUID.fromString("8f4e2a10-6c3b-4d9a-9e12-47f8c31b0002")
    val MESH_TX_CHAR_UUID: UUID = UUID.fromString("8f4e2a10-6c3b-4d9a-9e12-47f8c31b0003")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val DEFAULT_ATT_PAYLOAD_SIZE = 20
    const val REQUESTED_MTU = 517
    const val HEADER_BYTES = 8
    const val MAX_REASSEMBLED_BYTES = 256 * 1024

    fun requiredBlePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    fun hasBlePermissions(context: Context): Boolean {
        return requiredBlePermissions().all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }
}

class BleMeshFramer {
    private val frameCounter = AtomicInteger(1)
    private val incomingBuffers = ConcurrentHashMap<String, MutableMap<Int, FrameAssembly>>()

    private data class FrameAssembly(
        val totalChunks: Int,
        val chunks: Array<ByteArray?>,
        var receivedCount: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    )

    fun fragment(payload: ByteArray, mtuPayloadCapacity: Int): List<ByteArray> {
        val maxBody = (mtuPayloadCapacity - BleConstants.HEADER_BYTES).coerceAtLeast(12)
        val totalChunks = ((payload.size + maxBody - 1) / maxBody).coerceAtLeast(1)
        val frameId = frameCounter.getAndIncrement()

        return (0 until totalChunks).map { index ->
            val start = index * maxBody
            val end = minOf(start + maxBody, payload.size)
            val sliceLen = end - start
            ByteBuffer.allocate(BleConstants.HEADER_BYTES + sliceLen)
                .putInt(frameId)
                .putShort(index.toShort())
                .putShort(totalChunks.toShort())
                .put(payload, start, sliceLen)
                .array()
        }
    }

    fun acceptFragment(sourceKey: String, frameBytes: ByteArray): ByteArray? {
        if (frameBytes.size < BleConstants.HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(frameBytes)
        val frameId = buffer.int
        val index = buffer.short.toInt() and 0xFFFF
        val total = buffer.short.toInt() and 0xFFFF
        if (total <= 0 || index >= total || total > 4096) return null

        val body = ByteArray(buffer.remaining())
        buffer.get(body)

        val peerFrames = incomingBuffers.getOrPut(sourceKey) { ConcurrentHashMap() }
        pruneStale(peerFrames)

        val assembly = peerFrames.getOrPut(frameId) {
            FrameAssembly(totalChunks = total, chunks = arrayOfNulls(total))
        }
        if (assembly.totalChunks != total) return null

        if (assembly.chunks[index] == null) {
            assembly.chunks[index] = body
            assembly.receivedCount++
        }

        if (assembly.receivedCount < assembly.totalChunks) {
            return null
        }

        peerFrames.remove(frameId)
        val totalSize = assembly.chunks.sumOf { it?.size ?: 0 }
        if (totalSize > BleConstants.MAX_REASSEMBLED_BYTES) return null

        val out = ByteArray(totalSize)
        var offset = 0
        for (chunk in assembly.chunks) {
            val part = chunk ?: return null
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    fun clearPeer(sourceKey: String) {
        incomingBuffers.remove(sourceKey)
    }

    private fun pruneStale(peerFrames: MutableMap<Int, FrameAssembly>) {
        val now = System.currentTimeMillis()
        val expired = peerFrames.entries.filter { now - it.value.createdAt > 30_000L }.map { it.key }
        expired.forEach { peerFrames.remove(it) }
    }
}
