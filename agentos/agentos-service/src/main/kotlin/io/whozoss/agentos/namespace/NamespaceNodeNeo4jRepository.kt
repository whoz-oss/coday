package io.whozoss.agentos.namespace

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

interface NamespaceNodeNeo4jRepository : Neo4jRepository<NamespaceNode, String> {

    @Query(
        """
            MATCH (n:Namespace)
            WHERE n.removed IS NULL OR n.removed = false
            RETURN n
            """,
    )
    fun findAllActive(): List<NamespaceNode>

    @Query(
        $$"""
            MATCH (n:Namespace)
            WHERE n.externalId = $externalId AND (n.removed IS NULL OR n.removed = false)
            RETURN n LIMIT 1
            """,
    )
    fun findActiveByExternalId(externalId: String): NamespaceNode?
}
