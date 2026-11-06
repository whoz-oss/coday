package io.whozoss.agentos.messaging

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [MessagingBotNode].
 */
interface MessagingBotNodeNeo4jRepository : Neo4jRepository<MessagingBotNode, String> {
    /** Found through the index of the `messaging_bot_bot_key_unique` constraint. */
    @Query($$"MATCH (b:MessagingBot {botKey: $botKey}) RETURN b LIMIT 1")
    fun findByBotKey(botKey: String): MessagingBotNode?

    /** Found through the index of the `messaging_bot_slot_key_unique` constraint. */
    @Query($$"MATCH (b:MessagingBot {slotKey: $slotKey}) RETURN b LIMIT 1")
    fun findBySlotKey(slotKey: String): MessagingBotNode?

    @Query($$"MATCH (b:MessagingBot) WHERE b.agentId = $agentId RETURN b ORDER BY b.created ASC")
    fun findByAgentId(agentId: String): List<MessagingBotNode>

    @Query($$"MATCH (b:MessagingBot) WHERE b.role = $role RETURN b ORDER BY b.created ASC")
    fun findByRole(role: String): List<MessagingBotNode>

    @Query($$"MATCH (b:MessagingBot) WHERE b.role = 'AGENT' AND b.namespaceId = $namespaceId RETURN b ORDER BY b.created ASC")
    fun findAgentBotsByNamespaceId(namespaceId: String): List<MessagingBotNode>
}
