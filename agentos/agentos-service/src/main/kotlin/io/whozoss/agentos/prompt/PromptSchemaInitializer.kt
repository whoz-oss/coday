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
 * Creates an index on namespaceId to back efficient namespace-scoped listing
 * (PromptNodeNeo4jRepository.findActiveByNamespaceId).
 *
 * No uniqueness constraint on name — the same name may coexist across platform
 * and namespace scopes by design.
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
    }

    private fun ensureNamespaceIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX prompt_namespace_id IF NOT EXISTS FOR (p:Prompt) ON (p.namespaceId)",
            ).run()
        logger.info { "[PromptSchema] index 'prompt_namespace_id' ensured" }
    }

    companion object : KLogging()
}
