package io.whozoss.agentos.userGroup

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

interface UserGroupNodeNeo4jRepository : Neo4jRepository<UserGroupNode, String> {
    @Query(
        $$"""
            MATCH (g:UserGroup)
            WHERE g.namespaceId = $namespaceId AND (g.removed IS NULL OR g.removed = false)
            RETURN g ORDER BY g.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<UserGroupNode>
}
