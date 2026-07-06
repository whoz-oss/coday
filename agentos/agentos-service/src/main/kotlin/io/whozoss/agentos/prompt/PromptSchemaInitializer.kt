package io.whozoss.agentos.prompt

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [Prompt].
 *
 * Creates:
 * - A UNIQUE constraint on `tripleKey` to enforce name uniqueness per
 *   `(namespaceId, userId, name)` scope. On soft-delete the key is rewritten
 *   to `tombstone:<id>` to free the unique slot immediately.
 * - Auxiliary indexes on `namespaceId` and `userId` to back the listing queries.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class PromptSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        ensureTripleKeyUniqueConstraint()
        ensureNamespaceIdIndex()
        ensureUserIdIndex()
    }

    private fun ensureTripleKeyUniqueConstraint() {
        neo4jClient
            .query(
                "CREATE CONSTRAINT prompt_triple_key_unique IF NOT EXISTS " +
                    "FOR (p:Prompt) REQUIRE p.tripleKey IS UNIQUE",
            ).run()
        logger.info { "[PromptSchema] constraint 'prompt_triple_key_unique' ensured" }
    }

    private fun ensureNamespaceIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX prompt_namespace_id IF NOT EXISTS FOR (p:Prompt) ON (p.namespaceId)",
            ).run()
        logger.info { "[PromptSchema] index 'prompt_namespace_id' ensured" }
    }

    private fun ensureUserIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX prompt_user_id IF NOT EXISTS FOR (p:Prompt) ON (p.userId)",
            ).run()
        logger.info { "[PromptSchema] index 'prompt_user_id' ensured" }
    }

    companion object : KLogging()
}
