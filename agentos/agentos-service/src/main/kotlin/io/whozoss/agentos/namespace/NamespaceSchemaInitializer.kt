package io.whozoss.agentos.namespace

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.core.annotation.Order
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [Namespace].
 *
 * Backfills `removed = false` on any pre-existing nodes that have the property absent
 * (stored as `null` before the field was made non-nullable), then ensures the unique
 * constraint on `externalId` via the `ActiveNamespace` virtual label.
 */
@Component
@Order(1)
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class NamespaceSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        backfillRemoved()
        ensureExternalIdUniqueConstraint()
        ensureExternalIdIndex()
    }

    private fun backfillRemoved() {
        val backfilled =
            neo4jClient
                .query("MATCH (n:Namespace) WHERE n.removed IS NULL SET n.removed = false RETURN count(n) AS count")
                .fetchAs(Long::class.java)
                .mappedBy { _, record -> record["count"].asLong() }
                .one()
                .orElse(0L)
        if (backfilled > 0L) {
            logger.info { "[NamespaceSchema] Backfilled removed=false on $backfilled Namespace node(s)" }
        }
    }

    private fun ensureExternalIdUniqueConstraint() {
        neo4jClient.query(
            "CREATE CONSTRAINT namespace_external_id_unique IF NOT EXISTS " +
                "FOR (n:ActiveNamespace) REQUIRE n.externalId IS UNIQUE",
        ).run()
        logger.info { "[NamespaceSchema] Constraint namespace_external_id_unique ensured" }
    }

    private fun ensureExternalIdIndex() {
        neo4jClient.query(
            "CREATE INDEX namespace_externalId IF NOT EXISTS FOR (n:Namespace) ON (n.externalId)",
        ).run()
        logger.info { "[NamespaceSchema] Index namespace_externalId ensured" }
    }

    companion object : KLogging()
}
