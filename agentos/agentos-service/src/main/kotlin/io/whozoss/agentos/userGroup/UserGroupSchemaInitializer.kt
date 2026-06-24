package io.whozoss.agentos.userGroup

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.core.annotation.Order
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [UserGroup].
 *
 * Backfills `removed = false` on any pre-existing nodes that have the property absent
 * (stored as `null` before the field was made non-nullable), then ensures the unique
 * constraint on `(name, namespaceId)` via the `ActiveUserGroup` virtual label.
 */
@Component
@Order(1)
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class UserGroupSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        backfillRemoved()
        ensureNameUniqueConstraint()
    }

    private fun backfillRemoved() {
        val backfilled =
            neo4jClient
                .query("MATCH (g:UserGroup) WHERE g.removed IS NULL SET g.removed = false RETURN count(g) AS count")
                .fetchAs(Long::class.java)
                .mappedBy { _, record -> record["count"].asLong() }
                .one()
                .orElse(0L)
        if (backfilled > 0L) {
            logger.info { "[UserGroupSchema] Backfilled removed=false on $backfilled UserGroup node(s)" }
        }
    }

    private fun ensureNameUniqueConstraint() {
        neo4jClient.query(
            "CREATE CONSTRAINT user_group_name_namespace_unique IF NOT EXISTS " +
                "FOR (g:ActiveUserGroup) REQUIRE (g.name, g.namespaceId) IS UNIQUE",
        ).run()
        logger.info { "[UserGroupSchema] Constraint user_group_name_namespace_unique ensured" }
    }

    companion object : KLogging()
}
