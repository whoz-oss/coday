package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.persistence.TenantScope
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * `NamedParameterJdbcTemplate` implementation of [AgentStepAttemptRepository].
 *
 * Every statement is parameterized and constrained to the supplied [TenantScope]
 * `(organization_id, workstream_id)`. The attempt is the mutable root of the
 * V6 AGENT-STEP aggregate.
 */
@Repository
class JdbcAgentStepAttemptRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : AgentStepAttemptRepository {

    override fun insert(scope: TenantScope, attempt: AgentStepAttemptRecord) {
        jdbc.update(
            """
            INSERT INTO agent_step_attempts (
                organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id,
                agent_id, status, revision, payload
            ) VALUES (
                :organizationId, :workstreamId, :namespaceId, :workflowId, :stepId, :attemptId,
                :agentId, :status, :revision, CAST(:payload AS jsonb)
            )
            """.trimIndent(),
            params(scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId)
                .addValue("agentId", attempt.agentId)
                .addValue("status", attempt.status)
                .addValue("revision", attempt.revision)
                .addValue("payload", attempt.payload),
        )
    }

    override fun exists(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): Boolean =
        (jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM agent_step_attempts
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId
               AND workflow_id = :workflowId
               AND step_id = :stepId
               AND attempt_id = :attemptId
            """.trimIndent(),
            params(scope, namespaceId, workflowId, stepId, attemptId),
            Long::class.javaObjectType,
        ) ?: 0L) > 0L

    override fun find(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): AgentStepAttemptRecord? =
        jdbc.query(
            """
            SELECT namespace_id, workflow_id, step_id, attempt_id, agent_id, status, revision, payload
              FROM agent_step_attempts
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId
               AND workflow_id = :workflowId
               AND step_id = :stepId
               AND attempt_id = :attemptId
            """.trimIndent(),
            params(scope, namespaceId, workflowId, stepId, attemptId),
        ) { rs, _ ->
            AgentStepAttemptRecord(
                namespaceId = rs.getString("namespace_id"),
                workflowId = rs.getString("workflow_id"),
                stepId = rs.getString("step_id"),
                attemptId = rs.getString("attempt_id"),
                agentId = rs.getString("agent_id"),
                status = rs.getString("status"),
                revision = rs.getInt("revision"),
                payload = rs.getString("payload") ?: "{}",
            )
        }.firstOrNull()

    override fun terminalize(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        status: String,
    ): Int =
        jdbc.update(
            """
            UPDATE agent_step_attempts
               SET status = :status,
                   revision = revision + 1,
                   updated_at = CURRENT_TIMESTAMP
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId
               AND workflow_id = :workflowId
               AND step_id = :stepId
               AND attempt_id = :attemptId
            """.trimIndent(),
            params(scope, namespaceId, workflowId, stepId, attemptId).addValue("status", status),
        )

    private fun params(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("organizationId", scope.organizationId)
            .addValue("workstreamId", scope.workstreamId)
            .addValue("namespaceId", namespaceId)
            .addValue("workflowId", workflowId)
            .addValue("stepId", stepId)
            .addValue("attemptId", attemptId)
}
