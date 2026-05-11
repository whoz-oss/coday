package io.whozoss.agentos.aiModel

import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * SpEL-callable guard for [AiModelController].
 *
 * The standard `hasPermission(#id, 'AiModel', 'WRITE')` is unsuitable for `POST` because
 * permission is determined by the **parent AiProvider's scope**, not by the AiModel itself
 * (which doesn't even exist yet at create time, and whose `namespaceId`/`userId` are
 * denormalised from the provider).
 *
 * [canCreateVerdict] returns a single 3-case sealed verdict covering NS-shared and user-scope parents:
 * - [CreateVerdict.Ok] — parent visible and caller permitted.
 * - [CreateVerdict.ParentInvisible] — parent missing, owned by another user, or in a namespace
 *   where the caller has no READ (existence-hiding, 404 via `@HideOnAccessDenied`).
 * - [CreateVerdict.ParentNotWritable] — parent NS-shared, caller has READ but lacks WRITE (403 explicit).
 *
 * [canSeeProvider] is the canonical helper used by [AiModelController.list] branches that
 * touch `?aiProviderId=`, ensuring consistent empty-list-vs-error behaviour (SF5 cohérence).
 */
@Component("aiModelGuard")
class AiModelGuard(
    private val aiProviderService: AiProviderService,
    private val permissionService: PermissionService,
) {

    fun canCreateVerdict(resource: AiModelResource, auth: Authentication): CreateVerdict {
        val providerId = resource.aiProviderId ?: return CreateVerdict.ParentInvisible
        val parent = aiProviderService.findById(providerId) ?: return CreateVerdict.ParentInvisible
        val callerId = auth.name ?: return CreateVerdict.ParentInvisible

        return when {
            parent.userId != null -> when {
                parent.userId.toString() == callerId -> CreateVerdict.Ok
                else -> CreateVerdict.ParentInvisible
            }
            else -> {
                val nsId = parent.namespaceId!!.toString()
                val hasRead = permissionService.hasPermission(callerId, EntityType.NAMESPACE, nsId, Action.READ)
                when {
                    !hasRead -> CreateVerdict.ParentInvisible
                    !permissionService.hasPermission(callerId, EntityType.NAMESPACE, nsId, Action.WRITE) -> CreateVerdict.ParentNotWritable
                    else -> CreateVerdict.Ok
                }
            }
        }
    }

    fun canSeeProvider(providerId: UUID, auth: Authentication): Boolean {
        val parent = aiProviderService.findById(providerId) ?: return false
        val callerId = auth.name ?: return false
        return when {
            parent.userId != null -> parent.userId.toString() == callerId
            else -> permissionService.hasPermission(callerId, EntityType.NAMESPACE, parent.namespaceId!!.toString(), Action.READ)
        }
    }

    fun canListByProvider(providerId: UUID, auth: Authentication): Boolean = canSeeProvider(providerId, auth)

    sealed class CreateVerdict {
        data object Ok : CreateVerdict()
        data object ParentInvisible : CreateVerdict()
        data object ParentNotWritable : CreateVerdict()
    }
}
