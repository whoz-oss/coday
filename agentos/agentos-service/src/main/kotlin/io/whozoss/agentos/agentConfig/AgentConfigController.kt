package io.whozoss.agentos.agentConfig

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.agent.ResolvedAgentDefinition
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
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
import java.util.UUID
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest as SdkGetByIdsRequest

@RestController
@RequestMapping(AgentConfigController.PATH)
class AgentConfigController(
    private val agentConfigService: AgentConfigService,
    private val agentService: AgentService,
    private val userService: UserService,
    permissionService: PermissionService,
    private val yamlExportMapper: ObjectMapper,
) : AgentConfigApi {
    private val crud =
        EntityCrudDelegate(
            service = agentConfigService,
            userService = userService,
            permissions = permissionService,
            entityType = EntityType.AGENT_CONFIG,
            toResource = { toDto(it as AgentConfig) },
            toDomain = { toDomain(it) },
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AGENT_CONFIG', 'READ')")
    override fun getById(
        @PathVariable id: UUID,
    ): AgentConfigDto = crud.getById(id)

    @PostMapping("/by-ids")
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<AgentConfigDto> = crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @GetMapping("/by-parentId/{namespaceId}")
    @PreAuthorize("hasPermission(#namespaceId, 'NAMESPACE', 'READ')")
    override fun listByParent(
        @PathVariable namespaceId: UUID,
        @RequestParam(defaultValue = "true") withDisabled: Boolean,
    ): List<AgentConfigDto> =
        agentConfigService
            .findByNamespace(namespaceId, withDisabled)
            .map { toDto(it) }

    @GetMapping("/platform")
    @PreAuthorize("hasPermission(null, 'PLATFORM', 'SUPER_ADMIN')")
    fun listPlatformAgents(
        @RequestParam(defaultValue = "false") withDisabled: Boolean,
    ): List<AgentConfigDto> =
        agentConfigService
            .findByNamespace(null, withDisabled)
            .map { toDto(it) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(
        "#resource.namespaceId == null ? hasPermission(null, 'PLATFORM', 'SUPER_ADMIN') : " +
            "hasPermission(#resource.namespaceId, 'NAMESPACE', 'WRITE')",
    )
    override fun create(
        @RequestBody resource: AgentConfigDto,
    ): AgentConfigDto = crud.create(resource)

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AGENT_CONFIG', 'WRITE')")
    override fun update(
        @PathVariable id: UUID,
        @RequestBody resource: AgentConfigDto,
    ): AgentConfigDto {
        val existing =
            agentConfigService.findById(id)
                ?: throw ResourceNotFoundException("AgentConfig with id '$id' not found")
        val domain = toDomain(resource.copy(id = id))
        return toDto(
            agentConfigService.update(
                domain.copy(
                    namespaceId = existing.namespaceId,
                    enabled = resource.enabled ?: existing.enabled,
                    externalMetadata = resource.externalMetadata,
                    skillSelectors = resource.skillSelectors,
                ),
            ),
        )
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasPermission(#id, 'AGENT_CONFIG', 'WRITE')")
    override fun enable(
        @PathVariable id: UUID,
    ): AgentConfigDto = toDto(agentConfigService.enable(id))

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasPermission(#id, 'AGENT_CONFIG', 'WRITE')")
    override fun disable(
        @PathVariable id: UUID,
    ): AgentConfigDto = toDto(agentConfigService.disable(id))

    @PostMapping("/search")
    @PreAuthorize("hasPermission(#request.namespaceId, 'NAMESPACE', 'READ')")
    override fun search(
        @RequestBody request: AgentConfigSearchRequest,
    ): List<AgentConfigDto> {
        val results =
            agentConfigService.findAvailableByUserExternalId(
                namespaceId = request.namespaceId,
                userExternalId = request.userExternalId,
            )
        return results.map { toDto(it) }
    }

    @GetMapping("/{id}/definition")
    @PreAuthorize("hasPermission(#id, 'AGENT_CONFIG', 'READ')")
    override suspend fun getDefinition(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "true") withUserOverlay: Boolean,
        @RequestParam(required = false) namespaceId: UUID?,
    ): AgentDefinitionDto {
        val config =
            agentConfigService.findById(id)
                ?: throw ResourceNotFoundException("AgentConfig with id '$id' not found")
        val effectiveNamespaceId =
            namespaceId
                ?: config.namespaceId
                ?: throw IllegalArgumentException("namespaceId is required to resolve definition for a platform agent")
        val caller = if (withUserOverlay) userService.getCurrentUser() else null
        val definition =
            agentService.resolveDefinition(
                agentConfigId = id,
                namespaceId = effectiveNamespaceId,
                userId = caller?.metadata?.id,
            )
        return toDefinitionDto(definition)
    }

    @GetMapping("/{id}/export")
    @PreAuthorize("hasPermission(#id, 'AGENT_CONFIG', 'READ')")
    fun export(
        @PathVariable id: UUID,
    ): ResponseEntity<String> {
        val config =
            agentConfigService.findById(id)
                ?: throw ResourceNotFoundException("AgentConfig with id '$id' not found")

        val payload =
            buildMap {
                put("name", config.name)
                config.description?.let { put("description", it) }
                config.instructions?.let { put("instructions", it) }
                config.modelName?.let { put("modelName", it) }
                config.integrations?.takeIf { it.isNotEmpty() }?.let { put("integrations", it) }
                config.subAgents?.takeIf { it.isNotEmpty() }?.let { put("subAgents", it) }
                config.docs?.takeIf { it.isNotEmpty() }?.let { put("docs", it) }
                config.skillSelectors?.takeIf { it.isNotEmpty() }?.let { put("skillSelectors", it) }
            }

        val yamlContent = yamlExportMapper.writeValueAsString(payload)
        val filename = "${sanitizeFilename(config.name)}.yaml"

        return ResponseEntity
            .ok()
            .contentType(MediaType("application", "x-yaml"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(yamlContent)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#id, 'AGENT_CONFIG', 'WRITE')")
    override fun delete(
        @PathVariable id: UUID,
    ) {
        crud.delete(id)
    }

    private fun toDefinitionDto(definition: ResolvedAgentDefinition): AgentDefinitionDto =
        AgentDefinitionDto(
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

    companion object {
        const val PATH = "/api/agent-configs"

        private val SPECIAL_CHARS_REGEX = Regex("[^a-zA-Z0-9]+")
        internal fun sanitizeFilename(name: String): String =
            name
                .lowercase()
                .replace(SPECIAL_CHARS_REGEX, "-")
                .ifEmpty { "agent" }
    }
}

internal fun toDto(entity: AgentConfig): AgentConfigDto =
    AgentConfigDto(
        id = entity.id,
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
        skillSelectors = entity.skillSelectors,
    )

internal fun toDomain(resource: AgentConfigDto): AgentConfig =
    AgentConfig(
        metadata =
            EntityMetadata(
                id = resource.id ?: UUID.randomUUID(),
                createdBy = resource.createdBy,
                created = resource.createdOn ?: java.time.Instant.now(),
                modifiedBy = resource.updatedBy,
                modified = resource.updatedOn ?: java.time.Instant.now(),
            ),
        namespaceId = resource.namespaceId,
        name = resource.name,
        description = resource.description,
        instructions = resource.instructions,
        modelName = resource.modelName,
        integrations = resource.integrations,
        advancedExecution = resource.advancedExecution ?: false,
        externalMetadata = resource.externalMetadata,
        enabled = resource.enabled ?: false,
        subAgents = resource.subAgents,
        skillSelectors = resource.skillSelectors,
    )
