package io.whozoss.agentos.config

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:embedded-neo4j}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:embedded-neo4j}' == 'embedded-neo4j'",
)
class Neo4jSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        neo4jClient.query(
            "CREATE CONSTRAINT user_group_name_namespace_unique IF NOT EXISTS " +
                "FOR (g:ActiveUserGroup) REQUIRE (g.name, g.namespaceId) IS UNIQUE",
        ).run()
        logger.info { "[Neo4jSchemaInitializer] Constraint user_group_name_namespace_unique ensured" }

        neo4jClient.query(
            "CREATE CONSTRAINT namespace_external_id_unique IF NOT EXISTS " +
                "FOR (n:ActiveNamespace) REQUIRE n.externalId IS UNIQUE",
        ).run()
        logger.info { "[Neo4jSchemaInitializer] Constraint namespace_external_id_unique ensured" }

        neo4jClient.query(
            "CREATE CONSTRAINT user_external_id_unique IF NOT EXISTS " +
                "FOR (u:ActiveUser) REQUIRE u.externalId IS UNIQUE",
        ).run()
        logger.info { "[Neo4jSchemaInitializer] Constraint user_external_id_unique ensured" }

        // Backfill @Version on AgentConfig nodes created before the version field was introduced.
        // Spring Data Neo4j's optimistic-locking check generates MATCH WHERE version = ?
        // which fails if the property is absent. Setting version = 0 makes existing nodes
        // compatible without affecting any application logic.
        val backfilled = neo4jClient.query(
            "MATCH (a:AgentConfig) WHERE a.version IS NULL SET a.version = 0 RETURN count(a) AS count",
        ).fetchAs(Long::class.java)
            .mappedBy { _, record -> record["count"].asLong() }
            .one()
            .orElse(0L)
        if (backfilled > 0L) {
            logger.info { "[Neo4jSchemaInitializer] Backfilled version=0 on $backfilled AgentConfig node(s)" }
        }

        // Backfill enabled=false on AgentConfig nodes created before the enabled field was introduced.
        // Once all nodes have the property set, Cypher queries can use a.enabled directly
        // without COALESCE(a.enabled, false).
        val backfilledEnabled = neo4jClient.query(
            "MATCH (a:AgentConfig) WHERE a.enabled IS NULL SET a.enabled = false RETURN count(a) AS count",
        ).fetchAs(Long::class.java)
            .mappedBy { _, record -> record["count"].asLong() }
            .one()
            .orElse(0L)
        if (backfilledEnabled > 0L) {
            logger.info { "[Neo4jSchemaInitializer] Backfilled enabled=false on $backfilledEnabled AgentConfig node(s)" }
        }
    }

    companion object : KLogging()
}
