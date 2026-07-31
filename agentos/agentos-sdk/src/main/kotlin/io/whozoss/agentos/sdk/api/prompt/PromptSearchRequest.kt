package io.whozoss.agentos.sdk.api.prompt

import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import java.util.UUID

/**
 * Request body for `POST /api/prompts/search`.
 *
 * Returns prompts declared at a single exact scope level — no merge, no inheritance.
 * The `(namespaceId?, userId?)` combination determines the level:
 *
 * | namespaceId | userId   | level           |
 * |-------------|----------|-----------------|
 * | null        | null     | platform        |
 * | non-null    | null     | namespace-shared|
 * | null        | non-null | user-global     |
 * | non-null    | non-null | user×namespace  |
 *
 * **Namespace resolution:** provide at most one of [namespaceId] or [namespaceExternalId].
 * When [namespaceExternalId] is supplied the server resolves it to the namespace UUID internally.
 * Providing both is rejected at the Bean Validation layer (see [isNamespaceIdentifierValid]).
 *
 * **User resolution:** provide at most one of [userId] or [userExternalId].
 * When [userExternalId] is supplied the server resolves it to the user UUID internally
 * (looked up via the IdP key), mirroring namespace resolution. Providing both is rejected
 * at the Bean Validation layer (see [isUserIdentifierValid]).
 *
 * [agentConfigIds] is an optional filter: when provided, only prompts linked
 * to one of those agents are returned. When null or empty, all prompts at the
 * resolved scope level are returned.
 */
@Schema(name = "PromptSearchRequest")
data class PromptSearchRequest(
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val userId: UUID? = null,
    @field:Schema(types = ["string", "null"])
    val namespaceExternalId: String? = null,
    @field:Schema(types = ["string", "null"])
    val userExternalId: String? = null,
    val agentConfigIds: List<UUID>? = null,
) {
    @get:AssertTrue(message = "Provide exactly one of namespaceId or namespaceExternalId")
    @get:JsonIgnore
    val isNamespaceIdentifierValid: Boolean
        get() = (namespaceId == null) != (namespaceExternalId == null)

    @get:AssertTrue(message = "Provide exactly one of userId or userExternalId")
    @get:JsonIgnore
    val isUserIdentifierValid: Boolean
        get() = (userId == null) != (userExternalId == null)
}
