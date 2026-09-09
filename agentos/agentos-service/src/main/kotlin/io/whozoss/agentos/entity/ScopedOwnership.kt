package io.whozoss.agentos.entity

import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.user.UserService
import org.springframework.security.access.AccessDeniedException
import java.util.UUID

/**
 * Delegate for overlay-entity controllers (AiProvider, IntegrationConfig) that follow
 * the three-scope ownership model (namespace-shared, user-global, user×namespace).
 *
 * Wraps [EntityCrudDelegate] and adds:
 * - [getByIds] with owner-visibility baked in: user-owned records are always returned
 *   to their owner regardless of the permission graph.
 * - [create] with scope validation via [validateAndResolveScope].
 *
 * All other operations ([getById], [listByParent], [delete]) are forwarded to the
 * inner delegate unchanged.
 *
 * @param entityLabel Human-readable name used in error messages (e.g. `"AiProvider"`).
 * @param ownerOf Extracts the `userId` field from a **domain** entity; used to grant
 *   owner-visibility in [getByIds].
 * @param userIdOf Extracts the `userId` field from the incoming **resource** DTO;
 *   used to validate ownership intent in [create].
 * @param namespaceIdOf Extracts the `namespaceId` field from the incoming **resource** DTO;
 *   used to validate namespace scope in [create].
 * @param buildEntity Constructs the domain entity from the resolved scope and the
 *   incoming DTO; called by [create] after scope validation succeeds.
 * @param crud The inner [EntityCrudDelegate] this class forwards to. Its [EntityCrudDelegate.create]
 *   is used to persist the entity built by [buildEntity]; supply a real [toDomain] there even
 *   though the controller never calls [EntityCrudDelegate.create] directly.
 */
class ScopedOwnershipCrudDelegate<ResourceType>(
    private val entityLabel: String,
    private val userService: UserService,
    private val namespaceService: NamespaceService,
    private val permissionService: PermissionService,
    private val ownerOf: (Entity) -> UUID?,
    private val userIdOf: (ResourceType) -> UUID?,
    private val namespaceIdOf: (ResourceType) -> UUID?,
    private val buildEntity: (resource: ResourceType, resolvedNs: UUID?, resolvedUser: UUID?) -> Entity,
    private val crud: EntityCrudDelegate<ResourceType>,
) {
    fun getById(id: UUID): ResourceType = crud.getById(id)

    fun getByIds(request: GetByIdsRequest): List<ResourceType> =
        crud.getByIds(request, extraVisibility = { entity, callerId -> ownerOf(entity) == callerId })

    fun listByParent(parentId: UUID): List<ResourceType> = crud.listByParent(parentId)

    /**
     * Validates the caller's scope intent, constructs the domain entity via [buildEntity],
     * and persists it through the inner [crud] delegate.
     */
    fun create(resource: ResourceType): ResourceType {
        val me = userService.getCurrentUser().id
        val (resolvedNs, resolvedUser) = validateAndResolveScope(
            entityLabel = entityLabel,
            callerId = me,
            requestedUserId = userIdOf(resource),
            requestedNamespaceId = namespaceIdOf(resource),
            namespaceService = namespaceService,
            permissionService = permissionService,
        )
        return crud.createEntity(buildEntity(resource, resolvedNs, resolvedUser))
    }

    fun delete(id: UUID) = crud.delete(id)
}

// ---------------------------------------------------------------------------

/**
 * Sentinel values used in scope query parameters across all overlay-entity endpoints
 * (AiProvider, IntegrationConfig).
 */
object ScopeParams {
    /** Passed as `namespaceId` to mean "no namespace" (user-global scope). */
    const val NONE = "none"

    /** Passed as `userId` to mean "the authenticated caller". */
    const val ME = "me"

    /**
     * Parses the raw `namespaceId` query parameter.
     *
     * Returns `null` for both absent and the [NONE] sentinel.
     * Throws [BadRequestException] on an unparseable UUID string.
     */
    fun parseNamespaceParam(raw: String?): UUID? =
        when {
            raw == null -> null
            raw.equals(NONE, ignoreCase = true) -> null
            else -> runCatching { UUID.fromString(raw) }
                .getOrElse { throw BadRequestException("Invalid namespaceId: '$raw'") }
        }

    /**
     * Validates the raw `userId` query parameter.
     *
     * Only `null` (absent) and the [ME] sentinel are accepted.
     * Throws [BadRequestException] for any other value.
     */
    fun validateUserParam(raw: String?) {
        if (raw != null && !raw.equals(ME, ignoreCase = true)) {
            throw BadRequestException("Invalid userId filter: '$raw' — only 'me' is supported")
        }
    }
}

// ---------------------------------------------------------------------------

/**
 * Resolved scope for a user-owned overlay entity (AiProvider, IntegrationConfig).
 *
 * @property namespaceId The namespace the entity belongs to, or `null` for user-global scope.
 * @property userId The owner's UUID, or `null` for namespace-shared scope.
 */
data class ResolvedScope(
    val namespaceId: UUID?,
    val userId: UUID?,
)

/**
 * Validates the caller's scope intent for creating an overlay entity and returns the
 * resolved [ResolvedScope].
 *
 * Rules (same for AiProvider and IntegrationConfig):
 * - [requestedUserId] must be `null` or equal to [callerId]; any other value is rejected.
 * - At least one of namespace or user scope must be present.
 * - Namespace-only scope requires WRITE on the namespace.
 * - Namespace + user scope requires only READ on the namespace (the user owns the record).
 * - The namespace must exist.
 *
 * @param entityLabel Human-readable entity name used in error messages (e.g. `"AiProvider"`).
 * @param callerId UUID of the authenticated caller.
 * @param requestedUserId The `userId` field from the incoming DTO body.
 * @param requestedNamespaceId The `namespaceId` field from the incoming DTO body.
 */
fun validateAndResolveScope(
    entityLabel: String,
    callerId: UUID,
    requestedUserId: UUID?,
    requestedNamespaceId: UUID?,
    namespaceService: NamespaceService,
    permissionService: PermissionService,
): ResolvedScope {
    if (requestedUserId != null && requestedUserId != callerId) {
        throw BadRequestException("userId in body must match authenticated user or be omitted")
    }
    val resolvedNs = requestedNamespaceId
    val resolvedUser = if (requestedUserId != null) callerId else null

    if (resolvedNs == null && resolvedUser == null) {
        throw BadRequestException("must provide namespaceId, userId, or both")
    }

    if (resolvedNs != null) {
        val requiredAction = if (resolvedUser != null) Action.READ else Action.WRITE
        if (!permissionService.hasPermission(
                callerId.toString(),
                EntityType.NAMESPACE,
                resolvedNs.toString(),
                requiredAction,
            )
        ) {
            throw AccessDeniedException(
                "Cannot create $entityLabel in namespace $resolvedNs (${requiredAction.name} required)",
            )
        }
        if (namespaceService.findById(resolvedNs) == null) {
            throw ResourceNotFoundException("Namespace not found: $resolvedNs")
        }
    }

    return ResolvedScope(namespaceId = resolvedNs, userId = resolvedUser)
}
