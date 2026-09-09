package io.whozoss.agentos.sdk.api.prompt

import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import java.util.UUID

/**
 * Request body for `POST /api/prompts/effective`.
 *
 * Resolves and merges the four overlay layers (platform, user-global,
 * namespace-shared, user×namespace) for the given namespace, scoped to the
 * authenticated caller.
 * The highest-priority layer wins for each prompt name.
 *
 * Priority: platform (0) < user-global (1) < namespace-shared (2) < user×namespace (3).
 *
 * **Namespace resolution:** provide exactly one of [namespaceId] or [namespaceExternalId]
 * — enforced declaratively via [isNamespaceIdentifierValid] (Bean Validation rejects
 * both-null and both-non-null with a 400 before the controller method runs).
 *
 * The user layer is always the authenticated caller — there is no `userId` field to supply,
 * since the endpoint only ever resolves the effective set for the caller themselves.
 *
 * [agentConfigId] is an optional post-resolution filter: when provided, only prompts
 * linked to that agent are returned. When null, all resolved prompts are returned
 * (both agent-linked and autonomous).
 */
@Schema(name = "PromptEffectiveRequest")
data class PromptEffectiveRequest(
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"])
    val namespaceExternalId: String? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val agentConfigId: UUID? = null,
) {
    @get:AssertTrue(message = "Provide exactly one of namespaceId or namespaceExternalId")
    @get:JsonIgnore
    val isNamespaceIdentifierValid: Boolean
        get() = (namespaceId == null) != (namespaceExternalId == null)
}
