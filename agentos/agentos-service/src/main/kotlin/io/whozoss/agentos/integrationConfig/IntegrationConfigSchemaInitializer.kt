package io.whozoss.agentos.integrationConfig

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [IntegrationConfig] (story 6.1, hardened in 6.2.5).
 *
 * Runs as an [ApplicationRunner] after Spring is up but before HTTP traffic is accepted.
 * The order of operations is significant:
 *
 * 1. **Backfill** the `tripleKey` discriminator on any pre-existing rows that do not yet
 *    have it. The expression mirrors [IntegrationConfigNode.computeTripleKey] verbatim so
 *    backfilled rows match those written by the application going forward.
 * 2. **Pre-flight check** that no row remains with `tripleKey IS NULL`. A non-zero count
 *    would mean a stale row blocks the unique constraint creation; we fail fast with a
 *    descriptive message instead of letting `CREATE CONSTRAINT` raise an opaque error.
 * 3. **Unique constraint** on `tripleKey`. Property uniqueness on a single non-null String
 *    is fully enforced by Neo4j, including across triple modes — unlike a composite
 *    constraint on `(namespaceId, userId, name)` which Neo4j 5.x silently exempts when any
 *    component is NULL (cf. limitations documented in `project-context.md`).
 * 4. **Auxiliary index** on `userId` to back `findActiveByUserId`. The unique constraint on
 *    `tripleKey` already provisions an index covering [IntegrationConfigNodeNeo4jRepository.findActiveByTripleKey].
 *
 * The legacy composite index `integration_config_triple_lookup` is dropped explicitly: it
 * was never effective for the NULL-arm lookups (Neo4j indexes do not seek on `IS NULL`)
 * and is now superseded by the `tripleKey` index.
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
        backfillTripleKey()
        assertTripleKeyComplete()
        ensureTripleKeyUniqueConstraint()
        ensureUserIdIndex()
        dropLegacyCompositeIndex()
    }

    private fun backfillTripleKey() {
        // Active rows get the deterministic active key. Soft-deleted (tombstone) rows get a
        // per-id key so they never collide with each other or with a future re-creation of the
        // same triple — a NULL-out + active-key migration would otherwise reintroduce the very
        // collision the constraint is meant to prevent (multiple soft-deleted rows can legally
        // pre-date the constraint with identical triples).
        val cypher =
            """
            MATCH (c:IntegrationConfig)
            WHERE c.tripleKey IS NULL
            SET c.tripleKey = CASE
                WHEN c.removed = true THEN 'tombstone:' + c.id
                ELSE coalesce(c.namespaceId, '_') + ':' + coalesce(c.userId, '_') + ':' + c.name
            END
            RETURN count(c) AS migrated
            """.trimIndent()
        val migrated =
            neo4jClient
                .query(cypher)
                .fetchAs(Long::class.java)
                .one()
                .orElse(0L)
        logger.info { "[IntegrationConfigSchema] tripleKey backfill migrated=$migrated row(s)" }
    }

    private fun assertTripleKeyComplete() {
        val pending =
            neo4jClient
                .query("MATCH (c:IntegrationConfig) WHERE c.tripleKey IS NULL RETURN count(c) AS pending")
                .fetchAs(Long::class.java)
                .one()
                .orElse(0L)
        if (pending > 0L) {
            error(
                "[IntegrationConfigSchema] aborting: $pending IntegrationConfig row(s) still " +
                    "have a NULL tripleKey after backfill — refusing to create unique constraint",
            )
        }
    }

    private fun ensureTripleKeyUniqueConstraint() {
        val cypher =
            """
            CREATE CONSTRAINT integration_config_triple_key_unique IF NOT EXISTS
            FOR (c:IntegrationConfig) REQUIRE c.tripleKey IS UNIQUE
            """.trimIndent()
        neo4jClient.query(cypher).run()
        logger.info { "[IntegrationConfigSchema] constraint 'integration_config_triple_key_unique' ensured" }
    }

    private fun ensureUserIdIndex() {
        val cypher =
            """
            CREATE INDEX integration_config_user_lookup IF NOT EXISTS
            FOR (c:IntegrationConfig) ON (c.userId)
            """.trimIndent()
        neo4jClient.query(cypher).run()
        logger.info { "[IntegrationConfigSchema] index 'integration_config_user_lookup' ensured" }
    }

    private fun dropLegacyCompositeIndex() {
        neo4jClient.query("DROP INDEX integration_config_triple_lookup IF EXISTS").run()
        logger.info { "[IntegrationConfigSchema] legacy index 'integration_config_triple_lookup' dropped if present" }
    }

    companion object : KLogging()
}
