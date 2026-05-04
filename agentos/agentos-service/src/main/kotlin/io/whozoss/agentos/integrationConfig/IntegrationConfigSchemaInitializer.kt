package io.whozoss.agentos.integrationConfig

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [IntegrationConfig] (story 6.1).
 *
 * Runs as an [ApplicationRunner] after Spring is up but before HTTP traffic is accepted.
 * Creates two indexes used by the triple-mode lookup paths:
 *
 * - `integration_config_triple_lookup` on `(namespaceId, userId, name)` — backs
 *   [IntegrationConfigNodeNeo4jRepository.findActiveByTriple], which is the hot path
 *   for [io.whozoss.agentos.reconciliation.ConfigReconciliationService.resolve] (3 lookups
 *   per Tool resolution at run time, story 6.4).
 * - `integration_config_user_lookup` on `userId` — backs `findActiveByUserId` and the
 *   user-scoped CRUD listing landing in story 6.2.
 *
 * No data backfill is required: existing namespace-only rows have an absent `userId`
 * property, which Neo4j returns as `NULL` automatically. The composite index covers
 * those rows transparently.
 *
 * Active for both `neo4j` and `embedded-neo4j` persistence modes — exactly the modes
 * for which a Neo4j Driver bean is provisioned (`{@code Neo4jPersistenceConfiguration}`).
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class IntegrationConfigSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        ensureIndex(
            name = "integration_config_triple_lookup",
            cypher = """
                CREATE INDEX integration_config_triple_lookup IF NOT EXISTS
                FOR (c:IntegrationConfig) ON (c.namespaceId, c.userId, c.name)
            """.trimIndent(),
        )
        ensureIndex(
            name = "integration_config_user_lookup",
            cypher = """
                CREATE INDEX integration_config_user_lookup IF NOT EXISTS
                FOR (c:IntegrationConfig) ON (c.userId)
            """.trimIndent(),
        )
    }

    private fun ensureIndex(
        name: String,
        cypher: String,
    ) {
        neo4jClient.query(cypher).run()
        logger.info { "[IntegrationConfigSchema] index '$name' ensured" }
    }

    companion object : KLogging()
}
