package io.whozoss.agentos.persistence.neo4j

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
     * parent key [io.whozoss.agentos.user.UserRepository.USER_PARENT_KEY].
     */
    @Query("MATCH (u:User) WHERE u.removed = false RETURN u")
    fun findAllActive(): List<UserNode>

    /**
     * Find a non-removed user by external identity provider key.
     * Replaces the O(n) filesystem scan with a direct property lookup.
     */
    @Query("MATCH (u:User) WHERE u.externalId = \$externalId AND u.removed = false RETURN u LIMIT 1")
    fun findActiveByExternalId(externalId: String): UserNode?
}
