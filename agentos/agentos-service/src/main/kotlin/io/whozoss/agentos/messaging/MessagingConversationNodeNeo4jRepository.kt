package io.whozoss.agentos.messaging

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [MessagingConversationNode].
 */
interface MessagingConversationNodeNeo4jRepository : Neo4jRepository<MessagingConversationNode, String> {
    @Query($$"MATCH (c:MessagingConversation {id: $id}) SET c:ActiveMessagingConversation")
    fun setActive(id: String)

    @Query($$"MATCH (c:MessagingConversation {id: $id}) REMOVE c:ActiveMessagingConversation")
    fun setInactive(id: String)

    /** Found through the index of the `messaging_conversation_active_key_unique` constraint. */
    @Query($$"MATCH (c:ActiveMessagingConversation {conversationKey: $conversationKey}) RETURN c LIMIT 1")
    fun findActiveByConversationKey(conversationKey: String): MessagingConversationNode?

    /** Served by the `messaging_conversation_case_lookup` index. */
    @Query($$"MATCH (c:ActiveMessagingConversation) WHERE c.caseId = $caseId RETURN c ORDER BY c.created ASC")
    fun findActiveByCaseId(caseId: String): List<MessagingConversationNode>

    /** Behind [MessagingConversationRepository.findByParent]: a conversation's parent is its user. */
    @Query($$"MATCH (c:ActiveMessagingConversation) WHERE c.userId = $userId RETURN c ORDER BY c.created ASC")
    fun findActiveByUserId(userId: String): List<MessagingConversationNode>

    @Query($$"MATCH (c:ActiveMessagingConversation) WHERE c.userId = $userId REMOVE c:ActiveMessagingConversation")
    fun setInactiveByUserId(userId: String)
}
