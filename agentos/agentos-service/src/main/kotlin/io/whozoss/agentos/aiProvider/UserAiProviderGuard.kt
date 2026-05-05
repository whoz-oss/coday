package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

/**
 * Authorization guard for user-scoped [AiProvider] entities.
 *
 * Authorization is **ownership-based**: a row is accessible iff `provider.userId == auth.name`.
 * This is intentionally distinct from the namespace-membership model used by [AiProviderController],
 * which evaluates `hasPermission(...)` SpEL via [PermissionService].
 *
 * Returns booleans rather than throwing. Translation to HTTP semantics
 * ([org.springframework.security.access.AccessDeniedException] → 404 via
 * [io.whozoss.agentos.security.declarative.HideOnAccessDenied]) is the controller's job.
 */
@Component
class UserAiProviderGuard(
    private val permissionService: PermissionService,
) {
    fun canRead(provider: AiProvider, auth: Authentication): Boolean = isOwner(provider, auth)

    fun canModify(provider: AiProvider, auth: Authentication): Boolean = isOwner(provider, auth)

    /**
     * True iff the caller may create the given target row:
     * - user-global ([AiProvider.namespaceId] == null) → authentication suffices.
     * - user × namespace ([AiProvider.namespaceId] != null) → the caller must hold READ on the namespace.
     */
    fun canCreate(target: AiProvider, auth: Authentication): Boolean {
        val me = auth.name ?: return false
        return when (val nsId = target.namespaceId) {
            null -> true
            else -> permissionService.hasPermission(me, EntityType.NAMESPACE, nsId.toString(), Action.READ)
        }
    }

    private fun isOwner(provider: AiProvider, auth: Authentication): Boolean {
        val me = auth.name ?: return false
        return provider.userId?.toString() == me
    }
}
