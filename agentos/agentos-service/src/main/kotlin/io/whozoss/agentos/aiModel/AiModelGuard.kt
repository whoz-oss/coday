package io.whozoss.agentos.aiModel

import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.aiProvider.AiModelDto
import io.whozoss.agentos.user.UserService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * SpEL-callable guard for [AiModelController].
 *
 * Permission is determined by the **parent AiProvider's scope** — an AiModel
 * inherits the access rules of its provider:
 *
 * - Provider namespace-scoped (`namespaceId != null`): permission checked against
 *   the namespace (READ or WRITE depending on the operation).
 * - Provider platform-level (`namespaceId = null AND userId = null`): permission
 *   checked with `entityId = null`, which [io.whozoss.agentos.permissions.PermissionServiceImpl]
 *   resolves as READ open / WRITE super-admin only.
 * - Provider user-scoped (deprecated, `namespaceId = null AND userId != null`): fail-closed.
 *
 * All checks delegate to [PermissionService.hasPermission] — no custom logic here.
 *
 * When the referenced provider does not exist, [ResourceNotFoundException] is thrown so
 * the caller receives a 404, not a misleading 403 (existence and permission are separate
 * concerns).
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
     * Returns true if the current user has WRITE access on the scope of the parent
     * [io.whozoss.agentos.aiProvider.AiProvider] referenced by [resource.aiProviderId].
     *
     * Throws [ResourceNotFoundException] when the referenced provider does not exist.
     * Fail-closed (returns false) only for user-scoped providers (deprecated).
     */
    fun canCreate(resource: AiModelDto): Boolean {
        val providerId = resource.aiProviderId ?: return false
        val provider = aiProviderService.findById(providerId)
            ?: throw ResourceNotFoundException("AiProvider not found: $providerId")
        val nsId = provider.namespaceId
        val userId = provider.userId
        return when {
            // User-scoped provider (deprecated): fail-closed
            nsId == null && userId != null -> false
            // Namespace-scoped or platform-level: check WRITE on the namespace (null = platform)
            else -> permissionService.hasPermission(
                currentUserId(), EntityType.NAMESPACE, nsId?.toString(), Action.WRITE,
            )
        }
    }

    /**
     * Returns true when the caller may invoke `listByParent(providerId)`.
     *
     * Throws [ResourceNotFoundException] when the provider does not exist.
     * Fail-closed (returns false) only for user-scoped providers (deprecated).
     */
    fun canListByProvider(providerId: UUID): Boolean {
        val provider = aiProviderService.findById(providerId)
            ?: throw ResourceNotFoundException("AiProvider not found: $providerId")
        val nsId = provider.namespaceId
        val userId = provider.userId
        return when {
            // User-scoped provider (deprecated): fail-closed
            nsId == null && userId != null -> false
            // Namespace-scoped or platform-level: check READ on the namespace (null = platform)
            else -> permissionService.hasPermission(
                currentUserId(), EntityType.NAMESPACE, nsId?.toString(), Action.READ,
            )
        }
    }

    private fun currentUserId(): String = userService.getCurrentUser().id.toString()
}
