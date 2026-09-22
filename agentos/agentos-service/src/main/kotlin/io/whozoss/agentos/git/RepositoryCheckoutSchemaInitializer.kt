package io.whozoss.agentos.git

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [RepositoryCheckout].
 *
 * Provisions the id constraint and the "one active checkout per namespace" constraint. The latter
 * is enforced in the database rather than by a read-then-write check: two concurrent provisioning
 * attempts would both observe no checkout and both clone into the same directory.
 *
 * Active for both `neo4j` and `embedded-neo4j`, the modes for which a Neo4j driver exists.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class RepositoryCheckoutSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        assertNoDuplicateNamespaceKeys()
        ensureIdConstraint()
        ensureNamespaceUniqueConstraint()
    }

    /**
     * Pre-flight so a pre-existing duplicate surfaces as an actionable message rather than as an
     * opaque failure from `CREATE CONSTRAINT`.
     */
    private fun assertNoDuplicateNamespaceKeys() {
        val offending =
            neo4jClient
                .query(
                    """
                    MATCH (c:RepositoryCheckout)
                    WHERE c.activeNamespaceKey IS NOT NULL
                    WITH c.activeNamespaceKey AS key, count(c) AS dups
                    WHERE dups > 1
                    RETURN key ORDER BY dups DESC LIMIT 3
                    """.trimIndent(),
                ).fetchAs(String::class.java)
                .all()
        if (offending.isNotEmpty()) {
            error(
                "[RepositoryCheckoutSchema] aborting: ${offending.size} namespace(s) have more than one " +
                    "active repository checkout. Soft-delete the extras before next start. " +
                    "Sample: ${offending.joinToString(prefix = "[", postfix = "]")}",
            )
        }
    }

    private fun ensureIdConstraint() {
        neo4jClient
            .query(
                """
                CREATE CONSTRAINT repository_checkout_id_unique IF NOT EXISTS
                FOR (c:RepositoryCheckout) REQUIRE c.id IS UNIQUE
                """.trimIndent(),
            ).run()
        logger.info { "[RepositoryCheckoutSchema] constraint 'repository_checkout_id_unique' ensured" }
    }

    private fun ensureNamespaceUniqueConstraint() {
        neo4jClient
            .query(
                """
                CREATE CONSTRAINT repository_checkout_namespace_unique IF NOT EXISTS
                FOR (c:RepositoryCheckout) REQUIRE c.activeNamespaceKey IS UNIQUE
                """.trimIndent(),
            ).run()
        logger.info { "[RepositoryCheckoutSchema] constraint 'repository_checkout_namespace_unique' ensured" }
    }

    companion object : KLogging()
}
