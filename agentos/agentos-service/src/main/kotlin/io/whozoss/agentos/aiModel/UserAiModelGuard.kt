package io.whozoss.agentos.aiModel

import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.sdk.aiProvider.AiModel
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

/**
 * Authorization guard for user-scoped [AiModel] entities.
 *
 * Read/modify authorization is ownership-based (`model.userId == auth.name`).
 *
 * Create authorization requires resolving the parent [io.whozoss.agentos.sdk.aiProvider.AiProvider]
 * from [AiProviderService] because three distinct failure modes map to different HTTP codes:
 * - Parent not found → 404 (indistinguishable from a missing entity)
 * - Parent found but namespace-only (parent.userId == null) → 403 (explicit; caller supplied the id)
 * - Parent found but owned by another user → 404 (existence-hiding)
 * A sealed [CreateVerdict] communicates which case applies to the controller.
 */
@Component
class UserAiModelGuard(
    private val aiProviderService: AiProviderService,
) {
    fun canRead(model: AiModel, auth: Authentication): Boolean = isOwner(model, auth)

    fun canModify(model: AiModel, auth: Authentication): Boolean = isOwner(model, auth)

    fun canCreateVerdict(target: AiModel, auth: Authentication): CreateVerdict {
        val me = auth.name ?: return CreateVerdict.ParentMissing
        val parent = aiProviderService.findById(target.aiProviderId) ?: return CreateVerdict.ParentMissing
        val parentUserId = parent.userId ?: return CreateVerdict.ParentNotUserScoped
        return if (parentUserId.toString() == me) CreateVerdict.Ok else CreateVerdict.CrossUser
    }

    private fun isOwner(model: AiModel, auth: Authentication): Boolean {
        val me = auth.name ?: return false
        return model.userId?.toString() == me
    }

    sealed class CreateVerdict {
        object Ok : CreateVerdict()
        object ParentMissing : CreateVerdict()
        object ParentNotUserScoped : CreateVerdict()
        object CrossUser : CreateVerdict()
    }
}
