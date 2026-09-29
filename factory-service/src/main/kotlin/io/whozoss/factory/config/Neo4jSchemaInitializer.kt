package io.whozoss.factory.config

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Creates the Neo4j constraints and indexes the factory persistence relies on.
 *
 * Replaces the Flyway migration chain that created the PostgreSQL schema. Every
 * statement is idempotent (`IF NOT EXISTS`), so the initializer is safe to run
 * on every boot and against both the embedded engine and a standalone server.
 */
@Component
@ConditionalOnExpression(
    "'\${factory.persistence.mode:embedded-neo4j}' == 'neo4j' " +
        "or '\${factory.persistence.mode:embedded-neo4j}' == 'embedded-neo4j'",
)
class Neo4jSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        // ── Oracle executions ──────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT oracle_execution_id_unique IF NOT EXISTS " +
                    "FOR (e:OracleExecution) REQUIRE e.id IS UNIQUE",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Constraint oracle_execution_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX oracle_execution_idempotency IF NOT EXISTS " +
                    "FOR (e:OracleExecution) ON (e.organizationId, e.workstreamId, e.idempotencyKey)",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Index oracle_execution_idempotency created" }

        // ── Artifact metadata ──────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT artifact_metadata_id_unique IF NOT EXISTS " +
                    "FOR (a:ArtifactMetadata) REQUIRE a.id IS UNIQUE",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Constraint artifact_metadata_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX artifact_metadata_scope IF NOT EXISTS " +
                    "FOR (a:ArtifactMetadata) ON (a.organizationId, a.workstreamId)",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Index artifact_metadata_scope created" }
    }

    companion object : KLogging()
}
