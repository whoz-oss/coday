package io.whozoss.agentos.persistence.neo4j

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [NamespaceNode].
 *
 * Namespaces have no parent, so [findByParent] is not applicable here.
 * All queries exclude soft-deleted nodes via `WHERE n.removed = false`.
 */
interface NamespaceNodeNeo4jRepository : Neo4jRepository<NamespaceNode, String> {
    /**
     * Find all non-removed namespaces.
     * Used by [Neo4jNamespaceRepository.findByParent]
     * which passes a dummy parent (Unit — namespaces are root-level).
     */
    @Query("MATCH (n:Namespace) WHERE n.removed = false RETURN n")
    fun findAllActive(): List<NamespaceNode>
}
