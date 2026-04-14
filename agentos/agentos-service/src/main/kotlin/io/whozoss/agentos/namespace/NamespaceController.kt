package io.whozoss.agentos.namespace

import io.whozoss.agentos.auth.AuthorizationService
import io.whozoss.agentos.auth.RoleRepository
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for managing Namespaces.
 *
 * Extends [EntityController] with [NamespaceResource] as the HTTP DTO.
 *
 * Standard CRUD endpoints (inherited):
 *   GET    /api/namespaces/{id}
 *   POST   /api/namespaces/by-ids
 *   GET    /api/namespaces/by-parentId/{parentId}
 *   POST   /api/namespaces
 *   PUT    /api/namespaces/{id}
 *   DELETE /api/namespaces/{id}
 *
 * Additional endpoints:
 *   GET    /api/namespaces — list all namespaces (filtered by access)
 *
 * Every endpoint enforces authorization via [AuthorizationService].
 */
@RestController
@RequestMapping(
    "/api/namespaces",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class NamespaceController(
    private val namespaceService: NamespaceService,
    private val authorizationService: AuthorizationService,
    private val userService: UserService,
    private val roleRepository: RoleRepository,
) : EntityController<Namespace, String, NamespaceResource>(namespaceService) {

    // -------------------------------------------------------------------------
    // Mapping between domain entity and HTTP resource
    // -------------------------------------------------------------------------

    override fun toResource(entity: Namespace): NamespaceResource =
        NamespaceResource(
            id = entity.metadata.id,
            name = entity.name,
            description = entity.description,
        )

    override fun toDomain(resource: NamespaceResource): Namespace =
        Namespace(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            name = resource.name,
            description = resource.description,
        )

    // -------------------------------------------------------------------------
    // Overridden CRUD with authorization
    // -------------------------------------------------------------------------

    override fun getById(@PathVariable id: UUID): NamespaceResource {
        authorizationService.requireNamespaceAccess(currentUserId(), id.toString(), NamespaceRole.VIEWER)
        return super.getById(id)
    }

    override fun getByIds(@RequestBody ids: List<UUID>): List<NamespaceResource> {
        val accessibleIds = authorizationService.filterAccessibleNamespaceIds(currentUserId())
        val filteredIds = ids.filter { it.toString() in accessibleIds }
        return service.findByIds(filteredIds).map { toResource(it) }
    }

    /**
     * POST /api/namespaces — create a new namespace.
     *
     * No namespace-level check (the namespace doesn't exist yet).
     * After creation, the creator is auto-assigned OWNER (AC-D, FR34).
     */
    override fun create(@Valid @RequestBody resource: NamespaceResource): NamespaceResource {
        val userId = currentUserId()
        val created = service.create(toDomain(resource))
        val createdNsId = created.id.toString()

        roleRepository.assignNamespaceRole(userId, createdNsId, NamespaceRole.OWNER, userId)
        logger.info { "Auto-assigned OWNER to user $userId on namespace $createdNsId" }

        return toResource(created)
    }

    override fun update(@PathVariable id: UUID, @Valid @RequestBody resource: NamespaceResource): NamespaceResource {
        authorizationService.requireNamespaceAccess(currentUserId(), id.toString(), NamespaceRole.ADMIN)
        return super.update(id, resource)
    }

    override fun delete(@PathVariable id: UUID) {
        authorizationService.requireNamespaceAccess(currentUserId(), id.toString(), NamespaceRole.OWNER)
        super.delete(id)
    }

    // -------------------------------------------------------------------------
    // Additional endpoints
    // -------------------------------------------------------------------------

    /**
     * GET /api/namespaces — list all namespaces accessible to the current user.
     *
     * Filtered by [AuthorizationService.filterAccessibleNamespaceIds].
     */
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    fun listAll(): List<NamespaceResource> {
        val userId = currentUserId()
        val accessibleIds = authorizationService.filterAccessibleNamespaceIds(userId)
        logger.info { "listing namespaces for user $userId (${accessibleIds.size} accessible)" }
        return namespaceService.findAll()
            .filter { it.id.toString() in accessibleIds }
            .map { toResource(it) }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun currentUserId(): String = userService.getCurrentUser().id.toString()

    companion object : KLogging()
}
