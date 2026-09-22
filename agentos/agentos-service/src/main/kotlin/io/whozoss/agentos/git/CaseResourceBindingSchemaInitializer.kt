package io.whozoss.agentos.git

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [CaseResourceBinding].
 *
 * The per-root-case constraint is what makes allocation safe under concurrency: two requests
 * creating the same root case would otherwise each insert a binding and each start provisioning a
 * worktree into the same directory.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class CaseResourceBindingSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        assertNoDuplicateRootCaseKeys()
        ensureIdConstraint()
        ensureRootCaseUniqueConstraint()
        ensureNamespaceIndex()
    }

    private fun assertNoDuplicateRootCaseKeys() {
        val offending =
            neo4jClient
                .query(
                    """
                    MATCH (b:CaseResourceBinding)
                    WHERE b.activeRootCaseKey IS NOT NULL
                    WITH b.activeRootCaseKey AS key, count(b) AS dups
                    WHERE dups > 1
                    RETURN key ORDER BY dups DESC LIMIT 3
                    """.trimIndent(),
                ).fetchAs(String::class.java)
                .all()
        if (offending.isNotEmpty()) {
            error(
                "[CaseResourceBindingSchema] aborting: ${offending.size} root case(s) own more than one " +
                    "active workspace binding. Soft-delete the extras before next start. " +
                    "Sample: ${offending.joinToString(prefix = "[", postfix = "]")}",
            )
        }
    }

    private fun ensureIdConstraint() {
        neo4jClient
            .query(
                """
                CREATE CONSTRAINT case_resource_binding_id_unique IF NOT EXISTS
                FOR (b:CaseResourceBinding) REQUIRE b.id IS UNIQUE
                """.trimIndent(),
            ).run()
        logger.info { "[CaseResourceBindingSchema] constraint 'case_resource_binding_id_unique' ensured" }
    }

    private fun ensureRootCaseUniqueConstraint() {
        neo4jClient
            .query(
                """
                CREATE CONSTRAINT case_resource_binding_root_case_unique IF NOT EXISTS
                FOR (b:CaseResourceBinding) REQUIRE b.activeRootCaseKey IS UNIQUE
                """.trimIndent(),
            ).run()
        logger.info { "[CaseResourceBindingSchema] constraint 'case_resource_binding_root_case_unique' ensured" }
    }

    private fun ensureNamespaceIndex() {
        neo4jClient
            .query(
                """
                CREATE INDEX case_resource_binding_namespace_lookup IF NOT EXISTS
                FOR (b:CaseResourceBinding) ON (b.namespaceId)
                """.trimIndent(),
            ).run()
        logger.info { "[CaseResourceBindingSchema] index 'case_resource_binding_namespace_lookup' ensured" }
    }

    companion object : KLogging()
}
