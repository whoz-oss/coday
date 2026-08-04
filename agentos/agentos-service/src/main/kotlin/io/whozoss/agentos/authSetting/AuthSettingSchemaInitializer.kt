package io.whozoss.agentos.authSetting

import io.whozoss.agentos.persistence.OverlayKeyEncoding
import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [AuthSetting].
 *
 * Mirrors the AiProvider pattern:
 * 1. Backfill the `tripleKey` discriminator on any pre-existing rows.
 * 2. Create the UNIQUE CONSTRAINT on `tripleKey`.
 * 3. Create reconciliation indexes.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class AuthSettingSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        backfillTripleKey()
        ensureTripleKeyUniqueConstraint()
        ensureReconciliationIndexes()
    }

    private fun backfillTripleKey() {
        // Cypher mirrors `AuthSettingNode.computeTripleKey` / `tombstoneTripleKey` and pulls
        // the literals from the shared encoding to avoid drift if a maintainer renames them.
        val nullSentinel = OverlayKeyEncoding.NULL_ID_SENTINEL
        val separator = OverlayKeyEncoding.SEPARATOR
        val tombstonePrefix = OverlayKeyEncoding.TOMBSTONE_PREFIX
        val cypher =
            """
            MATCH (c:AuthSetting)
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
        logger.info { "[AuthSettingSchema] tripleKey backfill migrated=$migrated row(s)" }
    }

    private fun ensureTripleKeyUniqueConstraint() {
        val cypher =
            """
            CREATE CONSTRAINT auth_setting_triple_key_unique IF NOT EXISTS
            FOR (c:AuthSetting) REQUIRE c.tripleKey IS UNIQUE
            """.trimIndent()
        neo4jClient.query(cypher).run()
        logger.info { "[AuthSettingSchema] constraint 'auth_setting_triple_key_unique' ensured" }
    }

    private fun ensureReconciliationIndexes() {
        neo4jClient.query(
            "CREATE INDEX authSetting_triple IF NOT EXISTS FOR (n:AuthSetting) ON (n.namespaceId, n.userId, n.name)",
        ).run()
        neo4jClient.query(
            "CREATE INDEX authSetting_userId IF NOT EXISTS FOR (n:AuthSetting) ON (n.userId)",
        ).run()
        logger.info { "[AuthSettingSchema] reconciliation indexes ensured" }
    }

    companion object : KLogging()
}
