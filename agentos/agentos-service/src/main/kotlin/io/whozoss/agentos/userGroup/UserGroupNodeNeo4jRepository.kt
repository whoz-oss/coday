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

    @Query(
        $$"""
            MATCH (g:UserGroup)-[:BELONGS_TO]->(ns:Namespace)
            WHERE ns.externalId = $externalId
              AND (g.removed IS NULL OR g.removed = false)
              AND (ns.removed IS NULL OR ns.removed = false)
            RETURN g AS userGroup, ns.externalId AS namespaceExternalId
            ORDER BY g.name ASC
            """,
    )
    fun findByNamespaceExternalId(externalId: String): List<UserGroupNamespaceProjection>
}
