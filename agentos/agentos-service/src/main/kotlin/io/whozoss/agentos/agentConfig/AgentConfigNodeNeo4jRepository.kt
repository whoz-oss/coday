package io.whozoss.agentos.agentConfig

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [AgentConfigNode].
 */
interface AgentConfigNodeNeo4jRepository : Neo4jRepository<AgentConfigNode, String> {
    /**
     * Find non-removed agent configs belonging to a namespace, ordered by name.
     *
     * When [withDisabled] is `true` (the default), all active configs are returned.
     * When [withDisabled] is `false`, only published (enabled) agents are returned.
     */
    @Query(
        $$"""
            MATCH (a:AgentConfig)
            WHERE a.namespaceId = $namespaceId
              AND NOT a.removed
              AND ($withDisabled OR a.enabled)
            RETURN a ORDER BY a.name ASC
            """,
    )
    fun findActiveByNamespaceId(
        namespaceId: String,
        withDisabled: Boolean = true,
    ): List<AgentConfigNode>

    /**
     * Returns the union of [AgentConfigNode]s accessible to [userId] in [namespaceId]:
     * - (admin path) all active agents in the namespace when the user has isAdmin = true
     * - agents deployed on a [io.whozoss.agentos.userGroup.UserGroup] the user is a member of (within the namespace)
     * - agents deployed directly on the namespace, for a user holding MEMBER or ADMIN
     *
     * Only enabled (published) agents are returned — disabled agents are never surfaced at runtime.
     *
     * When [agentName] is non-null, the result is filtered to configs whose name
     * matches case-insensitively. When null, all accessible configs are returned.
     */
    @Query(
        $$"""
            MATCH (u:User {id: $userId, removed: false})
            MATCH (ns:Namespace {id: $namespaceId, removed: false})
            MATCH (a:AgentConfig {removed: false)
            WHERE (a.namespaceId = $namespaceId OR a.namespaceId IS NULL)
              AND a.enabled
              AND NOT COALESCE(a.removed, false)
              AND ($agentName IS NULL OR toLower(a.name) = toLower($agentName))
              AND (
                u.isAdmin = true
                OR EXISTS { MATCH (u)-[:MEMBER]->(g:UserGroup)-[:BELONGS_TO]->(ns) MATCH (a)-[:DEPLOYED_TO]->(g) WHERE NOT COALESCE(g.removed, false) }
                OR EXISTS { MATCH (u)-[:MEMBER|ADMIN]->(ns) MATCH (a)-[:DEPLOYED_TO]->(ns) }
              )
            RETURN DISTINCT a ORDER BY a.name ASC
            """,
    )
    fun findAvailableByNamespaceIdAndUserId(
        namespaceId: String,
        userId: String,
        agentName: String?,
    ): List<AgentConfigNode>
}
