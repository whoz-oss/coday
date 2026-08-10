package io.whozoss.agentos.user

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.userGroup.UserGroupRepository
import mu.KLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class UserOffboardingServiceImpl(
    private val userService: UserService,
    private val userGroupRepository: UserGroupRepository,
    private val permissionService: PermissionService,
) : UserOffboardingService {
    @Transactional
    override fun revokeNamespaceAccess(
        userId: UUID,
        namespaceId: UUID,
    ) {
        val userIdStr = userId.toString()
        val namespaceIdStr = namespaceId.toString()

        userGroupRepository.removeUserFromGroupsInNamespace(userIdStr, namespaceId)

        runCatching {
            permissionService.revokePermission(userIdStr, EntityType.NAMESPACE, namespaceIdStr, PermissionRelation.ADMIN)
        }.onFailure { e -> logger.warn(e) { "Failed to revoke ADMIN on namespace $namespaceId for user $userIdStr" } }

        runCatching {
            permissionService.revokePermission(userIdStr, EntityType.NAMESPACE, namespaceIdStr, PermissionRelation.MEMBER)
        }.onFailure { e -> logger.warn(e) { "Failed to revoke MEMBER on namespace $namespaceId for user $userIdStr" } }

        // removeUserFromGroupsInNamespace bypasses the permission cache (direct Cypher write on
        // MEMBER|ADMIN edges); revokePermission already clears the cache per-call, but that
        // does not help if the user held no namespace relation at all.
        permissionService.clearUserCache(userIdStr)
        logger.info { "Revoked group and namespace access for user $userIdStr on namespace $namespaceId" }
    }

    @Transactional
    override fun revokeNamespaceAccessByExternalId(
        userExternalId: String,
        namespaceId: UUID,
    ) {
        val user = userService.findByExternalId(userExternalId) ?: run {
            logger.warn { "[UserOffboardingService] User not found for externalId: $userExternalId" }
            throw ResourceNotFoundException("User not found for externalId: $userExternalId")
        }
        revokeNamespaceAccess(userId = user.id, namespaceId = namespaceId)
    }

    companion object : KLogging()
}
