package com.example.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ui.components.MyQrCodeDialog
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.ChatsScreen
import com.example.ui.screens.ContactsScreen
import com.example.ui.screens.FileSharingScreen
import com.example.ui.screens.GroupDetailScreen
import com.example.ui.screens.GroupsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.NearbyDevicesScreen
import com.example.ui.screens.QrScannerScreen
import com.example.ui.screens.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object ChatsRoute
@Serializable object GroupsRoute
@Serializable object NearbyRoute
@Serializable object FilesRoute
@Serializable object ContactsRoute
@Serializable object SettingsRoute
@Serializable object QrScannerRoute
@Serializable data class ChatDetailRoute(val conversationId: String)
@Serializable data class GroupDetailRoute(val groupId: String)

private data class TopDestination(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMeshRoot(viewModel: MeshViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val identity by viewModel.localIdentity.collectAsStateWithLifecycle()
    val bleStatus by viewModel.bleStatus.collectAsStateWithLifecycle()
    val nearbyPeers by viewModel.nearbyPeers.collectAsStateWithLifecycle()
    val routeLogs by viewModel.routeLogs.collectAsStateWithLifecycle()
    val pendingRelayCount by viewModel.pendingRelayCount.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val fileTransfers by viewModel.fileTransfers.collectAsStateWithLifecycle()

    var myQrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val primaryBottomNav = remember {
        listOf(
            TopDestination(HomeRoute, "Home", Icons.Filled.Home, Icons.Outlined.Home, "nav_home"),
            TopDestination(ChatsRoute, "Chats", Icons.Filled.Chat, Icons.Outlined.Chat, "nav_chats"),
            TopDestination(GroupsRoute, "Groups", Icons.Filled.Groups, Icons.Outlined.Groups, "nav_groups"),
            TopDestination(NearbyRoute, "Nearby", Icons.Filled.BluetoothSearching, Icons.Outlined.BluetoothSearching, "nav_nearby"),
            TopDestination(FilesRoute, "Files", Icons.Filled.FolderZip, Icons.Outlined.FolderZip, "nav_files")
        )
    }

    val allDestinations = remember {
        primaryBottomNav + listOf(
            TopDestination(ContactsRoute, "Contacts", Icons.Filled.Contacts, Icons.Outlined.Contacts, "nav_contacts"),
            TopDestination(SettingsRoute, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRouteName = backStackEntry?.destination?.route ?: ""
    val isTopLevelScreen = !currentRouteName.contains("ChatDetailRoute") &&
        !currentRouteName.contains("GroupDetailRoute") &&
        !currentRouteName.contains("QrScannerRoute")

    val currentTitle = when {
        currentRouteName.contains("ChatsRoute") -> "Private Chats"
        currentRouteName.contains("GroupsRoute") -> "Peer Groups"
        currentRouteName.contains("NearbyRoute") -> "Nearby Devices"
        currentRouteName.contains("FilesRoute") -> "File Sharing"
        currentRouteName.contains("ContactsRoute") -> "Contacts"
        currentRouteName.contains("SettingsRoute") -> "Settings"
        else -> "OfflineMesh"
    }

    fun navigateTop(dest: Any) {
        navController.navigate(dest) {
            popUpTo(HomeRoute) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 680.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (useRail && isTopLevelScreen) {
                NavigationRail {
                    allDestinations.forEach { item ->
                        val selected = currentRouteName.contains(item.route::class.simpleName ?: "")
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTop(item.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) },
                            modifier = Modifier.testTag(item.testTag)
                        )
                    }
                }
            }

            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    if (isTopLevelScreen) {
                        TopAppBar(
                            title = { Text(currentTitle, style = MaterialTheme.typography.titleLarge) },
                            actions = {
                                if (!useRail) {
                                    IconButton(
                                        onClick = { navigateTop(ContactsRoute) },
                                        modifier = Modifier.testTag("topbar_contacts_button")
                                    ) {
                                        Icon(
                                            imageVector = if (currentRouteName.contains("ContactsRoute")) {
                                                Icons.Filled.Contacts
                                            } else {
                                                Icons.Outlined.Contacts
                                            },
                                            contentDescription = "Contacts",
                                            tint = if (currentRouteName.contains("ContactsRoute")) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    }
                                    IconButton(
                                        onClick = { navigateTop(SettingsRoute) },
                                        modifier = Modifier.testTag("topbar_settings_button")
                                    ) {
                                        Icon(
                                            imageVector = if (currentRouteName.contains("SettingsRoute")) {
                                                Icons.Filled.Settings
                                            } else {
                                                Icons.Outlined.Settings
                                            },
                                            contentDescription = "Settings",
                                            tint = if (currentRouteName.contains("SettingsRoute")) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                    }
                },
                bottomBar = {
                    if (!useRail && isTopLevelScreen) {
                        NavigationBar {
                            primaryBottomNav.forEach { item ->
                                val selected = currentRouteName.contains(item.route::class.simpleName ?: "")
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTop(item.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                            contentDescription = item.label
                                        )
                                    },
                                    label = { Text(item.label) },
                                    modifier = Modifier.testTag(item.testTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = HomeRoute,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    composable<HomeRoute> {
                        HomeScreen(
                            identity = identity,
                            bleStatus = bleStatus,
                            nearbyCount = nearbyPeers.size,
                            verifiedContactCount = contacts.count { it.isVerified },
                            pendingRelayCount = pendingRelayCount,
                            onStartMesh = { viewModel.startMeshRadio() },
                            onStopMesh = { viewModel.stopMeshRadio() },
                            onShowMyQr = { myQrBitmap = viewModel.generateMyQrBitmap() },
                            onOpenQrScanner = { navController.navigate(QrScannerRoute) },
                            onBroadcastBeacon = { viewModel.broadcastIdentityBeacon() },
                            onFlushOfflineQueue = { viewModel.flushOfflineQueue() },
                            onOpenNearby = { navigateTop(NearbyRoute) },
                            onRefreshBleStatus = { viewModel.refreshBleStatus() }
                        )
                    }

                    composable<ChatsRoute> {
                        ChatsScreen(
                            conversations = conversations,
                            onOpenChat = { conv ->
                                navController.navigate(ChatDetailRoute(conv.conversationId))
                            },
                            onScanQrToStart = { navController.navigate(QrScannerRoute) }
                        )
                    }

                    composable<GroupsRoute> {
                        GroupsScreen(
                            groups = groups,
                            contacts = contacts,
                            creationPolicy = settings.groupCreationPolicy,
                            onCreateGroup = { name, memberIds, invitePolicy ->
                                viewModel.createGroup(name, memberIds, invitePolicy) { groupId ->
                                    navController.navigate(GroupDetailRoute(groupId))
                                }
                            },
                            onOpenGroup = { groupId ->
                                navController.navigate(GroupDetailRoute(groupId))
                            }
                        )
                    }

                    composable<NearbyRoute> {
                        NearbyDevicesScreen(
                            bleStatus = bleStatus,
                            nearbyPeers = nearbyPeers,
                            routeLogs = routeLogs,
                            onStartMesh = { viewModel.startMeshRadio() },
                            onConnectPeer = { viewModel.connectPeer(it) },
                            onDisconnectPeer = { viewModel.disconnectPeer(it) }
                        )
                    }

                    composable<FilesRoute> {
                        FileSharingScreen(
                            transfers = fileTransfers,
                            conversations = conversations,
                            onSendMedia = { uri, convId, targetId, isGroup, category ->
                                viewModel.sendMedia(uri, convId, targetId, isGroup, category)
                            },
                            onResumeTransfer = { viewModel.resumeFileTransfer(it) }
                        )
                    }

                    composable<ContactsRoute> {
                        ContactsScreen(
                            contacts = contacts,
                            onShowMyQr = { myQrBitmap = viewModel.generateMyQrBitmap() },
                            onScanPeerQr = { navController.navigate(QrScannerRoute) },
                            onOpenChat = { contact ->
                                viewModel.openChatWithContact(contact) { convId ->
                                    navController.navigate(ChatDetailRoute(convId))
                                }
                            }
                        )
                    }

                    composable<SettingsRoute> {
                        SettingsScreen(
                            settings = settings,
                            identity = identity,
                            onUpdateDisplayName = { viewModel.updateDisplayName(it) },
                            onUpdateAutoDelete = { viewModel.updateAutoDeleteSetting(it) },
                            onUpdateGroupCreationPolicy = { viewModel.updateGroupCreationPolicy(it) },
                            onUpdateDefaultTtl = { viewModel.updateDefaultTtl(it) },
                            onUpdateAllowRelayForwarding = { viewModel.updateAllowRelayForwarding(it) },
                            onUpdateBackgroundRelay = { viewModel.updateBackgroundRelay(it) },
                            onClearAllData = { viewModel.clearAllLocalData() }
                        )
                    }

                    composable<QrScannerRoute> {
                        QrScannerScreen(
                            onQrScanned = { raw ->
                                viewModel.verifyScannedQr(raw) {
                                    navController.popBackStack()
                                }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<ChatDetailRoute> { entry ->
                        val route = entry.toRoute<ChatDetailRoute>()
                        val conv by viewModel.observeConversation(route.conversationId)
                            .collectAsStateWithLifecycle(initialValue = null)
                        val msgs by viewModel.observeMessages(route.conversationId)
                            .collectAsStateWithLifecycle(initialValue = emptyList())
                        ChatDetailScreen(
                            conversation = conv,
                            messages = msgs,
                            onMarkRead = { viewModel.markConversationRead(route.conversationId) },
                            onSendMessage = { text ->
                                viewModel.sendMessage(route.conversationId, isGroup = false, text = text)
                            },
                            onSendMedia = { uri, category ->
                                viewModel.sendMedia(
                                    uri = uri,
                                    conversationId = route.conversationId,
                                    targetPeerOrGroupId = route.conversationId,
                                    isGroup = false,
                                    category = category
                                )
                            },
                            onUpdateAutoDelete = { option ->
                                viewModel.updateConversationAutoDelete(route.conversationId, option)
                            },
                            onVerifyPeerQr = { navController.navigate(QrScannerRoute) },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<GroupDetailRoute> { entry ->
                        val route = entry.toRoute<GroupDetailRoute>()
                        val group by viewModel.observeGroup(route.groupId)
                            .collectAsStateWithLifecycle(initialValue = null)
                        val members by viewModel.observeGroupMembers(route.groupId)
                            .collectAsStateWithLifecycle(initialValue = emptyList())
                        val msgs by viewModel.observeMessages(route.groupId)
                            .collectAsStateWithLifecycle(initialValue = emptyList())
                        GroupDetailScreen(
                            group = group,
                            members = members,
                            allContacts = contacts,
                            messages = msgs,
                            selfNodeId = identity.nodeId,
                            onSendMessage = { text ->
                                viewModel.sendMessage(route.groupId, isGroup = true, text = text)
                            },
                            onSendMedia = { uri, category ->
                                viewModel.sendMedia(
                                    uri = uri,
                                    conversationId = route.groupId,
                                    targetPeerOrGroupId = route.groupId,
                                    isGroup = true,
                                    category = category
                                )
                            },
                            onUpdateInvitePolicy = { policy ->
                                viewModel.updateGroupInvitePolicy(route.groupId, policy)
                            },
                            onInviteMember = { contact ->
                                viewModel.inviteMemberToGroup(route.groupId, contact)
                            },
                            onRemoveAndRotate = { memberId ->
                                viewModel.removeGroupMemberAndRotate(route.groupId, memberId)
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    myQrBitmap?.let { bmp ->
        MyQrCodeDialog(
            displayName = identity.displayName,
            nodeId = identity.nodeId,
            fingerprint = identity.safetyFingerprint,
            qrBitmap = bmp,
            onDismiss = { myQrBitmap = null }
        )
    }
}
