package io.whozoss.agentos.integrationConfig

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
 * REST API for managing [IntegrationConfig] entities (Epic 4 Story 4.2).
 *
 * Extends [SecuredEntityController] — CREATE / UPDATE / DELETE are restricted
 * to namespace ADMINs (FR23/FR24/FR25). LIST / READ are open to every caller
 * with at least MEMBER access on the parent namespace (FR27) via the standard
 * transitive permission rules. The `[:BELONGS_TO]` edge between each
 * IntegrationConfig node and its parent Namespace node is already maintained
 * by [Neo4jIntegrationConfigRepository.save] (pre-existing).
 *
 * Standard CRUD endpoints (inherited, permission-gated):
 *   GET    /api/integration-configs/{id}
 *   POST   /api/integration-configs/by-ids
 *   GET    /api/integration-configs/by-parentId/{namespaceId}
 *   POST   /api/integration-configs
 *   PUT    /api/integration-configs/{id}
 *   DELETE /api/integration-configs/{id}
 */
@RestController
@RequestMapping(
    "/api/integration-configs",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class IntegrationConfigController(
    private val integrationConfigService: IntegrationConfigService,
    userService: UserService,
    permissionService: PermissionService,
) : SecuredEntityController<IntegrationConfig, UUID, IntegrationConfigResource>(
    integrationConfigService,
    userService,
    permissionService,
) {

    override fun getEntityType(): String = ENTITY_TYPE

    /**
     * IntegrationConfig creation/update/delete is restricted to namespace ADMINs
     * (FR23/FR24/FR25). `Action.WRITE` maps to `PermissionRelation.ADMIN` in
     * [io.whozoss.agentos.permissions.PermissionServiceImpl.evaluatePermission];
     * super-admins pass via the `isAdmin` bypass inside `hasPermission`.
     */
    override fun checkCreatePermission(userId: String, entity: IntegrationConfig) {
        if (!permissionService.hasPermission(userId, NAMESPACE_TYPE, entity.namespaceId.toString(), Action.WRITE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied - namespace ADMIN role required")
        }
    }

    /**
     * GET /api/integration-configs/by-parentId/{parentId} — list configs in a namespace.
     *
     * IntegrationConfig is a shared configuration (not owner-private): any caller
     * with READ on the parent namespace sees every config in that namespace
     * (FR27). Short-circuits the N+1 per-entity `hasPermission` cost of
     * [io.whozoss.agentos.entity.SecuredEntityController.listByParent] by
     * checking the namespace-level READ once.
     */
    override fun listByParent(@PathVariable parentId: UUID): List<IntegrationConfigResource> {
        val userId = userService.getCurrentUser().id.toString()
        val canRead = permissionService.hasPermission(userId, NAMESPACE_TYPE, parentId.toString(), Action.READ)
        if (!canRead) {
            logger.debug { "User $userId has no READ on namespace $parentId — returning empty IntegrationConfig list" }
            return emptyList()
        }
        return integrationConfigService.findByParent(parentId).map { toResource(it) }
    }

    // -------------------------------------------------------------------------
    // Mapping between domain entity and HTTP resource
    // -------------------------------------------------------------------------

    override fun toResource(entity: IntegrationConfig): IntegrationConfigResource =
        IntegrationConfigResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            name = entity.name,
            integrationType = entity.integrationType,
            description = entity.description,
            parameters = entity.parameters,
        )

    override fun toDomain(resource: IntegrationConfigResource): IntegrationConfig =
        IntegrationConfig(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            // namespaceId is guaranteed non-null by @NotNull on IntegrationConfigResource;
            // !! is safe here — @Valid prevents toDomain from being called with a null value.
            namespaceId = resource.namespaceId!!,
            name = resource.name,
            integrationType = resource.integrationType,
            description = resource.description,
            parameters = resource.parameters,
        )

    /**
     * Merge an update resource onto an existing persisted entity.
     *
     * [IntegrationConfig.namespaceId] is server-owned — the client cannot
     * relocate an IntegrationConfig across namespaces via PUT. Only mutable
     * fields are taken from the payload.
     */
    private fun toDomainForUpdate(
        resource: IntegrationConfigResource,
        existing: IntegrationConfig,
    ): IntegrationConfig =
        existing.copy(
            name = resource.name,
            integrationType = resource.integrationType,
            description = resource.description,
            parameters = resource.parameters,
        )

    /**
     * PUT /{id} — update mutable fields of an existing IntegrationConfig.
     *
     * Combines the secured-controller's WRITE permission check (namespace
     * ADMIN via transitivity) with namespace-pinning: the persisted
     * [IntegrationConfig.namespaceId] is preserved, blocking cross-namespace
     * privilege escalation by a single-namespace ADMIN.
     */
    @PutMapping(
        "/{id}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: IntegrationConfigResource,
    ): IntegrationConfigResource {
        val existing = integrationConfigService.findById(id)
            ?: throw ResourceNotFoundException("IntegrationConfig not found: $id")
        val userId = userService.getCurrentUser().id.toString()
        if (!permissionService.hasPermission(userId, ENTITY_TYPE, id.toString(), Action.WRITE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }
        return toResource(integrationConfigService.update(toDomainForUpdate(resource, existing)))
    }

    companion object : KLogging() {
        private const val ENTITY_TYPE = "IntegrationConfig"
        private const val NAMESPACE_TYPE = "Namespace"
    }
}
