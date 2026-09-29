package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.HumanInteractionEventRecord
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.WorkflowCodeTransitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowProjectionRecord
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import io.whozoss.factory.workflow.domain.WorkflowTransitionRequest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * `NamedParameterJdbcTemplate` implementation of the workflow persistence ports.
 *
 * The durable surface mirrors the Node SQL adapters
 * (`sql-workflow-definition-repository.ts`, `sql-workflow-instance-repository.ts`,
 * `sql-workflow-evidence-repository.ts`, `sql-workflow-human-interaction-repository.ts`)
 * plus the declarative `workflow_projections` store added by V9. Every statement
 * is constrained by the composite tenant key
 * `(organizationId, workstreamId, namespaceId, workflowId)` — a query that
 * cannot prove its scope fails closed (it simply matches nothing).
 *
 * Every multi-write mutation runs inside the caller's Spring transaction
 * (the [io.whozoss.factory.workflow.service.WorkflowService] annotations), so the
 * atomic interaction -> evidence -> transition path shares one transaction.
 */
@Repository
class JdbcWorkflowRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : WorkflowRepository, WorkflowEvidenceRepository, HumanInteractionRepository {

    // ------------------------------------------------------------------
    // Definitions
    // ------------------------------------------------------------------

    override fun findDefinition(scope: TenantScope, workflowType: String, version: String): WorkflowDefinitionRecord? {
        val params = scopeParams(scope)
            .addValue("workflowType", workflowType)
            .addValue("version", version)
        val rows = jdbc.query(
            """
            SELECT workflow_type, version, definition_hash, definition_json
              FROM workflow_definitions
             WHERE organization_id = :organizationId AND workflow_type = :workflowType AND version = :version
            """.trimIndent(),
            params,
        ) { rs, _ ->
            WorkflowDefinitionRecord(
                workflowType = rs.getString("workflow_type"),
                version = rs.getString("version"),
                definitionHash = rs.getString("definition_hash"),
                definition = deserializeMap(rs.getString("definition_json")),
            )
        }
        return rows.firstOrNull()
    }

    override fun listDefinitions(scope: TenantScope): List<WorkflowDefinitionRecord> =
        jdbc.query(
            """
            SELECT workflow_type, version, definition_hash, definition_json
              FROM workflow_definitions
             WHERE organization_id = :organizationId
             ORDER BY workflow_type ASC, version ASC
            """.trimIndent(),
            scopeParams(scope),
        ) { rs, _ ->
            WorkflowDefinitionRecord(
                workflowType = rs.getString("workflow_type"),
                version = rs.getString("version"),
                definitionHash = rs.getString("definition_hash"),
                definition = deserializeMap(rs.getString("definition_json")),
            )
        }

    override fun saveDefinition(scope: TenantScope, record: WorkflowDefinitionRecord) {
        jdbc.update(
            """
            INSERT INTO workflow_definitions (
                organization_id, workstream_id, workflow_type, version, definition_hash, definition_json
            ) VALUES (
                :organizationId, :workstreamId, :workflowType, :version, :definitionHash, CAST(:definitionJson AS jsonb)
            )
            ON CONFLICT (organization_id, workflow_type, version) DO UPDATE
               SET definition_hash = EXCLUDED.definition_hash,
                   definition_json = EXCLUDED.definition_json
            """.trimIndent(),
            scopeParams(scope)
                .addValue("workflowType", record.workflowType)
                .addValue("version", record.version)
                .addValue("definitionHash", record.definitionHash)
                .addValue("definitionJson", serialize(record.definition)),
        )
    }

    override fun deleteDefinition(scope: TenantScope, workflowType: String, version: String): Boolean {
        // Mirrors the definition primary key `(organization_id, workflow_type, version)`
        // (saveDefinition upserts on the same key), so the row a list/get shows is
        // always the one deleted regardless of the writing workstream.
        val rows = jdbc.update(
            """
            DELETE FROM workflow_definitions
             WHERE organization_id = :organizationId
               AND workflow_type = :workflowType
               AND version = :version
            """.trimIndent(),
            scopeParams(scope)
                .addValue("workflowType", workflowType)
                .addValue("version", version),
        )
        return rows > 0
    }

    // ------------------------------------------------------------------
    // Governed instances
    // ------------------------------------------------------------------

    override fun findInstance(scope: TenantScope, namespaceId: String, workflowId: String): WorkflowInstanceRecord? {
        val rows = jdbc.query(
            """
            SELECT namespace_id, workflow_id, revision, status, creation_command_hash, instance_json, projection_json
              FROM workflow_instances
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId
            """.trimIndent(),
            instanceParams(scope, namespaceId, workflowId),
        ) { rs, _ -> readInstance(rs) }
        return rows.firstOrNull()
    }

    override fun listInstances(scope: TenantScope, namespaceId: String): List<WorkflowInstanceRecord> =
        jdbc.query(
            """
            SELECT namespace_id, workflow_id, revision, status, creation_command_hash, instance_json, projection_json
              FROM workflow_instances
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND status = 'active'
             ORDER BY workflow_id ASC
            """.trimIndent(),
            scopeParams(scope).addValue("namespaceId", namespaceId),
        ) { rs, _ -> readInstance(rs) }

    override fun insertInstance(scope: TenantScope, record: WorkflowInstanceRecord): WorkflowInstanceRecord {
        val existingParams = instanceParams(scope, record.namespaceId, record.workflowId)
        val update = jdbc.update(
            """
            INSERT INTO workflow_instances (
                organization_id, workstream_id, namespace_id, workflow_id, revision, status,
                instance_json, projection_json, creation_command_hash
            ) VALUES (
                :organizationId, :workstreamId, :namespaceId, :workflowId, :revision, :status,
                CAST(:instanceJson AS jsonb), CAST(:projectionJson AS jsonb), :commandHash
            )
            ON CONFLICT (organization_id, workstream_id, namespace_id, workflow_id) DO NOTHING
            """.trimIndent(),
            existingParams
                .addValue("revision", record.revision)
                .addValue("status", record.status)
                .addValue("instanceJson", serialize(record.instance))
                .addValue("projectionJson", serialize(record.projection))
                .addValue("commandHash", record.creationCommandHash),
        )
        if (update == 0) {
            val current = findInstance(scope, record.namespaceId, record.workflowId)
                ?: throw IllegalStateException("workflow instance insert conflicted but row is absent")
            if (current.creationCommandHash != record.creationCommandHash) {
                throw io.whozoss.factory.workflow.domain.workflowException(
                    io.whozoss.factory.workflow.domain.WorkflowErrorCodes.WORKFLOW_IDENTITY_CONFLICT,
                )
            }
            return current
        }
        return record
    }

    override fun updateInstance(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        expectedRevision: Int,
        next: WorkflowInstanceRecord,
    ): Boolean {
        val rows = jdbc.update(
            """
            UPDATE workflow_instances
               SET revision = :revision,
                   status = :status,
                   instance_json = CAST(:instanceJson AS jsonb),
                   projection_json = CAST(:projectionJson AS jsonb)
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId
               AND revision = :expectedRevision AND status = 'active'
            """.trimIndent(),
            instanceParams(scope, namespaceId, workflowId)
                .addValue("revision", next.revision)
                .addValue("status", next.status)
                .addValue("instanceJson", serialize(next.instance))
                .addValue("projectionJson", serialize(next.projection))
                .addValue("expectedRevision", expectedRevision),
        )
        return rows > 0
    }

    override fun setInstanceStatus(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        from: String,
        to: String,
    ): Boolean {
        val rows = jdbc.update(
            """
            UPDATE workflow_instances
               SET status = :toStatus
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId AND status = :fromStatus
            """.trimIndent(),
            instanceParams(scope, namespaceId, workflowId)
                .addValue("fromStatus", from)
                .addValue("toStatus", to),
        )
        return rows > 0
    }

    override fun deleteInstance(scope: TenantScope, namespaceId: String, workflowId: String): Boolean {
        val rows = jdbc.update(
            """
            DELETE FROM workflow_instances
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId
            """.trimIndent(),
            instanceParams(scope, namespaceId, workflowId),
        )
        return rows > 0
    }

    private fun readInstance(rs: java.sql.ResultSet): WorkflowInstanceRecord = WorkflowInstanceRecord(
        namespaceId = rs.getString("namespace_id"),
        workflowId = rs.getString("workflow_id"),
        revision = rs.getInt("revision"),
        status = rs.getString("status"),
        creationCommandHash = rs.getString("creation_command_hash"),
        instance = deserializeMap(rs.getString("instance_json")),
        projection = deserializeMap(rs.getString("projection_json")),
    )

    // ------------------------------------------------------------------
    // Transition logs
    // ------------------------------------------------------------------

    override fun appendTransition(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        transitionId: String,
        request: WorkflowTransitionRequest,
        fromStepId: String?,
        toStepId: String,
        payload: Map<String, Any?>,
    ) {
        jdbc.update(
            """
            INSERT INTO workflow_transitions (
                organization_id, workstream_id, namespace_id, workflow_id, transition_id,
                from_step_id, to_step_id, event_name, payload
            ) VALUES (
                :organizationId, :workstreamId, :namespaceId, :workflowId, :transitionId,
                :fromStepId, :toStepId, :eventName, CAST(:payload AS jsonb)
            )
            ON CONFLICT (organization_id, workstream_id, namespace_id, workflow_id, transition_id) DO NOTHING
            """.trimIndent(),
            instanceParams(scope, namespaceId, workflowId)
                .addValue("transitionId", transitionId)
                .addValue("fromStepId", fromStepId)
                .addValue("toStepId", toStepId)
                .addValue("eventName", request.requestedStatus)
                .addValue("payload", serialize(payload)),
        )
    }

    override fun listTransitionTimestamps(scope: TenantScope, namespaceId: String, workflowId: String): List<String> =
        jdbc.query(
            """
            SELECT created_at
              FROM workflow_transitions
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId
             ORDER BY created_at ASC
            """.trimIndent(),
            instanceParams(scope, namespaceId, workflowId),
        ) { rs, _ -> rs.getTimestamp("created_at").toInstant().toString() }

    override fun appendCodeTransition(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        record: WorkflowCodeTransitionRecord,
    ) {
        jdbc.update(
            """
            INSERT INTO workflow_code_transitions (
                organization_id, workstream_id, namespace_id, workflow_id, code_transition_id,
                step_id, outcome, exit_code, payload
            ) VALUES (
                :organizationId, :workstreamId, :namespaceId, :workflowId, :codeTransitionId,
                :stepId, :outcome, :exitCode, CAST(:payload AS jsonb)
            )
            ON CONFLICT (organization_id, workstream_id, namespace_id, workflow_id, code_transition_id) DO NOTHING
            """.trimIndent(),
            instanceParams(scope, namespaceId, workflowId)
                .addValue("codeTransitionId", record.codeTransitionId)
                .addValue("stepId", record.stepId)
                .addValue("outcome", record.outcome)
                .addValue("exitCode", record.exitCode)
                .addValue("payload", serialize(record.payload)),
        )
    }

    override fun listCodeTransitions(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
    ): List<WorkflowCodeTransitionRecord> =
        jdbc.query(
            """
            SELECT code_transition_id, step_id, outcome, exit_code, payload, created_at
              FROM workflow_code_transitions
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId
             ORDER BY created_at ASC
            """.trimIndent(),
            instanceParams(scope, namespaceId, workflowId),
        ) { rs, _ ->
            WorkflowCodeTransitionRecord(
                codeTransitionId = rs.getString("code_transition_id"),
                stepId = rs.getString("step_id"),
                outcome = rs.getString("outcome"),
                exitCode = rs.getObject("exit_code") as? Int,
                payload = deserializeMap(rs.getString("payload")),
                createdAt = rs.getTimestamp("created_at").toInstant().toString(),
            )
        }

    // ------------------------------------------------------------------
    // Per-step DAG state
    // ------------------------------------------------------------------

    override fun findStepStates(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
    ): List<WorkflowStepStateRecord> =
        jdbc.query(
            """
            SELECT namespace_id, workflow_id, step_id, revision, status, payload, created_at, updated_at
              FROM workflow_step_states
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId
             ORDER BY created_at ASC, step_id ASC
            """.trimIndent(),
            instanceParams(scope, namespaceId, workflowId),
        ) { rs, _ -> readStepState(rs) }

    override fun upsertStepState(scope: TenantScope, record: WorkflowStepStateRecord): WorkflowStepStateRecord {
        jdbc.update(
            """
            INSERT INTO workflow_step_states (
                organization_id, workstream_id, namespace_id, workflow_id, step_id, revision, status, payload
            ) VALUES (
                :organizationId, :workstreamId, :namespaceId, :workflowId, :stepId, 1, :status, CAST(:payload AS jsonb)
            )
            ON CONFLICT (organization_id, workstream_id, namespace_id, workflow_id, step_id) DO UPDATE
               SET status = EXCLUDED.status,
                   payload = EXCLUDED.payload,
                   revision = workflow_step_states.revision + 1,
                   updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
            instanceParams(scope, record.namespaceId, record.workflowId)
                .addValue("stepId", record.stepId)
                .addValue("status", record.status)
                .addValue("payload", serialize(record.payload)),
        )
        return jdbc.query(
            """
            SELECT namespace_id, workflow_id, step_id, revision, status, payload, created_at, updated_at
              FROM workflow_step_states
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId AND step_id = :stepId
            """.trimIndent(),
            instanceParams(scope, record.namespaceId, record.workflowId).addValue("stepId", record.stepId),
        ) { rs, _ -> readStepState(rs) }.firstOrNull() ?: record
    }

    override fun updateStepStatus(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        expectedRevision: Int,
        nextStatus: String,
        payload: Map<String, Any?>,
    ): Boolean {
        val rows = jdbc.update(
            """
            UPDATE workflow_step_states
               SET status = :status,
                   payload = CAST(:payload AS jsonb),
                   revision = revision + 1,
                   updated_at = CURRENT_TIMESTAMP
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId
               AND step_id = :stepId AND revision = :expectedRevision
            """.trimIndent(),
            instanceParams(scope, namespaceId, workflowId)
                .addValue("stepId", stepId)
                .addValue("expectedRevision", expectedRevision)
                .addValue("status", nextStatus)
                .addValue("payload", serialize(payload)),
        )
        return rows > 0
    }

    private fun readStepState(rs: java.sql.ResultSet): WorkflowStepStateRecord = WorkflowStepStateRecord(
        namespaceId = rs.getString("namespace_id"),
        workflowId = rs.getString("workflow_id"),
        stepId = rs.getString("step_id"),
        revision = rs.getInt("revision"),
        status = rs.getString("status"),
        payload = deserializeMap(rs.getString("payload")),
        createdAt = rs.getTimestamp("created_at")?.toInstant()?.toString(),
        updatedAt = rs.getTimestamp("updated_at")?.toInstant()?.toString(),
    )

    // ------------------------------------------------------------------
    // Declarative projection store
    // ------------------------------------------------------------------

    override fun findProjection(scope: TenantScope, namespaceId: String, workflowId: String): WorkflowProjectionRecord? =
        readProjection(scope, namespaceId, workflowId, forUpdate = false)

    private fun readProjection(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        forUpdate: Boolean,
    ): WorkflowProjectionRecord? {
        val sql = buildString {
            append(
                """
                SELECT namespace_id, workflow_id, schema_version, revision, projection_hash, status,
                       projection_json, instance_json, governance_mode, definition_version, definition_hash,
                       relations_json, controller_execution, lifecycle_state
                  FROM workflow_projections
                 WHERE organization_id = :organizationId AND workstream_id = :workstreamId
                   AND namespace_id = :namespaceId AND workflow_id = :workflowId
                """.trimIndent(),
            )
            if (forUpdate) append(" FOR UPDATE")
        }
        return jdbc.query(sql, instanceParams(scope, namespaceId, workflowId)) { rs, _ -> readProjection(rs) }.firstOrNull()
    }

    private fun readProjection(rs: java.sql.ResultSet): WorkflowProjectionRecord = WorkflowProjectionRecord(
        namespaceId = rs.getString("namespace_id"),
        workflowId = rs.getString("workflow_id"),
        schemaVersion = rs.getString("schema_version"),
        revision = rs.getInt("revision"),
        projectionHash = rs.getString("projection_hash"),
        status = rs.getString("status"),
        projection = deserializeMap(rs.getString("projection_json")),
        instance = deserializeOptional(rs.getString("instance_json")),
        governanceMode = rs.getString("governance_mode"),
        definitionVersion = rs.getString("definition_version"),
        definitionHash = rs.getString("definition_hash"),
        relations = deserializeOptional(rs.getString("relations_json")),
        controllerExecution = deserializeOptional(rs.getString("controller_execution")),
        lifecycleState = rs.getString("lifecycle_state"),
    )

    override fun listProjections(
        scope: TenantScope,
        namespaceId: String?,
        lifecycleState: String,
    ): List<WorkflowProjectionRecord> {
        val namespaceFilter = if (namespaceId.isNullOrBlank()) "" else " AND namespace_id = :namespaceId"
        val params = scopeParams(scope).addValue("lifecycleState", lifecycleState)
        if (!namespaceId.isNullOrBlank()) params.addValue("namespaceId", namespaceId)
        return jdbc.query(
            """
            SELECT namespace_id, workflow_id, schema_version, revision, projection_hash, status,
                   projection_json, instance_json, governance_mode, definition_version, definition_hash,
                   relations_json, controller_execution, lifecycle_state
              FROM workflow_projections
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId$namespaceFilter
               AND lifecycle_state = :lifecycleState
             ORDER BY workflow_id ASC
            """.trimIndent(),
            params,
        ) { rs, _ -> readProjection(rs) }
    }

    override fun publishProjection(
        scope: TenantScope,
        record: WorkflowProjectionRecord,
        expectedRevision: Int?,
    ): ProjectionPublishResult {
        val current = readProjection(scope, record.namespaceId, record.workflowId, forUpdate = true)
        if (current == null) {
            if (expectedRevision != null && expectedRevision != 0) {
                return ProjectionPublishResult.Conflict(io.whozoss.factory.workflow.domain.WorkflowErrorCodes.REVISION_CONFLICT)
            }
            jdbc.update(
                """
                INSERT INTO workflow_projections (
                    organization_id, workstream_id, namespace_id, workflow_id, schema_version, revision,
                    projection_hash, status, projection_json, instance_json, governance_mode,
                    definition_version, definition_hash, relations_json, controller_execution, lifecycle_state
                ) VALUES (
                    :organizationId, :workstreamId, :namespaceId, :workflowId, :schemaVersion, 1,
                    :projectionHash, :status, CAST(:projectionJson AS jsonb), CAST(:instanceJson AS jsonb),
                    :governanceMode, :definitionVersion, :definitionHash, CAST(:relationsJson AS jsonb),
                    CAST(:controllerExecution AS jsonb), 'active'
                )
                """.trimIndent(),
                projectionParams(scope, record),
            )
            return ProjectionPublishResult.Changed(record.copy(revision = 1, lifecycleState = "active"))
        }
        if (current.lifecycleState == "purged") {
            return ProjectionPublishResult.Conflict(io.whozoss.factory.workflow.domain.WorkflowErrorCodes.WORKFLOW_PURGED)
        }
        if (expectedRevision != null && expectedRevision != current.revision) {
            return ProjectionPublishResult.Conflict(io.whozoss.factory.workflow.domain.WorkflowErrorCodes.REVISION_CONFLICT)
        }
        if (current.projectionHash == record.projectionHash && current.status == record.status) {
            return ProjectionPublishResult.Idempotent(current)
        }
        val nextRevision = current.revision + 1
        jdbc.update(
            """
            UPDATE workflow_projections
               SET schema_version = :schemaVersion,
                   revision = :revision,
                   projection_hash = :projectionHash,
                   status = :status,
                   projection_json = CAST(:projectionJson AS jsonb),
                   instance_json = CAST(:instanceJson AS jsonb),
                   governance_mode = :governanceMode,
                   definition_version = :definitionVersion,
                   definition_hash = :definitionHash,
                   relations_json = CAST(:relationsJson AS jsonb),
                   controller_execution = CAST(:controllerExecution AS jsonb)
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId AND revision = :currentRevision
            """.trimIndent(),
            projectionParams(scope, record)
                .addValue("revision", nextRevision)
                .addValue("currentRevision", current.revision),
        )
        return ProjectionPublishResult.Changed(record.copy(revision = nextRevision))
    }

    override fun setProjectionLifecycle(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        from: List<String>,
        to: String,
    ): Boolean {
        val rows = jdbc.update(
            """
            UPDATE workflow_projections
               SET lifecycle_state = :toState
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId
               AND lifecycle_state IN (:fromStates)
            """.trimIndent(),
            instanceParams(scope, namespaceId, workflowId)
                .addValue("toState", to)
                .addValue("fromStates", from),
        )
        return rows > 0
    }

    override fun deleteProjectionLifecycle(scope: TenantScope, namespaceId: String, workflowId: String): Boolean {
        val rows = jdbc.update(
            """
            DELETE FROM workflow_projections
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId
            """.trimIndent(),
            instanceParams(scope, namespaceId, workflowId),
        )
        return rows > 0
    }

    // ------------------------------------------------------------------
    // Evidence (append-only)
    // ------------------------------------------------------------------

    override fun list(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String?,
    ): List<WorkflowEvidenceItem> {
        val sql = StringBuilder(
            """
            SELECT evidence_id, namespace_id, workflow_id, evidence_type, source, producer, payload, created_at
              FROM workflow_evidence
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId
            """.trimIndent(),
        )
        val params = instanceParams(scope, namespaceId, workflowId)
        if (stepId != null) {
            sql.append(" AND payload->>'stepId' = :stepId")
            params.addValue("stepId", stepId)
        }
        sql.append(" ORDER BY created_at ASC")
        return jdbc.query(sql.toString(), params) { rs, _ -> readEvidence(rs) }
    }

    override fun append(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        item: WorkflowEvidenceItem,
    ): EvidenceAppendResult {
        if (item.idempotencyKey != null) {
            val existing = jdbc.query(
                """
                SELECT evidence_id, namespace_id, workflow_id, evidence_type, source, producer, payload, created_at
                  FROM workflow_evidence
                 WHERE organization_id = :organizationId AND workstream_id = :workstreamId
                   AND namespace_id = :namespaceId AND workflow_id = :workflowId
                   AND payload->>'idempotencyKey' = :idempotencyKey
                """.trimIndent(),
                instanceParams(scope, namespaceId, workflowId).addValue("idempotencyKey", item.idempotencyKey),
            ) { rs, _ -> readEvidence(rs) }.firstOrNull()
            if (existing != null) {
                return if (existing.evidenceId == item.evidenceId) {
                    EvidenceAppendResult.Idempotent(existing)
                } else {
                    EvidenceAppendResult.Collision(io.whozoss.factory.workflow.domain.WorkflowErrorCodes.IDEMPOTENCY_KEY_COLLISION)
                }
            }
        }
        val rows = jdbc.update(
            """
            INSERT INTO workflow_evidence (
                organization_id, workstream_id, namespace_id, workflow_id, evidence_id,
                evidence_type, source, producer, payload
            ) VALUES (
                :organizationId, :workstreamId, :namespaceId, :workflowId, :evidenceId,
                :evidenceType, :source, :producer, CAST(:payload AS jsonb)
            )
            ON CONFLICT (organization_id, workstream_id, namespace_id, workflow_id, evidence_id) DO NOTHING
            """.trimIndent(),
            evidenceParams(scope, namespaceId, workflowId, item),
        )
        return if (rows == 0) EvidenceAppendResult.Idempotent(item) else EvidenceAppendResult.Created(item)
    }

    private fun readEvidence(rs: java.sql.ResultSet): WorkflowEvidenceItem {
        val payload = deserializeMap(rs.getString("payload"))
        return WorkflowEvidenceItem(
            evidenceId = rs.getString("evidence_id"),
            namespaceId = rs.getString("namespace_id"),
            workflowId = rs.getString("workflow_id"),
            stepId = payload["stepId"] as? String,
            kind = rs.getString("evidence_type"),
            outcome = payload["outcome"] as? String,
            source = (payload["source"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value },
            facts = (payload["facts"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap(),
            idempotencyKey = payload["idempotencyKey"] as? String,
            createdAt = rs.getTimestamp("created_at").toInstant().toString(),
        )
    }

    // ------------------------------------------------------------------
    // Human interactions
    // ------------------------------------------------------------------

    override fun find(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        interactionId: String,
    ): HumanInteractionRecord? =
        jdbc.query(
            """
            SELECT interaction_id, namespace_id, workflow_id, interaction_type, status, revision, payload
              FROM human_interactions
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId AND interaction_id = :interactionId
            """.trimIndent(),
            interactionParams(scope, namespaceId, workflowId).addValue("interactionId", interactionId),
        ) { rs, _ -> readInteraction(rs) }.firstOrNull()

    override fun list(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        openOnly: Boolean,
    ): List<HumanInteractionRecord> {
        val sql = StringBuilder(
            """
            SELECT interaction_id, namespace_id, workflow_id, interaction_type, status, revision, payload
              FROM human_interactions
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId
            """.trimIndent(),
        )
        if (openOnly) sql.append(" AND status = 'waiting'")
        sql.append(" ORDER BY created_at ASC")
        return jdbc.query(sql.toString(), instanceParams(scope, namespaceId, workflowId)) { rs, _ -> readInteraction(rs) }
    }

    override fun insert(scope: TenantScope, record: HumanInteractionRecord): HumanInteractionRecord {
        jdbc.update(
            """
            INSERT INTO human_interactions (
                organization_id, workstream_id, namespace_id, workflow_id, interaction_id,
                interaction_type, status, revision, payload
            ) VALUES (
                :organizationId, :workstreamId, :namespaceId, :workflowId, :interactionId,
                :interactionType, :status, :revision, CAST(:payload AS jsonb)
            )
            ON CONFLICT (organization_id, workstream_id, namespace_id, workflow_id, interaction_id) DO NOTHING
            """.trimIndent(),
            interactionParams(scope, record.namespaceId, record.workflowId)
                .addValue("interactionId", record.interactionId)
                .addValue("interactionType", record.interactionType)
                .addValue("status", record.status)
                .addValue("revision", record.revision)
                .addValue("payload", serialize(record.payload)),
        )
        return find(scope, record.namespaceId, record.workflowId, record.interactionId) ?: record
    }

    override fun update(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        interactionId: String,
        expectedRevision: Int,
        next: HumanInteractionRecord,
    ): Boolean {
        val rows = jdbc.update(
            """
            UPDATE human_interactions
               SET status = :status, revision = :revision, payload = CAST(:payload AS jsonb)
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId
               AND interaction_id = :interactionId AND revision = :expectedRevision
            """.trimIndent(),
            interactionParams(scope, namespaceId, workflowId)
                .addValue("interactionId", interactionId)
                .addValue("status", next.status)
                .addValue("revision", next.revision)
                .addValue("payload", serialize(next.payload))
                .addValue("expectedRevision", expectedRevision),
        )
        return rows > 0
    }

    override fun appendEvent(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        event: HumanInteractionEventRecord,
    ) {
        jdbc.update(
            """
            INSERT INTO human_interaction_events (
                organization_id, workstream_id, namespace_id, workflow_id, interaction_id,
                event_id, event_type, actor_id, payload
            ) VALUES (
                :organizationId, :workstreamId, :namespaceId, :workflowId, :interactionId,
                :eventId, :eventType, :actorId, CAST(:payload AS jsonb)
            )
            ON CONFLICT (organization_id, workstream_id, namespace_id, workflow_id, interaction_id, event_id)
            DO NOTHING
            """.trimIndent(),
            interactionParams(scope, namespaceId, workflowId)
                .addValue("interactionId", event.interactionId)
                .addValue("eventId", event.eventId)
                .addValue("eventType", event.eventType)
                .addValue("actorId", event.actorId)
                .addValue("payload", serialize(event.payload)),
        )
    }

    override fun listEvents(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
    ): List<HumanInteractionEventRecord> =
        jdbc.query(
            """
            SELECT interaction_id, event_id, event_type, actor_id, payload
              FROM human_interaction_events
             WHERE organization_id = :organizationId AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId AND workflow_id = :workflowId
             ORDER BY created_at ASC
            """.trimIndent(),
            instanceParams(scope, namespaceId, workflowId),
        ) { rs, _ ->
            HumanInteractionEventRecord(
                eventId = rs.getString("event_id"),
                interactionId = rs.getString("interaction_id"),
                eventType = rs.getString("event_type"),
                actorId = rs.getString("actor_id"),
                payload = deserializeMap(rs.getString("payload")),
            )
        }

    private fun readInteraction(rs: java.sql.ResultSet): HumanInteractionRecord {
        val payload = deserializeMap(rs.getString("payload"))
        return HumanInteractionRecord(
            interactionId = rs.getString("interaction_id"),
            namespaceId = rs.getString("namespace_id"),
            workflowId = rs.getString("workflow_id"),
            stepId = payload["stepId"] as? String ?: "",
            interactionType = rs.getString("interaction_type"),
            status = rs.getString("status"),
            revision = rs.getInt("revision"),
            payload = payload,
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun scopeParams(scope: TenantScope): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("organizationId", scope.organizationId)
            .addValue("workstreamId", scope.workstreamId)

    private fun instanceParams(scope: TenantScope, namespaceId: String, workflowId: String): MapSqlParameterSource =
        scopeParams(scope).addValue("namespaceId", namespaceId).addValue("workflowId", workflowId)

    private fun interactionParams(scope: TenantScope, namespaceId: String, workflowId: String): MapSqlParameterSource =
        instanceParams(scope, namespaceId, workflowId)

    private fun evidenceParams(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        item: WorkflowEvidenceItem,
    ): MapSqlParameterSource {
        val payload = LinkedHashMap<String, Any?>()
        if (item.stepId != null) payload["stepId"] = item.stepId
        payload["kind"] = item.kind
        if (item.outcome != null) payload["outcome"] = item.outcome
        if (item.source != null) payload["source"] = item.source
        payload["facts"] = item.facts
        if (item.idempotencyKey != null) payload["idempotencyKey"] = item.idempotencyKey
        val source = item.source?.get("kind") as? String ?: "factory-control-plane"
        val producer = item.source?.get("agentId") as? String
            ?: item.source?.get("actorId") as? String
            ?: "factory"
        return instanceParams(scope, namespaceId, workflowId)
            .addValue("evidenceId", item.evidenceId)
            .addValue("evidenceType", item.kind)
            .addValue("source", source)
            .addValue("producer", producer)
            .addValue("payload", serialize(payload))
    }

    private fun projectionParams(scope: TenantScope, record: WorkflowProjectionRecord): MapSqlParameterSource =
        instanceParams(scope, record.namespaceId, record.workflowId)
            .addValue("schemaVersion", record.schemaVersion)
            .addValue("projectionHash", record.projectionHash)
            .addValue("status", record.status)
            .addValue("projectionJson", serialize(record.projection))
            .addValue("instanceJson", serializeNullable(record.instance))
            .addValue("governanceMode", record.governanceMode)
            .addValue("definitionVersion", record.definitionVersion)
            .addValue("definitionHash", record.definitionHash)
            .addValue("relationsJson", serializeNullable(record.relations))
            .addValue("controllerExecution", serializeNullable(record.controllerExecution))

    private fun serialize(value: Any?): String = objectMapper.writeValueAsString(value)

    /** Serializes a nullable JSONB column, using the JSON literal `null` so the parameter is never SQL NULL. */
    private fun serializeNullable(value: Any?): String = if (value == null) "null" else serialize(value)

    @Suppress("UNCHECKED_CAST")
    private fun deserializeMap(json: String?): Map<String, Any?> =
        if (json.isNullOrBlank()) emptyMap() else objectMapper.readValue(json, Map::class.java) as Map<String, Any?>

    /** Reads an optional JSONB column; a JSON `null` document maps to Kotlin `null`. */
    @Suppress("UNCHECKED_CAST")
    private fun deserializeOptional(json: String?): Map<String, Any?>? {
        if (json.isNullOrBlank() || json == "null") return null
        return objectMapper.readValue(json, Map::class.java) as Map<String, Any?>?
    }
}
