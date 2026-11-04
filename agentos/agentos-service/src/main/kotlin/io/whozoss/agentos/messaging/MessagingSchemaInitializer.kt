package io.whozoss.agentos.messaging

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [MessagingBot], [MessagingIdentity] and [MessagingConversation].
 *
 * The constraints are what make attaching a bot, linking and the first message of a chat safe under
 * concurrency: two attachments of the same bot, two links of the same account, or two first messages of the
 * same chat would otherwise each create a row.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class MessagingSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        ensure(
            "messaging_bot_id_unique",
            "CREATE CONSTRAINT messaging_bot_id_unique IF NOT EXISTS FOR (b:MessagingBot) REQUIRE b.id IS UNIQUE",
        )
        // A bot serves one agent: its token cannot be attached twice.
        ensure(
            "messaging_bot_bot_key_unique",
            "CREATE CONSTRAINT messaging_bot_bot_key_unique IF NOT EXISTS FOR (b:MessagingBot) REQUIRE b.botKey IS UNIQUE",
        )
        // An agent has one bot per platform and namespace, an instance one bot manager per platform.
        ensure(
            "messaging_bot_slot_key_unique",
            "CREATE CONSTRAINT messaging_bot_slot_key_unique IF NOT EXISTS FOR (b:MessagingBot) REQUIRE b.slotKey IS UNIQUE",
        )
        // Serves MessagingBotNodeNeo4jRepository.findByAgentId, read by the agent's page.
        ensure(
            "messaging_bot_agent_lookup",
            "CREATE INDEX messaging_bot_agent_lookup IF NOT EXISTS FOR (b:MessagingBot) ON (b.agentId)",
        )
        ensure(
            "messaging_identity_id_unique",
            "CREATE CONSTRAINT messaging_identity_id_unique IF NOT EXISTS FOR (i:MessagingIdentity) REQUIRE i.id IS UNIQUE",
        )
        ensure(
            "messaging_identity_active_identity_key_unique",
            "CREATE CONSTRAINT messaging_identity_active_identity_key_unique IF NOT EXISTS " +
                "FOR (i:ActiveMessagingIdentity) REQUIRE i.identityKey IS UNIQUE",
        )
        ensure(
            "messaging_identity_active_user_key_unique",
            "CREATE CONSTRAINT messaging_identity_active_user_key_unique IF NOT EXISTS " +
                "FOR (i:ActiveMessagingIdentity) REQUIRE i.userKey IS UNIQUE",
        )
        // Serves MessagingIdentityNodeNeo4jRepository.findActiveByUserId, the list of the profile.
        ensure(
            "messaging_identity_user_lookup",
            "CREATE INDEX messaging_identity_user_lookup IF NOT EXISTS FOR (i:ActiveMessagingIdentity) ON (i.userId)",
        )
        ensure(
            "messaging_conversation_id_unique",
            "CREATE CONSTRAINT messaging_conversation_id_unique IF NOT EXISTS " +
                "FOR (c:MessagingConversation) REQUIRE c.id IS UNIQUE",
        )
        ensure(
            "messaging_conversation_active_key_unique",
            "CREATE CONSTRAINT messaging_conversation_active_key_unique IF NOT EXISTS " +
                "FOR (c:ActiveMessagingConversation) REQUIRE c.conversationKey IS UNIQUE",
        )
        // Serves MessagingConversationNodeNeo4jRepository.findActiveByCaseId, read for every agent reply to deliver.
        ensure(
            "messaging_conversation_case_lookup",
            "CREATE INDEX messaging_conversation_case_lookup IF NOT EXISTS " +
                "FOR (c:ActiveMessagingConversation) ON (c.caseId)",
        )
        ensure(
            "messaging_conversation_user_lookup",
            "CREATE INDEX messaging_conversation_user_lookup IF NOT EXISTS " +
                "FOR (c:ActiveMessagingConversation) ON (c.userId)",
        )
    }

    private fun ensure(
        name: String,
        statement: String,
    ) {
        neo4jClient.query(statement).run()
        logger.info { "[MessagingSchema] '$name' ensured" }
    }

    companion object : KLogging()
}
