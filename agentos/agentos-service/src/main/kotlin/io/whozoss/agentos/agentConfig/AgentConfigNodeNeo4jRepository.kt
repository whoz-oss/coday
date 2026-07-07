package io.whozoss.agentos.agentConfig

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [AgentConfigNode].
 */
interface AgentConfigNodeNeo4jRepository : Neo4jRepository<AgentConfigNode, String> {
    /**
     * Find non-removed agent configs belonging to a namespace, ordered by name.
     * Returns all active configs (enabled and disabled).
     *
     * Platform agents (namespaceId IS NULL) are intentionally excluded — use
     * [findPlatformAgents] to retrieve them.
     */
    @Query(
        $$"""
            MATCH (a:AgentConfig)
            WHERE a.namespaceId = $namespaceId
              AND NOT (COALESCE(a.removed, false))
              AND ($withDisabled OR a.enabled)
            RETURN a ORDER BY a.name ASC
            """,
    )
    fun findByNamespaceId(
        namespaceId: String,
        withDisabled: Boolean,
    ): List<AgentConfigNode>

    /**
     * Returns the union of [AgentConfigNode]s accessible to [userId] in [namespaceId]:
     * - (admin path) all active agents in the namespace when the user has isAdmin = true
     * - agents deployed on a [io.whozoss.agentos.userGroup.UserGroup] the user is a member of (within the namespace)
     * - agents deployed directly on the namespace, for a user holding MEMBER or ADMIN
     * - platform agents (namespaceId IS NULL) that have a DEPLOYED_TO relation to the namespace or
     *   to a UserGroup in that namespace the user has MEMBER or ADMIN on
     *
     * The DEPLOYED_TO check applies uniformly to both namespace-scoped and platform agents.
     * The only exception is the super-admin path, which bypasses DEPLOYED_TO for namespace-scoped
     * agents but not for platform agents (platform agents always require explicit deployment).
     *
     * Only enabled (published) agents are returned — disabled agents are never surfaced at runtime.
     *
     * When [agentName] is non-null, the result is filtered to configs whose name
     * starts with [agentName] (case-insensitive prefix match). When null, all accessible configs are returned.
     */
    @Query(
        $$"""
            OPTIONAL MATCH (u:User {id: $userId})
                WHERE NOT COALESCE(u.removed, false)
            OPTIONAL MATCH (ns:Namespace {id: $namespaceId})
                WHERE NOT COALESCE(ns.removed, false)
            MATCH (a:AgentConfig)
            WHERE (a.namespaceId IS NULL OR a.namespaceId = $namespaceId)
                AND ($withDisabled OR a.enabled)
                AND NOT COALESCE(a.removed, false)
                AND ($agentName IS NULL OR toLower(a.name) STARTS WITH toLower($agentName))
                AND (
                    // user is super-admin
                    u.isAdmin = true
                    
                    // agent is deployed through user group
                    OR EXISTS {
                        MATCH (u)-[:MEMBER|ADMIN]->(g:UserGroup)-[:BELONGS_TO]->(ns)
                        WHERE NOT COALESCE(g.removed, false)
                        MATCH (a)-[:DEPLOYED_TO]->(g)
                    }
                    
                    // agent is deployed through namespace
                    OR (
                        EXISTS { 
                            MATCH (a)-[:DEPLOYED_TO]->(ns)
                        }
                        AND ($userId IS NULL
                            OR EXISTS {
                                MATCH (u)-[:MEMBER|ADMIN]->(ns)
                            }
                        )
                    )
                )
            RETURN DISTINCT a ORDER BY a.name ASC
            """,
    )
    fun findDeployedByNamespaceIdAndUserId(
        namespaceId: String?,
        userId: String?,
        agentName: String?,
        withDisabled: Boolean = false,
    ): List<AgentConfigNode>

    /**
     * Find platform-level agent configs (namespaceId IS NULL), ordered by name.
     */
    @Query(
        $$"""
            MATCH (a:AgentConfig)
            WHERE a.namespaceId IS NULL
              AND NOT COALESCE(a.removed, false)
              AND ($withDisabled OR a.enabled)
            RETURN a ORDER BY a.name ASC
            """,
    )
    fun findPlatformAgents(withDisabled: Boolean = false): List<AgentConfigNode>
}
