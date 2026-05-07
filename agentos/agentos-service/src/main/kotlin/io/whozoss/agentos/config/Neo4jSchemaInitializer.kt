package io.whozoss.agentos.config

import mu.KLogging
import org.neo4j.driver.Driver
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class Neo4jSchemaInitializer(private val driver: Driver) {

    /**
     * Initialises Neo4j schema constraints before any [org.springframework.boot.ApplicationRunner]
     * executes.
     *
     * [ApplicationStartedEvent] is published after the Spring context is fully refreshed
     * (all beans initialised, all [org.springframework.context.ApplicationContextInitializer]s run)
     * but **before** [ApplicationReadyEvent] — and critically, before any [org.springframework.boot.ApplicationRunner]
     * or [org.springframework.boot.CommandLineRunner] beans are invoked.
     *
     * Using [ApplicationReadyEvent] (the previous choice) caused a race: the bootstrap
     * [org.springframework.boot.ApplicationRunner] could create User nodes before the
     * `user_external_id_unique` constraint existed, leading to duplicate nodes that
     * then prevented the constraint from being created on the next startup.
     */
    @EventListener(ApplicationStartedEvent::class)
    fun initSchema() {
        driver.session().use { session ->
            session.run(
                "CREATE CONSTRAINT user_group_name_namespace_unique IF NOT EXISTS " +
                    "FOR (g:ActiveUserGroup) REQUIRE (g.name, g.namespaceId) IS UNIQUE",
            )
            logger.info { "[Neo4jSchemaInitializer] Constraint user_group_name_namespace_unique ensured" }

            session.run(
                "CREATE CONSTRAINT namespace_external_id_unique IF NOT EXISTS " +
                    "FOR (n:ActiveNamespace) REQUIRE n.externalId IS UNIQUE",
            )
            logger.info { "[Neo4jSchemaInitializer] Constraint namespace_external_id_unique ensured" }

            session.run(
                "CREATE CONSTRAINT user_external_id_unique IF NOT EXISTS " +
                    "FOR (u:ActiveUser) REQUIRE u.externalId IS UNIQUE",
            )
            logger.info { "[Neo4jSchemaInitializer] Constraint user_external_id_unique ensured" }
        }
    }

    companion object : KLogging()
}
