package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.SecuredEntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
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
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * REST API for managing Namespaces.
 *
 * Extends [SecuredEntityController] so that read/list/update flows are guarded by
 * permission checks (404 for unauthorized READ, 403 for unauthorized WRITE on update).
 *
 * Namespace-specific rules (Story 2.1):
 * - CREATE is restricted to SUPER-ADMIN users (isAdmin = true). The creator is
 *   automatically granted an ADMIN relationship on the new namespace.
 * - DELETE is restricted to SUPER-ADMIN users. All permission relationships
 *   (ADMIN, MEMBER) pointing to the deleted namespace are cascade-revoked.
 * - UPDATE is allowed for namespace ADMINs (via the inherited WRITE permission check).
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
 *   GET    /api/namespaces — list all namespaces (filtering is scope of Story 2.4)
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
) : SecuredEntityController<Namespace, String, NamespaceResource>(
    namespaceService,
    userService,
    permissionService,
) {

    override fun getEntityType(): String = ENTITY_TYPE

    // -------------------------------------------------------------------------
    // Mapping between domain entity and HTTP resource
    // -------------------------------------------------------------------------

    override fun toResource(entity: Namespace): NamespaceResource =
        NamespaceResource(
            id = entity.metadata.id,
            name = entity.name,
            description = entity.description,
            configPath = entity.configPath,
        )

    override fun toDomain(resource: NamespaceResource): Namespace =
        Namespace(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            name = resource.name,
            description = resource.description,
            // Normalize blank strings to null so the backend never stores an empty configPath.
            configPath = resource.configPath?.takeIf { it.isNotBlank() },
        )

    // -------------------------------------------------------------------------
    // Security overrides (Story 2.1)
    // -------------------------------------------------------------------------

    /**
     * Only SUPER-ADMIN users may create namespaces (FR1).
     * A namespace ADMIN is not enough — this is a system-level operation.
     *
     * Uses the [userId] passed by the parent [SecuredEntityController.create]
     * rather than refetching the current user, keeping the authorization decision
     * consistent with the caller identity resolved upstream.
     */
    override fun checkCreatePermission(userId: String, entity: Namespace) {
        val user = userService.findById(UUID.fromString(userId))
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, SUPER_ADMIN_REQUIRED)
        if (!user.isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, SUPER_ADMIN_REQUIRED)
        }
    }

    /**
     * POST /api/namespaces — delegates creation to the parent (which invokes
     * [checkCreatePermission]), then auto-grants an ADMIN relationship to the
     * creator. If the grant fails, we log a warning but do not roll back the
     * creation — the user keeps the super-admin bypass anyway.
     */
    override fun create(@Valid @RequestBody resource: NamespaceResource): NamespaceResource {
        val created = super.create(resource)
        val namespaceId = created.id
            ?: error("Created namespace must have an id")

        val userId = userService.getCurrentUser().id.toString()
        runCatching {
            permissionService.grantPermission(
                userId,
                ENTITY_TYPE,
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

    /**
     * DELETE /api/namespaces/{id} — SUPER-ADMIN only (FR2). Not overridable by a
     * namespace ADMIN: deleting a namespace is a system-level operation.
     *
     * Order of checks:
     *   1. Existence check (`findById`) — 404 if the entity does not exist.
     *   2. READ permission check — 404 on failure to hide the namespace's existence
     *      from callers without any access (aligns with the "always 404 if no READ"
     *      rule in the architecture doc).
     *   3. Super-admin gate — 403 for callers who have READ but are not super-admin.
     *
     * On success, cascade BEFORE the delete so that a failure to list/revoke
     * permissions does not leave the system with orphan relationships pointing at a
     * deleted namespace. Individual revoke failures are still best-effort (logged,
     * not propagated) — only the bulk listing failure surfaces as a 500.
     */
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(@PathVariable id: UUID) {
        service.findById(id) ?: throw ResourceNotFoundException("Entity not found: $id")

        val currentUser = userService.getCurrentUser()
        val currentUserId = currentUser.id.toString()
        if (!permissionService.hasPermission(currentUserId, ENTITY_TYPE, id.toString(), Action.READ)) {
            // Hide the namespace's existence from users without READ access.
            throw ResourceNotFoundException("Entity not found: $id")
        }
        if (!currentUser.isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, SUPER_ADMIN_REQUIRED)
        }

        val cascadedCount = cascadeRevokeNamespacePermissions(id)

        if (!service.delete(id)) {
            throw ResourceNotFoundException("Entity not found: $id")
        }

        logger.info {
            "Super-admin ${currentUser.id} deleted namespace $id " +
                "($cascadedCount permission relations cascade-removed)"
        }
    }

    private fun cascadeRevokeNamespacePermissions(namespaceId: UUID): Int {
        val namespaceIdString = namespaceId.toString()
        // Let listing failures surface as 500: if we cannot enumerate the users with
        // permissions, we must not proceed with deletion (would leave orphan relations).
        val affectedUserIds = permissionService
            .listUsersWithPermission(ENTITY_TYPE, namespaceIdString, null)
            .distinct()

        var revoked = 0
        affectedUserIds.forEach { affectedUserId ->
            listOf(PermissionRelation.ADMIN, PermissionRelation.MEMBER).forEach { relation ->
                runCatching {
                    permissionService.revokePermission(
                        affectedUserId,
                        ENTITY_TYPE,
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

    // -------------------------------------------------------------------------
    // Additional endpoints
    // -------------------------------------------------------------------------

    /**
     * GET /api/namespaces — list namespaces filtered by the caller's permissions (Story 2.4).
     *
     * - SUPER-ADMIN: returns every namespace with `role = "SUPER-ADMIN"`.
     * - Regular user: returns only the namespaces the caller has at least READ on,
     *   with `role = "ADMIN"` or `"MEMBER"` depending on the highest direct permission.
     * - User without any permission: returns an empty list.
     *
     * Performance: uses `listEntitiesForUser` (one Neo4j query) to avoid an N+1
     * `hasPermission` call per namespace. Per-namespace ADMIN detection is done
     * with a set lookup against the WRITE entity list.
     */
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    fun listAll(): List<NamespaceListItem> {
        logger.info { "listing all namespaces (permission-filtered)" }
        val currentUser = userService.getCurrentUser()

        if (currentUser.isAdmin) {
            // Defensive soft-delete filter: the repository is expected to exclude
            // removed entries, but we guard here explicitly to keep AC2 ("all namespaces
            // except the soft-deleted ones") robust to any future repo change.
            return namespaceService.findAll()
                .filter { it.metadata.removed != true }
                .map { toListItem(it, SUPER_ADMIN) }
        }

        val userId = currentUser.id.toString()
        val readableIds = permissionService.listEntitiesForUser(userId, ENTITY_TYPE, Action.READ)
        if (readableIds.isEmpty()) return emptyList()

        // Defensive: filter out any malformed UUID strings (should never happen
        // in practice since IDs come from Neo4j, but UUID.fromString throws).
        // Log each malformed id as WARN so data corruption surfaces in ops instead
        // of being silently swallowed.
        val uuids = readableIds.mapNotNull { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()
                ?: run {
                    logger.warn { "Dropping malformed namespace id from permission listing: '$raw'" }
                    null
                }
        }
        // findByIds already excludes soft-deleted namespaces (Neo4jNamespaceRepository filter).
        val namespaces = namespaceService.findByIds(uuids)

        val adminIdsSet = permissionService
            .listEntitiesForUser(userId, ENTITY_TYPE, Action.WRITE)
            .toSet()

        return namespaces.map { ns ->
            val role = if (ns.metadata.id.toString() in adminIdsSet) ADMIN else MEMBER
            toListItem(ns, role)
        }
    }

    private fun toListItem(entity: Namespace, role: String): NamespaceListItem =
        NamespaceListItem(
            id = entity.metadata.id,
            name = entity.name,
            description = entity.description,
            // Match toResource() semantics: never leak an empty string through the wire —
            // callers rely on null to mean "unset".
            configPath = entity.configPath?.takeIf { it.isNotBlank() },
            role = role,
        )

    companion object : KLogging() {
        private const val ENTITY_TYPE = "Namespace"
        private const val SUPER_ADMIN_REQUIRED = "SUPER-ADMIN role required"
        private const val SUPER_ADMIN = "SUPER-ADMIN"
        private const val ADMIN = "ADMIN"
        private const val MEMBER = "MEMBER"
    }
}
