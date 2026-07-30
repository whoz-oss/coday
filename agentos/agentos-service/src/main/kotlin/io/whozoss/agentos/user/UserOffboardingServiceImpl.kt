package io.whozoss.agentos.user

import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.userGroup.UserGroupRepository
import mu.KLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserOffboardingServiceImpl(
    private val userService: UserService,
    private val userGroupRepository: UserGroupRepository,
    private val permissionService: PermissionService,
) : UserOffboardingService {
    @Transactional
    override fun revokeAllAccess(userId: UUID) {
        val user = userService.getById(userId)
        val userIdStr = userId.toString()

        userGroupRepository.removeUserFromAllGroups(user.externalId)

        val adminNamespaceIds = permissionService.listEntitiesForUser(userIdStr, EntityType.NAMESPACE, Action.WRITE).toSet()
        val memberNamespaceIds =
            permissionService.listEntitiesForUser(userIdStr, EntityType.NAMESPACE, Action.READ).toSet() - adminNamespaceIds

        adminNamespaceIds.forEach { namespaceId ->
            runCatching {
                permissionService.revokePermission(userIdStr, EntityType.NAMESPACE, namespaceId, PermissionRelation.ADMIN)
            }.onFailure { e -> logger.warn(e) { "Failed to revoke ADMIN on namespace $namespaceId for user $userId" } }
        }
        memberNamespaceIds.forEach { namespaceId ->
            runCatching {
                permissionService.revokePermission(userIdStr, EntityType.NAMESPACE, namespaceId, PermissionRelation.MEMBER)
            }.onFailure { e -> logger.warn(e) { "Failed to revoke MEMBER on namespace $namespaceId for user $userId" } }
        }

        // removeUserFromAllGroups bypasses the permission cache (direct Cypher write on
        // MEMBER|ADMIN edges); revokePermission already clears the cache per-call, but that
        // does not help if the user held no namespace relation at all.
        permissionService.clearUserCache(userIdStr)
        logger.info { "Revoked all group and namespace access for user $userId" }
    }

    companion object : KLogging()
}
