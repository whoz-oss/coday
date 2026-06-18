package io.whozoss.agentos.agentConfig

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.core.annotation.Order
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [AgentConfig].
 *
 * Backfills `removed = false` on any pre-existing nodes that have the property absent
 * (stored as `null` before the field was made non-nullable). Once all nodes carry the
 * explicit boolean, Cypher queries can use `a.removed` directly without `IS NULL` guards.
 */
@Component
@Order(1)
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class AgentConfigSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        backfillVersion()
        backfillEnabled()
        backfillRemoved()
    }

    /**
     * Backfill `version = 0` on nodes created before the `@Version` field was introduced.
     * Spring Data Neo4j's optimistic-locking check generates `MATCH WHERE version = ?`
     * which fails if the property is absent.
     */
    private fun backfillVersion() {
        val backfilled =
            neo4jClient
                .query("MATCH (a:AgentConfig) WHERE a.version IS NULL SET a.version = 0 RETURN count(a) AS count")
                .fetchAs(Long::class.java)
                .mappedBy { _, record -> record["count"].asLong() }
                .one()
                .orElse(0L)
        if (backfilled > 0L) {
            logger.info { "[AgentConfigSchema] Backfilled version=0 on $backfilled AgentConfig node(s)" }
        }
    }

    /**
     * Backfill `enabled = false` on nodes created before the `enabled` field was introduced.
     */
    private fun backfillEnabled() {
        val backfilled =
            neo4jClient
                .query("MATCH (a:AgentConfig) WHERE a.enabled IS NULL SET a.enabled = false RETURN count(a) AS count")
                .fetchAs(Long::class.java)
                .mappedBy { _, record -> record["count"].asLong() }
                .one()
                .orElse(0L)
        if (backfilled > 0L) {
            logger.info { "[AgentConfigSchema] Backfilled enabled=false on $backfilled AgentConfig node(s)" }
        }
    }

    /**
     * Backfill `removed = false` on nodes created before the field was made non-nullable.
     */
    private fun backfillRemoved() {
        val backfilled =
            neo4jClient
                .query("MATCH (a:AgentConfig) WHERE a.removed IS NULL SET a.removed = false RETURN count(a) AS count")
                .fetchAs(Long::class.java)
                .mappedBy { _, record -> record["count"].asLong() }
                .one()
                .orElse(0L)
        if (backfilled > 0L) {
            logger.info { "[AgentConfigSchema] Backfilled removed=false on $backfilled AgentConfig node(s)" }
        }
    }

    companion object : KLogging()
}
