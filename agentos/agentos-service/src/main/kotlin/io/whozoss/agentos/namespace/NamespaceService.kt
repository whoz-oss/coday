package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.permissions.Action
import java.util.UUID

/**
 * Service for managing Namespace entities.
 *
 * Namespaces are root-level entities grouped under a fixed parent key ("all").
 * Exposes an additional [findAll] convenience method since listing all namespaces
 * is the primary use case (vs. listing by parent).
 */
interface NamespaceService : EntityService<Namespace, String> {
    /**
     * Retrieve all non-removed namespaces.
     */
    fun findAll(): List<Namespace>

    /**
     * Returns the IDs of namespaces on which [userId] has the relation required by
     * [action]. Thin typed wrapper over `PermissionService.listEntitiesForUser` that
     * encapsulates the `"Namespace"` entityType literal and the `String` → [UUID]
     * conversion. Malformed IDs (should not happen but defended) are dropped.
     *
     * Callers wanting "all namespaces" for super-admins should use [findAll]
     * instead — the permission system bypass logic stays at the controller layer
     * (cf. PermissionServiceImpl which delegates plainly to the repository).
     */
    fun findByExternalId(externalId: String): Namespace?

    fun findByExternalIds(externalIds: Collection<String>): List<Namespace>

    fun findIdsVisibleTo(userId: String, action: Action): List<UUID>
}
