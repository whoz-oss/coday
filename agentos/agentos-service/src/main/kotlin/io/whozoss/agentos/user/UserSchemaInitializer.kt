package io.whozoss.agentos.user

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.core.annotation.Order
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [User].
 *
 * Backfills `removed = false` on any pre-existing nodes that have the property absent
 * (stored as `null` before the field was made non-nullable), then ensures the unique
 * constraint on `externalId` via the `ActiveUser` virtual label.
 */
@Component
@Order(1)
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class UserSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        backfillRemoved()
        ensureExternalIdUniqueConstraint()
    }

    private fun backfillRemoved() {
        val backfilled =
            neo4jClient
                .query("MATCH (u:User) WHERE u.removed IS NULL SET u.removed = false RETURN count(u) AS count")
                .fetchAs(Long::class.java)
                .mappedBy { _, record -> record["count"].asLong() }
                .one()
                .orElse(0L)
        if (backfilled > 0L) {
            logger.info { "[UserSchema] Backfilled removed=false on $backfilled User node(s)" }
        }
    }

    private fun ensureExternalIdUniqueConstraint() {
        neo4jClient.query(
            "CREATE CONSTRAINT user_external_id_unique IF NOT EXISTS " +
                "FOR (u:ActiveUser) REQUIRE u.externalId IS UNIQUE",
        ).run()
        logger.info { "[UserSchema] Constraint user_external_id_unique ensured" }
    }

    companion object : KLogging()
}
