package io.whozoss.agentos.caseFlow

import mu.KLogging
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Gives a version to every [Case] node saved before [CaseNode.version] existed. Spring Data Neo4j
 * refuses to save a loaded case without one, so until this runs no existing case can be updated.
 *
 * It runs once every bean exists, before the web server and the schedulers start. An
 * [org.springframework.boot.ApplicationRunner] such as
 * [io.whozoss.agentos.config.Neo4jSchemaInitializer] runs after the port opens, and a status
 * change on an existing case in that window would fail. The statement is idempotent and runs on
 * every start, so nodes written by an earlier release after a rollback are caught on the next one.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:embedded-neo4j}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:embedded-neo4j}' == 'embedded-neo4j'",
)
class CaseVersionBackfill(
    private val neo4jClient: Neo4jClient,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val backfilled =
            neo4jClient
                .query(BACKFILL_VERSION)
                .fetchAs(Long::class.java)
                .mappedBy { _, record -> record["count"].asLong() }
                .one()
                .orElse(0L)
        if (backfilled > 0L) {
            logger.info { "[CaseVersionBackfill] Backfilled version=0 on $backfilled Case node(s)" }
        }
    }

    companion object : KLogging() {
        private const val BACKFILL_VERSION =
            "MATCH (c:Case) WHERE c.version IS NULL SET c.version = 0 RETURN count(c) AS count"
    }
}
