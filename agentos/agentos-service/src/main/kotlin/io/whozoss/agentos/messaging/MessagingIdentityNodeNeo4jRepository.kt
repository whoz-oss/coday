package io.whozoss.agentos.messaging

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [MessagingIdentityNode].
 */
interface MessagingIdentityNodeNeo4jRepository : Neo4jRepository<MessagingIdentityNode, String> {
    @Query($$"MATCH (i:MessagingIdentity {id: $id}) SET i:ActiveMessagingIdentity")
    fun setActive(id: String)

    @Query($$"MATCH (i:MessagingIdentity {id: $id}) REMOVE i:ActiveMessagingIdentity")
    fun setInactive(id: String)

    /** Found through the index of the `messaging_identity_active_identity_key_unique` constraint. */
    @Query($$"MATCH (i:ActiveMessagingIdentity {identityKey: $identityKey}) RETURN i LIMIT 1")
    fun findActiveByIdentityKey(identityKey: String): MessagingIdentityNode?

    /** Found through the index of the `messaging_identity_active_user_key_unique` constraint. */
    @Query($$"MATCH (i:ActiveMessagingIdentity {userKey: $userKey}) RETURN i LIMIT 1")
    fun findActiveByUserKey(userKey: String): MessagingIdentityNode?

    /** Behind [MessagingIdentityRepository.findByParent]: an identity's parent is its user. */
    @Query($$"MATCH (i:ActiveMessagingIdentity) WHERE i.userId = $userId RETURN i ORDER BY i.created ASC")
    fun findActiveByUserId(userId: String): List<MessagingIdentityNode>

    /** The identities linked through one bot, removed with it. */
    @Query($$"MATCH (i:ActiveMessagingIdentity) WHERE i.platform = $platform AND i.accountId = $accountId RETURN i")
    fun findActiveByAccount(
        platform: String,
        accountId: String,
    ): List<MessagingIdentityNode>

    @Query($$"MATCH (i:ActiveMessagingIdentity) WHERE i.userId = $userId REMOVE i:ActiveMessagingIdentity")
    fun setInactiveByUserId(userId: String)
}
