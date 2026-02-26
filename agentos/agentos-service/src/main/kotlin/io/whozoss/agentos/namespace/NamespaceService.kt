package io.whozoss.agentos.namespace

import io.whozoss.agentos.sdk.entity.EntityService

/**
 * Service for managing Namespace entities.
 *
 * Namespaces are root-level entities (no parent), so the parent type is Unit.
 * Exposes an additional [findAll] convenience method since listing all namespaces
 * is the primary use case (vs. listing by parent).
 */
interface NamespaceService : EntityService<Namespace, Unit> {
    /**
     * Retrieve all non-removed namespaces.
     */
    fun findAll(): List<Namespace>
}
