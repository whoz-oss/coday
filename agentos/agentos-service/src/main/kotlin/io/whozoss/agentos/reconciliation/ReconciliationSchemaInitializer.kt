package io.whozoss.agentos.reconciliation

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.core.annotation.Order
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [AiProvider] and [AiModel] reconciliation indexes.
 *
 * Creates composite indexes on `(namespaceId, userId, name/alias)` and simple `(userId)` indexes
 * to back the 3-tier reconciliation `findByTriple` queries (story 6.4 T21, AC19, NFR-PERF-4).
 *
 * These indexes cannot replace the NULL-tolerant `IS NULL` arms of the Cypher queries
 * (Neo4j 5.x composite indexes are not seekable for NULL values), but they significantly
 * accelerate the non-NULL equality paths and the `userId` filter.
 *
 * Ordered after [io.whozoss.agentos.integrationConfig.IntegrationConfigSchemaInitializer]
 * (which runs at the default order) so any Neo4j session is already established.
 */
@Component
@Order(20)
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class ReconciliationSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        ensureAiProviderIndexes()
        ensureAiModelIndexes()
    }

    private fun ensureAiProviderIndexes() {
        neo4jClient.query(
            "CREATE INDEX aiProvider_triple IF NOT EXISTS FOR (n:AiProvider) ON (n.namespaceId, n.userId, n.name)",
        ).run()
        neo4jClient.query(
            "CREATE INDEX aiProvider_userId IF NOT EXISTS FOR (n:AiProvider) ON (n.userId)",
        ).run()
        logger.info { "[ReconciliationSchema] AiProvider reconciliation indexes ensured" }
    }

    private fun ensureAiModelIndexes() {
        neo4jClient.query(
            "CREATE INDEX aiModel_triple IF NOT EXISTS FOR (n:AiModel) ON (n.namespaceId, n.userId, n.alias)",
        ).run()
        neo4jClient.query(
            "CREATE INDEX aiModel_userId IF NOT EXISTS FOR (n:AiModel) ON (n.userId)",
        ).run()
        logger.info { "[ReconciliationSchema] AiModel reconciliation indexes ensured" }
    }

    companion object : KLogging()
}
