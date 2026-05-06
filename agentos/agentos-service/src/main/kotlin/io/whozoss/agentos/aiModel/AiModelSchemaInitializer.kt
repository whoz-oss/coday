package io.whozoss.agentos.aiModel

import io.whozoss.agentos.persistence.TripleKeyEncoding
import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [AiModel] (story 6.1 IG-7 — homogenisation
 * with [io.whozoss.agentos.integrationConfig.IntegrationConfigSchemaInitializer] and
 * [io.whozoss.agentos.aiProvider.AiProviderSchemaInitializer]).
 *
 * 1. Backfill the `tripleKey` discriminator on any pre-existing rows.
 * 2. Create the UNIQUE CONSTRAINT on `tripleKey`.
 *
 * The matching name for the tripleKey is `coalesce(alias, apiName)` — same convention as
 * the runtime reconciliation lookup ([AiModelNodeNeo4jRepository.findActiveByTripleKey]).
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
        backfillTripleKey()
        ensureTripleKeyUniqueConstraint()
    }

    private fun backfillTripleKey() {
        val nullSentinel = TripleKeyEncoding.NULL_ID_SENTINEL
        val separator = TripleKeyEncoding.SEPARATOR
        val tombstonePrefix = TripleKeyEncoding.TOMBSTONE_PREFIX
        // `coalesce(alias, apiName)` mirrors `AiModelNode.matchingName`. Cypher `coalesce`
        // returns the first non-null arg, which matches Kotlin's `alias ?: apiName`.
        val cypher =
            """
            MATCH (m:AiModel)
            WHERE m.tripleKey IS NULL
            SET m.tripleKey = CASE
                WHEN m.removed = true THEN '$tombstonePrefix' + m.id
                ELSE coalesce(m.namespaceId, '$nullSentinel') + '$separator' + coalesce(m.userId, '$nullSentinel') + '$separator' + coalesce(m.alias, m.apiName)
            END
            RETURN count(m) AS migrated
            """.trimIndent()
        val migrated =
            neo4jClient
                .query(cypher)
                .fetchAs(Long::class.java)
                .one()
                .orElse(0L)
        logger.info { "[AiModelSchema] tripleKey backfill migrated=$migrated row(s)" }
    }

    private fun ensureTripleKeyUniqueConstraint() {
        val cypher =
            """
            CREATE CONSTRAINT ai_model_triple_key_unique IF NOT EXISTS
            FOR (m:AiModel) REQUIRE m.tripleKey IS UNIQUE
            """.trimIndent()
        neo4jClient.query(cypher).run()
        logger.info { "[AiModelSchema] constraint 'ai_model_triple_key_unique' ensured" }
    }

    companion object : KLogging()
}
