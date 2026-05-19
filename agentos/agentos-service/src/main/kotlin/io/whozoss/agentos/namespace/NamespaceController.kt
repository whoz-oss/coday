package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.*

/**
 * REST API for managing Namespaces.
 *
 * Authorization declared via `@PreAuthorize`:
 * - READ: namespace MEMBER (transitive)
 * - WRITE: namespace ADMIN
 * - CREATE / DELETE: SUPER_ADMIN only (system-level operations, FR1/FR2)
 * - `listAll`: any authenticated user — body branches on `User.isAdmin` for filtering
 *
 * `userService` and `permissionService` remain injected for non-authorization concerns:
 * auto-ADMIN grant on create, cascade revoke on delete, permission-filtered listing.
 */
@RestController
@RequestMapping(
    "/api/namespaces",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class NamespaceController(
    private val namespaceService: NamespaceService,
    userService: UserService,
    permissionService: PermissionService,
) : EntityController<Namespace, String, NamespaceResource>(namespaceService, userService, permissionService) {
    override val entityType = EntityType.NAMESPACE

    override fun toResource(entity: Namespace): NamespaceResource =
        NamespaceResource(
            id = entity.metadata.id,
            name = entity.name,
            description = entity.description,
            configPath = entity.configPath,
            externalId = entity.externalId,
            defaultAgentName = entity.defaultAgentName,
        )

    override fun toDomain(resource: NamespaceResource): Namespace =
        Namespace(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            name = resource.name,
            description = resource.description,
            configPath = resource.configPath?.takeIf { it.isNotBlank() },
            externalId = resource.externalId?.takeIf { it.isNotBlank() },
            defaultAgentName = resource.defaultAgentName?.takeIf { it.isNotBlank() },
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Namespace', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): NamespaceResource = super.getById(id)

    // POST /by-ids — inherited from EntityController.getByIds (story 5-4 factorisation).

    /**
     * POST /api/namespaces — SUPER_ADMIN only (FR1). Auto-grants ADMIN on the new
     * namespace to the creator (best-effort, non-transactional).
     */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    override fun create(
        @Valid @RequestBody resource: NamespaceResource,
    ): NamespaceResource {
        val created = super.create(resource)
        val namespaceId = created.id ?: error("Created namespace must have an id")
        val userId = userService.getCurrentUser().id.toString()
        runCatching {
            permissionService.grantPermission(
                userId,
                EntityType.NAMESPACE,
                namespaceId.toString(),
                PermissionRelation.ADMIN,
            )
            logger.info { "Super-admin $userId created namespace $namespaceId with auto-ADMIN grant" }
        }.onFailure { e ->
            logger.warn(e) {
                "Auto-ADMIN grant failed for namespace $namespaceId — super-admin bypass still applies"
            }
        }
        return created
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Namespace', 'WRITE')")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: NamespaceResource,
    ): NamespaceResource = super.update(id, resource)

    /**
     * DELETE /api/namespaces/{id} — SUPER_ADMIN only (FR2). Cascade-revokes all
     * ADMIN/MEMBER relationships pointing to the deleted namespace BEFORE the
     * delete to avoid orphan relations on a delete failure.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    override fun delete(
        @PathVariable id: UUID,
    ) {
        service.findById(id) ?: throw ResourceNotFoundException("Entity not found: $id")
        val cascadedCount = cascadeRevokeNamespacePermissions(id)
        if (!service.delete(id)) {
            throw ResourceNotFoundException("Entity not found: $id")
        }
        logger.info {
            "Super-admin ${userService.getCurrentUser().id} deleted namespace $id " +
                "($cascadedCount permission relations cascade-removed)"
        }
    }

    private fun cascadeRevokeNamespacePermissions(namespaceId: UUID): Int {
        val namespaceIdString = namespaceId.toString()
        val affectedUserIds =
            permissionService
                .listUsersWithPermission(EntityType.NAMESPACE, namespaceIdString, null)
                .distinct()
        var revoked = 0
        affectedUserIds.forEach { affectedUserId ->
            listOf(PermissionRelation.ADMIN, PermissionRelation.MEMBER).forEach { relation ->
                runCatching {
                    permissionService.revokePermission(
                        affectedUserId,
                        EntityType.NAMESPACE,
                        namespaceIdString,
                        relation,
                    )
                    revoked++
                }.onFailure { e ->
                    logger.warn(e) {
                        "Failed to revoke $relation on namespace $namespaceId for user $affectedUserId"
                    }
                }
            }
        }
        return revoked
    }

    /**
     * POST /api/namespaces/by-external-ids — look up namespaces by a list of external
     * identifiers (e.g. federation ids from an external system), filtered to those the
     * caller has at least READ on.
     *
     * Super-admins receive all matching namespaces. Regular users receive only the
     * subset they can read (intersection with [NamespaceService.findIdsVisibleTo]).
     * External ids that match no active namespace are silently omitted from the result.
     */
    @PostMapping("/by-external-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("isAuthenticated()")
    fun listByExternalIds(
        @RequestBody externalIds: List<String>,
    ): List<NamespaceResource> =
        externalIds
            .takeIf { it.isNotEmpty() }
            ?.let { ids ->
                val found = namespaceService.findByExternalIds(ids)
                val currentUser = userService.getCurrentUser()
                when {
                    currentUser.isAdmin -> {
                        found
                    }

                    else -> {
                        val readableIds = namespaceService.findIdsVisibleTo(currentUser.id.toString(), Action.READ).toSet()
                        found.filter { it.metadata.id in readableIds }
                    }
                }.map { toResource(it) }
            }
            ?: emptyList()

    /**
     * GET /api/namespaces — list namespaces filtered by the caller's permissions.
     *
     * - SUPER-ADMIN: returns every non-deleted namespace with `role = "SUPER-ADMIN"`.
     * - Regular user: returns only namespaces the caller has at least READ on,
     *   with `role = "ADMIN"` or `"MEMBER"` based on the highest direct permission.
     */
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("isAuthenticated()")
    fun listAll(): List<NamespaceListItem> {
        logger.info { "listing all namespaces (permission-filtered)" }
        val currentUser = userService.getCurrentUser()

        if (currentUser.isAdmin) {
            return namespaceService
                .findAll()
                .filter { it.metadata.removed != true }
                .map { toListItem(it, SUPER_ADMIN) }
        }

        val userId = currentUser.id.toString()
        val readableIds = namespaceService.findIdsVisibleTo(userId, Action.READ)
        if (readableIds.isEmpty()) return emptyList()

        val namespaces = namespaceService.findByIds(readableIds)
        val adminIdsSet = namespaceService.findIdsVisibleTo(userId, Action.WRITE).toSet()

        return namespaces.map { ns ->
            val role = if (ns.metadata.id in adminIdsSet) ADMIN else MEMBER
            toListItem(ns, role)
        }
    }

    /**
     * POST /api/namespaces/{namespaceId}/deploy-agents
     *
     * Deploys the given agents on the namespace by creating a DEPLOYED_TO relation.
     */
    @PostMapping("/{namespaceId}/deploy-agents", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun deployAgents(
        @PathVariable namespaceId: UUID,
        @RequestBody request: NamespaceAgentDeployRequest,
    ) {
        logger.info { "Deploying ${request.agentIds.size} agent(s) on namespace $namespaceId" }
        namespaceService.deployAgents(namespaceId, request.agentIds)
    }

    /**
     * POST /api/namespaces/{namespaceId}/undeploy-agents
     *
     * Removes the DEPLOYED_TO relation between the given agents and the namespace.
     */
    @PostMapping("/{namespaceId}/undeploy-agents", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun undeployAgents(
        @PathVariable namespaceId: UUID,
        @RequestBody request: NamespaceAgentDeployRequest,
    ) {
        logger.info { "Undeploying ${request.agentIds.size} agent(s) from namespace $namespaceId" }
        namespaceService.undeployAgents(namespaceId, request.agentIds)
    }

    private fun toListItem(
        entity: Namespace,
        role: String,
    ): NamespaceListItem =
        NamespaceListItem(
            id = entity.metadata.id,
            name = entity.name,
            description = entity.description,
            configPath = entity.configPath?.takeIf { it.isNotBlank() },
            externalId = entity.externalId,
            defaultAgentName = entity.defaultAgentName,
            role = role,
        )

    companion object : KLogging() {
        private const val SUPER_ADMIN = "SUPER-ADMIN"
        private const val ADMIN = "ADMIN"
        private const val MEMBER = "MEMBER"
    }
}
