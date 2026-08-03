package io.whozoss.agentos.scheduledPrompt

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [ScheduledPromptRun].
 *
 * Creates:
 * - A UNIQUE constraint on [ScheduledPromptRunNode.slotKey] to prevent double-firing for the
 *   same `(scheduledPromptId, scheduledFor)` slot.
 * - Auxiliary indexes on [ScheduledPromptRunNode.scheduledPromptId] and
 *   [ScheduledPromptRunNode.status] to back the [ScheduledPromptRunNodeNeo4jRepository.existsActiveByScheduledPromptId]
 *   query.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class ScheduledPromptRunSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        ensureSlotKeyUniqueConstraint()
        ensureScheduledPromptIdIndex()
        ensureStatusIndex()
    }

    private fun ensureSlotKeyUniqueConstraint() {
        neo4jClient
            .query(
                "CREATE CONSTRAINT scheduled_prompt_run_slot_key_unique IF NOT EXISTS " +
                    "FOR (r:ScheduledPromptRun) REQUIRE r.slotKey IS UNIQUE",
            ).run()
        logger.info { "[ScheduledPromptRunSchema] constraint 'scheduled_prompt_run_slot_key_unique' ensured" }
    }

    private fun ensureScheduledPromptIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX scheduled_prompt_run_sp_id IF NOT EXISTS FOR (r:ScheduledPromptRun) ON (r.scheduledPromptId)",
            ).run()
        logger.info { "[ScheduledPromptRunSchema] index 'scheduled_prompt_run_sp_id' ensured" }
    }

    private fun ensureStatusIndex() {
        neo4jClient
            .query(
                "CREATE INDEX scheduled_prompt_run_status IF NOT EXISTS FOR (r:ScheduledPromptRun) ON (r.status)",
            ).run()
        logger.info { "[ScheduledPromptRunSchema] index 'scheduled_prompt_run_status' ensured" }
    }

    companion object : KLogging()
}
