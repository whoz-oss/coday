package io.whozoss.agentos.permissions

import io.whozoss.agentos.sdk.entity.ScopedEntity
import mu.KLogging
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Single definition of the overlay-entity access predicate.
 *
 * The rule is: *a membership or permission edge in the permission graph grants access, or the
 * caller owns the row*. This predicate is currently restated independently in every service that
 * manages an overlay entity (e.g. `IntegrationConfigServiceImpl`, `AiProviderServiceImpl`).
 * Consolidating it here means a change to the rule is a change in one file.
 *
 * All methods accept an explicit [Caller] rather than reading from a thread-local security
 * context. See [Caller] for the rationale.
 */
@Component
class ScopedAccessPolicy(private val permissionService: PermissionService) {

    companion object : KLogging()

    /**
     * Returns `true` when [caller] may perform [action] on [entity].
     *
     * Short-circuits to `true` for Super Admins without querying the permission graph.
     * For regular callers, access is granted when the permission graph contains a suitable edge
     * **or** the caller owns the row ([ScopedEntity.userId] equals [caller.userId]).
     */
    fun canAccess(caller: Caller, entityType: EntityType, entity: ScopedEntity, action: Action): Boolean {
        if (caller.isAdmin) return true
        return permissionService.hasPermission(
            caller.userId.toString(),
            entityType,
            entity.id.toString(),
            action,
        ) || (entity.userId != null && entity.userId == caller.userId)
    }

    /**
     * Asserts that [caller] may perform [action] on [entity], throwing [AccessDeniedException]
     * otherwise.
     */
    fun requireAccess(caller: Caller, entityType: EntityType, entity: ScopedEntity, action: Action) {
        if (!canAccess(caller, entityType, entity, action)) {
            throw AccessDeniedException("Access denied on $entityType ${entity.id}")
        }
    }

    /**
     * Throws [AccessDeniedException] when [entity] is platform-scoped
     * ([ScopedEntity.namespaceId] and [ScopedEntity.userId] are both `null`) and [caller] is not
     * a Super Admin. Scoped entities (namespace-shared, user-global, user×namespace) are allowed
     * through unconditionally.
     */
    fun requireAdminForPlatform(caller: Caller, entityLabel: String, entity: ScopedEntity) {
        if (entity.namespaceId != null || entity.userId != null) return
        if (!caller.isAdmin) throw AccessDeniedException("Platform-level $entityLabel requires Super Admin")
    }

    /**
     * Returns `true` when [caller] may read the namespace identified by [namespaceId].
     *
     * Short-circuits to `true` for Super Admins.
     */
    fun canReadNamespace(caller: Caller, namespaceId: UUID): Boolean =
        caller.isAdmin || permissionService.hasPermission(
            caller.userId.toString(),
            EntityType.NAMESPACE,
            namespaceId.toString(),
            Action.READ,
        )

    /**
     * Asserts that [caller] may perform [action] on namespace [namespaceId], throwing
     * [AccessDeniedException] otherwise.
     *
     * The exception message is stable and asserted by existing specs:
     * `"Cannot create $entityLabel in namespace $namespaceId (${action.name} required)"`.
     */
    fun requireNamespacePermission(caller: Caller, namespaceId: UUID, action: Action, entityLabel: String) {
        if (!permissionService.hasPermission(
                caller.userId.toString(),
                EntityType.NAMESPACE,
                namespaceId.toString(),
                action,
            )
        ) {
            throw AccessDeniedException(
                "Cannot create $entityLabel in namespace $namespaceId (${action.name} required)",
            )
        }
    }

    /**
     * Returns the set of ids from [entities] that [caller] may perform [action] on.
     *
     * - Empty [entities] → `emptySet()` without calling [PermissionService].
     * - Super Admin → all ids, without calling [PermissionService.filterVisibleIds].
     * - Otherwise, delegates to [PermissionService.filterVisibleIds] for the permission-graph
     *   membership check, then unions the result with the ids of entities the caller owns
     *   ([ScopedEntity.userId] equals [caller.userId]).
     *
     * Non-UUID strings returned by [PermissionService.filterVisibleIds] are silently dropped with
     * a WARN log, matching the wording used in `IntegrationConfigServiceImpl.findByIdsForCaller`.
     */
    fun filterVisible(
        caller: Caller,
        entityType: EntityType,
        entities: List<ScopedEntity>,
        action: Action = Action.READ,
    ): Set<UUID> {
        if (entities.isEmpty()) return emptySet()
        if (caller.isAdmin) return entities.map { it.id }.toSet()

        val rawVisible = permissionService.filterVisibleIds(
            userId = caller.userId.toString(),
            entityType = entityType,
            ids = entities.map { it.id.toString() },
            action = action,
        )
        val parsed = rawVisible.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        if (parsed.size != rawVisible.size) {
            logger.warn {
                "[$entityType] PermissionService returned ${rawVisible.size - parsed.size} non-UUID id(s); dropping. " +
                    "This indicates data corruption or schema drift — investigate."
            }
        }
        val permittedIds = parsed.toSet()
        val ownedIds = entities.filter { it.userId == caller.userId }.map { it.id }.toSet()
        return permittedIds + ownedIds
    }
}
