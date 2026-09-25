package io.whozoss.agentos.git

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [RepositoryCheckoutNode].
 */
interface RepositoryCheckoutNodeNeo4jRepository : Neo4jRepository<RepositoryCheckoutNode, String> {
    /**
     * The one active checkout of a namespace, found by an exact seek on the index provisioned by
     * the `repository_checkout_namespace_unique` constraint.
     *
     * Matching [RepositoryCheckoutNode.activeNamespaceKey] implies the row is active, so no
     * `removed` predicate is needed: a soft-deleted row has no key.
     */
    @Query(
        $$"""
            MATCH (c:RepositoryCheckout {activeNamespaceKey: $namespaceId})
            RETURN c
            LIMIT 1
            """,
    )
    fun findActiveByNamespaceKey(namespaceId: String): RepositoryCheckoutNode?

    /** Every active checkout of a namespace. Normally zero or one; used by the parent listing. */
    @Query(
        $$"""
            MATCH (c:RepositoryCheckout)
            WHERE c.namespaceId = $namespaceId AND (c.removed IS NULL OR c.removed = false)
            RETURN c ORDER BY c.created ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<RepositoryCheckoutNode>

    /**
     * Active checkouts in one of [statuses], oldest first.
     *
     * Drives the checkout sweep: associating a repository records the intent and returns, because
     * cloning must not happen in a request thread. Ordering by creation keeps a queue fair.
     */
    @Query(
        $$"""
            MATCH (c:RepositoryCheckout)
            WHERE c.status IN $statuses AND (c.removed IS NULL OR c.removed = false)
            RETURN c ORDER BY c.created ASC
            LIMIT $limit
            """,
    )
    fun findActiveByStatusIn(
        statuses: Collection<String>,
        limit: Int,
    ): List<RepositoryCheckoutNode>
}
