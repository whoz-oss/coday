package io.whozoss.agentos.sdk.api.prompt

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * Request body for `POST /api/prompts/effective`.
 *
 * Resolves and merges the four overlay layers (platform, user-global,
 * namespace-shared, user×namespace) for the given `(namespaceId, userId)` context.
 * The highest-priority layer wins for each prompt name.
 *
 * Priority: platform (0) < user-global (1) < namespace-shared (2) < user×namespace (3).
 *
 * [agentConfigId] is an optional post-resolution filter: when provided, only prompts
 * linked to that agent are returned. When null, all resolved prompts are returned
 * (both agent-linked and autonomous).
 *
 * The [userId] of resolution is always the authenticated caller — validated
 * server-side, never trusted from the client.
 */
@Schema(name = "PromptEffectiveRequest")
data class PromptEffectiveRequest(
    @field:NotNull
    val namespaceId: UUID,
    @field:NotNull
    val userId: UUID,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val agentConfigId: UUID? = null,
)
