package io.whozoss.agentos.git

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [RepositoryCheckoutNode].
 */
interface RepositoryCheckoutNodeNeo4jRepository : Neo4jRepository<RepositoryCheckoutNode, String> {
    @Query($$"MATCH (c:RepositoryCheckout {id: $id}) SET c:ActiveRepositoryCheckout")
    fun setActive(id: String)

    @Query($$"MATCH (c:RepositoryCheckout {id: $id}) REMOVE c:ActiveRepositoryCheckout")
    fun setInactive(id: String)

    @Query($$"MATCH (c:ActiveRepositoryCheckout {namespaceId: $namespaceId}) REMOVE c:ActiveRepositoryCheckout")
    fun setInactiveByNamespaceId(namespaceId: String)

    /**
     * The active checkouts of a namespace: at most one, enforced by the
     * `repository_checkout_active_namespace_unique` constraint, whose index serves this seek.
     */
    @Query(
        $$"""
            MATCH (c:ActiveRepositoryCheckout {namespaceId: $namespaceId})
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
            MATCH (c:ActiveRepositoryCheckout)
            WHERE c.status IN $statuses
            RETURN c ORDER BY c.created ASC
            LIMIT $limit
            """,
    )
    fun findActiveByStatusIn(
        statuses: Collection<String>,
        limit: Int,
    ): List<RepositoryCheckoutNode>
}
