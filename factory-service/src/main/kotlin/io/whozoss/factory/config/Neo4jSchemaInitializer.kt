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
        logger.debug { "[Neo4jSchemaInitializer] Constraint oracle_execution_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX oracle_execution_idempotency IF NOT EXISTS " +
                    "FOR (e:OracleExecution) ON (e.organizationId, e.workstreamId, e.idempotencyKey)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index oracle_execution_idempotency ensured" }

        // ── Artifact metadata ──────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT artifact_metadata_id_unique IF NOT EXISTS " +
                    "FOR (a:ArtifactMetadata) REQUIRE a.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint artifact_metadata_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX artifact_metadata_scope IF NOT EXISTS " +
                    "FOR (a:ArtifactMetadata) ON (a.organizationId, a.workstreamId)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index artifact_metadata_scope ensured" }

        // ── Delivery snapshot + append-only journal ────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT delivery_id_unique IF NOT EXISTS " +
                    "FOR (d:Delivery) REQUIRE d.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint delivery_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE CONSTRAINT delivery_record_id_unique IF NOT EXISTS " +
                    "FOR (r:DeliveryRecord) REQUIRE r.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint delivery_record_id_unique ensured" }

        // ── Work-unit leases (fencing) ─────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT work_unit_lease_id_unique IF NOT EXISTS " +
                    "FOR (l:WorkUnitLease) REQUIRE l.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint work_unit_lease_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX work_unit_lease_active IF NOT EXISTS " +
                    "FOR (l:WorkUnitLease) ON (l.organizationId, l.workstreamId, l.workUnitId, l.status)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index work_unit_lease_active ensured" }

        // ── Workers ────────────────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT worker_id_unique IF NOT EXISTS " +
                    "FOR (w:Worker) REQUIRE w.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint worker_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX worker_scope IF NOT EXISTS " +
                    "FOR (w:Worker) ON (w.organizationId, w.status)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index worker_scope ensured" }

        // ── Work units ─────────────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT work_unit_id_unique IF NOT EXISTS " +
                    "FOR (w:WorkUnit) REQUIRE w.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint work_unit_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX work_unit_scope IF NOT EXISTS " +
                    "FOR (w:WorkUnit) ON (w.organizationId, w.workstreamId, w.status)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index work_unit_scope ensured" }

        // ── Work environments ──────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT work_environment_id_unique IF NOT EXISTS " +
                    "FOR (e:WorkEnvironment) REQUIRE e.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint work_environment_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX work_environment_workflow IF NOT EXISTS " +
                    "FOR (e:WorkEnvironment) ON (e.organizationId, e.workstreamId, e.workflowId)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index work_environment_workflow ensured" }

        // ── Workstreams ────────────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT workstream_id_unique IF NOT EXISTS " +
                    "FOR (w:Workstream) REQUIRE w.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint workstream_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX workstream_scope IF NOT EXISTS " +
                    "FOR (w:Workstream) ON (w.organizationId, w.workstreamId)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index workstream_scope ensured" }

        // ── Agent-step attempts ────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT agent_step_attempt_id_unique IF NOT EXISTS " +
                    "FOR (a:AgentStepAttempt) REQUIRE a.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint agent_step_attempt_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX agent_step_attempt_scope IF NOT EXISTS " +
                    "FOR (a:AgentStepAttempt) ON (a.organizationId, a.workstreamId, a.status)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index agent_step_attempt_scope ensured" }

        // ── Agent-step results ─────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT agent_step_result_id_unique IF NOT EXISTS " +
                    "FOR (r:AgentStepResult) REQUIRE r.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint agent_step_result_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX agent_step_result_attempt IF NOT EXISTS " +
                    "FOR (r:AgentStepResult) ON " +
                    "(r.organizationId, r.workstreamId, r.namespaceId, r.workflowId, r.stepId, r.attemptId)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index agent_step_result_attempt ensured" }

        // ── Result capabilities ────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT result_capability_id_unique IF NOT EXISTS " +
                    "FOR (c:ResultCapability) REQUIRE c.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint result_capability_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX result_capability_token_hash IF NOT EXISTS " +
                    "FOR (c:ResultCapability) ON (c.organizationId, c.workstreamId, c.tokenHash)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index result_capability_token_hash ensured" }

        // ── Transactional outbox ───────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT outbox_event_id_unique IF NOT EXISTS " +
                    "FOR (e:OutboxEvent) REQUIRE e.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint outbox_event_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX outbox_event_pending IF NOT EXISTS " +
                    "FOR (e:OutboxEvent) ON (e.organizationId, e.status, e.createdAt)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index outbox_event_pending ensured" }

        // ── Idempotency records ────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT idempotency_record_id_unique IF NOT EXISTS " +
                    "FOR (i:IdempotencyRecord) REQUIRE i.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint idempotency_record_id_unique ensured" }

        // ── Durable agent attempt journal (append-only) ────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT durable_agent_attempt_journal_id_unique IF NOT EXISTS " +
                    "FOR (j:DurableAgentAttemptJournal) REQUIRE j.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint durable_agent_attempt_journal_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX durable_agent_attempt_journal_attempt IF NOT EXISTS " +
                    "FOR (j:DurableAgentAttemptJournal) ON " +
                    "(j.organizationId, j.workstreamId, j.namespaceId, j.workflowId, j.stepId, j.attemptId)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index durable_agent_attempt_journal_attempt ensured" }

        // ── Workflow definitions ───────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT workflow_definition_id_unique IF NOT EXISTS " +
                    "FOR (d:WorkflowDefinition) REQUIRE d.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint workflow_definition_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX workflow_definition_scope IF NOT EXISTS " +
                    "FOR (d:WorkflowDefinition) ON (d.organizationId, d.workflowType)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index workflow_definition_scope ensured" }

        // ── Workflow instances ─────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT workflow_instance_id_unique IF NOT EXISTS " +
                    "FOR (i:WorkflowInstance) REQUIRE i.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint workflow_instance_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX workflow_instance_scope IF NOT EXISTS " +
                    "FOR (i:WorkflowInstance) ON (i.organizationId, i.workstreamId, i.namespaceId, i.status)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index workflow_instance_scope ensured" }

        // ── Workflow projections ───────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT workflow_projection_id_unique IF NOT EXISTS " +
                    "FOR (p:WorkflowProjection) REQUIRE p.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint workflow_projection_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX workflow_projection_scope IF NOT EXISTS " +
                    "FOR (p:WorkflowProjection) ON " +
                    "(p.organizationId, p.workstreamId, p.namespaceId, p.lifecycleState)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index workflow_projection_scope ensured" }

        // ── Workflow step states ───────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT workflow_step_state_id_unique IF NOT EXISTS " +
                    "FOR (s:WorkflowStepState) REQUIRE s.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint workflow_step_state_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX workflow_step_state_instance IF NOT EXISTS " +
                    "FOR (s:WorkflowStepState) ON " +
                    "(s.organizationId, s.workstreamId, s.namespaceId, s.workflowId)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index workflow_step_state_instance ensured" }

        // ── Workflow transitions ───────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT workflow_transition_id_unique IF NOT EXISTS " +
                    "FOR (t:WorkflowTransition) REQUIRE t.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint workflow_transition_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX workflow_transition_instance IF NOT EXISTS " +
                    "FOR (t:WorkflowTransition) ON " +
                    "(t.organizationId, t.workstreamId, t.namespaceId, t.workflowId)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index workflow_transition_instance ensured" }

        // ── Workflow code transitions ──────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT workflow_code_transition_id_unique IF NOT EXISTS " +
                    "FOR (t:WorkflowCodeTransition) REQUIRE t.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint workflow_code_transition_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX workflow_code_transition_instance IF NOT EXISTS " +
                    "FOR (t:WorkflowCodeTransition) ON " +
                    "(t.organizationId, t.workstreamId, t.namespaceId, t.workflowId)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index workflow_code_transition_instance ensured" }

        // ── Workflow evidence ──────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT workflow_evidence_id_unique IF NOT EXISTS " +
                    "FOR (e:WorkflowEvidence) REQUIRE e.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint workflow_evidence_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX workflow_evidence_instance IF NOT EXISTS " +
                    "FOR (e:WorkflowEvidence) ON " +
                    "(e.organizationId, e.workstreamId, e.namespaceId, e.workflowId, e.stepId)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index workflow_evidence_instance ensured" }

        neo4jClient
            .query(
                "CREATE INDEX workflow_evidence_idempotency IF NOT EXISTS " +
                    "FOR (e:WorkflowEvidence) ON " +
                    "(e.organizationId, e.workstreamId, e.namespaceId, e.workflowId, e.idempotencyKey)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index workflow_evidence_idempotency ensured" }

        // ── Human interactions ─────────────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT human_interaction_id_unique IF NOT EXISTS " +
                    "FOR (h:HumanInteraction) REQUIRE h.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint human_interaction_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX human_interaction_instance IF NOT EXISTS " +
                    "FOR (h:HumanInteraction) ON " +
                    "(h.organizationId, h.workstreamId, h.namespaceId, h.workflowId, h.status)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index human_interaction_instance ensured" }

        // ── Human interaction events ───────────────────────────────────────
        neo4jClient
            .query(
                "CREATE CONSTRAINT human_interaction_event_id_unique IF NOT EXISTS " +
                    "FOR (e:HumanInteractionEvent) REQUIRE e.id IS UNIQUE",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Constraint human_interaction_event_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX human_interaction_event_instance IF NOT EXISTS " +
                    "FOR (e:HumanInteractionEvent) ON " +
                    "(e.organizationId, e.workstreamId, e.namespaceId, e.workflowId)",
            ).run()
        logger.debug { "[Neo4jSchemaInitializer] Index human_interaction_event_instance ensured" }

        logger.info { "[Neo4jSchemaInitializer] Neo4j schema ensured" }
    }

    companion object : KLogging()
}
