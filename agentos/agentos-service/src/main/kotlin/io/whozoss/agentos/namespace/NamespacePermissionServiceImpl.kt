package io.whozoss.agentos.namespace

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.stereotype.Service

@Service
class NamespacePermissionServiceImpl(
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
) : NamespacePermissionService {
    override fun syncUserRoles(request: SyncUserRolesRequest) {
        val user =
            userService.findByExternalId(request.userExternalId)
                ?: throw ResourceNotFoundException("User not found: ${request.userExternalId}")
        val userIdStr = user.metadata.id.toString()

        // Resolve all listed namespaces in one batch — fail before any graph mutation.
        val requestedExternalIds = request.namespaceRoles.map { it.namespaceExternalId }.distinct()
        val namespacesByExternalId =
            namespaceService
                .findByExternalIds(requestedExternalIds)
                .associateBy { it.externalId!! }
        val missing = requestedExternalIds - namespacesByExternalId.keys
        if (missing.isNotEmpty()) {
            throw ResourceNotFoundException("Namespace(s) not found: ${missing.joinToString(", ")}")
        }

        // Map internal namespace id -> desired role.
        val targetRoleByNamespaceId =
            request.namespaceRoles.associate {
                namespacesByExternalId
                    .getValue(it.namespaceExternalId)
                    .metadata.id
                    .toString() to
                    PermissionRelation.valueOf(it.role)
            }

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
            val targetRole = targetRoleByNamespaceId[namespaceId] ?: PermissionRelation.NONE
            val currentRole =
                when (namespaceId) {
                    in currentAdminIds -> PermissionRelation.ADMIN
                    in currentMemberIds -> PermissionRelation.MEMBER
                    else -> PermissionRelation.NONE
                }

            when {
                targetRole == currentRole -> {
                    logger.debug { "Role unchanged ($currentRole) for namespace $namespaceId — no-op" }
                }

                targetRole == PermissionRelation.NONE -> {
                    revoke(userIdStr, namespaceId, currentRole)
                }

                currentRole == PermissionRelation.NONE -> {
                    grant(userIdStr, namespaceId, targetRole)
                }

                targetRole == PermissionRelation.ADMIN -> {
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
