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
import mu.KLogging
import org.springframework.http.HttpStatus
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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * REST API for managing [AgentConfig] entities. Implements [AgentConfigApi] so external
 * consumers can declare a Feign client against the SDK interface.
 *
 * Authorization is declared via `@PreAuthorize` on each endpoint:
 * - READ: namespace MEMBER (transitive permission via `[:BELONGS_TO]`)
 * - WRITE/DELETE: namespace ADMIN (FR17/18/19)
 * - CREATE: namespace ADMIN (target namespace from payload)
 *
 * The `update` override preserves [AgentConfig.namespaceId] from the persisted entity
 * (mass-assignment guard); permission is checked declaratively before the body runs.
 *
 * **Platform agents** (namespaceId = null): the `@PreAuthorize` annotations on [create],
 * [update], and [delete] delegate to [PermissionServiceImpl.hasPermission] with a null
 * entityId. [PermissionServiceImpl] enforces the rule: READ is open to any authenticated
 * user; WRITE/DELETE with a null entityId return `false` for non-super-admins. Super-admins
 * bypass all checks via the `user.isAdmin` flag and are therefore the only callers who can
 * create, update, or delete platform-level agents.
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
            toResource = { toDto(it as AgentConfig) },
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
                    subAgents = resource.subAgents?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
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
        @RequestBody request: io.whozoss.agentos.sdk.api.common.GetByIdsRequest,
    ): List<AgentConfigDto> = crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(
        @PathVariable parentId: UUID,
        @RequestParam(required = false, defaultValue = "true") withDisabled: Boolean,
    ): List<AgentConfigDto> = agentConfigService.findByNamespace(parentId, withDisabled = withDisabled).map(::toDto)

    @GetMapping("/platform")
    @PreAuthorize("isAuthenticated()")
    fun listPlatformAgents(
        @RequestParam(required = false, defaultValue = "false") withDisabled: Boolean,
    ): List<AgentConfigDto> = agentConfigService.findByNamespace(null, withDisabled = withDisabled).map(::toDto)

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'WRITE')")
    @ResponseStatus(HttpStatus.CREATED)
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
        return toDto(
            agentConfigService.update(
                existing.copy(
                    name = resource.name,
                    description = resource.description,
                    instructions = resource.instructions,
                    modelName = resource.modelName,
                    integrations = resource.integrations,
                    advancedExecution = resource.advancedExecution ?: false,
                    externalMetadata = resource.externalMetadata,
                    enabled = resource.enabled ?: existing.enabled,
                    subAgents = resource.subAgents?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
                ),
            ),
        )
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'DELETE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(
        @PathVariable id: UUID,
    ) = crud.delete(id)

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    override fun enable(
        @PathVariable id: UUID,
    ): AgentConfigDto {
        logger.info { "[AgentConfig] Enabling agent config $id" }
        return toDto(agentConfigService.enable(id))
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    override fun disable(
        @PathVariable id: UUID,
    ): AgentConfigDto {
        logger.info { "[AgentConfig] Disabling agent config $id" }
        return toDto(agentConfigService.disable(id))
    }

    @PostMapping("/search", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#request.namespaceId, 'Namespace', 'READ')")
    override fun search(
        @Valid @RequestBody request: AgentConfigSearchRequest,
    ): List<AgentConfigDto> =
        agentConfigService
            .findAvailableByUserExternalId(request.namespaceId, request.userExternalId)
            .map(::toDto)

    @GetMapping("/{id}/definition")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'READ')")
    @HideOnAccessDenied
    override suspend fun getDefinition(
        @PathVariable id: UUID,
        @RequestParam(required = false, defaultValue = "false") withUserOverlay: Boolean,
        @RequestParam(required = false) namespaceId: UUID?,
    ): AgentDefinitionDto {
        val agentConfig =
            agentConfigService.findById(id)
                ?: throw ResourceNotFoundException("AgentConfig not found: $id")
        val resolvedNamespaceId =
            agentConfig.namespaceId ?: namespaceId
                ?: throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "namespaceId query parameter is required for platform-level agent configs",
                )
        val resolvedUserId = if (withUserOverlay) userService.getCurrentUser().metadata.id else null
        val definition =
            agentService.resolveDefinition(
                agentConfigId = id,
                namespaceId = resolvedNamespaceId,
                userId = resolvedUserId,
            )
        return AgentDefinitionDto(
            agentConfigId = definition.agentConfigId,
            name = definition.name,
            systemPrompt = definition.systemPrompt,
            instructions = definition.instructions,
            resolvedModelApiName = definition.resolvedModelApiName,
            resolvedProviderName = definition.resolvedProviderName,
            tools =
                definition.tools.map { tool ->
                    AgentDefinitionDto.ToolSummary(
                        name = tool.name,
                        description = tool.description,
                        inputSchema = tool.inputSchema,
                    )
                },
            advancedExecution = definition.advancedExecution,
            namespaceId = definition.namespaceId,
            userId = definition.userId,
        )
    }

    companion object : KLogging()
}

internal fun toDomain(resource: AgentConfigDto): AgentConfig {
    val metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID())
    return AgentConfig(
        metadata = metadata,
        namespaceId = resource.namespaceId,
        name = resource.name,
        description = resource.description,
        instructions = resource.instructions,
        modelName = resource.modelName,
        integrations = resource.integrations,
        advancedExecution = resource.advancedExecution ?: false,
        externalMetadata = resource.externalMetadata,
        enabled = resource.enabled ?: false,
        subAgents = resource.subAgents?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
    )
}

internal fun toDto(entity: AgentConfig) =
    AgentConfigDto(
        id = entity.metadata.id,
        namespaceId = entity.namespaceId,
        name = entity.name,
        description = entity.description,
        instructions = entity.instructions,
        modelName = entity.modelName,
        integrations = entity.integrations,
        advancedExecution = entity.advancedExecution.takeIf { it },
        externalMetadata = entity.externalMetadata,
        createdBy = entity.metadata.createdBy,
        createdOn = entity.metadata.created,
        updatedBy = entity.metadata.modifiedBy,
        updatedOn = entity.metadata.modified,
        enabled = entity.enabled,
        subAgents = entity.subAgents,
    )
