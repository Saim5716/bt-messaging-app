package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.model.ChatMessage
import com.example.core.model.Contact
import com.example.core.model.GroupInfo
import com.example.core.model.GroupInvitePolicy
import com.example.core.model.GroupMember
import com.example.core.model.GroupRole
import com.example.core.model.MediaCategory
import com.example.ui.components.VerificationBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    group: GroupInfo?,
    members: List<GroupMember>,
    allContacts: List<Contact>,
    messages: List<ChatMessage>,
    selfNodeId: String,
    onSendMessage: (String) -> Unit,
    onSendMedia: (Uri, MediaCategory) -> Unit,
    onUpdateInvitePolicy: (GroupInvitePolicy) -> Unit,
    onInviteMember: (Contact) -> Unit,
    onRemoveAndRotate: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var inputText by remember { mutableStateOf("") }
    var showAdminDialog by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { onSendMedia(it, MediaCategory.PHOTO) } }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { onSendMedia(it, MediaCategory.VIDEO) } }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onSendMedia(it, MediaCategory.DOCUMENT) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(group?.name ?: "Peer Group", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "${members.size} members • Key Epoch ${group?.keyEpoch ?: 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("group_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAdminDialog = true },
                        modifier = Modifier.testTag("group_admin_button")
                    ) {
                        Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Group Administration")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(messages, key = { it.messageId }) { msg ->
                    MessageBubble(message = msg)
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    IconButton(
                        onClick = { showAttachMenu = true },
                        modifier = Modifier.testTag("group_attach_button")
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach file to group")
                    }
                    DropdownMenu(
                        expanded = showAttachMenu,
                        onDismissRequest = { showAttachMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Photo (Photo Picker)") },
                            leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                            onClick = {
                                showAttachMenu = false
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Video (Photo Picker)") },
                            leadingIcon = { Icon(Icons.Filled.VideoLibrary, contentDescription = null) },
                            onClick = {
                                showAttachMenu = false
                                videoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Document (Encrypted)") },
                            leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
                            onClick = {
                                showAttachMenu = false
                                documentPicker.launch(arrayOf("*/*"))
                            }
                        )
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Encrypted group message...") },
                        maxLines = 4,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("group_message_input")
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                onSendMessage(inputText)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank(),
                        modifier = Modifier.testTag("group_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send group message",
                            tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showAdminDialog && group != null) {
        GroupAdminDialog(
            group = group,
            members = members,
            allContacts = allContacts,
            selfNodeId = selfNodeId,
            onUpdateInvitePolicy = onUpdateInvitePolicy,
            onInviteMember = onInviteMember,
            onRemoveAndRotate = onRemoveAndRotate,
            onDismiss = { showAdminDialog = false }
        )
    }
}

@Composable
private fun GroupAdminDialog(
    group: GroupInfo,
    members: List<GroupMember>,
    allContacts: List<Contact>,
    selfNodeId: String,
    onUpdateInvitePolicy: (GroupInvitePolicy) -> Unit,
    onInviteMember: (Contact) -> Unit,
    onRemoveAndRotate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val canInvite = group.isSelfAdmin || group.invitePolicy == GroupInvitePolicy.ALL_MEMBERS
    val memberIds = members.map { it.nodeId }.toSet()
    val invitableContacts = allContacts.filterNot { memberIds.contains(it.nodeId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group Admin & Key Epoch ${group.keyEpoch}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Invitation Permission", style = MaterialTheme.typography.titleSmall)
                GroupInvitePolicy.entries.forEach { policy ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = group.isSelfAdmin) {
                                onUpdateInvitePolicy(policy)
                            }
                    ) {
                        RadioButton(
                            selected = group.invitePolicy == policy,
                            enabled = group.isSelfAdmin,
                            onClick = { onUpdateInvitePolicy(policy) }
                        )
                        Text(policy.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Text("Members (${members.size})", style = MaterialTheme.typography.titleSmall)
                members.forEach { m ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${m.displayName} (${m.role.name})",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            VerificationBadge(isVerified = m.isVerified)
                        }
                        if (group.isSelfAdmin && m.nodeId != selfNodeId && m.role != GroupRole.ADMIN) {
                            IconButton(
                                onClick = { onRemoveAndRotate(m.nodeId) },
                                modifier = Modifier.testTag("remove_member_${m.nodeId}")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PersonRemove,
                                    contentDescription = "Remove member and rotate group key",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                if (canInvite && invitableContacts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Invite Contact", style = MaterialTheme.typography.titleSmall)
                    invitableContacts.take(4).forEach { contact ->
                        OutlinedButton(
                            onClick = { onInviteMember(contact) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Invite ${contact.displayName}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
