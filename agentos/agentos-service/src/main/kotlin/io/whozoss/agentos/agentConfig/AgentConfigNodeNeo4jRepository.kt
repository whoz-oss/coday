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
            WHERE a.namespaceId = $namespaceId AND NOT COALESCE(a.removed, false)
            RETURN a ORDER BY a.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<AgentConfigNode>


    /**
     * Returns the union of [AgentConfigNode]s accessible to [userId] in [namespaceId]:
     * - (admin path) all active agents in the namespace when the user has isAdmin = true
     * - agents deployed on a [io.whozoss.agentos.userGroup.UserGroup] the user is a member of (within the namespace)
     * - agents deployed directly on the namespace, for a user holding MEMBER or ADMIN
     *
     * When [agentName] is non-null, the result is filtered to configs whose name
     * matches case-insensitively. When null, all accessible configs are returned.
     */
    @Query(
        $$"""
            MATCH (u:User {id: $userId})
            MATCH (ns:Namespace {id: $namespaceId})
            WHERE NOT COALESCE(u.removed, false)
              AND NOT COALESCE(ns.removed, false)
              AND u.isAdmin = true
            MATCH (a:AgentConfig)
              WHERE a.namespaceId = $namespaceId AND NOT COALESCE(a.removed, false)
                AND (toLower(a.name) = toLower(COALESCE($agentName, a.name)))
            RETURN DISTINCT a
            UNION
            MATCH (u:User {id: $userId})
              WHERE NOT COALESCE(u.removed, false)
            MATCH (ns:Namespace {id: $namespaceId})
              WHERE NOT COALESCE(ns.removed, false)
            MATCH (u)-[:MEMBER]->(g:UserGroup)-[:BELONGS_TO]->(ns)
              WHERE NOT COALESCE(g.removed, false)
            MATCH (a:AgentConfig)-[:DEPLOYED_TO]->(g)
              WHERE NOT COALESCE(a.removed, false)
                AND (toLower(a.name) = toLower(COALESCE($agentName, a.name)))
            RETURN DISTINCT a
            UNION
            MATCH (u:User {id: $userId})
              WHERE NOT COALESCE(u.removed, false)
            MATCH (ns:Namespace {id: $namespaceId})
              WHERE NOT COALESCE(ns.removed, false)
            MATCH (u)-[:MEMBER|ADMIN]->(ns)
            MATCH (a:AgentConfig)-[:DEPLOYED_TO]->(ns)
              WHERE NOT COALESCE(a.removed, false)
                AND (toLower(a.name) = toLower(COALESCE($agentName, a.name)))
            RETURN DISTINCT a
            """,
    )
    fun findAvailableByNamespaceIdAndUserId(namespaceId: String, userId: String, agentName: String?): List<AgentConfigNode>
}
