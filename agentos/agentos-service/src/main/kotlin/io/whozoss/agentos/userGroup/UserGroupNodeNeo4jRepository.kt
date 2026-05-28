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

    @Query($$"MATCH (g:UserGroup {id: $id}) SET g:ActiveUserGroup")
    fun setActive(id: String)

    @Query($$"MATCH (g:UserGroup {id: $id}) REMOVE g:ActiveUserGroup")
    fun setInactive(id: String)

    @Query($$"MATCH (g:UserGroup) WHERE g.namespaceId = $namespaceId AND (g.removed IS NULL OR g.removed = false) REMOVE g:ActiveUserGroup")
    fun setInactiveByNamespaceId(namespaceId: String)

    @Query($$"MATCH (:AgentConfig)-[r:DEPLOYED_TO]->(:UserGroup {id: $groupId}) DELETE r")
    fun removeAllAgents(groupId: String)

    @Query(
        $$"""
        UNWIND $agentIds AS agentId
        MATCH (g:UserGroup {id: $groupId})
        MATCH (a:AgentConfig {id: agentId})
        MERGE (a)-[:DEPLOYED_TO]->(g)
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
        MERGE (u)-[:MEMBER]->(g)
        """,
    )
    fun addUsers(
        groupId: String,
        userExternalIds: List<String>,
    )

    @Query(
        $$"""
        UNWIND $userExternalIds AS userExternalId
        MATCH (u:User {externalId: userExternalId})-[r:MEMBER]->(g:UserGroup {id: $groupId})
        DELETE r
        """,
    )
    fun removeUsers(
        groupId: String,
        userExternalIds: List<String>,
    )

    /**
     * Revoke both [:ADMIN] and [:MEMBER] edges from [userId] to [groupId],
     * then MERGE the desired [role] edge. Called once per entry in the admin
     * membership update batch.
     *
     * The OPTIONAL MATCH + DELETE pattern is idempotent: if the edge does not
     * exist the DELETE is a no-op.
     */
    @Query(
        $$"""
        MATCH (u:User {id: $userId})
          WHERE u.removed IS NULL OR u.removed = false
        MATCH (g:UserGroup {id: $groupId})
          WHERE g.removed IS NULL OR g.removed = false
        OPTIONAL MATCH (u)-[rm:MEMBER]->(g) DELETE rm
        WITH u, g
        OPTIONAL MATCH (u)-[ra:ADMIN]->(g)  DELETE ra
        WITH u, g
        FOREACH (_ IN CASE $role WHEN 'ADMIN'   THEN [1] ELSE [] END | MERGE (u)-[:ADMIN]->(g))
        FOREACH (_ IN CASE $role WHEN 'MEMBER'  THEN [1] ELSE [] END | MERGE (u)-[:MEMBER]->(g))
        """,
    )
    fun upsertMembership(
        groupId: String,
        userId: String,
        role: String?,
    )
}
