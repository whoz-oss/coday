package io.whozoss.agentos.aiModel

import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.user.UserService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * SpEL-callable guard for [AiModelController] (Story 5.2).
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
     * referenced by [resource.aiProviderId]. Returns false (→ 403) for missing or user-scoped
     * providers — a strict fail-closed posture aligned with story 4.4 AC4.
     */
    fun canCreate(resource: AiModelResource): Boolean {
        val providerId = resource.aiProviderId ?: return false
        val provider = aiProviderService.findById(providerId) ?: return false
        val nsId = provider.namespaceId ?: return false
        return permissionService.hasPermission(currentUserId(), NAMESPACE_TYPE, nsId.toString(), Action.WRITE)
    }

    /**
     * Returns true when the caller may invoke `listByParent(providerId)`. Aligns with story 4.4
     * AC5 "empty-list semantics" :
     * - Provider missing: returns true → controller body runs → `findByParent` returns []
     *   (no info leak; we surface "no models" rather than "no such provider")
     * - Provider user-scoped (legacy): same — returns true, body returns []
     * - Provider namespace-scoped: requires namespace READ on the parent namespace
     *
     * The fall-through to true for missing/user-scoped providers is deliberate: returning false
     * would surface 403 and disclose to a non-MEMBER caller that a namespace-scoped sibling
     * provider exists for that id-space. Empty list is the safer default.
     */
    fun canListByProvider(providerId: UUID): Boolean {
        val provider = aiProviderService.findById(providerId) ?: return true
        val nsId = provider.namespaceId ?: return true
        return permissionService.hasPermission(currentUserId(), NAMESPACE_TYPE, nsId.toString(), Action.READ)
    }

    private fun currentUserId(): String = userService.getCurrentUser().id.toString()

    companion object {
        private const val NAMESPACE_TYPE = "Namespace"
    }
}
