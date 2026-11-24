package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.core.model.ConversationSummary
import com.example.core.model.FileTransferSession
import com.example.core.model.MediaCategory
import com.example.core.model.TransferStatus
import com.example.ui.components.formatBytes
import java.io.File

@Composable
fun FileSharingScreen(
    transfers: List<FileTransferSession>,
    conversations: List<ConversationSummary>,
    onSendMedia: (uri: Uri, conversationId: String, targetId: String, isGroup: Boolean, category: MediaCategory) -> Unit,
    onResumeTransfer: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedConversation by remember(conversations) { mutableStateOf(conversations.firstOrNull()) }
    var showTargetDropdown by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val conv = selectedConversation ?: return@rememberLauncherForActivityResult
        val target = conv.groupId ?: conv.peerNodeId ?: return@rememberLauncherForActivityResult
        uri?.let { onSendMedia(it, conv.conversationId, target, conv.isGroup, MediaCategory.PHOTO) }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val conv = selectedConversation ?: return@rememberLauncherForActivityResult
        val target = conv.groupId ?: conv.peerNodeId ?: return@rememberLauncherForActivityResult
        uri?.let { onSendMedia(it, conv.conversationId, target, conv.isGroup, MediaCategory.VIDEO) }
    }

    val docPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val conv = selectedConversation ?: return@rememberLauncherForActivityResult
        val target = conv.groupId ?: conv.peerNodeId ?: return@rememberLauncherForActivityResult
        uri?.let { onSendMedia(it, conv.conversationId, target, conv.isGroup, MediaCategory.DOCUMENT) }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Encrypted Chunked Media Transfer", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "Files are encrypted with XChaCha20-Poly1305, split into 4 KB verified chunks, and support interrupted-transfer recovery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (conversations.isEmpty()) {
                        Text(
                            text = "Verify or connect a peer first to choose a transfer destination.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    } else {
                        OutlinedButton(
                            onClick = { showTargetDropdown = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("file_target_selector")
                        ) {
                            Text("Destination: ${selectedConversation?.title ?: "Select Chat/Group"}")
                        }
                        DropdownMenu(
                            expanded = showTargetDropdown,
                            onDismissRequest = { showTargetDropdown = false }
                        ) {
                            conversations.forEach { conv ->
                                DropdownMenuItem(
                                    text = { Text("${conv.title} (${if (conv.isGroup) "Group" else "1:1"})") },
                                    onClick = {
                                        selectedConversation = conv
                                        showTargetDropdown = false
                                    }
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                enabled = selectedConversation != null,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("send_photo_button")
                            ) {
                                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Photo")
                            }
                            FilledTonalButton(
                                onClick = {
                                    videoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                },
                                enabled = selectedConversation != null,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("send_video_button")
                            ) {
                                Icon(Icons.Filled.VideoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Video")
                            }
                            FilledTonalButton(
                                onClick = { docPicker.launch(arrayOf("*/*")) },
                                enabled = selectedConversation != null,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("send_doc_button")
                            ) {
                                Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Doc")
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Transfer Queue & Integrity (${transfers.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (transfers.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No encrypted file transfers yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(transfers, key = { it.transferId }) { transfer ->
                val progress = if (transfer.totalChunks > 0) {
                    transfer.completedChunks.toFloat() / transfer.totalChunks.toFloat()
                } else 0f

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(transfer.fileName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "${transfer.direction.name} • ${formatBytes(transfer.totalSize)} • ${transfer.completedChunks}/${transfer.totalChunks} chunks",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (transfer.status == TransferStatus.COMPLETED) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "SHA-256 Verified",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )

                        Text(
                            text = "SHA-256: ${transfer.sha256Hex.take(24)}... • ${transfer.status.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (transfer.status == TransferStatus.INTERRUPTED || transfer.status == TransferStatus.IN_PROGRESS) {
                                Button(
                                    onClick = { onResumeTransfer(transfer.transferId) },
                                    modifier = Modifier.testTag("resume_transfer_${transfer.transferId}")
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Resume")
                                }
                            }
                            if (transfer.status == TransferStatus.COMPLETED && transfer.localFilePath != null) {
                                val file = File(transfer.localFilePath)
                                if (file.exists()) {
                                    OutlinedButton(
                                        onClick = {
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, transfer.mimeType)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            runCatching { context.startActivity(intent) }
                                        }
                                    ) {
                                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Open")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
