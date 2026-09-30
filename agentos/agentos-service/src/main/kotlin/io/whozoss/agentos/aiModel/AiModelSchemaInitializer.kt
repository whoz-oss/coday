package io.whozoss.agentos.aiModel

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [AiModel].
 *
 * Creates auxiliary indexes to back the hot-path queries in
 * [AiModelNodeNeo4jRepository]:
 * - `aiProviderId` — used by findActiveByAiProviderId and related lookups.
 * - `namespaceId` — used by findActiveByNamespaceId / findAllForNamespace.
 *
 * The UNIQUE constraint on `id` is handled by the central [io.whozoss.agentos.config.Neo4jSchemaInitializer].
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class AiModelSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        ensureAiProviderIdIndex()
        ensureNamespaceIdIndex()
    }

    private fun ensureAiProviderIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX ai_model_provider_id IF NOT EXISTS FOR (m:AiModel) ON (m.aiProviderId)",
            ).run()
        logger.info { "[AiModelSchema] index 'ai_model_provider_id' ensured" }
    }

    private fun ensureNamespaceIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX ai_model_namespace_id IF NOT EXISTS FOR (m:AiModel) ON (m.namespaceId)",
            ).run()
        logger.info { "[AiModelSchema] index 'ai_model_namespace_id' ensured" }
    }

    companion object : KLogging()
}
