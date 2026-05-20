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
    }

    companion object : KLogging()
}
