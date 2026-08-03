package io.whozoss.agentos.scheduledPrompt

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [ScheduledPromptUserRun].
 *
 * Creates:
 * - A UNIQUE constraint on [ScheduledPromptUserRunNode.userRunKey] — the distributed lock
 *   preventing duplicate per-user launches within the same Run.
 * - Auxiliary indexes on [ScheduledPromptUserRunNode.status], [ScheduledPromptUserRunNode.runId],
 *   and [ScheduledPromptUserRunNode.userId] to back the
 *   [ScheduledPromptUserRunNodeNeo4jRepository] queries.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class ScheduledPromptUserRunSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        ensureUserRunKeyUniqueConstraint()
        ensureStatusIndex()
        ensureRunIdIndex()
        ensureUserIdIndex()
    }

    private fun ensureUserRunKeyUniqueConstraint() {
        neo4jClient
            .query(
                "CREATE CONSTRAINT sp_user_run_key_unique IF NOT EXISTS " +
                    "FOR (ur:ScheduledPromptUserRun) REQUIRE ur.userRunKey IS UNIQUE",
            ).run()
        logger.info { "[ScheduledPromptUserRunSchema] constraint 'sp_user_run_key_unique' ensured" }
    }

    private fun ensureStatusIndex() {
        neo4jClient
            .query(
                "CREATE INDEX sp_user_run_status IF NOT EXISTS FOR (ur:ScheduledPromptUserRun) ON (ur.status)",
            ).run()
        logger.info { "[ScheduledPromptUserRunSchema] index 'sp_user_run_status' ensured" }
    }

    private fun ensureRunIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX sp_user_run_run_id IF NOT EXISTS FOR (ur:ScheduledPromptUserRun) ON (ur.runId)",
            ).run()
        logger.info { "[ScheduledPromptUserRunSchema] index 'sp_user_run_run_id' ensured" }
    }

    private fun ensureUserIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX sp_user_run_user_id IF NOT EXISTS FOR (ur:ScheduledPromptUserRun) ON (ur.userId)",
            ).run()
        logger.info { "[ScheduledPromptUserRunSchema] index 'sp_user_run_user_id' ensured" }
    }

    companion object : KLogging()
}
