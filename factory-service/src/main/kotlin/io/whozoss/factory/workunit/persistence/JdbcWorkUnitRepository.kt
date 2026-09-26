package io.whozoss.factory.workunit.persistence

import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workunit.domain.WorkUnit
import io.whozoss.factory.workunit.domain.WorkUnitNotFoundException
import io.whozoss.factory.workunit.domain.WorkUnitState
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * `NamedParameterJdbcTemplate` implementation of [WorkUnitRepository].
 *
 * Ported from `factory/src/adapters/persistence/sql/sql-work-unit-repository.ts`.
 * Every statement is parameterized and constrained to the supplied [TenantScope]
 * `(organization_id, workstream_id)`; a scope-less statement is impossible by
 * construction.
 */
@Repository
class JdbcWorkUnitRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : WorkUnitRepository {

    override fun insert(scope: TenantScope, workUnit: WorkUnit): WorkUnit {
        jdbc.update(
            """
            INSERT INTO work_units (
                organization_id, workstream_id, work_unit_id, unit_type, status, revision,
                priority, not_before, attempt_count, payload, created_at, updated_at
            ) VALUES (
                :organizationId, :workstreamId, :workUnitId, :unitType, :status, :revision,
                :priority, :notBefore, :attemptCount, CAST(:payload AS jsonb), :createdAt, :updatedAt
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("workUnitId", workUnit.workUnitId)
                .addValue("unitType", workUnit.unitType)
                .addValue("status", workUnit.status.dbValue)
                .addValue("revision", workUnit.revision)
                .addValue("priority", workUnit.priority)
                .addValue("notBefore", workUnit.notBefore?.let { Timestamp.from(it) })
                .addValue("attemptCount", workUnit.attemptCount)
                .addValue("payload", workUnit.payload)
                .addValue("createdAt", Timestamp.from(workUnit.createdAt))
                .addValue("updatedAt", Timestamp.from(workUnit.updatedAt)),
        )
        return findById(scope, workUnit.workUnitId)
            ?: throw IllegalStateException("Work unit '${workUnit.workUnitId}' vanished after insert")
    }

    override fun findById(scope: TenantScope, workUnitId: String): WorkUnit? =
        jdbc.query(
            "$SELECT_COLUMNS WHERE organization_id = :organizationId AND workstream_id = :workstreamId " +
                "AND work_unit_id = :workUnitId",
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("workUnitId", workUnitId),
            rowMapper,
        ).firstOrNull()

    override fun list(scope: TenantScope, statuses: Set<WorkUnitState>?): List<WorkUnit> {
        val statusValues = statuses?.map { it.dbValue }
        val sql = buildString {
            append(SELECT_COLUMNS)
            append(" WHERE organization_id = :organizationId AND workstream_id = :workstreamId")
            if (!statusValues.isNullOrEmpty()) {
                append(" AND status IN (:statuses)")
            }
            append(" ORDER BY priority DESC, created_at ASC")
        }
        val params = MapSqlParameterSource()
            .addValue("organizationId", scope.organizationId)
            .addValue("workstreamId", scope.workstreamId)
        if (!statusValues.isNullOrEmpty()) {
            params.addValue("statuses", statusValues)
        }
        return jdbc.query(sql, params, rowMapper)
    }

    override fun updateStatus(
        scope: TenantScope,
        workUnitId: String,
        status: WorkUnitState,
        expectedRevision: Int,
        updatedAt: Instant,
    ): WorkUnit {
        val updated = jdbc.update(
            """
            UPDATE work_units
               SET status = :status,
                   revision = revision + 1,
                   updated_at = :updatedAt
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND work_unit_id = :workUnitId
               AND revision = :expectedRevision
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("status", status.dbValue)
                .addValue("updatedAt", Timestamp.from(updatedAt))
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("workUnitId", workUnitId)
                .addValue("expectedRevision", expectedRevision),
        )
        if (updated == 0) {
            val existing = findById(scope, workUnitId)
            if (existing != null) {
                throw RevisionConflictException(
                    "Revision conflict for work unit '$workUnitId': expected revision $expectedRevision, " +
                        "found ${existing.revision}",
                )
            }
            throw WorkUnitNotFoundException("Work unit '$workUnitId' not found")
        }
        return findById(scope, workUnitId)
            ?: throw IllegalStateException("Work unit '$workUnitId' vanished after update")
    }

    private val rowMapper = RowMapper { rs: ResultSet, _: Int -> mapRow(rs) }

    private fun mapRow(rs: ResultSet): WorkUnit = WorkUnit(
        organizationId = rs.getString("organization_id"),
        workstreamId = rs.getString("workstream_id"),
        workUnitId = rs.getString("work_unit_id"),
        unitType = rs.getString("unit_type"),
        status = WorkUnitState.fromDbValue(rs.getString("status")),
        revision = rs.getInt("revision"),
        priority = rs.getInt("priority"),
        notBefore = rs.getTimestamp("not_before")?.toInstant(),
        attemptCount = rs.getInt("attempt_count"),
        payload = rs.getString("payload") ?: "{}",
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT organization_id, workstream_id, work_unit_id, unit_type, status, revision,
                   priority, not_before, attempt_count, payload, created_at, updated_at
              FROM work_units
        """
    }
}
