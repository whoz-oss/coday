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

        // ── Delivery snapshot + append-only journal ────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT delivery_id_unique IF NOT EXISTS " +
                    "FOR (d:Delivery) REQUIRE d.id IS UNIQUE",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Constraint delivery_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE CONSTRAINT delivery_record_id_unique IF NOT EXISTS " +
                    "FOR (r:DeliveryRecord) REQUIRE r.id IS UNIQUE",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Constraint delivery_record_id_unique ensured" }

        // ── Work-unit leases (fencing) ─────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT work_unit_lease_id_unique IF NOT EXISTS " +
                    "FOR (l:WorkUnitLease) REQUIRE l.id IS UNIQUE",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Constraint work_unit_lease_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX work_unit_lease_active IF NOT EXISTS " +
                    "FOR (l:WorkUnitLease) ON (l.organizationId, l.workstreamId, l.workUnitId, l.status)",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Index work_unit_lease_active created" }

        // ── Workers ────────────────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT worker_id_unique IF NOT EXISTS " +
                    "FOR (w:Worker) REQUIRE w.id IS UNIQUE",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Constraint worker_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX worker_scope IF NOT EXISTS " +
                    "FOR (w:Worker) ON (w.organizationId, w.status)",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Index worker_scope created" }

        // ── Work units ─────────────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT work_unit_id_unique IF NOT EXISTS " +
                    "FOR (w:WorkUnit) REQUIRE w.id IS UNIQUE",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Constraint work_unit_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX work_unit_scope IF NOT EXISTS " +
                    "FOR (w:WorkUnit) ON (w.organizationId, w.workstreamId, w.status)",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Index work_unit_scope created" }

        // ── Work environments ──────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT work_environment_id_unique IF NOT EXISTS " +
                    "FOR (e:WorkEnvironment) REQUIRE e.id IS UNIQUE",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Constraint work_environment_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX work_environment_workflow IF NOT EXISTS " +
                    "FOR (e:WorkEnvironment) ON (e.organizationId, e.workstreamId, e.workflowId)",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Index work_environment_workflow created" }
    }

    companion object : KLogging()
}
