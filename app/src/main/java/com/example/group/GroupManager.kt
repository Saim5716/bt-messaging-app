package com.example.group

import com.example.core.model.Contact
import com.example.core.model.GroupCreationPolicy
import com.example.core.model.GroupInvitePayload
import com.example.core.model.GroupInvitePolicy
import com.example.core.model.GroupKeyRotationPayload
import com.example.core.model.GroupMemberWire
import com.example.core.model.GroupRole
import com.example.core.model.PacketType
import com.example.crypto.CryptoEngine
import com.example.identity.IdentityManager
import com.example.routing.MeshRouter
import com.example.storage.ConversationEntity
import com.example.storage.GroupEntity
import com.example.storage.GroupMemberEntity
import com.example.storage.MeshRepository
import java.util.UUID
import kotlinx.serialization.json.Json

class GroupManager(
    private val repository: MeshRepository,
    private val crypto: CryptoEngine,
    private val identityManager: IdentityManager,
    private val router: MeshRouter
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createGroup(
        name: String,
        initialMembers: List<Contact>,
        invitePolicy: GroupInvitePolicy,
        creationPolicy: GroupCreationPolicy,
        selfDisplayName: String
    ): Result<String> = runCatching {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "Group name cannot be empty" }

        if (creationPolicy == GroupCreationPolicy.VERIFIED_ONLY) {
            require(initialMembers.isNotEmpty() && initialMembers.all { it.isVerified }) {
                "Settings restrict group creation to QR-verified contacts only"
            }
        }

        val selfKeys = identityManager.ensureKeyBundle()
        val selfNodeId = identityManager.deriveNodeId(selfKeys.signPublicKey)
        val groupId = "grp_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val epoch = 1
        val groupKey = crypto.generateSymmetricKey()
        val now = System.currentTimeMillis()

        repository.groupDao.upsertGroup(
            GroupEntity(
                groupId = groupId,
                name = cleanName,
                createdByNodeId = selfNodeId,
                invitePolicyName = invitePolicy.name,
                keyEpoch = epoch,
                createdAt = now
            )
        )
        repository.saveGroupKey(groupId, epoch, groupKey)

        val selfWire = GroupMemberWire(
            nodeId = selfNodeId,
            displayName = selfDisplayName,
            signPublicKeyBase64 = crypto.toBase64(selfKeys.signPublicKey),
            kxPublicKeyBase64 = crypto.toBase64(selfKeys.kxPublicKey),
            role = GroupRole.ADMIN
        )
        val memberWires = listOf(selfWire) + initialMembers.map {
            GroupMemberWire(
                nodeId = it.nodeId,
                displayName = it.displayName,
                signPublicKeyBase64 = it.signPublicKeyBase64,
                kxPublicKeyBase64 = it.kxPublicKeyBase64,
                role = GroupRole.MEMBER
            )
        }

        repository.groupDao.upsertMembers(
            memberWires.map {
                GroupMemberEntity(
                    groupId = groupId,
                    nodeId = it.nodeId,
                    displayName = it.displayName,
                    roleName = it.role.name,
                    joinedAt = now
                )
            }
        )

        repository.conversationDao.upsert(
            ConversationEntity(
                conversationId = groupId,
                title = cleanName,
                isGroup = true,
                peerNodeId = null,
                groupId = groupId,
                lastMessagePreview = "Group created (Epoch $epoch)",
                lastMessageTimestamp = now,
                unreadCount = 0
            )
        )

        for (member in initialMembers) {
            sendGroupInviteToPeer(groupId, cleanName, selfNodeId, invitePolicy, epoch, groupKey, member, memberWires)
        }

        groupKey.fill(0)
        groupId
    }

    suspend fun inviteMember(groupId: String, contact: Contact): Result<Unit> = runCatching {
        val group = repository.groupDao.getGroup(groupId) ?: error("Group not found")
        val selfNodeId = identityManager.deriveNodeId(identityManager.ensureKeyBundle().signPublicKey)
        val members = repository.groupDao.getMembers(groupId)
        val selfMember = members.firstOrNull { it.nodeId == selfNodeId } ?: error("Not a group member")
        val policy = GroupInvitePolicy.valueOf(group.invitePolicyName)

        if (policy == GroupInvitePolicy.ADMINS_ONLY && selfMember.roleName != GroupRole.ADMIN.name) {
            error("Only group administrators can invite members")
        }
        if (members.any { it.nodeId == contact.nodeId }) {
            error("${contact.displayName} is already in this group")
        }

        val groupKey = repository.loadGroupKey(groupId, group.keyEpoch)
            ?: error("Group encryption key missing")
        val now = System.currentTimeMillis()
        repository.groupDao.upsertMembers(
            listOf(
                GroupMemberEntity(
                    groupId = groupId,
                    nodeId = contact.nodeId,
                    displayName = contact.displayName,
                    roleName = GroupRole.MEMBER.name,
                    joinedAt = now
                )
            )
        )

        val updatedWires = buildWireMembers(groupId)
        sendGroupInviteToPeer(
            groupId = groupId,
            groupName = group.name,
            createdBy = group.createdByNodeId,
            invitePolicy = policy,
            epoch = group.keyEpoch,
            groupKey = groupKey,
            target = contact,
            allMembers = updatedWires
        )
        groupKey.fill(0)
    }

    suspend fun updateInvitePolicy(groupId: String, newPolicy: GroupInvitePolicy): Result<Unit> = runCatching {
        val selfNodeId = identityManager.deriveNodeId(identityManager.ensureKeyBundle().signPublicKey)
        val selfRole = repository.groupDao.getMembers(groupId).firstOrNull { it.nodeId == selfNodeId }?.roleName
        require(selfRole == GroupRole.ADMIN.name) { "Only administrators can change invite policy" }
        repository.groupDao.updateInvitePolicy(groupId, newPolicy.name)
    }

    suspend fun removeMemberAndRotateKey(groupId: String, memberNodeId: String): Result<Int> = runCatching {
        val group = repository.groupDao.getGroup(groupId) ?: error("Group not found")
        val selfNodeId = identityManager.deriveNodeId(identityManager.ensureKeyBundle().signPublicKey)
        val members = repository.groupDao.getMembers(groupId)
        val selfMember = members.firstOrNull { it.nodeId == selfNodeId }
        require(selfMember?.roleName == GroupRole.ADMIN.name) { "Only administrators can remove members" }
        require(memberNodeId != selfNodeId) { "Administrator cannot remove themselves here" }

        repository.groupDao.removeMember(groupId, memberNodeId)

        val newEpoch = group.keyEpoch + 1
        val newGroupKey = crypto.generateSymmetricKey()
        repository.groupDao.updateKeyEpoch(groupId, newEpoch)
        repository.saveGroupKey(groupId, newEpoch, newGroupKey)

        val remainingWires = buildWireMembers(groupId)
        for (wire in remainingWires) {
            if (wire.nodeId == selfNodeId) continue
            val contact = repository.getContact(wire.nodeId) ?: continue
            val wrapped = wrapKeyForPeer(newGroupKey, contact.kxPublicKeyBase64, "group-rotate|$groupId|$newEpoch")
            val payload = GroupKeyRotationPayload(
                groupId = groupId,
                newEpoch = newEpoch,
                removedNodeId = memberNodeId,
                wrappedGroupKeyBase64 = crypto.toBase64(wrapped),
                updatedMembers = remainingWires
            )
            router.createAndSendPacket(
                packetType = PacketType.GROUP_KEY_ROTATION,
                targetNodeId = wire.nodeId,
                payloadBytes = json.encodeToString(GroupKeyRotationPayload.serializer(), payload)
                    .toByteArray(Charsets.UTF_8)
            )
        }
        newGroupKey.fill(0)
        newEpoch
    }

    suspend fun handleIncomingInvite(senderNodeId: String, payload: GroupInvitePayload) {
        val senderContact = repository.getContact(senderNodeId) ?: return
        val wrapped = crypto.fromBase64(payload.wrappedGroupKeyBase64)
        val rawGroupKey = unwrapKeyFromPeer(
            wrapped = wrapped,
            peerKxPkB64 = senderContact.kxPublicKeyBase64,
            context = "group-invite|${payload.groupId}|${payload.keyEpoch}"
        )
        val now = System.currentTimeMillis()
        repository.groupDao.upsertGroup(
            GroupEntity(
                groupId = payload.groupId,
                name = payload.groupName,
                createdByNodeId = payload.createdByNodeId,
                invitePolicyName = payload.invitePolicy.name,
                keyEpoch = payload.keyEpoch,
                createdAt = now
            )
        )
        repository.saveGroupKey(payload.groupId, payload.keyEpoch, rawGroupKey)
        rawGroupKey.fill(0)

        repository.groupDao.upsertMembers(
            payload.members.map { m ->
                GroupMemberEntity(
                    groupId = payload.groupId,
                    nodeId = m.nodeId,
                    displayName = m.displayName,
                    roleName = m.role.name,
                    joinedAt = now
                )
            }
        )
        if (repository.conversationDao.getById(payload.groupId) == null) {
            repository.conversationDao.upsert(
                ConversationEntity(
                    conversationId = payload.groupId,
                    title = payload.groupName,
                    isGroup = true,
                    peerNodeId = null,
                    groupId = payload.groupId,
                    lastMessagePreview = "Joined encrypted group (Epoch ${payload.keyEpoch})",
                    lastMessageTimestamp = now,
                    unreadCount = 1
                )
            )
        }
    }

    suspend fun handleIncomingKeyRotation(senderNodeId: String, payload: GroupKeyRotationPayload) {
        val group = repository.groupDao.getGroup(payload.groupId) ?: return
        val members = repository.groupDao.getMembers(payload.groupId)
        val senderIsAdmin = members.any { it.nodeId == senderNodeId && it.roleName == GroupRole.ADMIN.name }
        if (!senderIsAdmin) return
        if (payload.newEpoch <= group.keyEpoch) return

        val senderContact = repository.getContact(senderNodeId) ?: return
        val wrapped = crypto.fromBase64(payload.wrappedGroupKeyBase64)
        val rawNewKey = unwrapKeyFromPeer(
            wrapped = wrapped,
            peerKxPkB64 = senderContact.kxPublicKeyBase64,
            context = "group-rotate|${payload.groupId}|${payload.newEpoch}"
        )
        repository.groupDao.updateKeyEpoch(payload.groupId, payload.newEpoch)
        repository.saveGroupKey(payload.groupId, payload.newEpoch, rawNewKey)
        rawNewKey.fill(0)

        val now = System.currentTimeMillis()
        repository.groupDao.deleteMembersForGroup(payload.groupId)
        repository.groupDao.upsertMembers(
            payload.updatedMembers.map { m ->
                GroupMemberEntity(
                    groupId = payload.groupId,
                    nodeId = m.nodeId,
                    displayName = m.displayName,
                    roleName = m.role.name,
                    joinedAt = now
                )
            }
        )
    }

    private suspend fun sendGroupInviteToPeer(
        groupId: String,
        groupName: String,
        createdBy: String,
        invitePolicy: GroupInvitePolicy,
        epoch: Int,
        groupKey: ByteArray,
        target: Contact,
        allMembers: List<GroupMemberWire>
    ) {
        val wrapped = wrapKeyForPeer(groupKey, target.kxPublicKeyBase64, "group-invite|$groupId|$epoch")
        val payload = GroupInvitePayload(
            groupId = groupId,
            groupName = groupName,
            createdByNodeId = createdBy,
            invitePolicy = invitePolicy,
            keyEpoch = epoch,
            wrappedGroupKeyBase64 = crypto.toBase64(wrapped),
            members = allMembers
        )
        router.createAndSendPacket(
            packetType = PacketType.GROUP_INVITE,
            targetNodeId = target.nodeId,
            payloadBytes = json.encodeToString(GroupInvitePayload.serializer(), payload)
                .toByteArray(Charsets.UTF_8)
        )
    }

    private suspend fun buildWireMembers(groupId: String): List<GroupMemberWire> {
        val selfKeys = identityManager.ensureKeyBundle()
        val selfNodeId = identityManager.deriveNodeId(selfKeys.signPublicKey)
        return repository.groupDao.getMembers(groupId).mapNotNull { m ->
            if (m.nodeId == selfNodeId) {
                GroupMemberWire(
                    nodeId = m.nodeId,
                    displayName = m.displayName,
                    signPublicKeyBase64 = crypto.toBase64(selfKeys.signPublicKey),
                    kxPublicKeyBase64 = crypto.toBase64(selfKeys.kxPublicKey),
                    role = GroupRole.valueOf(m.roleName)
                )
            } else {
                val c = repository.getContact(m.nodeId) ?: return@mapNotNull null
                GroupMemberWire(
                    nodeId = m.nodeId,
                    displayName = m.displayName,
                    signPublicKeyBase64 = c.signPublicKeyBase64,
                    kxPublicKeyBase64 = c.kxPublicKeyBase64,
                    role = GroupRole.valueOf(m.roleName)
                )
            }
        }
    }

    private fun wrapKeyForPeer(secretKey: ByteArray, peerKxPkB64: String, context: String): ByteArray {
        val selfKeys = identityManager.ensureKeyBundle()
        val shared = crypto.deriveSharedKey(selfKeys.kxSecretKey, crypto.fromBase64(peerKxPkB64), context)
        return try {
            crypto.encryptAead(shared, secretKey, context.toByteArray(Charsets.UTF_8))
        } finally {
            shared.fill(0)
        }
    }

    private fun unwrapKeyFromPeer(wrapped: ByteArray, peerKxPkB64: String, context: String): ByteArray {
        val selfKeys = identityManager.ensureKeyBundle()
        val shared = crypto.deriveSharedKey(selfKeys.kxSecretKey, crypto.fromBase64(peerKxPkB64), context)
        return try {
            crypto.decryptAead(shared, wrapped, context.toByteArray(Charsets.UTF_8))
        } finally {
            shared.fill(0)
        }
    }
}
