package com.example.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.model.Contact
import com.example.core.model.GroupCreationPolicy
import com.example.core.model.GroupInfo
import com.example.core.model.GroupInvitePolicy
import com.example.ui.components.VerificationBadge

@Composable
fun GroupsScreen(
    groups: List<GroupInfo>,
    contacts: List<Contact>,
    creationPolicy: GroupCreationPolicy,
    onCreateGroup: (name: String, memberIds: Set<String>, invitePolicy: GroupInvitePolicy) -> Unit,
    onOpenGroup: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("create_group_fab")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Create Group")
            }
        }
    ) { innerPadding ->
        if (groups.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Groups,
                    contentDescription = "No Groups",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No Peer Groups",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Create an encrypted peer group with configurable invitation rules and automatic key rotation when members leave.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.testTag("empty_create_group_button")
                ) {
                    Text("New Encrypted Group")
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(groups, key = { it.groupId }) { group ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("group_card_${group.groupId}")
                            .clickable { onOpenGroup(group.groupId) }
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Groups,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Key,
                                            contentDescription = "Key Epoch",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Epoch ${group.keyEpoch}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${group.memberCount} members",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Invites: ${group.invitePolicy.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (group.isSelfAdmin) {
                                    Icon(
                                        imageVector = Icons.Filled.AdminPanelSettings,
                                        contentDescription = "Group Admin",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateGroupDialog(
            contacts = contacts,
            creationPolicy = creationPolicy,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, selectedIds, invitePolicy ->
                onCreateGroup(name, selectedIds, invitePolicy)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun CreateGroupDialog(
    contacts: List<Contact>,
    creationPolicy: GroupCreationPolicy,
    onDismiss: () -> Unit,
    onConfirm: (String, Set<String>, GroupInvitePolicy) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var invitePolicy by remember { mutableStateOf(GroupInvitePolicy.ADMINS_ONLY) }

    val eligibleContacts = if (creationPolicy == GroupCreationPolicy.VERIFIED_ONLY) {
        contacts.filter { it.isVerified }
    } else {
        contacts
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Encrypted Group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Policy: ${creationPolicy.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Group Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_group_name_input")
                )

                Text("Invitation Permission", style = MaterialTheme.typography.titleSmall)
                GroupInvitePolicy.entries.forEach { policy ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { invitePolicy = policy }
                    ) {
                        RadioButton(
                            selected = invitePolicy == policy,
                            onClick = { invitePolicy = policy }
                        )
                        Text(policy.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Text("Initial Members (${selectedIds.size})", style = MaterialTheme.typography.titleSmall)
                if (eligibleContacts.isEmpty()) {
                    Text(
                        text = if (creationPolicy == GroupCreationPolicy.VERIFIED_ONLY) {
                            "No QR-verified contacts available yet."
                        } else {
                            "You can create a group now and invite peers later."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    eligibleContacts.take(5).forEach { c ->
                        val checked = selectedIds.contains(c.nodeId)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (checked) selectedIds - c.nodeId else selectedIds + c.nodeId
                                }
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    selectedIds = if (checked) selectedIds - c.nodeId else selectedIds + c.nodeId
                                }
                            )
                            Text(
                                text = c.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            VerificationBadge(isVerified = c.isVerified)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(groupName, selectedIds, invitePolicy) },
                enabled = groupName.isNotBlank() &&
                    (creationPolicy == GroupCreationPolicy.ANYONE || selectedIds.isNotEmpty()),
                modifier = Modifier.testTag("confirm_create_group_button")
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
