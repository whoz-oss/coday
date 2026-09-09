package io.whozoss.agentos.entity

import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.user.UserService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Resolves a namespace or user UUID from either a direct UUID or an external identifier
 * (namespace `externalId` / user IdP key), a pattern shared by every controller that
 * accepts both forms in its request DTOs ([io.whozoss.agentos.prompt.PromptController],
 * [io.whozoss.agentos.scheduledPrompt.ScheduledPromptController], ...).
 *
 * **Mutual exclusivity is NOT re-validated here.** The "not both" / "exactly one" rule is
 * enforced declaratively by `@AssertTrue` on the request DTOs (e.g.
 * `PromptSearchRequest.isNamespaceIdentifierValid`) and rejected by Bean Validation
 * (`@Valid`) before the controller method — and therefore this resolver — ever runs.
 * Callers can assume at most one of the two arguments is non-null.
 *
 * Two flavours per identifier type:
 * - `resolve*Id` — exactly one of the pair is required; throws [BadRequestException]
 *   when both are absent. Used by endpoints where the scope is mandatory (e.g. `effective`).
 * - `resolveOptional*Id` — both may be absent (`null` result), used where the identifier
 *   is one axis of an otherwise-optional scope (e.g. `search`, where `(null, null)` means
 *   platform scope).
 */
@Service
class ExternalIdentifierResolver(
    private val namespaceService: NamespaceService,
    private val userService: UserService,
) {
    /** Resolves a namespace UUID; throws [BadRequestException] when both arguments are null. */
    fun resolveNamespaceId(id: UUID?, externalId: String?): UUID =
        resolveOptionalNamespaceId(id, externalId)
            ?: throw BadRequestException("namespaceId or namespaceExternalId is required")

    /** Resolves a namespace UUID, or null when both arguments are null (e.g. platform scope). */
    fun resolveOptionalNamespaceId(id: UUID?, externalId: String?): UUID? =
        id ?: externalId?.let {
            namespaceService.findByExternalId(it)?.metadata?.id
                ?: throw ResourceNotFoundException("Namespace not found for externalId: $it")
        }

    /** Resolves a user UUID; throws [BadRequestException] when both arguments are null. */
    fun resolveUserId(id: UUID?, externalId: String?): UUID =
        resolveOptionalUserId(id, externalId)
            ?: throw BadRequestException("userId or userExternalId is required")

    /** Resolves a user UUID, or null when both arguments are null (e.g. namespace-shared / platform scope). */
    fun resolveOptionalUserId(id: UUID?, externalId: String?): UUID? =
        id ?: externalId?.let {
            userService.findByExternalId(it)?.metadata?.id
                ?: throw ResourceNotFoundException("User not found for externalId: $it")
        }
}
