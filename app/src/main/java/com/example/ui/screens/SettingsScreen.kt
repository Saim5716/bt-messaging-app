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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.example.core.model.AutoDeleteOption
import com.example.core.model.GroupCreationPolicy
import com.example.core.model.LocalIdentity
import com.example.core.model.MeshSettings
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: MeshSettings,
    identity: LocalIdentity,
    onUpdateDisplayName: (String) -> Unit,
    onUpdateAutoDelete: (AutoDeleteOption) -> Unit,
    onUpdateGroupCreationPolicy: (GroupCreationPolicy) -> Unit,
    onUpdateDefaultTtl: (Int) -> Unit,
    onUpdateAllowRelayForwarding: (Boolean) -> Unit,
    onUpdateBackgroundRelay: (Boolean) -> Unit,
    onClearAllData: () -> Unit
) {
    var nameDraft by remember(settings.displayName) { mutableStateOf(settings.displayName) }
    var showClearDialog by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Node Identity", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_display_name_input")
                    )
                    Button(
                        onClick = { onUpdateDisplayName(nameDraft) },
                        enabled = nameDraft.isNotBlank() && nameDraft.trim() != settings.displayName,
                        modifier = Modifier.testTag("settings_save_name_button")
                    ) {
                        Text("Save Node Name")
                    }
                    Text(
                        text = "Node ID: ${identity.nodeId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Ed25519 Fingerprint: ${identity.safetyFingerprint}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Default Message Auto-Delete", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Automatically purges local messages after expiration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AutoDeleteOption.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onUpdateAutoDelete(option) }
                        ) {
                            RadioButton(
                                selected = settings.autoDeleteOption == option,
                                onClick = { onUpdateAutoDelete(option) }
                            )
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Group Creation Policy", style = MaterialTheme.typography.titleMedium)
                    GroupCreationPolicy.entries.forEach { policy ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onUpdateGroupCreationPolicy(policy) }
                        ) {
                            RadioButton(
                                selected = settings.groupCreationPolicy == policy,
                                onClick = { onUpdateGroupCreationPolicy(policy) }
                            )
                            Text(policy.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Mesh TTL & Hop Limit: ${settings.defaultTtl} hops",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Slider(
                        value = settings.defaultTtl.toFloat(),
                        onValueChange = { onUpdateDefaultTtl(it.roundToInt()) },
                        valueRange = 1f..7f,
                        steps = 5
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Multi-Hop Relay Forwarding", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Forward opaque encrypted packets for nearby peers without decrypting.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = settings.allowRelayForwarding,
                            onCheckedChange = onUpdateAllowRelayForwarding
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background BLE Service", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Keeps connectedDevice BLE mesh relay alive when app is minimized.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = settings.backgroundRelayEnabled,
                            onCheckedChange = onUpdateBackgroundRelay
                        )
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = { showClearDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_clear_data_button")
            ) {
                Text("Wipe Local Chats, Groups & Queue")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Wipe Local Storage?") },
            text = {
                Text("This permanently deletes all local Room chats, groups, contacts, and queued packets on this device.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAllData()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Wipe")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
