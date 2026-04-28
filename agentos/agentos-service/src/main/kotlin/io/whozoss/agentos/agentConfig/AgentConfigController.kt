package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
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
    private val userService: UserService,
    private val permissionService: PermissionService,
) : EntityController<AgentConfig, UUID, AgentConfigResource>(agentConfigService) {

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

    /**
     * Merge an update resource onto an existing persisted entity. The persisted
     * [AgentConfig.namespaceId] is preserved — clients cannot relocate an
     * AgentConfig across namespaces via PUT (mass-assignment guard).
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
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'READ')")
    @HideOnAccessDenied
    override fun getById(@PathVariable id: UUID): AgentConfigResource = super.getById(id)

    /**
     * POST `/by-ids` — batch fetch with permission filter resolved in a single Cypher
     * round-trip via [PermissionService.filterVisibleIds] (story 5-3, drops the previous
     * `@PostFilter` N+1 pattern).
     */
    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(@RequestBody ids: List<UUID>): List<AgentConfigResource> {
        if (ids.isEmpty()) return emptyList()
        val currentUser = userService.getCurrentUser()
        val visibleIds: Set<UUID> = if (currentUser.isAdmin) {
            ids.toSet()
        } else {
            permissionService
                .filterVisibleIds(currentUser.id.toString(), ENTITY_TYPE, ids.map(UUID::toString), Action.READ)
                .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                .toSet()
        }
        if (visibleIds.isEmpty()) return emptyList()
        return agentConfigService.findByIds(visibleIds).map(::toResource)
    }

    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(@PathVariable parentId: UUID): List<AgentConfigResource> = super.listByParent(parentId)

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'WRITE')")
    override fun create(@Valid @RequestBody resource: AgentConfigResource): AgentConfigResource =
        super.create(resource)

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

    companion object : KLogging() {
        private const val ENTITY_TYPE = "AgentConfig"
    }
}
