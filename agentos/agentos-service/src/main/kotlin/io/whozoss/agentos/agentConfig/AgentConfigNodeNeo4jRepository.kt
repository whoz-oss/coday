package io.whozoss.agentos.agentConfig

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [AgentConfigNode].
 */
interface AgentConfigNodeNeo4jRepository : Neo4jRepository<AgentConfigNode, String> {
    /**
     * Find all non-removed agent configs belonging to a namespace, ordered by name.
     */
    @Query(
        $$"""
            MATCH (a:AgentConfig)
            WHERE a.namespaceId = $namespaceId AND (a.removed IS NULL OR a.removed = false)
            RETURN a ORDER BY a.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<AgentConfigNode>


    /**
     * Returns the union of:
     * - agents deployed on any [UserGroup] the user is a member of
     * - agents deployed on any [Namespace] the user has a MEMBER or ADMIN relation on
     */
    @Query(
        $$"""
            MATCH (u:User {externalId: $userExternalId})
            WHERE u.removed IS NULL OR u.removed = false
            
            MATCH (u)-[:MEMBER]->(g:UserGroup)<-[:DEPLOYED_TO]-(a:AgentConfig)
              WHERE (g.removed IS NULL OR g.removed = false)
                AND (a.removed IS NULL OR a.removed = false)
            RETURN DISTINCT a
            UNION
            MATCH (u)-[:MEMBER|ADMIN]->(ns:Namespace)<-[:DEPLOYED_TO]-(a:AgentConfig)
              WHERE (ns.removed IS NULL OR ns.removed = false)
                AND (a.removed IS NULL OR a.removed = false)
            RETURN DISTINCT a
            """,
    )
    fun findAvailableByUserExternalId(userExternalId: String): List<AgentConfigNode>
}
