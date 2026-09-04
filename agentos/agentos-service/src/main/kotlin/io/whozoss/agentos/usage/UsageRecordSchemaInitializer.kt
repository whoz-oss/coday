package io.whozoss.agentos.usage

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [UsageRecord].
 *
 * Creates indexes on the properties most likely to appear in WHERE clauses:
 * - [UsageRecordNode.caseId]       — primary lookup when replaying a case
 * - [UsageRecordNode.namespaceId]  — namespace-scoped aggregations (Lot 3)
 * - [UsageRecordNode.userId]       — per-user cost reporting (Lot 3)
 * - [UsageRecordNode.timestamp]    — time-range queries in aggregation dashboards (Lot 3)
 *
 * A UNIQUE constraint on `id` is handled globally by
 * [io.whozoss.agentos.config.Neo4jSchemaInitializer] for every node label — no need
 * to duplicate it here.
 *
 * All statements use `IF NOT EXISTS` so the initialiser is safe to run on every startup.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:embedded-neo4j}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:embedded-neo4j}' == 'embedded-neo4j'",
)
class UsageRecordSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        ensureCaseIdIndex()
        ensureNamespaceIdIndex()
        ensureUserIdIndex()
        ensureTimestampIndex()
    }

    private fun ensureCaseIdIndex() {
        neo4jClient
            .query("CREATE INDEX usage_record_case_id IF NOT EXISTS FOR (u:UsageRecord) ON (u.caseId)")
            .run()
        logger.info { "[UsageRecordSchema] index 'usage_record_case_id' ensured" }
    }

    private fun ensureNamespaceIdIndex() {
        neo4jClient
            .query("CREATE INDEX usage_record_namespace_id IF NOT EXISTS FOR (u:UsageRecord) ON (u.namespaceId)")
            .run()
        logger.info { "[UsageRecordSchema] index 'usage_record_namespace_id' ensured" }
    }

    private fun ensureUserIdIndex() {
        neo4jClient
            .query("CREATE INDEX usage_record_user_id IF NOT EXISTS FOR (u:UsageRecord) ON (u.userId)")
            .run()
        logger.info { "[UsageRecordSchema] index 'usage_record_user_id' ensured" }
    }

    private fun ensureTimestampIndex() {
        neo4jClient
            .query("CREATE INDEX usage_record_timestamp IF NOT EXISTS FOR (u:UsageRecord) ON (u.timestamp)")
            .run()
        logger.info { "[UsageRecordSchema] index 'usage_record_timestamp' ensured" }
    }

    companion object : KLogging()
}
