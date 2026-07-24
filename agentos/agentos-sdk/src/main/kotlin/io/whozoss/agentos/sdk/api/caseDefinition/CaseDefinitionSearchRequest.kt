package io.whozoss.agentos.sdk.api.caseDefinition

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * Request body for `POST /api/case-definitions/search`.
 *
 * Returns case definitions declared at a single exact scope level — no merge, no inheritance.
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
 *
 * [agentConfigIds] is an optional filter: when provided, only case definitions linked
 * to one of those agents are returned. When null or empty, all case definitions at the
 * resolved scope level are returned.
 */
@Schema(name = "CaseDefinitionSearchRequest")
data class CaseDefinitionSearchRequest(
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val userId: UUID? = null,
    @field:Schema(types = ["string", "null"])
    val namespaceExternalId: String? = null,
    val agentConfigIds: List<UUID>? = null,
)
