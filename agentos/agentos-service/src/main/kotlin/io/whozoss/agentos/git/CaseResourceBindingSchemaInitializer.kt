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
    "('\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j') " +
        "and '\${agentos.git.workspaces.enabled:false}'.equalsIgnoreCase('true')",
)
class CaseResourceBindingSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        assertNoDuplicateRootCaseKeys()
        ensureIdConstraint()
        ensureRootCaseUniqueConstraint()
        ensureNamespaceIndex()
        ensureStatusIndex()
        ensureSweepCursorIndex()
        backfillVersion()
        backfillSetupState()
    }

    private fun assertNoDuplicateRootCaseKeys() {
        val offending =
            neo4jClient
                .query(
                    """
                    MATCH (b:ActiveCaseResourceBinding)
                    WITH b.rootCaseId AS key, count(b) AS dups
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
                CREATE CONSTRAINT case_resource_binding_active_root_case_unique IF NOT EXISTS
                FOR (b:ActiveCaseResourceBinding) REQUIRE b.rootCaseId IS UNIQUE
                """.trimIndent(),
            ).run()
        logger.info { "[CaseResourceBindingSchema] constraint 'case_resource_binding_active_root_case_unique' ensured" }
    }

    /** Serves [CaseResourceBindingNodeNeo4jRepository.findActiveByNamespaceId], which filters by namespace. */
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

    /** Serves [CaseResourceBindingNodeNeo4jRepository.findActiveByStatusIn], the first page of the worker's sweeps. */
    private fun ensureStatusIndex() {
        neo4jClient
            .query(
                """
                CREATE INDEX case_resource_binding_active_status IF NOT EXISTS
                FOR (b:ActiveCaseResourceBinding) ON (b.status, b.created)
                """.trimIndent(),
            ).run()
        logger.info { "[CaseResourceBindingSchema] index 'case_resource_binding_active_status' ensured" }
    }

    /**
     * Serves [CaseResourceBindingNodeNeo4jRepository.findActiveByStatusInAfter]: the next pages of a
     * sweep are read in `created, id` order, so a page stops after its limit without a sort.
     */
    private fun ensureSweepCursorIndex() {
        neo4jClient
            .query(
                """
                CREATE INDEX case_resource_binding_active_created_id IF NOT EXISTS
                FOR (b:ActiveCaseResourceBinding) ON (b.created, b.id)
                """.trimIndent(),
            ).run()
        logger.info { "[CaseResourceBindingSchema] index 'case_resource_binding_active_created_id' ensured" }
    }

    /** Rows saved before [CaseResourceBindingNode.version] existed need one for optimistic locking. */
    private fun backfillVersion() {
        neo4jClient.query("MATCH (b:CaseResourceBinding) WHERE b.version IS NULL SET b.version = 0").run()
    }

    /** Rows saved before [CaseResourceBindingNode.setupState] kept the setup progress in two flags. */
    private fun backfillSetupState() {
        neo4jClient
            .query(
                """
                MATCH (b:CaseResourceBinding) WHERE b.setupState IS NULL
                SET b.setupState = CASE
                    WHEN b.setupCompleted THEN 'COMPLETED'
                    WHEN b.setupStarted THEN 'STARTED'
                    ELSE 'NOT_STARTED'
                END
                REMOVE b.setupStarted, b.setupCompleted
                """.trimIndent(),
            ).run()
    }

    companion object : KLogging()
}
