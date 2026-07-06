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
 * **[list]** returns prompts for the given [OverlayScope]. [namespaceId] is required
 * for `NAMESPACE` and `USER_NAMESPACE` scopes, and must be omitted for `PLATFORM`
 * and `USER`.
 *
 * **[effective]** returns the merged set of prompts accessible in the given namespace
 * context (platform + namespace + user layers merged by name, highest layer wins).
 */
interface PromptApi : EntityCrudApi<PromptDto> {

    /**
     * GET /api/prompts/by-parentId/{parentId} — list namespace-scoped prompts.
     * Requires READ on the namespace.
     */
    fun listByParent(parentId: UUID): List<PromptDto>

    /**
     * GET /api/prompts?scope=SCOPE[&namespaceId=UUID] — list prompts by explicit scope.
     */
    fun list(scope: String, namespaceId: UUID? = null): List<PromptDto>

    /**
     * GET /api/prompts/effective?namespaceId=UUID — effective merged prompt set.
     * Requires READ on the namespace.
     */
    fun effective(namespaceId: UUID): List<PromptDto>
}
