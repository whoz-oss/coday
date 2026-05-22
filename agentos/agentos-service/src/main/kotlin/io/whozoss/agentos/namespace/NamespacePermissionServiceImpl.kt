package io.whozoss.agentos.namespace

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
                    revoke(userIdStr, namespaceId, PermissionRelation.MEMBER)
                    grant(userIdStr, namespaceId, PermissionRelation.ADMIN)
                }

                else -> {
                    revoke(userIdStr, namespaceId, PermissionRelation.ADMIN)
                    grant(userIdStr, namespaceId, PermissionRelation.MEMBER)
                }
            }
        }
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

    companion object : KLogging()
}
