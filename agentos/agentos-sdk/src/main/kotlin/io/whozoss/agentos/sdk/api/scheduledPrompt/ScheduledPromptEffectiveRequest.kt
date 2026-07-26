package io.whozoss.agentos.sdk.api.scheduledPrompt

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * Request body for `POST /api/scheduled-prompts/effective`.
 *
 * Resolves and merges the four overlay layers (platform, user-global,
 * namespace-shared, user×namespace) for the given `(namespaceId, userId)` context.
 * The highest-priority layer wins for each scheduled prompt name.
 *
 * Priority: platform (0) < user-global (1) < namespace-shared (2) < user×namespace (3).
 *
 * **Namespace resolution:** provide exactly one of [namespaceId] or [namespaceExternalId].
 * **User resolution:** provide exactly one of [userId] or [userExternalId].
 * The resolved userId is always validated against the authenticated caller.
 *
 * [agentConfigId] is an optional post-resolution filter.
 */
@Schema(name = "ScheduledPromptEffectiveRequest")
data class ScheduledPromptEffectiveRequest(
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val userId: UUID? = null,
    @field:Schema(types = ["string", "null"])
    val namespaceExternalId: String? = null,
    @field:Schema(types = ["string", "null"])
    val userExternalId: String? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val agentConfigId: UUID? = null,
)
