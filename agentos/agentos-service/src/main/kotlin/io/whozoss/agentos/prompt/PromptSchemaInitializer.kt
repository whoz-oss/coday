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
 * - An index on `namespaceId` for efficient namespace-scoped listing.
 * - A UNIQUE constraint on `scopeKey` to enforce name uniqueness per scope.
 *   The `scopeKey` encodes `(namespaceId, name)` into a single non-null string,
 *   using `_` as sentinel for null namespaceId (platform scope). On soft-delete
 *   the key is rewritten to `tombstone:<id>` on soft-delete to free the unique slot.
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
        ensureNamespaceIdIndex()
        ensureScopeKeyConstraint()
    }

    private fun ensureNamespaceIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX prompt_namespace_id IF NOT EXISTS FOR (p:Prompt) ON (p.namespaceId)",
            ).run()
        logger.info { "[PromptSchema] index 'prompt_namespace_id' ensured" }
    }

    private fun ensureScopeKeyConstraint() {
        neo4jClient
            .query(
                "CREATE CONSTRAINT prompt_scope_key_unique IF NOT EXISTS " +
                    "FOR (p:Prompt) REQUIRE p.scopeKey IS UNIQUE",
            ).run()
        logger.info { "[PromptSchema] constraint 'prompt_scope_key_unique' ensured" }
    }

    companion object : KLogging()
}
