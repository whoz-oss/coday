package io.whozoss.agentos.aiModel

import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.user.UserService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * SpEL-callable guard for [AiModelController].
 *
 * The standard `hasPermission(#id, 'AiModel', 'WRITE')` is unsuitable for create/list-by-parent
 * because permission is determined by the **parent AiProvider's namespace**, not by the AiModel
 * itself (which doesn't even exist yet at create time, and whose `namespaceId` is denormalised
 * from the provider).
 *
 * Used in `@PreAuthorize` SpEL via the bean-reference syntax:
 * ```
 * @PreAuthorize("@aiModelGuard.canCreate(#resource)")
 * @PreAuthorize("@aiModelGuard.canListByProvider(#parentId)")
 * ```
 */
@Component("aiModelGuard")
class AiModelGuard(
    private val aiProviderService: AiProviderService,
    private val permissionService: PermissionService,
    private val userService: UserService,
) {
    /**
     * Returns true if the current user has WRITE on the namespace of the parent AiProvider
     * referenced by [resource.aiProviderId].
     *
     * - Provider missing: fail-closed (false).
     * - Platform-level provider (namespaceId=null, userId=null): super-admin only, checked
     *   via [PermissionService.hasPermission] with entityId=null which PermissionServiceImpl
     *   resolves to denied for non-admins.
     * - User-scoped provider (namespaceId=null, userId!=null): fail-closed (false) — user-scoped
     *   providers are deprecated by issue #809 and never have AiModels managed via this guard.
     */
    fun canCreate(resource: AiModelResource): Boolean {
        val providerId = resource.aiProviderId ?: return false
        val provider = aiProviderService.findById(providerId) ?: return false
        return canWriteProvider(provider)
    }

    /**
     * Returns true when the caller may invoke `listByParent(providerId)`.
     *
     * - Provider missing: fail-closed (false).
     * - Platform-level provider: any authenticated user may read (checked via
     *   [PermissionService.hasPermission] with entityId=null which grants READ to all authenticated).
     * - User-scoped provider (legacy, no namespaceId): fail-closed (false).
     * - Namespace-scoped provider: requires namespace READ.
     */
    fun canListByProvider(providerId: UUID): Boolean {
        val provider = aiProviderService.findById(providerId) ?: return false
        return canReadProvider(provider)
    }

    private fun canWriteProvider(provider: AiProvider): Boolean {
        val nsId = provider.namespaceId
        val userId = provider.userId
        return when {
            // Platform-level provider: super-admin only
            nsId == null && userId == null ->
                permissionService.hasPermission(currentUserId(), EntityType.AI_PROVIDER, null, Action.WRITE)
            // Namespace-scoped provider
            nsId != null ->
                permissionService.hasPermission(currentUserId(), EntityType.NAMESPACE, nsId.toString(), Action.WRITE)
            // User-scoped provider (deprecated, fail-closed)
            else -> false
        }
    }

    private fun canReadProvider(provider: AiProvider): Boolean {
        val nsId = provider.namespaceId
        val userId = provider.userId
        return when {
            // Platform-level provider: any authenticated user may read
            nsId == null && userId == null ->
                permissionService.hasPermission(currentUserId(), EntityType.AI_PROVIDER, null, Action.READ)
            // Namespace-scoped provider
            nsId != null ->
                permissionService.hasPermission(currentUserId(), EntityType.NAMESPACE, nsId.toString(), Action.READ)
            // User-scoped provider (deprecated, fail-closed)
            else -> false
        }
    }

    private fun currentUserId(): String = userService.getCurrentUser().id.toString()
}
