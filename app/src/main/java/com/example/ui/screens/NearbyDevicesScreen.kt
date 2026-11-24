package com.example.ui.screens

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
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.model.BleAdapterStatus
import com.example.core.model.MeshRouteLogEntry
import com.example.core.model.NearbyPeer
import com.example.ui.components.SignalStrengthBars
import com.example.ui.components.VerificationBadge
import com.example.ui.components.formatShortTime

@Composable
fun NearbyDevicesScreen(
    bleStatus: BleAdapterStatus,
    nearbyPeers: List<NearbyPeer>,
    routeLogs: List<MeshRouteLogEntry>,
    onStartMesh: () -> Unit,
    onConnectPeer: (String) -> Unit,
    onDisconnectPeer: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (bleStatus.connectedCount > 0) Icons.Filled.BluetoothConnected
                            else Icons.Filled.BluetoothSearching,
                            contentDescription = "BLE Radio",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("BLE Mesh Radar", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = bleStatus.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!bleStatus.isScanning && bleStatus.hasRequiredPermissions && bleStatus.isBluetoothEnabled) {
                        Button(
                            onClick = onStartMesh,
                            modifier = Modifier.testTag("nearby_start_scan_button")
                        ) {
                            Text("Scan")
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Discovered BLE Nodes (${nearbyPeers.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (nearbyPeers.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No BLE OfflineMesh nodes detected nearby yet. Ensure Bluetooth is enabled on both Android devices and Mesh Radio is running.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(nearbyPeers, key = { it.deviceAddress }) { peer ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
                                VerificationBadge(isVerified = peer.isVerifiedContact)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${peer.deviceAddress} • ${peer.rssi} dBm" +
                                    (peer.nodeId?.let { " • Node $it" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        SignalStrengthBars(rssi = peer.rssi)
                        Spacer(modifier = Modifier.width(10.dp))
                        if (peer.isConnected) {
                            OutlinedButton(
                                onClick = { onDisconnectPeer(peer.deviceAddress) },
                                modifier = Modifier.testTag("disconnect_${peer.deviceAddress}")
                            ) {
                                Text("Unlink")
                            }
                        } else {
                            Button(
                                onClick = { onConnectPeer(peer.deviceAddress) },
                                modifier = Modifier.testTag("connect_${peer.deviceAddress}")
                            ) {
                                Text("Link")
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Route,
                    contentDescription = "Mesh Routing Log",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Multi-Hop Routing & Deduplication Log",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        if (routeLogs.isEmpty()) {
            item {
                Text(
                    text = "Packet relay, TTL, hop limits, and deduplication events will appear here when packets move across the mesh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(routeLogs, key = { it.id }) { log ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${log.packetType.name} • ${log.action}",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = "pkt:${log.packetId} • hop:${log.hopCount} • ttl:${log.ttlRemaining}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = formatShortTime(log.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
