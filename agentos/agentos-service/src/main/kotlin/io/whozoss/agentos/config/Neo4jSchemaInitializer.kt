package io.whozoss.agentos.config

import mu.KLogging
import org.neo4j.driver.Driver
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component

/**
 * Neo4j schema bootstrap for cross-cutting constraints (UserGroup, Namespace.externalId).
 *
 * Runs as an [ApplicationRunner] so a failure aborts the boot — previously this listened
 * on `ApplicationReadyEvent`, which fires AFTER the HTTP listener is up, so any exception
 * thrown from the listener was swallowed by Spring and the process kept serving requests
 * with the constraints absent (silent loss of uniqueness guarantees). Now any
 * [org.neo4j.driver.exceptions.Neo4jException] propagates from `run`, and the JVM exits
 * with a non-zero code instead of pretending to be ready.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class Neo4jSchemaInitializer(private val driver: Driver) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        try {
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
        } catch (e: Exception) {
            // Fail-fast: any failure here means the constraints are NOT installed. Continuing
            // to boot would let the service accept writes that violate uniqueness silently.
            logger.error(e) { "[Neo4jSchemaInitializer] Schema bootstrap FAILED — refusing to continue boot" }
            throw IllegalStateException(
                "[Neo4jSchemaInitializer] Schema bootstrap failed: ${e.message}",
                e,
            )
        }
    }

    companion object : KLogging()
}
