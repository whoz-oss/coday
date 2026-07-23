package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [IntegrationConfig] persistence.
 *
 * Because [IntegrationConfig] can be scoped to a namespace, a user, or both, there is no
 * single "parent" key. [findByParent] from [EntityRepository] is implemented as
 * [findByNamespaceId] by convention (namespace is the primary scope inherited from Epic 4).
 *
 * Storage layout (Neo4j): `(:IntegrationConfig)-[:BELONGS_TO]->(:Namespace)` for namespace-scoped
 * configs. User-only configs (`namespaceId == null && userId != null`) do not currently
 * materialise a relationship — that is handled by story 6.2 when the user CRUD is added.
 */
interface IntegrationConfigRepository : EntityRepository<IntegrationConfig, UUID> {
    /**
     * Find all non-removed configs scoped to the given namespace,
     * regardless of [IntegrationConfig.userId].
     */
    fun findByNamespaceId(namespaceId: UUID): List<IntegrationConfig>

    /**
     * Find all non-removed configs scoped to the given user,
     * regardless of [IntegrationConfig.namespaceId].
     */
    fun findByUserId(userId: UUID): List<IntegrationConfig>

    /**
     * Find all non-removed platform-level configs (`namespaceId IS NULL AND userId IS NULL`).
     */
    fun findPlatform(): List<IntegrationConfig>

    /**
     * Find a single config matching the (namespaceId, userId, name) triple.
     * NULL values are matched literally — `findByTriple(ns, null, name)` returns
     * configs where `userId IS NULL`, never user-scoped configs that happen to share `ns`.
     *
     * Returns null when no row matches. When the triple is degenerate (both ids null)
     * the result is also null — callers are expected to enforce the invariant.
     */
    fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): IntegrationConfig?

    fun findAllForNamespaceIdAndUserId(
        namespaceId: UUID?,
        userId: UUID?,
    ): List<IntegrationConfig>
}
