package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigApi
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigDto
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigSearchRequest
import io.whozoss.agentos.sdk.api.agentConfig.AgentDefinitionDto
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest as SdkGetByIdsRequest

/**
 * REST API for managing [AgentConfig] entities. Implements [AgentConfigApi] so external
 * consumers (e.g. whoz Copilot) can declare a Feign client against the SDK interface.
 *
 * Standard CRUD operations are delegated to [crud] explicitly by name.
 */
@RestController
@RequestMapping(
    "/api/agent-configs",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class AgentConfigController(
    private val agentConfigService: AgentConfigService,
    private val agentService: AgentService,
    private val userService: UserService,
    permissionService: PermissionService,
) : AgentConfigApi {
    private val crud =
        EntityCrudDelegate(
            service = agentConfigService,
            userService = userService,
            permissions = permissionService,
            entityType = EntityType.AGENT_CONFIG,
            toResource = { (it as AgentConfig).toDto() },
            toDomain = { resource ->
                AgentConfig(
                    metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
                    namespaceId = resource.namespaceId,
                    name = resource.name,
                    description = resource.description,
                    instructions = resource.instructions,
                    modelName = resource.modelName,
                    integrations = resource.integrations,
                    advancedExecution = resource.advancedExecution ?: false,
                    externalMetadata = resource.externalMetadata,
                    enabled = resource.enabled ?: false,
                )
            },
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): AgentConfigDto = crud.getById(id)

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<AgentConfigDto> = crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(
        @PathVariable parentId: UUID,
        @RequestParam(required = false, defaultValue = "true") withDisabled: Boolean,
    ): List<AgentConfigDto> = agentConfigService.findByNamespace(parentId, withDisabled = withDisabled).map { it.toDto() }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'WRITE')")
    override fun create(
        @Valid @RequestBody resource: AgentConfigDto,
    ): AgentConfigDto = crud.create(resource)

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: AgentConfigDto,
    ): AgentConfigDto {
        val existing =
            agentConfigService.findById(id)
                ?: throw ResourceNotFoundException("AgentConfig not found: $id")
        return agentConfigService
            .update(
                existing.copy(
                    name = resource.name,
                    description = resource.description,
                    instructions = resource.instructions,
                    modelName = resource.modelName,
                    integrations = resource.integrations,
                    advancedExecution = resource.advancedExecution ?: false,
                    externalMetadata = resource.externalMetadata,
                    // enabled is managed exclusively via /enable and /disable
                ),
            ).toDto()
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'DELETE')")
    override fun delete(
        @PathVariable id: UUID,
    ) = crud.delete(id)

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    override fun enable(
        @PathVariable id: UUID,
    ): AgentConfigDto {
        logger.info { "[AgentConfig] Enabling agent config $id" }
        return agentConfigService.enable(id).toDto()
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    override fun disable(
        @PathVariable id: UUID,
    ): AgentConfigDto {
        logger.info { "[AgentConfig] Disabling agent config $id" }
        return agentConfigService.disable(id).toDto()
    }

    @PostMapping("/search", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#request.namespaceId, 'Namespace', 'READ')")
    override fun search(
        @Valid @RequestBody request: AgentConfigSearchRequest,
    ): List<AgentConfigDto> =
        agentConfigService
            .findAvailableByUserExternalId(request.namespaceId, request.userExternalId)
            .map { it.toDto() }

    @GetMapping("/{id}/definition")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'READ')")
    @HideOnAccessDenied
    override fun getDefinition(
        @PathVariable id: UUID,
        @RequestParam(required = false, defaultValue = "false") withUserOverlay: Boolean,
    ): AgentDefinitionDto {
        val agentConfig =
            agentConfigService.findById(id)
                ?: throw ResourceNotFoundException("AgentConfig not found: $id")
        val resolvedUserId = if (withUserOverlay) userService.getCurrentUser().metadata.id else null
        val definition =
            runBlocking {
                agentService.resolveDefinition(
                    agentConfigId = id,
                    namespaceId = agentConfig.namespaceId,
                    userId = resolvedUserId,
                )
            }
        return AgentDefinitionDto(
            agentConfigId = definition.agentConfigId,
            name = definition.name,
            systemPrompt = definition.systemPrompt,
            instructions = definition.instructions,
            resolvedModelApiName = definition.resolvedModelApiName,
            resolvedProviderName = definition.resolvedProviderName,
            tools = definition.tools.map { AgentDefinitionDto.ToolSummary(it.name, it.description, it.inputSchema) },
            advancedExecution = definition.advancedExecution,
            namespaceId = definition.namespaceId,
            userId = definition.userId,
        )
    }

    companion object : KLogging()
}

// ---------------------------------------------------------------------------
// Extension: AgentConfig → AgentConfigDto
// ---------------------------------------------------------------------------

internal fun AgentConfig.toDto() =
    AgentConfigDto(
        id = metadata.id,
        namespaceId = namespaceId,
        name = name,
        description = description,
        instructions = instructions,
        modelName = modelName,
        integrations = integrations,
        advancedExecution = advancedExecution.takeIf { it },
        externalMetadata = externalMetadata,
        createdBy = metadata.createdBy,
        createdOn = metadata.created,
        updatedBy = metadata.modifiedBy,
        updatedOn = metadata.modified,
        enabled = enabled,
    )
