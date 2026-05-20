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
     * - agents deployed on a [UserGroup] belonging to the target namespace, of which the user is a member
     *   (no direct relation from user to namespace required)
     * - agents deployed directly on the target namespace, for a user holding MEMBER or ADMIN on it
     *
     * Each branch is fully self-contained (UNION branches share no variable bindings).
     * The namespace scope is enforced in both branches via the namespace externalId.
     */
    @Query(
        $$"""
            MATCH (u:User {externalId: $userExternalId})
              WHERE u.removed IS NULL OR u.removed = false
            MATCH (ns:Namespace {externalId: $namespaceExternalId})
              WHERE ns.removed IS NULL OR ns.removed = false
            MATCH (u)-[:MEMBER]->(g:UserGroup)-[:BELONGS_TO]->(ns)
              WHERE g.removed IS NULL OR g.removed = false
            MATCH (a:AgentConfig)-[:DEPLOYED_TO]->(g)
              WHERE a.removed IS NULL OR a.removed = false
            RETURN a
            UNION
            MATCH (u:User {externalId: $userExternalId})
              WHERE u.removed IS NULL OR u.removed = false
            MATCH (ns:Namespace {externalId: $namespaceExternalId})
              WHERE ns.removed IS NULL OR ns.removed = false
            MATCH (u)-[:MEMBER|ADMIN]->(ns)
            MATCH (a:AgentConfig)-[:DEPLOYED_TO]->(ns)
              WHERE a.removed IS NULL OR a.removed = false
            RETURN a
            """,
    )
    fun findAvailableByUserExternalId(namespaceExternalId: String, userExternalId: String): List<AgentConfigNode>

    /**
     * Returns the first [AgentConfigNode] accessible to the user (matched by internal [userId])
     * in the namespace (matched by internal [namespaceId]) whose name matches [name]
     * case-insensitively.
     *
     * Applies the same graph rules as [findAvailableByUserExternalId] (UserGroup membership
     * and direct Namespace MEMBER/ADMIN relations) with the name filter pushed into Cypher
     * to avoid fetching the full agent list.
     *
     * Used by [io.whozoss.agentos.agent.AgentServiceImpl.resolveAgentName] during
     * conversation runtime where only internal UUIDs are available.
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
              WHERE a.removed IS NULL OR a.removed = false
              AND toLower(a.name) = toLower($name)
            RETURN a
            UNION
            MATCH (u:User {id: $userId})
              WHERE u.removed IS NULL OR u.removed = false
            MATCH (ns:Namespace {id: $namespaceId})
              WHERE ns.removed IS NULL OR ns.removed = false
            MATCH (u)-[:MEMBER|ADMIN]->(ns)
            MATCH (a:AgentConfig)-[:DEPLOYED_TO]->(ns)
              WHERE a.removed IS NULL OR a.removed = false
              AND toLower(a.name) = toLower($name)
            RETURN a
            """,
    )
    fun findAvailableByUserIdAndName(namespaceId: String, userId: String, name: String): List<AgentConfigNode>
}
