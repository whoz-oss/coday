package io.whozoss.agentos.namespace

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.membership.resolveMembers
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.membership.MemberItem
import io.whozoss.agentos.sdk.api.user.UserMembershipRole
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Sync-algorithm-local role states.
 * Never persisted, never exposed outside this file.
 */
private enum class NamespaceRole {
    ADMIN,
    MEMBER,
    NONE,
    ;

    fun toPermissionRelation(): PermissionRelation =
        when (this) {
            ADMIN -> PermissionRelation.ADMIN
            MEMBER -> PermissionRelation.MEMBER
            NONE -> error("NONE has no PermissionRelation counterpart")
        }
}

@Service
class NamespacePermissionServiceImpl(
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
) : NamespacePermissionService {
    @Transactional
    override fun syncUserRoles(request: SyncUserRolesRequest) {
        val user =
            userService.findByExternalId(request.userExternalId)
                ?: throw ResourceNotFoundException("User not found: ${request.userExternalId}")
        val userIdStr = user.metadata.id.toString()

        // Resolve all listed namespaces in one batch — skip the call entirely when the
        // list is empty (empty-assignments sync is a valid "revoke everything" request).
        val requestedExternalIds = request.namespaceRoles.map { it.namespaceExternalId }.distinct()
        val namespacesByExternalId =
            if (requestedExternalIds.isEmpty()) {
                emptyMap()
            } else {
                val found =
                    namespaceService
                        .findByExternalIds(requestedExternalIds)
                        .associateBy { it.externalId!! }
                val missing = requestedExternalIds - found.keys
                if (missing.isNotEmpty()) {
                    logger.warn { "Namespace(s) not found: ${missing.joinToString(", ")}" }
                }
                found
            }

        // Map internal namespace id -> desired role, skipping any external ids that
        // were not found (already warned above).
        val targetRoleByNamespaceId =
            request.namespaceRoles.mapNotNull { entry ->
                namespacesByExternalId[entry.namespaceExternalId]
                    ?.metadata?.id?.toString()
                    ?.let { it to NamespaceRole.valueOf(entry.role) }
            }.toMap()

        // Fetch the user's current relations across ALL namespaces.
        // WRITE corresponds to ADMIN; READ minus WRITE corresponds to MEMBER.
        val currentAdminIds =
            permissionService
                .listEntitiesForUser(userIdStr, EntityType.NAMESPACE, Action.WRITE)
                .toSet()
        val currentMemberIds =
            permissionService
                .listEntitiesForUser(userIdStr, EntityType.NAMESPACE, Action.READ)
                .toSet() - currentAdminIds

        // Union of every namespace id that matters: currently held + desired.
        val fullNamespaceIds = currentAdminIds + currentMemberIds + targetRoleByNamespaceId.keys

        fullNamespaceIds.forEach { namespaceId ->
            val targetRole = targetRoleByNamespaceId[namespaceId] ?: NamespaceRole.NONE
            val currentRole =
                when (namespaceId) {
                    in currentAdminIds -> NamespaceRole.ADMIN
                    in currentMemberIds -> NamespaceRole.MEMBER
                    else -> NamespaceRole.NONE
                }

            when {
                targetRole == currentRole -> {
                    logger.debug { "Role unchanged ($currentRole) for namespace $namespaceId — no-op" }
                }

                targetRole == NamespaceRole.NONE -> {
                    revoke(userIdStr, namespaceId, currentRole.toPermissionRelation())
                }

                currentRole == NamespaceRole.NONE -> {
                    grant(userIdStr, namespaceId, targetRole.toPermissionRelation())
                }

                targetRole == NamespaceRole.ADMIN -> {
                    // Atomic promote: preserves relation properties (e.g. starred).
                    promote(userIdStr, namespaceId)
                }

                else -> {
                    // Atomic demote: preserves relation properties (e.g. starred).
                    demote(userIdStr, namespaceId)
                }
            }
        }
    }

    override fun getMembers(namespaceId: UUID): List<MemberItem> {
        namespaceService.getById(namespaceId)
        return resolveMembers(namespaceId, EntityType.NAMESPACE, permissionService, userService)
    }

    @Transactional
    override fun updateMembers(
        namespaceId: UUID,
        members: List<UserMembershipRole>,
        callerIsSuperAdmin: Boolean,
    ): List<MemberItem> {
        namespaceService.getById(namespaceId)

        // Duplicate-userId check is enforced by @NoDuplicateUserIds at the controller level.
        val (toUpsert, toRevoke) = members.partition { it.role != null }
        val upsertUserIds = toUpsert.map { it.userId }
        val revokeUserIds = toRevoke.map { it.userId }
        val involvedIds = (upsertUserIds + revokeUserIds).toSet()
        val namespaceIdStr = namespaceId.toString()

        // Single targeted query: fetch current relations only for the users in this request.
        // Non-involved members are left untouched by delta semantics — no need to load them.
        val currentRelationByUserId: Map<UUID, PermissionRelation> =
            permissionService
                .listRelationsForUsers(
                    EntityType.NAMESPACE,
                    namespaceIdStr,
                    involvedIds.map { it.toString() },
                ).mapKeys { (k, _) -> UUID.fromString(k) }

        val unknownRevovals = revokeUserIds.toSet() - currentRelationByUserId.keys
        if (unknownRevovals.isNotEmpty()) {
            throw UnprocessableEntityException("userId(s) not currently on the namespace: $unknownRevovals")
        }

        val newUserIds = upsertUserIds.filter { it !in currentRelationByUserId }
        if (newUserIds.isNotEmpty() && !callerIsSuperAdmin) {
            throw AccessDeniedException("Only a super-admin may add a new user to a namespace")
        }
        if (newUserIds.isNotEmpty()) {
            val found = userService.findByIds(newUserIds).map { it.metadata.id }.toSet()
            val unknown = newUserIds.toSet() - found
            if (unknown.isNotEmpty()) {
                throw UnprocessableEntityException("Unknown userId(s): $unknown")
            }
        }

        // Anti-lockout guard: load all existing admins (needed to account for admins
        // not in this request who will still be admins after the update).
        // Then apply the delta: remove revoked/demoted admins, add promoted ones.
        val existingAdminIds: Set<UUID> =
            permissionService
                .listUsersWithPermission(EntityType.NAMESPACE, namespaceIdStr, PermissionRelation.ADMIN)
                .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                .toSet()
        val hadAdminBefore = existingAdminIds.isNotEmpty()
        val removedFromAdmin: Set<UUID> =
            revokeUserIds.toSet() +
                toUpsert.filter { it.role != PermissionRelation.ADMIN.name }.map { it.userId }.toSet()
        val addedToAdmin: Set<UUID> =
            toUpsert.filter { it.role == PermissionRelation.ADMIN.name }.map { it.userId }.toSet()
        val hasAdminAfter = (existingAdminIds - removedFromAdmin + addedToAdmin).isNotEmpty()
        if (hadAdminBefore && !hasAdminAfter) {
            throw UnprocessableEntityException("This update would leave the namespace with no ADMIN")
        }

        val roleChanges: List<Pair<String, PermissionRelation?>> =
            toUpsert.mapNotNull { entry ->
                val targetRelation = PermissionRelation.valueOf(entry.role!!)
                val current = currentRelationByUserId[entry.userId]
                if (current == targetRelation) null
                else entry.userId.toString() to targetRelation
            }
        val revocations: List<Pair<String, PermissionRelation?>> =
            revokeUserIds.map { it.toString() to null }

        val entries = roleChanges + revocations
        if (entries.isNotEmpty()) {
            permissionService.applyShareBatch(EntityType.NAMESPACE, namespaceId.toString(), entries)
        }

        return resolveMembers(namespaceId, EntityType.NAMESPACE, permissionService, userService)
    }

    private fun grant(
        userIdStr: String,
        namespaceId: String,
        relation: PermissionRelation,
    ) {
        permissionService.grantPermission(userIdStr, EntityType.NAMESPACE, namespaceId, relation)
        logger.info { "Granted $relation on namespace $namespaceId to user $userIdStr" }
    }

    private fun revoke(
        userIdStr: String,
        namespaceId: String,
        relation: PermissionRelation,
    ) {
        permissionService.revokePermission(userIdStr, EntityType.NAMESPACE, namespaceId, relation)
        logger.info { "Revoked $relation on namespace $namespaceId from user $userIdStr" }
    }

    /**
     * Atomically promotes [:MEMBER] to [:ADMIN] on a namespace.
     * The [:HAS_USER_CASE_STATE] edge survives untouched (separate relationship).
     */
    private fun promote(
        userIdStr: String,
        namespaceId: String,
    ) {
        permissionService.promoteMemberToAdmin(userIdStr, EntityType.NAMESPACE, namespaceId)
        logger.info { "Promoted MEMBER to ADMIN on namespace $namespaceId for user $userIdStr" }
    }

    /**
     * Atomically demotes [:ADMIN] to [:MEMBER] on a namespace.
     * The [:HAS_USER_CASE_STATE] edge survives untouched (separate relationship).
     */
    private fun demote(
        userIdStr: String,
        namespaceId: String,
    ) {
        permissionService.demoteAdminToMember(userIdStr, EntityType.NAMESPACE, namespaceId)
        logger.info { "Demoted ADMIN to MEMBER on namespace $namespaceId for user $userIdStr" }
    }

    companion object : KLogging()
}
