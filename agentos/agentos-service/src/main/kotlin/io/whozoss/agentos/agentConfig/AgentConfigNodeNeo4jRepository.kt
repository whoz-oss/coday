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
     * Returns the union of [AgentConfigNode]s accessible to [userId] in [namespaceId]:
     * - agents deployed on a [io.whozoss.agentos.userGroup.UserGroup] the user is a member of (within the namespace)
     * - agents deployed directly on the namespace, for a user holding MEMBER or ADMIN
     *
     * When [agentName] is non-null, the result is filtered to configs whose name
     * matches case-insensitively. When null, all accessible configs are returned.
     */
    @Query(
        $$"""
            MATCH (u:User {id: $userId})
              WHERE u.removed IS NULL OR u.removed = false
            MATCH (ns:Namespace {id: $namespaceId})
              WHERE ns.removed IS NULL OR ns.removed = false
            MATCH (u)-[:MEMBER]->(g:UserGroup)-[:BELONGS_TO]->(ns)
              WHERE g.removed IS NULL OR g.removed = false
            MATCH (a:AgentConfig)-[:DEPLOYED_TO]->(g)
              WHERE (a.removed IS NULL OR a.removed = false)
                AND (toLower(a.name) = toLower(COALESCE($agentName, a.name)))
            RETURN a
            UNION
            MATCH (u:User {id: $userId})
              WHERE u.removed IS NULL OR u.removed = false
            MATCH (ns:Namespace {id: $namespaceId})
              WHERE ns.removed IS NULL OR ns.removed = false
            MATCH (u)-[:MEMBER|ADMIN]->(ns)
            MATCH (a:AgentConfig)-[:DEPLOYED_TO]->(ns)
              WHERE (a.removed IS NULL OR a.removed = false)
                AND (toLower(a.name) = toLower(COALESCE($agentName, a.name)))
            RETURN a
            """,
    )
    fun findAvailableByNamespaceIdAndUserId(namespaceId: String, userId: String, agentName: String?): List<AgentConfigNode>
}
