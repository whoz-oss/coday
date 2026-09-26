package io.whozoss.factory.oracle.persistence

import io.whozoss.factory.error.ResourceNotFoundException
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.oracle.domain.OracleExecution
import io.whozoss.factory.oracle.domain.OracleExecutionKey
import io.whozoss.factory.oracle.domain.OracleExecutionStatus
import io.whozoss.factory.persistence.TenantScope
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

/**
 * `NamedParameterJdbcTemplate` implementation of [OracleExecutionRepository].
 *
 * Every statement is parameterized and constrained to the supplied
 * [TenantScope] `(organization_id, workstream_id)`; a scope-less statement is
 * impossible by construction. Optimistic locking is enforced in SQL with
 * `... AND revision = :expectedRevision`.
 */
@Repository
class JdbcOracleExecutionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : OracleExecutionRepository {

    override fun save(scope: TenantScope, execution: OracleExecution): OracleExecution {
        jdbc.update(
            """
            INSERT INTO oracle_executions (
                organization_id, workstream_id, namespace_id, workflow_id, execution_id,
                oracle_id, status, revision, evidence_id, artifact_id, payload
            ) VALUES (
                :organizationId, :workstreamId, :namespaceId, :workflowId, :executionId,
                :oracleId, :status, :revision, :evidenceId, :artifactId, CAST(:payload AS jsonb)
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("namespaceId", execution.namespaceId)
                .addValue("workflowId", execution.workflowId)
                .addValue("executionId", execution.executionId)
                .addValue("oracleId", execution.oracleId)
                .addValue("status", execution.status.dbValue)
                .addValue("revision", execution.revision)
                .addValue("evidenceId", execution.evidenceId)
                .addValue("artifactId", execution.artifactId)
                .addValue("payload", execution.payload),
        )
        return findByKey(scope, execution.key)
            ?: throw IllegalStateException("Oracle execution '${execution.executionId}' vanished after insert")
    }

    override fun updateStatus(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        executionId: String,
        status: OracleExecutionStatus,
        expectedRevision: Int,
        artifactId: String?,
        evidenceId: String?,
        payload: String?,
    ): OracleExecution {
        val updated = jdbc.update(
            """
            UPDATE oracle_executions
               SET status = :status,
                   revision = revision + 1,
                   evidence_id = COALESCE(:evidenceId, evidence_id),
                   artifact_id = COALESCE(:artifactId, artifact_id),
                   payload = COALESCE(CAST(:payload AS jsonb), payload)
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId
               AND workflow_id = :workflowId
               AND execution_id = :executionId
               AND revision = :expectedRevision
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("status", status.dbValue)
                .addValue("evidenceId", evidenceId)
                .addValue("artifactId", artifactId)
                .addValue("payload", payload)
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("namespaceId", namespaceId)
                .addValue("workflowId", workflowId)
                .addValue("executionId", executionId)
                .addValue("expectedRevision", expectedRevision),
        )

        val key = OracleExecutionKey(namespaceId, workflowId, executionId)
        if (updated == 0) {
            val existing = findByKey(scope, key)
            if (existing != null) {
                throw RevisionConflictException(
                    "Revision conflict for oracle execution '$executionId': expected revision $expectedRevision, " +
                        "found ${existing.revision}",
                )
            }
            throw ResourceNotFoundException("Oracle execution '$executionId' not found")
        }
        return findByKey(scope, key)
            ?: throw IllegalStateException("Oracle execution '$executionId' vanished after update")
    }

    override fun findById(scope: TenantScope, id: OracleExecutionKey): OracleExecution? = findByKey(scope, id)

    override fun findByKey(scope: TenantScope, key: OracleExecutionKey): OracleExecution? =
        jdbc.query(
            "$SELECT_COLUMNS WHERE organization_id = :organizationId AND workstream_id = :workstreamId " +
                "AND namespace_id = :namespaceId AND workflow_id = :workflowId AND execution_id = :executionId",
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("namespaceId", key.namespaceId)
                .addValue("workflowId", key.workflowId)
                .addValue("executionId", key.executionId),
            rowMapper,
        ).firstOrNull()

    override fun findByIdempotencyKey(scope: TenantScope, idempotencyKey: String): OracleExecution? =
        jdbc.query(
            "$SELECT_COLUMNS WHERE organization_id = :organizationId AND workstream_id = :workstreamId " +
                "AND payload->>'idempotencyKey' = :idempotencyKey ORDER BY created_at ASC LIMIT 1",
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("idempotencyKey", idempotencyKey),
            rowMapper,
        ).firstOrNull()

    override fun deleteById(scope: TenantScope, id: OracleExecutionKey): Boolean =
        jdbc.update(
            """
            DELETE FROM oracle_executions
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId
               AND workflow_id = :workflowId
               AND execution_id = :executionId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("namespaceId", id.namespaceId)
                .addValue("workflowId", id.workflowId)
                .addValue("executionId", id.executionId),
        ) > 0

    private val rowMapper = RowMapper { rs: ResultSet, _: Int -> mapRow(rs) }

    private fun mapRow(rs: ResultSet): OracleExecution = OracleExecution(
        organizationId = rs.getString("organization_id"),
        workstreamId = rs.getString("workstream_id"),
        namespaceId = rs.getString("namespace_id"),
        workflowId = rs.getString("workflow_id"),
        executionId = rs.getString("execution_id"),
        oracleId = rs.getString("oracle_id"),
        status = OracleExecutionStatus.fromDbValue(rs.getString("status")),
        revision = rs.getInt("revision"),
        evidenceId = rs.getString("evidence_id"),
        artifactId = rs.getString("artifact_id"),
        payload = rs.getString("payload"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT organization_id, workstream_id, namespace_id, workflow_id, execution_id,
                   oracle_id, status, revision, evidence_id, artifact_id, payload, created_at, updated_at
              FROM oracle_executions
        """
    }
}
