package com.example.filetransfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.core.model.FileAckPayload
import com.example.core.model.FileChunkPayload
import com.example.core.model.FileManifestPayload
import com.example.core.model.MediaCategory
import com.example.core.model.PacketType
import com.example.core.model.TransferDirection
import com.example.core.model.TransferStatus
import com.example.crypto.CryptoEngine
import com.example.identity.IdentityManager
import com.example.routing.MeshRouter
import com.example.storage.FileChunkEntity
import com.example.storage.FileTransferEntity
import com.example.storage.MeshRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

class FileTransferManager(
    private val context: Context,
    private val repository: MeshRepository,
    private val crypto: CryptoEngine,
    private val identityManager: IdentityManager,
    private val router: MeshRouter
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaDir = File(context.filesDir, "mesh_media").apply { mkdirs() }

    suspend fun sendEncryptedFile(
        uri: Uri,
        conversationId: String,
        targetPeerOrGroupId: String,
        isGroup: Boolean,
        mediaCategory: MediaCategory
    ): Result<FileTransferEntity> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to read selected file")
        require(bytes.size <= MAX_MESH_FILE_BYTES) {
            "File exceeds 2 MB BLE mesh transfer limit"
        }

        val fileName = resolveFileName(uri) ?: "mesh_${System.currentTimeMillis()}"
        val mimeType = context.contentResolver.getType(uri) ?: when (mediaCategory) {
            MediaCategory.PHOTO -> "image/jpeg"
            MediaCategory.VIDEO -> "video/mp4"
            MediaCategory.DOCUMENT -> "application/octet-stream"
        }

        val transferId = UUID.randomUUID().toString()
        val selfKeys = identityManager.ensureKeyBundle()
        val selfNodeId = identityManager.deriveNodeId(selfKeys.signPublicKey)
        val fileKey = crypto.generateSymmetricKey()
        val fullSha256 = crypto.toHex(crypto.sha256(bytes))
        val totalChunks = ((bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE).coerceAtLeast(1)

        val keyEpoch: Int?
        val wrappedFileKey: ByteArray
        val recipientTargets: List<String>

        if (isGroup) {
            val group = repository.groupDao.getGroup(targetPeerOrGroupId) ?: error("Group not found")
            val groupKey = repository.loadGroupKey(group.groupId, group.keyEpoch)
                ?: error("Group encryption key missing")
            keyEpoch = group.keyEpoch
            wrappedFileKey = crypto.encryptAead(
                groupKey,
                fileKey,
                "file-key|$transferId|${group.groupId}|${group.keyEpoch}".toByteArray(Charsets.UTF_8)
            )
            groupKey.fill(0)
            recipientTargets = repository.groupDao.getMembers(group.groupId)
                .map { it.nodeId }
                .filter { it != selfNodeId }
        } else {
            val peer = repository.getContact(targetPeerOrGroupId) ?: error("Recipient contact not found")
            val shared = crypto.deriveSharedKey(
                selfKeys.kxSecretKey,
                crypto.fromBase64(peer.kxPublicKeyBase64),
                "file-key|$transferId"
            )
            keyEpoch = null
            wrappedFileKey = crypto.encryptAead(
                shared,
                fileKey,
                "file-key|$transferId".toByteArray(Charsets.UTF_8)
            )
            shared.fill(0)
            recipientTargets = listOf(targetPeerOrGroupId)
        }

        val localCopy = File(mediaDir, "${transferId}_${sanitizeFileName(fileName)}")
        localCopy.writeBytes(bytes)

        val wrappedFileKeyB64 = crypto.toBase64(wrappedFileKey)
        val now = System.currentTimeMillis()
        val transferEntity = FileTransferEntity(
            transferId = transferId,
            conversationId = conversationId,
            peerOrGroupId = targetPeerOrGroupId,
            isGroup = isGroup,
            fileName = fileName,
            mimeType = mimeType,
            mediaCategoryName = mediaCategory.name,
            totalSize = bytes.size.toLong(),
            chunkSize = CHUNK_SIZE,
            totalChunks = totalChunks,
            sha256Hex = fullSha256,
            wrappedFileKeyBase64 = wrappedFileKeyB64,
            directionName = TransferDirection.OUTGOING.name,
            statusName = TransferStatus.IN_PROGRESS.name,
            sourceUriString = uri.toString(),
            localFilePath = localCopy.absolutePath,
            updatedAt = now
        )
        repository.fileTransferDao.upsert(transferEntity)

        val chunkEntities = (0 until totalChunks).map { idx ->
            val start = idx * CHUNK_SIZE
            val end = minOf(start + CHUNK_SIZE, bytes.size)
            val plainSlice = bytes.copyOfRange(start, end)
            val encryptedSlice = crypto.encryptAead(
                fileKey,
                plainSlice,
                "$transferId|$idx".toByteArray(Charsets.UTF_8)
            )
            val chunkHash = crypto.toHex(crypto.sha256(encryptedSlice))
            FileChunkEntity(
                transferId = transferId,
                chunkIndex = idx,
                chunkSha256Hex = chunkHash,
                encryptedPayloadBase64 = crypto.toBase64(encryptedSlice),
                isTransferred = false
            )
        }
        fileKey.fill(0)
        repository.fileTransferDao.upsertChunks(chunkEntities)

        val manifest = FileManifestPayload(
            transferId = transferId,
            conversationId = conversationId,
            isGroup = isGroup,
            groupId = if (isGroup) targetPeerOrGroupId else null,
            keyEpoch = keyEpoch,
            senderNodeId = selfNodeId,
            fileName = fileName,
            mimeType = mimeType,
            mediaCategory = mediaCategory,
            totalSize = bytes.size.toLong(),
            chunkSize = CHUNK_SIZE,
            totalChunks = totalChunks,
            sha256Hex = fullSha256,
            wrappedFileKeyBase64 = wrappedFileKeyB64
        )
        val manifestBytes = json.encodeToString(FileManifestPayload.serializer(), manifest)
            .toByteArray(Charsets.UTF_8)

        for (targetNodeId in recipientTargets) {
            router.createAndSendPacket(PacketType.FILE_MANIFEST, targetNodeId, manifestBytes)
        }
        streamChunks(transferEntity, chunkEntities, recipientTargets)
        transferEntity
    }

    suspend fun resumeTransfer(transferId: String): Result<Unit> = runCatching {
        val transfer = repository.fileTransferDao.getById(transferId) ?: error("Transfer not found")
        val selfNodeId = identityManager.deriveNodeId(identityManager.ensureKeyBundle().signPublicKey)

        if (transfer.directionName == TransferDirection.OUTGOING.name) {
            repository.fileTransferDao.updateStatus(
                transferId,
                TransferStatus.IN_PROGRESS.name,
                null,
                System.currentTimeMillis()
            )
            val pending = repository.fileTransferDao.getPendingChunks(transferId)
            val targets = if (transfer.isGroup) {
                repository.groupDao.getMembers(transfer.peerOrGroupId)
                    .map { it.nodeId }
                    .filter { it != selfNodeId }
            } else {
                listOf(transfer.peerOrGroupId)
            }
            streamChunks(transfer, pending, targets)
        } else {
            // Incoming interrupted transfer: ask sender for missing chunk indices
            val received = repository.fileTransferDao.getCompletedChunkIndices(transferId).toSet()
            val missing = (0 until transfer.totalChunks).filterNot { received.contains(it) }
            val ack = FileAckPayload(
                transferId = transferId,
                receivedIndices = received.toList(),
                missingIndices = missing,
                completed = missing.isEmpty()
            )
            val senderTarget = transfer.peerOrGroupId
            router.createAndSendPacket(
                PacketType.FILE_ACK,
                senderTarget,
                json.encodeToString(FileAckPayload.serializer(), ack).toByteArray(Charsets.UTF_8)
            )
        }
    }

    suspend fun handleIncomingManifest(senderNodeId: String, manifest: FileManifestPayload) {
        val existing = repository.fileTransferDao.getById(manifest.transferId)
        if (existing != null) return

        val convId = if (manifest.isGroup && manifest.groupId != null) manifest.groupId else senderNodeId
        repository.fileTransferDao.upsert(
            FileTransferEntity(
                transferId = manifest.transferId,
                conversationId = convId,
                peerOrGroupId = if (manifest.isGroup && manifest.groupId != null) manifest.groupId else senderNodeId,
                isGroup = manifest.isGroup,
                fileName = manifest.fileName,
                mimeType = manifest.mimeType,
                mediaCategoryName = manifest.mediaCategory.name,
                totalSize = manifest.totalSize,
                chunkSize = manifest.chunkSize,
                totalChunks = manifest.totalChunks,
                sha256Hex = manifest.sha256Hex,
                wrappedFileKeyBase64 = manifest.wrappedFileKeyBase64,
                directionName = TransferDirection.INCOMING.name,
                statusName = TransferStatus.IN_PROGRESS.name,
                sourceUriString = null,
                localFilePath = null,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun handleIncomingChunk(senderNodeId: String, chunk: FileChunkPayload): String? {
        val transfer = repository.fileTransferDao.getById(chunk.transferId) ?: return null
        val encryptedBytes = crypto.fromBase64(chunk.encryptedChunkBase64)
        val computedHash = crypto.toHex(crypto.sha256(encryptedBytes))
        if (computedHash != chunk.chunkSha256Hex) {
            repository.fileTransferDao.updateStatus(
                chunk.transferId,
                TransferStatus.INTERRUPTED.name,
                null,
                System.currentTimeMillis()
            )
            return null
        }

        repository.fileTransferDao.upsertChunk(
            FileChunkEntity(
                transferId = chunk.transferId,
                chunkIndex = chunk.chunkIndex,
                chunkSha256Hex = chunk.chunkSha256Hex,
                encryptedPayloadBase64 = chunk.encryptedChunkBase64,
                isTransferred = true
            )
        )

        val receivedIndices = repository.fileTransferDao.getCompletedChunkIndices(chunk.transferId)
        val missing = (0 until transfer.totalChunks).filterNot { receivedIndices.contains(it) }

        if (missing.isNotEmpty()) {
            val ack = FileAckPayload(
                transferId = chunk.transferId,
                receivedIndices = receivedIndices,
                missingIndices = missing,
                completed = false
            )
            router.createAndSendPacket(
                PacketType.FILE_ACK,
                senderNodeId,
                json.encodeToString(FileAckPayload.serializer(), ack).toByteArray(Charsets.UTF_8)
            )
            return null
        }

        // All chunks present: unwrap file key, decrypt, and verify full SHA-256
        repository.fileTransferDao.updateStatus(
            chunk.transferId,
            TransferStatus.VERIFYING.name,
            null,
            System.currentTimeMillis()
        )
        return finalizeIncomingFile(transfer, senderNodeId)
    }

    suspend fun handleIncomingAck(ack: FileAckPayload) {
        for (idx in ack.receivedIndices) {
            repository.fileTransferDao.markChunkTransferred(ack.transferId, idx)
        }
        if (ack.completed) {
            repository.fileTransferDao.updateStatus(
                ack.transferId,
                TransferStatus.COMPLETED.name,
                null,
                System.currentTimeMillis()
            )
        } else if (ack.missingIndices.isNotEmpty()) {
            val transfer = repository.fileTransferDao.getById(ack.transferId) ?: return
            val allChunks = repository.fileTransferDao.getChunks(ack.transferId)
            val toResend = allChunks.filter { ack.missingIndices.contains(it.chunkIndex) }
            val selfNodeId = identityManager.deriveNodeId(identityManager.ensureKeyBundle().signPublicKey)
            val targets = if (transfer.isGroup) {
                repository.groupDao.getMembers(transfer.peerOrGroupId)
                    .map { it.nodeId }
                    .filter { it != selfNodeId }
            } else {
                listOf(transfer.peerOrGroupId)
            }
            streamChunks(transfer, toResend, targets)
        }
    }

    private suspend fun finalizeIncomingFile(transfer: FileTransferEntity, senderNodeId: String): String? {
        return runCatching {
            val fileKey = unwrapIncomingFileKey(transfer, senderNodeId)
            val chunks = repository.fileTransferDao.getChunks(transfer.transferId)
                .sortedBy { it.chunkIndex }
            val outputStream = ByteArrayOutputStream()

            for (c in chunks) {
                val enc = crypto.fromBase64(c.encryptedPayloadBase64)
                val dec = crypto.decryptAead(
                    fileKey,
                    enc,
                    "${transfer.transferId}|${c.chunkIndex}".toByteArray(Charsets.UTF_8)
                )
                outputStream.write(dec)
            }
            fileKey.fill(0)

            val plainBytes = outputStream.toByteArray()
            val fullHash = crypto.toHex(crypto.sha256(plainBytes))
            if (fullHash != transfer.sha256Hex) {
                repository.fileTransferDao.updateStatus(
                    transfer.transferId,
                    TransferStatus.FAILED.name,
                    null,
                    System.currentTimeMillis()
                )
                return null
            }

            val destFile = File(mediaDir, "${transfer.transferId}_${sanitizeFileName(transfer.fileName)}")
            destFile.writeBytes(plainBytes)

            repository.fileTransferDao.updateStatus(
                transfer.transferId,
                TransferStatus.COMPLETED.name,
                destFile.absolutePath,
                System.currentTimeMillis()
            )
            repository.messageDao.updateAttachmentPath(transfer.transferId, destFile.absolutePath)

            val ack = FileAckPayload(
                transferId = transfer.transferId,
                receivedIndices = (0 until transfer.totalChunks).toList(),
                missingIndices = emptyList(),
                completed = true
            )
            router.createAndSendPacket(
                PacketType.FILE_ACK,
                senderNodeId,
                json.encodeToString(FileAckPayload.serializer(), ack).toByteArray(Charsets.UTF_8)
            )
            destFile.absolutePath
        }.getOrElse {
            repository.fileTransferDao.updateStatus(
                transfer.transferId,
                TransferStatus.FAILED.name,
                null,
                System.currentTimeMillis()
            )
            null
        }
    }

    private suspend fun unwrapIncomingFileKey(transfer: FileTransferEntity, senderNodeId: String): ByteArray {
        val wrapped = crypto.fromBase64(transfer.wrappedFileKeyBase64)
        return if (transfer.isGroup) {
            val group = repository.groupDao.getGroup(transfer.peerOrGroupId) ?: error("Group missing")
            val groupKey = repository.loadGroupKey(group.groupId, group.keyEpoch)
                ?: error("Group key missing")
            try {
                crypto.decryptAead(
                    groupKey,
                    wrapped,
                    "file-key|${transfer.transferId}|${group.groupId}|${group.keyEpoch}".toByteArray(Charsets.UTF_8)
                )
            } finally {
                groupKey.fill(0)
            }
        } else {
            val sender = repository.getContact(senderNodeId) ?: error("Sender contact missing")
            val selfKeys = identityManager.ensureKeyBundle()
            val shared = crypto.deriveSharedKey(
                selfKeys.kxSecretKey,
                crypto.fromBase64(sender.kxPublicKeyBase64),
                "file-key|${transfer.transferId}"
            )
            try {
                crypto.decryptAead(
                    shared,
                    wrapped,
                    "file-key|${transfer.transferId}".toByteArray(Charsets.UTF_8)
                )
            } finally {
                shared.fill(0)
            }
        }
    }

    private suspend fun streamChunks(
        transfer: FileTransferEntity,
        chunks: List<FileChunkEntity>,
        targets: List<String>
    ) {
        var sentAll = true
        for (chunk in chunks) {
            val chunkPayload = FileChunkPayload(
                transferId = transfer.transferId,
                chunkIndex = chunk.chunkIndex,
                totalChunks = transfer.totalChunks,
                encryptedChunkBase64 = chunk.encryptedPayloadBase64,
                chunkSha256Hex = chunk.chunkSha256Hex
            )
            val encoded = json.encodeToString(FileChunkPayload.serializer(), chunkPayload)
                .toByteArray(Charsets.UTF_8)

            var anySent = false
            for (target in targets) {
                router.createAndSendPacket(PacketType.FILE_CHUNK, target, encoded)
                anySent = true
            }
            if (anySent) {
                repository.fileTransferDao.markChunkTransferred(transfer.transferId, chunk.chunkIndex)
            } else {
                sentAll = false
            }
            delay(35L)
        }
        val status = if (sentAll) TransferStatus.COMPLETED else TransferStatus.INTERRUPTED
        repository.fileTransferDao.updateStatus(
            transfer.transferId,
            status.name,
            null,
            System.currentTimeMillis()
        )
    }

    private fun resolveFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)

    companion object {
        const val CHUNK_SIZE = 4096
        const val MAX_MESH_FILE_BYTES = 2 * 1024 * 1024
    }
}
