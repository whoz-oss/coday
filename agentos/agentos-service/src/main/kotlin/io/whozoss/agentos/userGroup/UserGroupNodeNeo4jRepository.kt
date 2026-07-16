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
        MATCH (u:User {externalId: userExternalId})-[r:MEMBER|ADMIN]->(g:UserGroup {id: $groupId})
        DELETE r
        """,
    )
    fun removeUsers(
        groupId: String,
        userExternalIds: List<String>,
    )

    /**
     * Promotes the given members to ADMIN: replaces their `[:MEMBER]` edge with `[:ADMIN]`.
     * Members already ADMIN are left untouched; ids that are not members are ignored.
     */
    @Query(
        $$"""
        UNWIND $adminExternalIds AS userExternalId
        MATCH (u:User {externalId: userExternalId})-[m:MEMBER]->(g:UserGroup {id: $groupId})
        MERGE (u)-[:ADMIN]->(g)
        DELETE m
        """,
    )
    fun promoteAdmins(
        groupId: String,
        adminExternalIds: List<String>,
    )

    /**
     * Demotes any current ADMIN not in [adminExternalIds] back to MEMBER (replaces `[:ADMIN]` with
     * `[:MEMBER]`). With an empty list this demotes every ADMIN of the group.
     */
    @Query(
        $$"""
        MATCH (u:User)-[a:ADMIN]->(g:UserGroup {id: $groupId})
        WHERE NOT u.externalId IN $adminExternalIds
        MERGE (u)-[:MEMBER]->(g)
        DELETE a
        """,
    )
    fun demoteNonAdmins(
        groupId: String,
        adminExternalIds: List<String>,
    )
}
