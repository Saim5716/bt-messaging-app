package com.example.di

import android.content.Context
import com.example.core.model.MeshSettings
import com.example.crypto.CryptoEngine
import com.example.crypto.KeystoreVault
import com.example.crypto.LibsodiumCryptoEngine
import com.example.filetransfer.FileTransferManager
import com.example.group.GroupManager
import com.example.identity.IdentityManager
import com.example.messaging.MessagingCoordinator
import com.example.routing.MeshRouter
import com.example.storage.MeshRepository
import com.example.storage.OfflineMeshDatabase
import com.example.storage.SettingsDataStore
import com.example.transport.ble.BleMeshTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val cryptoEngine: CryptoEngine = LibsodiumCryptoEngine()
    val keystoreVault: KeystoreVault = KeystoreVault(appContext)
    val identityManager: IdentityManager = IdentityManager(cryptoEngine, keystoreVault)
    val database: OfflineMeshDatabase = OfflineMeshDatabase.getInstance(appContext)
    val settingsStore: SettingsDataStore = SettingsDataStore(appContext)
    val repository: MeshRepository = MeshRepository(database, cryptoEngine, keystoreVault)

    private val currentSettings = settingsStore.settingsFlow.stateIn(
        scope = appScope,
        started = SharingStarted.Eagerly,
        initialValue = MeshSettings()
    )

    val bleTransport: BleMeshTransport = BleMeshTransport(
        context = appContext,
        localNodeIdProvider = {
            identityManager.deriveNodeId(identityManager.ensureKeyBundle().signPublicKey)
        }
    )

    val meshRouter: MeshRouter = MeshRouter(
        transport = bleTransport,
        repository = repository,
        crypto = cryptoEngine,
        identityManager = identityManager,
        allowRelayForwardingProvider = { currentSettings.value.allowRelayForwarding },
        defaultTtlProvider = { currentSettings.value.defaultTtl }
    )

    val groupManager: GroupManager = GroupManager(
        repository = repository,
        crypto = cryptoEngine,
        identityManager = identityManager,
        router = meshRouter
    )

    val fileTransferManager: FileTransferManager = FileTransferManager(
        context = appContext,
        repository = repository,
        crypto = cryptoEngine,
        identityManager = identityManager,
        router = meshRouter
    )

    val messagingCoordinator: MessagingCoordinator = MessagingCoordinator(
        repository = repository,
        crypto = cryptoEngine,
        identityManager = identityManager,
        transport = bleTransport,
        router = meshRouter,
        groupManager = groupManager,
        fileTransferManager = fileTransferManager,
        settingsStore = settingsStore
    )
}
