package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.entity.SecuredEntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * REST API for managing [AgentConfig] entities (Epic 4 Story 4.1).
 *
 * Extends [SecuredEntityController] — CREATE / UPDATE / DELETE are gated on
 * namespace ADMIN (FR17/FR18/FR19), LIST / READ are open to every caller with
 * at least MEMBER access on the parent namespace (FR21/FR22) via the standard
 * transitive permission rules.
 *
 * Standard CRUD endpoints (inherited, permission-gated):
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
    userService: UserService,
    permissionService: PermissionService,
) : SecuredEntityController<AgentConfig, UUID, AgentConfigResource>(
    agentConfigService,
    userService,
    permissionService,
) {

    override fun getEntityType(): String = ENTITY_TYPE

    /**
     * AgentConfig creation/update/delete is restricted to namespace ADMINs
     * (FR17/FR18/FR19). `Action.WRITE` maps to `PermissionRelation.ADMIN` in
     * [io.whozoss.agentos.permissions.PermissionServiceImpl.evaluatePermission];
     * super-admins pass via the `isAdmin` bypass inside `hasPermission`.
     */
    override fun checkCreatePermission(userId: String, entity: AgentConfig) {
        if (!permissionService.hasPermission(userId, NAMESPACE_TYPE, entity.namespaceId.toString(), Action.WRITE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied - namespace ADMIN role required")
        }
    }

    /**
     * GET /api/agent-configs/by-parentId/{parentId} — list AgentConfigs in a namespace.
     *
     * AgentConfig is a shared configuration (not owner-private like Case): any
     * caller with READ on the parent namespace sees every AgentConfig in that
     * namespace (FR21). Short-circuits the N+1 per-entity `hasPermission` cost
     * of [io.whozoss.agentos.entity.SecuredEntityController.listByParent] by
     * checking the namespace-level READ once.
     */
    override fun listByParent(@PathVariable parentId: UUID): List<AgentConfigResource> {
        val userId = userService.getCurrentUser().id.toString()
        val canRead = permissionService.hasPermission(userId, NAMESPACE_TYPE, parentId.toString(), Action.READ)
        if (!canRead) {
            logger.debug { "User $userId has no READ on namespace $parentId — returning empty AgentConfig list" }
            return emptyList()
        }
        return agentConfigService.findByParent(parentId).map { toResource(it) }
    }

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

    /**
     * Merge an update resource onto an existing persisted entity.
     *
     * [AgentConfig.namespaceId] is server-owned — the client cannot relocate
     * an AgentConfig across namespaces via PUT. Only mutable fields are taken
     * from the payload.
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

    /**
     * PUT /{id} — update mutable fields of an existing AgentConfig.
     *
     * Combines the secured-controller's WRITE permission check (namespace
     * ADMIN via transitivity) with namespace-pinning: the persisted
     * [AgentConfig.namespaceId] is preserved, blocking cross-namespace
     * privilege escalation by a single-namespace ADMIN.
     */
    @PutMapping(
        "/{id}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: AgentConfigResource,
    ): AgentConfigResource {
        val existing = agentConfigService.findById(id)
            ?: throw ResourceNotFoundException("AgentConfig not found: $id")
        val userId = userService.getCurrentUser().id.toString()
        if (!permissionService.hasPermission(userId, ENTITY_TYPE, id.toString(), Action.WRITE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }
        return toResource(agentConfigService.update(toDomainForUpdate(resource, existing)))
    }

    companion object : KLogging() {
        private const val ENTITY_TYPE = "AgentConfig"
        private const val NAMESPACE_TYPE = "Namespace"
    }
}
