package io.whozoss.agentos.user

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [UserNode].
 *
 * Users have no parent, so queries operate on all non-removed nodes.
 * [findActiveByExternalId] enables O(1) identity-based lookup via an indexed
 * property rather than a full node scan.
 */
interface UserNodeNeo4jRepository : Neo4jRepository<UserNode, String> {
    /**
     * Find all non-removed users.
     * Used by [Neo4jUserRepository.findByParent] which receives the fixed
     * parent key [UserRepository.USER_PARENT_KEY].
     */
    @Query($$"MATCH (u:User {id: $id}) SET u:ActiveUser")
    fun setActive(id: String)

    @Query($$"MATCH (u:User {id: $id}) REMOVE u:ActiveUser")
    fun setInactive(id: String)

    @Query($$"UNWIND $ids AS id MATCH (u:User {id: id}) REMOVE u:ActiveUser")
    fun setInactiveByIds(ids: List<String>)

    @Query(
        """
            MATCH (u:User)
            WHERE u.removed IS NULL OR u.removed = false
            RETURN u
            """,
    )
    fun findAllActive(): List<UserNode>

    /**
     * Find a non-removed user by external identity provider key.
     * Replaces the O(n) filesystem scan with a direct property lookup.
     */
    @Query(
        $$"""
            MATCH (u:User {externalId: $externalId, removed: false})
            RETURN u LIMIT 1
            """,
    )
    fun findActiveByExternalId(externalId: String): UserNode?

    @Query(
        $$"""
            MATCH (u:User {removed: false})
            WHERE u.externalId IN $externalIds
            RETURN u
            """,
    )
    fun findActiveByExternalIds(externalIds: Collection<String>): List<UserNode>

    /**
     * Count all non-removed users.
     * Used to determine if this is the first user in the system.
     */
    @Query(
        """
            MATCH (u:User {removed: false})
            RETURN COUNT(u)
            """,
    )
    fun countActive(): Long
}
