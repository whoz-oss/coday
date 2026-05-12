package io.whozoss.agentos.namespace

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

interface NamespaceNodeNeo4jRepository : Neo4jRepository<NamespaceNode, String> {
    @Query($$"MATCH (n:Namespace {id: $id}) SET n:ActiveNamespace")
    fun setActive(id: String)

    @Query($$"MATCH (n:Namespace {id: $id}) REMOVE n:ActiveNamespace")
    fun setInactive(id: String)

    @Query($$"UNWIND $ids AS id MATCH (n:Namespace {id: id}) REMOVE n:ActiveNamespace")
    fun setInactiveByIds(ids: List<String>)

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

    @Query(
        $$"""
            MATCH (n:Namespace)
            WHERE n.externalId IN $externalIds AND (n.removed IS NULL OR n.removed = false)
            RETURN n
            """,
    )
    fun findActiveByExternalIdIn(externalIds: Collection<String>): List<NamespaceNode>
}
