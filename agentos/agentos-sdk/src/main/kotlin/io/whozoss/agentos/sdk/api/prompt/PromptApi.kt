package io.whozoss.agentos.sdk.api.prompt

import io.whozoss.agentos.sdk.api.common.EntityCrudApi
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest
import java.util.UUID

/**
 * HTTP API contract for Prompt entities.
 *
 * Implemented by `PromptController` in agentos-service. External consumers
 * (e.g. whoz Copilot) implement this interface as a Feign client.
 *
 * **Scope dispatch on [create]** — inferred from `(body.namespaceId, body.userId)`:
 * - `(null, null)`   → platform (Super Admin only)
 * - `(ns, null)`     → namespace-scoped (WRITE on namespace)
 * - `(null, me)`     → user-global (authenticated only)
 * - `(ns, me)`       → user × namespace (READ on namespace)
 *
 * **[search]** returns prompts declared at a single exact scope level.
 * The `(namespaceId?, userId?)` combination in the body determines the level:
 * - `(null, null)`   → platform
 * - `(ns, null)`     → namespace-shared
 * - `(null, userId)` → user-global
 * - `(ns, userId)`   → user × namespace
 * An optional [agentConfigIds] filter restricts results to prompts linked to those agents.
 * No merge, no inheritance — admin view only.
 *
 * **[effective]** returns the merged set of prompts accessible in the given namespace
 * context (platform + namespace + user layers merged by name, highest layer wins).
 * An optional [agentConfigId] filter is applied post-resolution.
 */
interface PromptApi : EntityCrudApi<PromptDto> {

    /**
     * POST /api/prompts/search — list prompts at an exact scope level.
     * Requires READ on the namespace when namespaceId is provided.
     */
    fun search(request: PromptSearchRequest): List<PromptDto>

    /**
     * POST /api/prompts/effective — effective merged prompt set for a user in a namespace.
     * Requires READ on the namespace.
     */
    fun effective(request: PromptEffectiveRequest): List<PromptDto>
}
