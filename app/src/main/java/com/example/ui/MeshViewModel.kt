package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.OfflineMeshApp
import com.example.core.model.AutoDeleteOption
import com.example.core.model.BleAdapterStatus
import com.example.core.model.ChatMessage
import com.example.core.model.Contact
import com.example.core.model.ConversationSummary
import com.example.core.model.FileTransferSession
import com.example.core.model.GroupCreationPolicy
import com.example.core.model.GroupInfo
import com.example.core.model.GroupInvitePolicy
import com.example.core.model.GroupMember
import com.example.core.model.LocalIdentity
import com.example.core.model.MediaCategory
import com.example.core.model.MeshRouteLogEntry
import com.example.core.model.MeshSettings
import com.example.core.model.NearbyPeer
import com.example.transport.ble.MeshForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MeshViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OfflineMeshApp).container
    private val appContext: Context = application.applicationContext

    private val selfNodeId: String by lazy {
        container.identityManager.deriveNodeId(
            container.identityManager.ensureKeyBundle().signPublicKey
        )
    }

    val settings: StateFlow<MeshSettings> = container.settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MeshSettings()
    )

    val localIdentity: StateFlow<LocalIdentity> = settings.map { s ->
        container.identityManager.getLocalIdentity(s.displayName)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = container.identityManager.getLocalIdentity("Mesh Node")
    )

    val bleStatus: StateFlow<BleAdapterStatus> = container.bleTransport.adapterStatus
    val nearbyPeers: StateFlow<List<NearbyPeer>> = container.bleTransport.nearbyPeers
    val routeLogs: StateFlow<List<MeshRouteLogEntry>> = container.meshRouter.routeLogs
    val pendingRelayCount: StateFlow<Int> = container.repository.observePendingRelayCount().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0
    )

    val conversations: StateFlow<List<ConversationSummary>> =
        container.repository.observeConversations().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val contacts: StateFlow<List<Contact>> =
        container.repository.observeContacts().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val groups: StateFlow<List<GroupInfo>> =
        container.repository.observeGroups(selfNodeId).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val fileTransfers: StateFlow<List<FileTransferSession>> =
        container.repository.observeFileTransfers().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun refreshBleStatus() {
        container.bleTransport.refreshStatus()
    }

    fun startMeshRadio() {
        container.bleTransport.refreshStatus()
        if (settings.value.backgroundRelayEnabled) {
            MeshForegroundService.start(appContext)
        } else {
            container.bleTransport.startMesh()
        }
        viewModelScope.launch(Dispatchers.IO) {
            container.messagingCoordinator.broadcastIdentityBeacon()
        }
    }

    fun stopMeshRadio() {
        MeshForegroundService.stop(appContext)
        container.bleTransport.stopMesh()
    }

    fun connectPeer(deviceAddress: String) {
        container.bleTransport.connectToPeer(deviceAddress)
    }

    fun disconnectPeer(deviceAddress: String) {
        container.bleTransport.disconnectPeer(deviceAddress)
    }

    fun broadcastIdentityBeacon() {
        viewModelScope.launch(Dispatchers.IO) {
            container.messagingCoordinator.broadcastIdentityBeacon()
            _events.emit("Identity beacon broadcast to connected peers")
        }
    }

    fun flushOfflineQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            container.meshRouter.flushPendingRelays()
            _events.emit("Flushed offline store-and-forward queue")
        }
    }

    fun generateMyQrBitmap(): Bitmap {
        val uri = container.identityManager.buildSignedQrUri(settings.value.displayName)
        return container.identityManager.generateQrBitmap(uri, 640)
    }

    fun getMyQrUriString(): String =
        container.identityManager.buildSignedQrUri(settings.value.displayName)

    fun verifyScannedQr(rawQr: String, onVerified: (Contact) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = container.identityManager.verifyAndParseQrUri(
                rawQr = rawQr,
                selfDisplayName = settings.value.displayName
            )
            result.onSuccess { contact ->
                container.repository.upsertContact(contact)
                container.messagingCoordinator.ensureDirectConversation(contact)
                _events.emit("Verified ${contact.displayName} via Ed25519 QR")
                onVerified(contact)
            }.onFailure { err ->
                _events.emit("QR verification failed: ${err.message ?: "Invalid payload"}")
            }
        }
    }

    fun openChatWithContact(contact: Contact, onReady: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val convId = container.messagingCoordinator.ensureDirectConversation(contact)
            onReady(convId)
        }
    }

    fun observeConversation(conversationId: String): Flow<ConversationSummary?> =
        container.repository.observeConversation(conversationId)

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        container.repository.observeMessages(conversationId)

    fun observeGroup(groupId: String): Flow<GroupInfo?> =
        container.repository.observeGroup(groupId, selfNodeId)

    fun observeGroupMembers(groupId: String): Flow<List<GroupMember>> =
        container.repository.observeGroupMembers(groupId)

    fun markConversationRead(conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.messagingCoordinator.markConversationRead(conversationId)
        }
    }

    fun sendMessage(conversationId: String, isGroup: Boolean, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = if (isGroup) {
                container.messagingCoordinator.sendGroupMessage(conversationId, text)
            } else {
                container.messagingCoordinator.sendDirectMessage(conversationId, text)
            }
            res.onFailure { err ->
                _events.emit(err.message ?: "Failed to send message")
            }
        }
    }

    fun sendMedia(
        uri: Uri,
        conversationId: String,
        targetPeerOrGroupId: String,
        isGroup: Boolean,
        category: MediaCategory
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            container.messagingCoordinator.sendMediaAttachment(
                uri = uri,
                conversationId = conversationId,
                targetPeerOrGroupId = targetPeerOrGroupId,
                isGroup = isGroup,
                mediaCategory = category
            ).onSuccess {
                _events.emit("Encrypted ${category.name.lowercase()} queued for mesh transfer")
            }.onFailure { err ->
                _events.emit(err.message ?: "File transfer failed")
            }
        }
    }

    fun resumeFileTransfer(transferId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.fileTransferManager.resumeTransfer(transferId)
                .onSuccess { _events.emit("Resumed encrypted chunk transfer") }
                .onFailure { _events.emit(it.message ?: "Resume failed") }
        }
    }

    fun createGroup(
        name: String,
        memberNodeIds: Set<String>,
        invitePolicy: GroupInvitePolicy,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val selectedContacts = contacts.value.filter { memberNodeIds.contains(it.nodeId) }
            container.groupManager.createGroup(
                name = name,
                initialMembers = selectedContacts,
                invitePolicy = invitePolicy,
                creationPolicy = settings.value.groupCreationPolicy,
                selfDisplayName = settings.value.displayName
            ).onSuccess { groupId ->
                _events.emit("Created encrypted group (Epoch 1)")
                onCreated(groupId)
            }.onFailure { err ->
                _events.emit(err.message ?: "Cannot create group")
            }
        }
    }

    fun inviteMemberToGroup(groupId: String, contact: Contact) {
        viewModelScope.launch(Dispatchers.IO) {
            container.groupManager.inviteMember(groupId, contact)
                .onSuccess { _events.emit("Invited ${contact.displayName} with wrapped group key") }
                .onFailure { _events.emit(it.message ?: "Invite rejected") }
        }
    }

    fun updateGroupInvitePolicy(groupId: String, policy: GroupInvitePolicy) {
        viewModelScope.launch(Dispatchers.IO) {
            container.groupManager.updateInvitePolicy(groupId, policy)
                .onSuccess { _events.emit("Updated group invite policy") }
                .onFailure { _events.emit(it.message ?: "Policy update failed") }
        }
    }

    fun removeGroupMemberAndRotate(groupId: String, memberNodeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.groupManager.removeMemberAndRotateKey(groupId, memberNodeId)
                .onSuccess { epoch -> _events.emit("Member removed; rotated group key to Epoch $epoch") }
                .onFailure { _events.emit(it.message ?: "Removal failed") }
        }
    }

    fun updateConversationAutoDelete(conversationId: String, option: AutoDeleteOption) {
        viewModelScope.launch(Dispatchers.IO) {
            container.repository.conversationDao.updateAutoDelete(conversationId, option.name)
            _events.emit("Chat auto-delete set to ${option.label}")
        }
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.settingsStore.updateDisplayName(name)
        }
    }

    fun updateAutoDeleteSetting(option: AutoDeleteOption) {
        viewModelScope.launch(Dispatchers.IO) {
            container.settingsStore.updateAutoDelete(option)
        }
    }

    fun updateGroupCreationPolicy(policy: GroupCreationPolicy) {
        viewModelScope.launch(Dispatchers.IO) {
            container.settingsStore.updateGroupCreationPolicy(policy)
        }
    }

    fun updateDefaultTtl(ttl: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            container.settingsStore.updateDefaultTtl(ttl)
        }
    }

    fun updateBackgroundRelay(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            container.settingsStore.updateBackgroundRelay(enabled)
            if (!enabled) {
                MeshForegroundService.stop(appContext)
            }
        }
    }

    fun updateAllowRelayForwarding(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            container.settingsStore.updateAllowRelayForwarding(enabled)
        }
    }

    fun clearAllLocalData() {
        viewModelScope.launch(Dispatchers.IO) {
            container.repository.clearAllLocalData()
            _events.emit("Cleared all local messages, contacts, and groups")
        }
    }
}
