package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
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
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
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
    userService: UserService,
    permissionService: PermissionService,
) : EntityController<AgentConfig, UUID?, AgentConfigResource>(agentConfigService, userService, permissionService) {
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
     * One field is intentionally excluded (mass-assignment guard):
     * - [AgentConfig.namespaceId]: clients cannot relocate an AgentConfig across namespaces via PUT
     *
     * [AgentConfig.enabled] is intentionally included so that PUT can toggle the published
     * state in a single call. The dedicated [enable] and [disable] endpoints remain available
     * as convenience shortcuts.
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
            enabled = resource.enabled ?: existing.enabled,
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): AgentConfigResource = super.getById(id)

    // POST /by-ids — inherited from EntityController.getByIds (batch authorization,
    // story 5-4 factorisation of the pattern introduced by 5-3).

    /**
     * GET /api/agent-configs/by-parentId/{parentId} (no withDisabled param)
     *
     * Matched by Spring MVC when `withDisabled` is absent from the query string.
     * The `params = ["!withDisabled"]` selector makes this mutually exclusive with
     * [listByNamespace], resolving the ambiguous-mapping conflict that arises from
     * the inherited [EntityController.listByParent] `@GetMapping`.
     *
     * Delegates to [listByNamespace] with `withDisabled = true`.
     *
     * The [parentId] type is `UUID?` because `AgentConfigController` extends
     * `EntityController<AgentConfig, UUID?, AgentConfigResource>` — the generic
     * forces the override signature. Spring MVC cannot bind a path segment to null
     * in practice, so null is unreachable via HTTP. Platform agents are listed via
     * [listPlatformAgents] (`GET /api/agent-configs/platform`) instead.
     */
    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(
        @PathVariable parentId: UUID?,
    ): List<AgentConfigResource> {
        if (parentId == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "parentId must not be null — use GET /api/agent-configs/platform for platform-level agents",
            )
        }
        return listByNamespace(parentId!!, withDisabled = true)
    }

    /**
     * GET /api/agent-configs/by-parentId/{parentId}?withDisabled=...
     *
     * Matched by Spring MVC when `withDisabled` is present in the query string.
     * When `true` (the default), all agents visible to namespace members/admins are returned.
     * When `false`, only published (enabled) agents are returned (end-user contexts like Copilot).
     * Spring's `DefaultConversionService` handles `Boolean` binding — invalid values
     * (neither `"true"` nor `"false"`) produce a 400.
     *
     * [parentId] is always a namespace UUID — platform agents are listed via [listPlatformAgents].
     */
    @GetMapping("/by-parentId/{parentId}", params = ["withDisabled"])
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    fun listByNamespace(
        @PathVariable parentId: UUID,
        @RequestParam(required = false, defaultValue = "true") withDisabled: Boolean,
    ): List<AgentConfigResource> = agentConfigService.findByNamespace(parentId, withDisabled = withDisabled).map { toResource(it) }

    /**
     * GET /api/agent-configs/platform
     *
     * Lists platform-level agent configs (namespaceId = null), with optional [withDisabled]
     * filtering (defaults to `false` so only published/enabled agents are returned to
     * regular callers).
     *
     * Readable by any authenticated user — consistent with `GET /{id}` which is open to
     * all authenticated users for platform agents (entityId = null → READ open).
     * Only super-admins should pass `withDisabled=true` to see disabled platform agents;
     * the endpoint does not enforce this distinction because the information is not sensitive.
     */
    @GetMapping("/platform")
    @PreAuthorize("isAuthenticated()")
    fun listPlatformAgents(
        @RequestParam(required = false, defaultValue = "false") withDisabled: Boolean,
    ): List<AgentConfigResource> = agentConfigService.findByNamespace(null, withDisabled = withDisabled).map { toResource(it) }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'WRITE')")
    override fun create(
        @Valid @RequestBody resource: AgentConfigResource,
    ): AgentConfigResource = toResource(agentConfigService.create(toDomain(resource)))

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: AgentConfigResource,
    ): AgentConfigResource {
        val existing =
            agentConfigService.findById(id)
                ?: throw ResourceNotFoundException("AgentConfig not found: $id")
        return toResource(agentConfigService.update(toDomainForUpdate(resource, existing)))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'DELETE')")
    override fun delete(
        @PathVariable id: UUID,
    ) = super.delete(id)

    /**
     * POST /api/agent-configs/{id}/enable
     *
     * Enables an agent, making it active.
     * Requires WRITE permission (namespace ADMIN, or super-admin for platform agents).
     */
    @PostMapping("/{id}/enable")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    fun enable(
        @PathVariable id: UUID,
    ): AgentConfigResource {
        logger.info { "[AgentConfig] Enabling agent config $id" }
        return toResource(agentConfigService.enable(id))
    }

    /**
     * POST /api/agent-configs/{id}/disable
     *
     * Disables an agent, making it inactive.
     * Requires WRITE permission (namespace ADMIN, or super-admin for platform agents).
     */
    @PostMapping("/{id}/disable")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    fun disable(
        @PathVariable id: UUID,
    ): AgentConfigResource {
        logger.info { "[AgentConfig] Disabling agent config $id" }
        return toResource(agentConfigService.disable(id))
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
     *
     * For platform agents (namespaceId = null), a [namespaceId] query parameter is required
     * to resolve the model and provider in the context of a specific namespace.
     */
    @GetMapping("/{id}/definition")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'READ')")
    @HideOnAccessDenied
    suspend fun getDefinition(
        @PathVariable id: UUID,
        @RequestParam(required = false, defaultValue = "false") withUserOverlay: Boolean = false,
        @RequestParam(required = false) namespaceId: UUID? = null,
    ): AgentDefinitionResource {
        val agentConfig =
            agentConfigService.findById(id)
                ?: throw ResourceNotFoundException("AgentConfig not found: $id")
        // For namespace-scoped agents, use the agent's own namespaceId.
        // For platform agents, a namespaceId query param is required for model/provider resolution.
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
        return AgentDefinitionResource(
            agentConfigId = definition.agentConfigId,
            name = definition.name,
            systemPrompt = definition.systemPrompt,
            instructions = definition.instructions,
            resolvedModelApiName = definition.resolvedModelApiName,
            resolvedProviderName = definition.resolvedProviderName,
            tools =
                definition.tools.map { tool ->
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
