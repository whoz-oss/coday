package io.whozoss.agentos.scheduledPrompt

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [ScheduledPrompt].
 *
 * Creates:
 * - A UNIQUE constraint on `tripleKey` to enforce name uniqueness per
 *   `(namespaceId, userId, name)` scope. On soft-delete the key is rewritten
 *   to `tombstone:<id>` to free the unique slot immediately.
 * - Auxiliary indexes on `namespaceId`, `userId`, and `agentConfigId`.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class ScheduledPromptSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        ensureTripleKeyUniqueConstraint()
        ensureNamespaceIdIndex()
        ensureUserIdIndex()
        ensureAgentConfigIdIndex()
    }

    private fun ensureTripleKeyUniqueConstraint() {
        neo4jClient
            .query(
                "CREATE CONSTRAINT scheduled_prompt_triple_key_unique IF NOT EXISTS " +
                    "FOR (sp:ScheduledPrompt) REQUIRE sp.tripleKey IS UNIQUE",
            ).run()
        logger.info { "[ScheduledPromptSchema] constraint 'scheduled_prompt_triple_key_unique' ensured" }
    }

    private fun ensureNamespaceIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX scheduled_prompt_namespace_id IF NOT EXISTS FOR (sp:ScheduledPrompt) ON (sp.namespaceId)",
            ).run()
        logger.info { "[ScheduledPromptSchema] index 'scheduled_prompt_namespace_id' ensured" }
    }

    private fun ensureUserIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX scheduled_prompt_user_id IF NOT EXISTS FOR (sp:ScheduledPrompt) ON (sp.userId)",
            ).run()
        logger.info { "[ScheduledPromptSchema] index 'scheduled_prompt_user_id' ensured" }
    }

    private fun ensureAgentConfigIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX scheduled_prompt_agent_config_id IF NOT EXISTS FOR (sp:ScheduledPrompt) ON (sp.agentConfigId)",
            ).run()
        logger.info { "[ScheduledPromptSchema] index 'scheduled_prompt_agent_config_id' ensured" }
    }

    companion object : KLogging()
}
