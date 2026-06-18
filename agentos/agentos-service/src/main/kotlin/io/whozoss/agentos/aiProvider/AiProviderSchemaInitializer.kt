package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.persistence.OverlayKeyEncoding
import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.core.annotation.Order
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [AiProvider] (story 6.1 IG-7 — homogenisation
 * with [io.whozoss.agentos.integrationConfig.IntegrationConfigSchemaInitializer]).
 *
 * Mirrors the IntegrationConfig pattern:
 * 1. Backfill the `tripleKey` discriminator on any pre-existing rows.
 * 2. Create the UNIQUE CONSTRAINT on `tripleKey`.
 *
 * The IntegrationConfig initialiser also runs heavy pre-flight assertions (blank-name and
 * duplicate-detection) because that path was migrated against a populated production
 * dataset. AiProvider is currently deployed only against developer machines (cf. branch
 * scope), so we keep the initialiser minimal — the backfill is sufficient and any duplicate
 * left over by a manual import will surface naturally as a `CREATE CONSTRAINT` failure
 * which the Spring container will already log clearly.
 */
@Component
@Order(1)
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class AiProviderSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        backfillTripleKey()
        ensureTripleKeyUniqueConstraint()
        ensureReconciliationIndexes()
    }

    private fun backfillTripleKey() {
        // Cypher mirrors `AiProviderNode.computeTripleKey` / `tombstoneTripleKey` and pulls
        // the literals from the shared encoding to avoid drift if a maintainer renames them.
        val nullSentinel = OverlayKeyEncoding.NULL_ID_SENTINEL
        val separator = OverlayKeyEncoding.SEPARATOR
        val tombstonePrefix = OverlayKeyEncoding.TOMBSTONE_PREFIX
        val cypher =
            """
            MATCH (c:AiProvider)
            WHERE c.tripleKey IS NULL
            SET c.tripleKey = CASE
                WHEN c.removed = true THEN '$tombstonePrefix' + c.id
                ELSE coalesce(c.namespaceId, '$nullSentinel') + '$separator' + coalesce(c.userId, '$nullSentinel') + '$separator' + c.name
            END
            RETURN count(c) AS migrated
            """.trimIndent()
        val migrated =
            neo4jClient
                .query(cypher)
                .fetchAs(Long::class.java)
                .one()
                .orElse(0L)
        logger.info { "[AiProviderSchema] tripleKey backfill migrated=$migrated row(s)" }
    }

    private fun ensureTripleKeyUniqueConstraint() {
        val cypher =
            """
            CREATE CONSTRAINT ai_provider_triple_key_unique IF NOT EXISTS
            FOR (c:AiProvider) REQUIRE c.tripleKey IS UNIQUE
            """.trimIndent()
        neo4jClient.query(cypher).run()
        logger.info { "[AiProviderSchema] constraint 'ai_provider_triple_key_unique' ensured" }
    }

    private fun ensureReconciliationIndexes() {
        neo4jClient.query(
            "CREATE INDEX aiProvider_triple IF NOT EXISTS FOR (n:AiProvider) ON (n.namespaceId, n.userId, n.name)",
        ).run()
        neo4jClient.query(
            "CREATE INDEX aiProvider_userId IF NOT EXISTS FOR (n:AiProvider) ON (n.userId)",
        ).run()
        logger.info { "[AiProviderSchema] reconciliation indexes ensured" }
    }

    companion object : KLogging()
}
