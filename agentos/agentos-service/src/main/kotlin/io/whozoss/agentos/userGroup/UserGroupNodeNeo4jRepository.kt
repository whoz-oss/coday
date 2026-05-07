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

    @Query($$"MATCH (:UserGroup {id: $groupId})-[r:HAS_AGENT]->(:AgentConfig) DELETE r")
    fun removeAllAgents(groupId: String)

    @Query(
        $$"""
        UNWIND $agentIds AS agentId
        MATCH (g:UserGroup {id: $groupId})
        MATCH (a:AgentConfig {id: agentId})
        MERGE (g)-[:HAS_AGENT]->(a)
        """,
    )
    fun addAgents(
        groupId: String,
        agentIds: List<String>,
    )

    @Query(
        $$"""
        UNWIND $userExternalIds AS userExternalId
        MATCH (g:UserGroup {id: $groupId})
        MATCH (u:User {externalId: userExternalId})
          WHERE u.removed IS NULL OR u.removed = false
        MERGE (g)-[:HAS_USER]->(u)
        """,
    )
    fun addUsers(
        groupId: String,
        userExternalIds: List<String>,
    )

    @Query(
        $$"""
        UNWIND $userExternalIds AS userExternalId
        MATCH (g:UserGroup {id: $groupId})-[r:HAS_USER]->(u:User {externalId: userExternalId})
        DELETE r
        """,
    )
    fun removeUsers(
        groupId: String,
        userExternalIds: List<String>,
    )
}
