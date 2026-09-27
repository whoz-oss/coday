package io.whozoss.factory.workstream

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.ScopedRepository
import io.whozoss.factory.persistence.TenantScope
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

/**
 * Tenant-scoped JDBC repository for the existing `workstreams` table.
 *
 * The table is created by `V2__tenant_and_membership.sql` and must never be
 * recreated or altered by this adapter: it only reads and appends rows within
 * the caller's [TenantScope]. Rows are exposed with the same `{slug, name,
 * status}` shape as the Node `forge/bmad/workstreams.toml` reader.
 */
@Repository
class JdbcWorkstreamRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : ScopedRepository<Map<String, Any?>, String> {

    private val objectMapper = ObjectMapper()

    private val selectColumns =
        "organization_id, workstream_id, name, revision, payload::text AS payload, created_at, updated_at"

    private val rowMapper = RowMapper { rs: ResultSet, _: Int ->
        linkedMapOf<String, Any?>(
            "workstreamId" to rs.getString("workstream_id"),
            "name" to rs.getString("name"),
            "revision" to rs.getInt("revision"),
            "payload" to rs.getString("payload"),
            "createdAt" to rs.getTimestamp("created_at")?.toInstant()?.toString(),
            "updatedAt" to rs.getTimestamp("updated_at")?.toInstant()?.toString(),
        ).toWorkstream()
    }

    override fun findById(scope: TenantScope, id: String): Map<String, Any?>? =
        jdbc.query(
            "SELECT $selectColumns FROM workstreams WHERE organization_id = :org AND workstream_id = :id",
            mapOf("org" to scope.organizationId, "id" to id),
            rowMapper,
        ).firstOrNull()

    /** List the workstreams visible to [scope], ordered by their stable id. */
    fun list(scope: TenantScope): List<Map<String, Any?>> =
        jdbc.query(
            "SELECT $selectColumns FROM workstreams WHERE organization_id = :org ORDER BY workstream_id",
            mapOf("org" to scope.organizationId),
            rowMapper,
        )

    /** Create a workstream row, failing on a conflicting primary key. */
    fun create(scope: TenantScope, workstreamId: String, name: String, status: String): Map<String, Any?> {
        jdbc.update(
            """
            INSERT INTO workstreams (organization_id, workstream_id, name, payload)
            VALUES (:org, :id, :name, CAST(:payload AS jsonb))
            """.trimIndent(),
            mapOf(
                "org" to scope.organizationId,
                "id" to workstreamId,
                "name" to name,
                "payload" to objectMapper.writeValueAsString(mapOf("status" to status)),
            ),
        )
        return linkedMapOf("slug" to workstreamId, "name" to name, "status" to status)
    }

    override fun deleteById(scope: TenantScope, id: String): Boolean =
        jdbc.update(
            "DELETE FROM workstreams WHERE organization_id = :org AND workstream_id = :id",
            mapOf("org" to scope.organizationId, "id" to id),
        ) > 0

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.toWorkstream(): Map<String, Any?> {
        val payloadRaw = this["payload"] as? String
        val payload = try {
            payloadRaw?.let {
                objectMapper.readValue(it, object : TypeReference<Map<String, Any?>>() {})
            } ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        val status = payload["status"] as? String ?: "active"
        return linkedMapOf(
            "slug" to this["workstreamId"],
            "name" to this["name"],
            "status" to status,
            "revision" to this["revision"],
        )
    }
}
