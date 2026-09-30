package io.whozoss.agentos.sdk.api.skill

import io.whozoss.agentos.sdk.api.common.EntityCrudApi
import java.util.UUID

/**
 * HTTP API contract for Skill entities.
 *
 * Implemented by `SkillController` in agentos-service. External consumers
 * (e.g. whoz Copilot) implement this interface as a Feign client, adding their own
 * `@FeignClient` and routing annotations. AgentOS does not prescribe the client
 * technology or configuration.
 */
interface SkillApi : EntityCrudApi<SkillDto> {

    /**
     * GET /api/skills/by-parentId/{parentId} — list skills scoped to a namespace.
     */
    fun listByParent(parentId: UUID): List<SkillDto>

    /**
     * GET /api/skills/platform — list platform-level skills (namespaceId == null).
     */
    fun listPlatform(): List<SkillDto>
}
