package io.whozoss.factory.lease.persistence

import io.whozoss.factory.lease.domain.AcquireLeaseResult
import io.whozoss.factory.lease.domain.InvalidLeaseStateException
import io.whozoss.factory.lease.domain.LeaseExpiredException
import io.whozoss.factory.lease.domain.LeaseFencedException
import io.whozoss.factory.lease.domain.LeaseStatus
import io.whozoss.factory.lease.domain.WorkUnitLease
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workunit.domain.WorkUnitNotFoundException
import io.whozoss.factory.workunit.domain.WorkUnitState
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `NamedParameterJdbcTemplate` implementation of the lease protocol.
 *
 * Ported from
 * `factory/src/adapters/persistence/sql/sql-lease-repository.ts`. It implements
 * the lease protocol over the V6 `work_unit_leases` skeleton extended by V7
 * (`fencing_token`, `acquired_at`, `lease_expires_at`, `heartbeat_at`,
 * `released_at`, `expiry_reason`) and the V7 `work_units` scheduling columns
 * (`priority`, `not_before`, `attempt_count`).
 *
 * The concurrency-safe claim uses PostgreSQL's `FOR UPDATE SKIP LOCKED`: two
 * concurrent acquisitions never block on the same row and never observe the same
 * work unit. Fencing tokens are drawn from the base-guaranteed monotone sequence
 * `work_unit_lease_fencing_seq`, whose `nextval()` is non-transactional and
 * strictly increasing across concurrent transactions.
 */
@Repository
class JdbcLeaseRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : LeaseRepository {

    override fun acquire(
        scope: TenantScope,
        workerId: String,
        ttlMs: Long,
        environmentId: String?,
        now: Instant,
    ): AcquireLeaseResult? {
        requireTtl(ttlMs)
        val nowTs = Timestamp.from(now)
        val candidate = jdbc.query(
            """
            SELECT work_unit_id, revision, attempt_count
              FROM work_units
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND status IN ('created', 'failed')
               AND (not_before IS NULL OR not_before <= :now)
             ORDER BY priority DESC, created_at ASC
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("now", nowTs),
            candidateRowMapper,
        ).firstOrNull() ?: return null

        val fencingToken = jdbc.queryForObject(
            "SELECT nextval('work_unit_lease_fencing_seq')",
            MapSqlParameterSource(),
            Long::class.javaObjectType,
        ) ?: error("Failed to draw a fencing token")

        val leaseId = "lease_${UUID.randomUUID()}"
        val expiresAt = Timestamp.from(now.plusMillis(ttlMs))

        jdbc.update(
            """
            INSERT INTO work_unit_leases (
                organization_id, workstream_id, work_unit_id, lease_id, worker_id, environment_id, status,
                fencing_token, acquired_at, lease_expires_at, heartbeat_at, created_at
            ) VALUES (
                :organizationId, :workstreamId, :workUnitId, :leaseId, :workerId, :environmentId, 'active',
                :fencingToken, :acquiredAt, :leaseExpiresAt, :heartbeatAt, :createdAt
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("workUnitId", candidate.workUnitId)
                .addValue("leaseId", leaseId)
                .addValue("workerId", workerId)
                .addValue("environmentId", environmentId)
                .addValue("fencingToken", fencingToken)
                .addValue("acquiredAt", nowTs)
                .addValue("leaseExpiresAt", expiresAt)
                .addValue("heartbeatAt", nowTs)
                .addValue("createdAt", nowTs),
        )

        val advanced = jdbc.update(
            """
            UPDATE work_units
               SET status = 'running',
                   attempt_count = attempt_count + 1,
                   revision = revision + 1,
                   updated_at = :now
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND work_unit_id = :workUnitId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("now", nowTs)
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("workUnitId", candidate.workUnitId),
        )
        if (advanced == 0) {
            throw WorkUnitNotFoundException("Work unit '${candidate.workUnitId}' not found")
        }

        val lease = selectLease(scope, candidate.workUnitId, leaseId, forUpdate = false)
            ?: throw IllegalStateException("Lease '$leaseId' vanished after insert")
        return AcquireLeaseResult(lease = lease, workUnitId = candidate.workUnitId)
    }

    override fun renew(
        scope: TenantScope,
        workUnitId: String,
        leaseId: String,
        fencingToken: Long,
        ttlMs: Long,
        now: Instant,
    ): WorkUnitLease {
        requireTtl(ttlMs)
        val lease = selectLease(scope, workUnitId, leaseId, forUpdate = true)
            ?: throw WorkUnitNotFoundException("Lease '$leaseId' for work unit '$workUnitId' not found")
        if (lease.status != LeaseStatus.ACTIVE) {
            throw InvalidLeaseStateException(
                "Cannot renew lease in state '${lease.status.dbValue}'",
                details = mapOf("leaseId" to leaseId, "status" to lease.status.dbValue),
            )
        }
        if (lease.isExpiredByTime(now)) {
            throw LeaseExpiredException(
                "Lease '$leaseId' expired at ${lease.leaseExpiresAt}",
                details = mapOf("leaseId" to leaseId, "leaseExpiresAt" to lease.leaseExpiresAt.toString()),
            )
        }
        assertFencingToken(lease, fencingToken)

        val nowTs = Timestamp.from(now)
        jdbc.update(
            """
            UPDATE work_unit_leases
               SET lease_expires_at = :leaseExpiresAt, heartbeat_at = :heartbeatAt
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND work_unit_id = :workUnitId
               AND lease_id = :leaseId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("leaseExpiresAt", Timestamp.from(now.plusMillis(ttlMs)))
                .addValue("heartbeatAt", nowTs)
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("workUnitId", workUnitId)
                .addValue("leaseId", leaseId),
        )
        return selectLease(scope, workUnitId, leaseId, forUpdate = false)
            ?: throw IllegalStateException("Lease '$leaseId' vanished after renew")
    }

    override fun release(
        scope: TenantScope,
        workUnitId: String,
        leaseId: String,
        fencingToken: Long?,
        resultStatus: WorkUnitState,
        now: Instant,
    ): WorkUnitLease {
        val lease = selectLease(scope, workUnitId, leaseId, forUpdate = true)
            ?: throw WorkUnitNotFoundException("Lease '$leaseId' for work unit '$workUnitId' not found")
        if (fencingToken != null) assertFencingToken(lease, fencingToken)
        if (lease.status != LeaseStatus.ACTIVE) {
            throw InvalidLeaseStateException(
                "Cannot release lease in state '${lease.status.dbValue}'",
                details = mapOf("leaseId" to leaseId, "status" to lease.status.dbValue),
            )
        }

        val nowTs = Timestamp.from(now)
        jdbc.update(
            """
            UPDATE work_unit_leases
               SET status = 'released', released_at = :releasedAt
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND work_unit_id = :workUnitId
               AND lease_id = :leaseId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("releasedAt", nowTs)
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("workUnitId", workUnitId)
                .addValue("leaseId", leaseId),
        )

        val advanced = jdbc.update(
            """
            UPDATE work_units
               SET status = :status, revision = revision + 1, updated_at = :now
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND work_unit_id = :workUnitId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("status", resultStatus.dbValue)
                .addValue("now", nowTs)
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("workUnitId", workUnitId),
        )
        if (advanced == 0) {
            throw WorkUnitNotFoundException("Work unit '$workUnitId' not found")
        }

        return selectLease(scope, workUnitId, leaseId, forUpdate = false)
            ?: throw IllegalStateException("Lease '$leaseId' vanished after release")
    }

    override fun expire(
        scope: TenantScope,
        expiryReason: String,
        now: Instant,
    ): List<WorkUnitLease> {
        val nowTs = Timestamp.from(now)
        val expiredRows = jdbc.query(
            """
            SELECT $LEASE_COLUMNS
              FROM work_unit_leases
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND status = 'active'
               AND lease_expires_at < :now
             FOR UPDATE
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("now", nowTs),
            leaseRowMapper,
        )

        return expiredRows.map { lease ->
            jdbc.update(
                """
                UPDATE work_unit_leases
                   SET status = 'expired', released_at = :releasedAt, expiry_reason = :expiryReason
                 WHERE organization_id = :organizationId
                   AND workstream_id = :workstreamId
                   AND work_unit_id = :workUnitId
                   AND lease_id = :leaseId
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("releasedAt", nowTs)
                    .addValue("expiryReason", expiryReason)
                    .addValue("organizationId", scope.organizationId)
                    .addValue("workstreamId", scope.workstreamId)
                    .addValue("workUnitId", lease.workUnitId)
                    .addValue("leaseId", lease.leaseId),
            )
            jdbc.update(
                """
                UPDATE work_units
                   SET status = 'created', revision = revision + 1, updated_at = :now
                 WHERE organization_id = :organizationId
                   AND workstream_id = :workstreamId
                   AND work_unit_id = :workUnitId
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("now", nowTs)
                    .addValue("organizationId", scope.organizationId)
                    .addValue("workstreamId", scope.workstreamId)
                    .addValue("workUnitId", lease.workUnitId),
            )
            // `attempt_count` is deliberately NOT bumped here: it was already
            // incremented when the lease was acquired, so the sweep only re-queues
            // the unit (no double counting of a single execution attempt).
            lease.copy(status = LeaseStatus.EXPIRED, releasedAt = now, expiryReason = expiryReason)
        }
    }

    override fun findByLeaseId(
        scope: TenantScope,
        workUnitId: String,
        leaseId: String,
    ): WorkUnitLease? = selectLease(scope, workUnitId, leaseId, forUpdate = false)

    override fun findActiveLeaseByWorkUnit(scope: TenantScope, workUnitId: String): WorkUnitLease? =
        jdbc.query(
            """
            SELECT $LEASE_COLUMNS
              FROM work_unit_leases
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND work_unit_id = :workUnitId
               AND status = 'active'
             ORDER BY fencing_token DESC
             LIMIT 1
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("workUnitId", workUnitId),
            leaseRowMapper,
        ).firstOrNull()

    private fun selectLease(
        scope: TenantScope,
        workUnitId: String,
        leaseId: String,
        forUpdate: Boolean,
    ): WorkUnitLease? {
        val sql = buildString {
            append("SELECT $LEASE_COLUMNS FROM work_unit_leases ")
            append("WHERE organization_id = :organizationId AND workstream_id = :workstreamId ")
            append("AND work_unit_id = :workUnitId AND lease_id = :leaseId")
            if (forUpdate) append(" FOR UPDATE")
        }
        return jdbc.query(
            sql,
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("workUnitId", workUnitId)
                .addValue("leaseId", leaseId),
            leaseRowMapper,
        ).firstOrNull()
    }

    private fun assertFencingToken(lease: WorkUnitLease, fencingToken: Long) {
        if (lease.fencingToken != fencingToken) {
            throw LeaseFencedException(
                "Fencing token $fencingToken is stale for lease '${lease.leaseId}' (current ${lease.fencingToken})",
                details = mapOf(
                    "currentFencingToken" to lease.fencingToken,
                    "incomingFencingToken" to fencingToken,
                ),
            )
        }
    }

    private fun requireTtl(ttlMs: Long) {
        require(ttlMs > 0) { "INVALID_LEASE_TTL" }
    }

    private data class CandidateRow(
        val workUnitId: String,
        val revision: Int,
        val attemptCount: Int,
    )

    private val candidateRowMapper = RowMapper { rs: ResultSet, _: Int ->
        CandidateRow(
            workUnitId = rs.getString("work_unit_id"),
            revision = rs.getInt("revision"),
            attemptCount = rs.getInt("attempt_count"),
        )
    }

    private val leaseRowMapper = RowMapper { rs: ResultSet, _: Int -> mapLease(rs) }

    private fun mapLease(rs: ResultSet): WorkUnitLease = WorkUnitLease(
        organizationId = rs.getString("organization_id"),
        workstreamId = rs.getString("workstream_id"),
        workUnitId = rs.getString("work_unit_id"),
        leaseId = rs.getString("lease_id"),
        workerId = rs.getString("worker_id"),
        environmentId = rs.getString("environment_id"),
        status = LeaseStatus.fromDbValue(rs.getString("status")),
        fencingToken = rs.getLong("fencing_token"),
        acquiredAt = rs.getTimestamp("acquired_at")?.toInstant(),
        leaseExpiresAt = rs.getTimestamp("lease_expires_at")?.toInstant(),
        heartbeatAt = rs.getTimestamp("heartbeat_at")?.toInstant(),
        releasedAt = rs.getTimestamp("released_at")?.toInstant(),
        expiryReason = rs.getString("expiry_reason"),
        createdAt = rs.getTimestamp("created_at")?.toInstant(),
    )

    private companion object {
        const val LEASE_COLUMNS = """
            organization_id, workstream_id, work_unit_id, lease_id, worker_id, environment_id, status,
            fencing_token, acquired_at, lease_expires_at, heartbeat_at, released_at, expiry_reason, created_at
        """
    }
}
