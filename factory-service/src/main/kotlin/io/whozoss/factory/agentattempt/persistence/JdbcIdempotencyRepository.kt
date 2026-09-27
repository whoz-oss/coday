package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.persistence.TenantScope
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * `NamedParameterJdbcTemplate` implementation of [IdempotencyRepository].
 *
 * The tenant-scoped primary key `(organization_id, idempotency_key)` is the
 * uniqueness guarantee: a second submission of the same key by the same tenant
 * collides at the database level.
 */
@Repository
class JdbcIdempotencyRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : IdempotencyRepository {

    override fun find(scope: TenantScope, idempotencyKey: String): IdempotencyRecord? =
        jdbc.query(
            """
            SELECT request_hash, response_payload, status
              FROM idempotency_records
             WHERE organization_id = :organizationId
               AND idempotency_key = :idempotencyKey
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("idempotencyKey", idempotencyKey),
        ) { rs, _ ->
            IdempotencyRecord(
                requestHash = rs.getString("request_hash"),
                responsePayload = rs.getString("response_payload") ?: "{}",
                status = rs.getString("status"),
            )
        }.firstOrNull()

    override fun save(scope: TenantScope, idempotencyKey: String, requestHash: String, responsePayload: String) {
        jdbc.update(
            """
            INSERT INTO idempotency_records (
                organization_id, idempotency_key, workstream_id, request_hash,
                resource_ref, status, response_payload
            ) VALUES (
                :organizationId, :idempotencyKey, :workstreamId, :requestHash,
                :resourceRef, :status, CAST(:responsePayload AS jsonb)
            )
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("requestHash", requestHash)
                .addValue("resourceRef", "agent-step-result")
                .addValue("status", "completed")
                .addValue("responsePayload", responsePayload),
        )
    }
}
