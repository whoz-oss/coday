package io.whozoss.factory.environment.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.whozoss.factory.environment.domain.EnvironmentErrorCodes
import io.whozoss.factory.environment.domain.EnvironmentNotFoundException
import io.whozoss.factory.environment.domain.InvalidEnvironmentStateException
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.persistence.TenantScope
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * `NamedParameterJdbcTemplate` implementation of [WorkEnvironmentRepository].
 *
 * Ported from
 * `factory/src/adapters/persistence/sql/sql-work-environment-repository.ts`. The
 * V6 `work_environments` table carries the reserved environment identity, the
 * lifecycle status (`provisioning` / `ready` / `busy` / `decommissioned`) and
 * the optimistic-locking `revision`, while the whole descriptor is kept verbatim
 * in the JSONB `payload`.
 */
@Repository
class JdbcWorkEnvironmentRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : WorkEnvironmentRepository {

    override fun insert(scope: TenantScope, environment: WorkEnvironment): WorkEnvironment {
        jdbc.update(
            """
            INSERT INTO work_environments (
                organization_id, workstream_id, environment_id, env_type, status, revision, payload,
                created_at, updated_at
            ) VALUES (
                :organizationId, :workstreamId, :environmentId, :envType, :status, :revision,
                CAST(:payload AS jsonb), :createdAt, :updatedAt
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("environmentId", environment.environmentId)
                .addValue("envType", environment.envType)
                .addValue("status", environment.lifecycleState.dbValue)
                .addValue("revision", environment.revision)
                .addValue("payload", serialize(environment))
                .addValue("createdAt", Timestamp.from(environment.createdAt))
                .addValue("updatedAt", Timestamp.from(environment.createdAt)),
        )
        return findByEnvironmentId(scope, environment.environmentId)
            ?: throw IllegalStateException("Environment '${environment.environmentId}' vanished after insert")
    }

    override fun findByEnvironmentId(scope: TenantScope, environmentId: String): WorkEnvironment? =
        jdbc.query(
            "$SELECT_COLUMNS WHERE organization_id = :organizationId AND workstream_id = :workstreamId " +
                "AND environment_id = :environmentId",
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("environmentId", environmentId),
            rowMapper,
        ).firstOrNull()

    override fun findLatestByWorkflowId(scope: TenantScope, workflowId: String): WorkEnvironment? =
        jdbc.query(
            """
            $SELECT_COLUMNS
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND payload->>'workflowId' = :workflowId
             ORDER BY updated_at DESC
             LIMIT 1
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("workflowId", workflowId),
            rowMapper,
        ).firstOrNull()

    override fun list(scope: TenantScope): List<WorkEnvironment> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE organization_id = :organizationId AND workstream_id = :workstreamId " +
                "ORDER BY environment_id ASC",
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId),
            rowMapper,
        )

    override fun updateState(
        scope: TenantScope,
        environment: WorkEnvironment,
        expectedRevision: Int,
        updatedAt: Instant,
    ): WorkEnvironment {
        val updated = jdbc.update(
            """
            UPDATE work_environments
               SET status = :status,
                   revision = revision + 1,
                   payload = CAST(:payload AS jsonb),
                   updated_at = :updatedAt
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND environment_id = :environmentId
               AND revision = :expectedRevision
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("status", environment.lifecycleState.dbValue)
                .addValue("payload", serialize(environment))
                .addValue("updatedAt", Timestamp.from(updatedAt))
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("environmentId", environment.environmentId)
                .addValue("expectedRevision", expectedRevision),
        )
        if (updated == 0) {
            val existing = findByEnvironmentId(scope, environment.environmentId)
            if (existing != null) {
                throw InvalidEnvironmentStateException(
                    "Revision conflict for environment '${environment.environmentId}': expected revision " +
                        "$expectedRevision, found ${existing.revision}",
                    details = mapOf("errorCode" to EnvironmentErrorCodes.INVALID_ENVIRONMENT_STATE),
                )
            }
            throw EnvironmentNotFoundException("Environment '${environment.environmentId}' not found")
        }
        return findByEnvironmentId(scope, environment.environmentId)
            ?: throw IllegalStateException("Environment '${environment.environmentId}' vanished after update")
    }

    private fun serialize(environment: WorkEnvironment): String = objectMapper.writeValueAsString(environment)

    private val rowMapper = RowMapper { rs: ResultSet, _: Int -> mapRow(rs) }

    private fun mapRow(rs: ResultSet): WorkEnvironment {
        val payload = rs.getString("payload") ?: "{}"
        val parsed = objectMapper.readValue<WorkEnvironment>(payload)
        // The column is authoritative for the revision and the mapped status.
        return parsed.copy(
            revision = rs.getInt("revision"),
            lifecycleState = WorkEnvironmentState.fromDbValue(rs.getString("status")),
        )
    }

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT organization_id, workstream_id, environment_id, env_type, status, revision, payload,
                   created_at, updated_at
              FROM work_environments
        """
    }
}
