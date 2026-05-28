package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
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

/**
 * REST API for managing [AgentConfig] entities.
 *
 * Authorization is declared via `@PreAuthorize` on each endpoint:
 * - READ: namespace MEMBER (transitive permission via `[:BELONGS_TO]`)
 * - WRITE/DELETE: namespace ADMIN (FR17/18/19)
 * - CREATE: namespace ADMIN (target namespace from payload)
 *
 * The `update` override preserves [AgentConfig.namespaceId] from the persisted entity
 * (mass-assignment guard); permission is checked declaratively before the body runs.
 */
@RestController
@RequestMapping(
    "/api/agent-configs",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class AgentConfigController(
    private val agentConfigService: AgentConfigService,
    private val agentService: AgentService,
    userService: UserService,
    permissionService: PermissionService,
) : EntityController<AgentConfig, UUID, AgentConfigResource>(agentConfigService, userService, permissionService) {

    override val entityType = EntityType.AGENT_CONFIG

    override fun toResource(entity: AgentConfig): AgentConfigResource =
        AgentConfigResource(
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
        )

    override fun toDomain(resource: AgentConfigResource): AgentConfig =
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

    /**
     * Merge an update resource onto an existing persisted entity.
     *
     * Two fields are intentionally excluded (mass-assignment guards):
     * - [AgentConfig.namespaceId]: clients cannot relocate an AgentConfig across namespaces via PUT
     * - [AgentConfig.enabled]: publication state is managed exclusively via the
     *   [publish] and [unpublish] endpoints
     */
    private fun toDomainForUpdate(
        resource: AgentConfigResource,
        existing: AgentConfig,
    ): AgentConfig =
        existing.copy(
            name = resource.name,
            description = resource.description,
            instructions = resource.instructions,
            modelName = resource.modelName,
            integrations = resource.integrations,
            advancedExecution = resource.advancedExecution ?: false,
            externalMetadata = resource.externalMetadata,
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'READ')")
    @HideOnAccessDenied
    override fun getById(@PathVariable id: UUID): AgentConfigResource = super.getById(id)

    // POST /by-ids — inherited from EntityController.getByIds (batch authorization,
    // story 5-4 factorisation of the pattern introduced by 5-3).

    /**
     * GET /api/agent-configs/by-parentId/{parentId} (no enabledOnly param)
     *
     * Matched by Spring MVC when `enabledOnly` is absent from the query string.
     * The `params = ["!enabledOnly"]` selector makes this mutually exclusive with
     * [listByNamespace], resolving the ambiguous-mapping conflict that arises from
     * the inherited [EntityController.listByParent] `@GetMapping`.
     *
     * Delegates to [listByNamespace] with `enabledOnly = false`.
     */
    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(@PathVariable parentId: UUID): List<AgentConfigResource> =
        listByNamespace(parentId, enabledOnly = false)

    /**
     * GET /api/agent-configs/by-parentId/{parentId}?enabledOnly=...
     *
     * Matched by Spring MVC when `enabledOnly` is present in the query string.
     * When `true`, only published agents are returned (end-user contexts like Copilot).
     * When `false`, all agents visible to namespace members/admins are returned.
     * Spring's `DefaultConversionService` handles `Boolean` binding — invalid values
     * (neither `"true"` nor `"false"`) produce a 400.
     */
    @GetMapping("/by-parentId/{parentId}", params = ["enabledOnly"])
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    fun listByNamespace(
        @PathVariable parentId: UUID,
        @RequestParam(required = false, defaultValue = "false") enabledOnly: Boolean,
    ): List<AgentConfigResource> =
        agentConfigService.findByNamespace(parentId, enabledOnly).map { toResource(it) }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'WRITE')")
    override fun create(@Valid @RequestBody resource: AgentConfigResource): AgentConfigResource =
        toResource(agentConfigService.create(toDomain(resource)))

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: AgentConfigResource,
    ): AgentConfigResource {
        val existing = agentConfigService.findById(id)
            ?: throw ResourceNotFoundException("AgentConfig not found: $id")
        return toResource(agentConfigService.update(toDomainForUpdate(resource, existing)))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'DELETE')")
    override fun delete(@PathVariable id: UUID) = super.delete(id)

    /**
     * POST /api/agent-configs/{id}/publish
     *
     * Publishes an agent, making it visible to end-users.
     * Requires WRITE permission (namespace ADMIN).
     */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    fun publish(@PathVariable id: UUID): AgentConfigResource {
        logger.info { "[AgentConfig] Publishing agent config $id" }
        return toResource(agentConfigService.publish(id))
    }

    /**
     * POST /api/agent-configs/{id}/unpublish
     *
     * Unpublishes an agent, hiding it from end-users while keeping it editable by admins.
     * Requires WRITE permission (namespace ADMIN).
     */
    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    fun unpublish(@PathVariable id: UUID): AgentConfigResource {
        logger.info { "[AgentConfig] Unpublishing agent config $id" }
        return toResource(agentConfigService.unpublish(id))
    }

    /**
     * POST /api/agent-configs/search
     *
     * Returns the deduplicated list of [AgentConfigResource] available to the user
     * identified by [userExternalId]. Availability is the union of:
     * - Agents deployed on any [io.whozoss.agentos.userGroup.UserGroup] the user is a member of
     * - Agents deployed directly on any [io.whozoss.agentos.namespace.Namespace] the user
     *   has a MEMBER or ADMIN relation on
     */
    @PostMapping("/search")
    @PreAuthorize("hasPermission(#agentConfigSearchRequest.namespaceId, 'Namespace', 'READ')")
    fun search(
        @Valid @RequestBody agentConfigSearchRequest: AgentConfigSearchRequest,
    ): List<AgentConfigResource> =
        agentConfigService
            .findAvailableByUserExternalId(agentConfigSearchRequest.namespaceId, agentConfigSearchRequest.userExternalId)
            .map { toResource(it) }

    /**
     * GET /api/agent-configs/{id}/definition
     *
     * Returns the fully-resolved definition of an agent config: effective instructions
     * (with namespace / integration / user context injected), resolved model and provider,
     * and the list of tools that would be made available to the agent.
     *
     * Useful for debugging agent configurations without starting a case.
     *
     * When [withUserOverlay] is `true`, the definition is resolved with the caller's user
     * context (3-tier provider/tool overlays applied). When false (default), the definition
     * is resolved without any user-specific overlay (namespace-only resolution).
     */
    @GetMapping("/{id}/definition")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'READ')")
    @HideOnAccessDenied
    fun getDefinition(
        @PathVariable id: UUID,
        @RequestParam(required = false, defaultValue = "false") withUserOverlay: Boolean,
    ): AgentDefinitionResource {
        val agentConfig = agentConfigService.findById(id)
            ?: throw ResourceNotFoundException("AgentConfig not found: $id")
        val resolvedUserId = if (withUserOverlay) userService.getCurrentUser().metadata.id else null
        val definition = agentService.resolveDefinition(
            agentConfigId = id,
            namespaceId = agentConfig.namespaceId,
            userId = resolvedUserId,
        )
        return AgentDefinitionResource(
            agentConfigId = definition.agentConfigId,
            name = definition.name,
            systemPrompt = definition.systemPrompt,
            instructions = definition.instructions,
            resolvedModelApiName = definition.resolvedModelApiName,
            resolvedProviderName = definition.resolvedProviderName,
            tools = definition.tools.map { tool ->
                AgentDefinitionResource.ToolSummary(
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
