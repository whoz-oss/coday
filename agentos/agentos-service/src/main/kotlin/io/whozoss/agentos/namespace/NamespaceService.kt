package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.EntityService

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
}
