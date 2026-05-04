package io.whozoss.agentos.usergroup

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

interface UserGroupNodeNeo4jRepository : Neo4jRepository<UserGroupNode, String> {
    @Query(
        $$"""
            MATCH (ug:UserGroup)
            WHERE ug.namespaceId = $namespaceId AND (ug.removed IS NULL OR ug.removed = false)
            RETURN ug ORDER BY ug.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<UserGroupNode>

    @Query(
        $$"""
            MATCH (ug:UserGroup {id: $userGroupId})-[:HAS_AGENT]->(a:AgentConfig)
            WHERE a.removed IS NULL OR a.removed = false
            RETURN a.id
            """,
    )
    fun findAgentIdsByUserGroupId(userGroupId: String): List<String>

    @Query(
        $$"""
            MATCH (ug:UserGroup {id: $userGroupId})
            OPTIONAL MATCH (ug)-[r:HAS_USER]->()
            OPTIONAL MATCH (ug)-[r2:HAS_AGENT]->()
            DELETE r, r2
            SET ug.removed = true
            """,
    )
    fun softDeleteWithRelationships(userGroupId: String)

    @Query(
        $$"""
            MATCH (ug:UserGroup {id: $userGroupId}), (u:User {id: $userId})
            MERGE (ug)-[:HAS_USER]->(u)
            """,
    )
    fun addUser(userGroupId: String, userId: String)

    @Query(
        $$"""
            MATCH (ug:UserGroup {id: $userGroupId})-[r:HAS_USER]->(u:User {id: $userId})
            DELETE r
            """,
    )
    fun removeUser(userGroupId: String, userId: String)

    @Query(
        $$"""
            MATCH (ug:UserGroup {id: $userGroupId})-[r:HAS_AGENT]->()
            DELETE r
            """,
    )
    fun removeAllAgents(userGroupId: String)

    @Query(
        $$"""
            MATCH (ug:UserGroup {id: $userGroupId}), (a:AgentConfig {id: $agentId})
            MERGE (ug)-[:HAS_AGENT]->(a)
            """,
    )
    fun addAgent(userGroupId: String, agentId: String)

    @Query(
        $$"""
            MATCH (ug:UserGroup {id: $userGroupId})-[:HAS_USER]->(u:User)
            WHERE u.removed IS NULL OR u.removed = false
            RETURN count(u)
            """,
    )
    fun countActiveUsers(userGroupId: String): Int

    @Query(
        $$"""
            MATCH (ug:UserGroup {id: $userGroupId})-[:HAS_AGENT]->(a:AgentConfig)
            WHERE a.removed IS NULL OR a.removed = false
            RETURN count(a)
            """,
    )
    fun countActiveAgents(userGroupId: String): Int
}
