package io.whozoss.agentos.sdk.api.prompt

import io.swagger.v3.oas.annotations.media.Schema
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
    val agentConfigIds: List<UUID>? = null,
)
