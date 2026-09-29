package io.whozoss.factory.worker.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.worker.domain.Worker
import io.whozoss.factory.worker.domain.WorkerNotFoundException
import io.whozoss.factory.worker.domain.WorkerState
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * `NamedParameterJdbcTemplate` implementation of [WorkerRepository].
 *
 * Ported from `factory/src/adapters/persistence/sql/sql-worker-repository.ts`.
 * The `capabilities` array and the opaque `payload` are written into JSONB
 * columns; `revision` is the optimistic-locking column compared-and-swapped by
 * the `UPDATE ... WHERE revision = :expectedRevision` clause.
 */
class JdbcWorkerRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : WorkerRepository {

    override fun insert(scope: TenantScope, worker: Worker): Worker {
        jdbc.update(
            """
            INSERT INTO workers (
                organization_id, worker_id, worker_type, status, revision,
                last_heartbeat_at, protocol_version, capabilities, payload, created_at, updated_at
            ) VALUES (
                :organizationId, :workerId, :workerType, :status, :revision,
                :lastHeartbeatAt, :protocolVersion, CAST(:capabilities AS jsonb),
                CAST(:payload AS jsonb), :createdAt, :updatedAt
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workerId", worker.workerId)
                .addValue("workerType", worker.workerType)
                .addValue("status", worker.status.dbValue)
                .addValue("revision", worker.revision)
                .addValue("lastHeartbeatAt", worker.lastHeartbeatAt?.let { Timestamp.from(it) })
                .addValue("protocolVersion", worker.protocolVersion)
                .addValue("capabilities", objectMapper.writeValueAsString(worker.capabilities))
                .addValue("payload", worker.payload)
                .addValue("createdAt", Timestamp.from(worker.createdAt))
                .addValue("updatedAt", Timestamp.from(worker.updatedAt)),
        )
        return findById(scope, worker.workerId)
            ?: throw IllegalStateException("Worker '${worker.workerId}' vanished after insert")
    }

    override fun findById(scope: TenantScope, workerId: String): Worker? =
        jdbc.query(
            "$SELECT_COLUMNS WHERE organization_id = :organizationId AND worker_id = :workerId",
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workerId", workerId),
            rowMapper,
        ).firstOrNull()

    override fun list(scope: TenantScope, statuses: Set<WorkerState>?): List<Worker> {
        val statusValues = statuses?.map { it.dbValue }
        val sql = buildString {
            append(SELECT_COLUMNS)
            append(" WHERE organization_id = :organizationId")
            if (!statusValues.isNullOrEmpty()) append(" AND status IN (:statuses)")
            append(" ORDER BY worker_id ASC")
        }
        val params = MapSqlParameterSource().addValue("organizationId", scope.organizationId)
        if (!statusValues.isNullOrEmpty()) params.addValue("statuses", statusValues)
        return jdbc.query(sql, params, rowMapper)
    }

    override fun updateStatus(
        scope: TenantScope,
        workerId: String,
        status: WorkerState,
        expectedRevision: Int,
        updatedAt: Instant,
    ): Worker {
        val updated = jdbc.update(
            """
            UPDATE workers
               SET status = :status, revision = revision + 1, updated_at = :updatedAt
             WHERE organization_id = :organizationId
               AND worker_id = :workerId
               AND revision = :expectedRevision
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("status", status.dbValue)
                .addValue("updatedAt", Timestamp.from(updatedAt))
                .addValue("organizationId", scope.organizationId)
                .addValue("workerId", workerId)
                .addValue("expectedRevision", expectedRevision),
        )
        if (updated == 0) throw conflictOrNotFound(scope, workerId, expectedRevision)
        return require(scope, workerId)
    }

    override fun heartbeat(
        scope: TenantScope,
        workerId: String,
        heartbeatAt: Instant,
        expectedRevision: Int?,
        updatedAt: Instant,
    ): Worker {
        val sql = buildString {
            append(
                """
                UPDATE workers
                   SET last_heartbeat_at = :heartbeatAt, revision = revision + 1, updated_at = :updatedAt
                 WHERE organization_id = :organizationId
                   AND worker_id = :workerId
                """.trimIndent(),
            )
            if (expectedRevision != null) append(" AND revision = :expectedRevision")
        }
        val params = MapSqlParameterSource()
            .addValue("heartbeatAt", Timestamp.from(heartbeatAt))
            .addValue("updatedAt", Timestamp.from(updatedAt))
            .addValue("organizationId", scope.organizationId)
            .addValue("workerId", workerId)
        if (expectedRevision != null) params.addValue("expectedRevision", expectedRevision)

        val updated = jdbc.update(sql, params)
        if (updated == 0) throw conflictOrNotFound(scope, workerId, expectedRevision)
        return require(scope, workerId)
    }

    private fun require(scope: TenantScope, workerId: String): Worker =
        findById(scope, workerId)
            ?: throw IllegalStateException("Worker '$workerId' vanished after update")

    private fun conflictOrNotFound(scope: TenantScope, workerId: String, expectedRevision: Int?): RuntimeException {
        val existing = findById(scope, workerId)
        return if (existing != null) {
            RevisionConflictException(
                "Revision conflict for worker '$workerId': expected revision $expectedRevision, " +
                    "found ${existing.revision}",
            )
        } else {
            WorkerNotFoundException("Worker '$workerId' not found")
        }
    }

    private val rowMapper = RowMapper { rs: ResultSet, _: Int -> mapRow(rs) }

    private fun mapRow(rs: ResultSet): Worker = Worker(
        organizationId = rs.getString("organization_id"),
        workerId = rs.getString("worker_id"),
        workerType = rs.getString("worker_type"),
        status = WorkerState.fromDbValue(rs.getString("status")),
        revision = rs.getInt("revision"),
        lastHeartbeatAt = rs.getTimestamp("last_heartbeat_at")?.toInstant(),
        protocolVersion = rs.getString("protocol_version"),
        capabilities = parseCapabilities(rs.getString("capabilities")),
        payload = rs.getString("payload") ?: "{}",
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )

    private fun parseCapabilities(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue<List<String>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT organization_id, worker_id, worker_type, status, revision, last_heartbeat_at,
                   protocol_version, capabilities, payload, created_at, updated_at
              FROM workers
        """
    }
}
