package io.whozoss.agentos.sdk.api.agentConfig

import io.whozoss.agentos.sdk.api.common.EntityCrudApi
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest
import java.util.UUID

/**
 * HTTP API contract for AgentConfig entities.
 *
 * Implemented by `AgentConfigController` in agentos-service. External consumers
 * (e.g. whoz Copilot) implement this interface as a Feign client, adding their own
 * `@FeignClient` and routing annotations. AgentOS does not prescribe the client
 * technology or configuration.
 *
 * The [withDisabled] parameter on [listByParent] controls whether disabled agents are
 * included. Defaults to `true` (admin contexts); set to `false` for end-user contexts
 * where only published agents should be visible.
 */
interface AgentConfigApi : EntityCrudApi<AgentConfigDto> {

    fun listByParent(parentId: UUID, withDisabled: Boolean = true): List<AgentConfigDto>

    /** POST /api/agent-configs/{id}/enable — publish an agent. */
    fun enable(id: UUID): AgentConfigDto

    /** POST /api/agent-configs/{id}/disable — unpublish an agent. */
    fun disable(id: UUID): AgentConfigDto

    /**
     * POST /api/agent-configs/search — list agents available to a user within a namespace.
     *
     * Availability is the union of agents deployed on the user's groups and agents deployed
     * directly on namespaces the user has MEMBER or ADMIN access to.
     */
    fun search(request: AgentConfigSearchRequest): List<AgentConfigDto>

    /**
     * GET /api/agent-configs/{id}/definition — resolve the full runtime definition of an agent.
     *
     * When [withUserOverlay] is true, the caller's user context is applied (3-tier provider
     * and tool overlays). Useful for debugging agent configurations without starting a case.
     *
     * Note: the service implementation may resolve the definition asynchronously internally,
     * but the HTTP interface is synchronous (blocking until the result is ready).
     */
    suspend fun getDefinition(id: UUID, withUserOverlay: Boolean = false, namespaceId: UUID? = null): AgentDefinitionDto
}
