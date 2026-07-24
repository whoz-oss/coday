package io.whozoss.agentos.sdk.api.caseDefinition

import io.whozoss.agentos.sdk.api.common.EntityCrudApi
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest
import java.util.UUID

/**
 * HTTP API contract for CaseDefinition entities.
 *
 * Implemented by `CaseDefinitionController` in agentos-service. External consumers
 * (e.g. whoz Copilot) implement this interface as a Feign client.
 *
 * **Scope dispatch on [create]** — inferred from `(body.namespaceId, body.userId)`:
 * - `(null, null)`   → platform (Super Admin only)
 * - `(ns, null)`     → namespace-scoped (WRITE on namespace)
 * - `(null, me)`     → user-global (authenticated only)
 * - `(ns, me)`       → user × namespace (READ on namespace)
 *
 * **[search]** returns case definitions declared at a single exact scope level.
 * The `(namespaceId?, userId?)` combination in the body determines the level:
 * - `(null, null)`   → platform
 * - `(ns, null)`     → namespace-shared
 * - `(null, userId)` → user-global
 * - `(ns, userId)`   → user × namespace
 * An optional [agentConfigIds] filter restricts results to case definitions linked to those agents.
 * No merge, no inheritance — admin view only.
 *
 * **[effective]** returns the merged set of case definitions accessible in the given namespace
 * context (platform + namespace + user layers merged by name, highest layer wins).
 * An optional [agentConfigId] filter is applied post-resolution.
 *
 * **[toggle]** flips the [CaseDefinitionDto.enabled] flag on a single case definition.
 * Specific to CaseDefinition — Prompt has no equivalent (no `enabled` field).
 */
interface CaseDefinitionApi : EntityCrudApi<CaseDefinitionDto> {

    /**
     * POST /api/case-definitions/search — list case definitions at an exact scope level.
     * Requires READ on the namespace when namespaceId is provided.
     */
    fun search(request: CaseDefinitionSearchRequest): List<CaseDefinitionDto>

    /**
     * POST /api/case-definitions/effective — effective merged case definition set for a user in a namespace.
     * Requires READ on the namespace.
     */
    fun effective(request: CaseDefinitionEffectiveRequest): List<CaseDefinitionDto>

    /**
     * PATCH /api/case-definitions/{id}/toggle — toggle the enabled flag.
     * Requires WRITE on the case definition.
     */
    fun toggle(id: UUID): CaseDefinitionDto
}
