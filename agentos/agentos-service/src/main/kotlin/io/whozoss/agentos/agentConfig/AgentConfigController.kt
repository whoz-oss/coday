package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for managing [AgentConfig] entities.
 *
 * Extends [EntityController] with [AgentConfigResource] as the HTTP DTO.
 *
 * Standard CRUD endpoints (inherited):
 *   GET    /api/agent-configs/{id}
 *   POST   /api/agent-configs/by-ids
 *   GET    /api/agent-configs/by-parentId/{parentId}   ← lists by namespaceId
 *   POST   /api/agent-configs
 *   PUT    /api/agent-configs/{id}
 *   DELETE /api/agent-configs/{id}
 */
@RestController
@RequestMapping(
    "/api/agent-configs",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class AgentConfigController(
    private val agentConfigService: AgentConfigService,
) : EntityController<AgentConfig, UUID, AgentConfigResource>(agentConfigService) {

    // -------------------------------------------------------------------------
    // Mapping between domain entity and HTTP resource
    // -------------------------------------------------------------------------

    override fun toResource(entity: AgentConfig): AgentConfigResource =
        AgentConfigResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            name = entity.name,
            description = entity.description,
            instructions = entity.instructions,
            modelName = entity.modelName,
        )

    override fun toDomain(resource: AgentConfigResource): AgentConfig =
        AgentConfig(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resource.namespaceId,
            name = resource.name,
            description = resource.description,
            instructions = resource.instructions,
            modelName = resource.modelName,
        )

    companion object : KLogging()
}
