package io.whozoss.agentos.config

import mu.KLogging
import org.neo4j.driver.Driver
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:embedded-neo4j}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:embedded-neo4j}' == 'embedded-neo4j'",
)
class Neo4jSchemaInitializer(private val driver: Driver) {

    @EventListener(ApplicationReadyEvent::class)
    fun initSchema() {
        driver.session().use { session ->
            session.run(
                "CREATE CONSTRAINT user_group_name_namespace_unique IF NOT EXISTS " +
                    "FOR (g:UserGroup) REQUIRE (g.name, g.namespaceId) IS UNIQUE",
            )
            logger.info { "[Neo4jSchemaInitializer] Constraint user_group_name_namespace_unique ensured" }

            session.run(
                "CREATE CONSTRAINT namespace_external_id_unique IF NOT EXISTS " +
                    "FOR (n:Namespace) REQUIRE n.externalId IS UNIQUE",
            )
            logger.info { "[Neo4jSchemaInitializer] Constraint namespace_external_id_unique ensured" }
        }
    }

    companion object : KLogging()
}
