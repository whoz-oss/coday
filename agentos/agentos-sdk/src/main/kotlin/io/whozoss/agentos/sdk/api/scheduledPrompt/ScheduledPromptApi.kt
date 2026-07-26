package io.whozoss.agentos.sdk.api.scheduledPrompt

import io.whozoss.agentos.sdk.api.common.EntityCrudApi
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest
import java.util.UUID

/**
 * HTTP API contract for ScheduledPrompt entities.
 *
 * Implemented by `ScheduledPromptController` in agentos-service. External consumers
 * (e.g. whoz Copilot) implement this interface as a Feign client.
 *
 * **Scope dispatch on [create]** — inferred from `(body.namespaceId, body.userId)`:
 * - `(null, null)`   → platform (Super Admin only)
 * - `(ns, null)`     → namespace-scoped (WRITE on namespace)
 * - `(null, me)`     → user-global (authenticated only)
 * - `(ns, me)`       → user × namespace (READ on namespace)
 *
 * **[search]** returns scheduled prompts declared at a single exact scope level.
 * **[effective]** returns the merged set accessible in the given namespace context.
 * **[toggle]** flips the [ScheduledPromptDto.enabled] flag.
 */
interface ScheduledPromptApi : EntityCrudApi<ScheduledPromptDto> {

    /**
     * POST /api/scheduled-prompts/search — list scheduled prompts at an exact scope level.
     */
    fun search(request: ScheduledPromptSearchRequest): List<ScheduledPromptDto>

    /**
     * POST /api/scheduled-prompts/effective — effective merged set for a user in a namespace.
     */
    fun effective(request: ScheduledPromptEffectiveRequest): List<ScheduledPromptDto>

    /**
     * PATCH /api/scheduled-prompts/{id}/toggle — toggle the enabled flag.
     */
    fun toggle(id: UUID): ScheduledPromptDto
}
