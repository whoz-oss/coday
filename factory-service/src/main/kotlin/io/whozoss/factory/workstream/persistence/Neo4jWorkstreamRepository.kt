package io.whozoss.factory.workstream.persistence

import io.whozoss.factory.persistence.ScopedRepository
import io.whozoss.factory.persistence.TenantScope
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository

/**
 * Neo4j implementation of the tenant-scoped workstream repository.
 *
 * Replaces `JdbcWorkstreamRepository`. Every statement is constrained to the
 * caller's [TenantScope]; rows are exposed with the same `{slug, name, status,
 * revision}` shape as the retired JDBC adapter.
 */
@Repository
@Primary
class Neo4jWorkstreamRepository(
    private val repository: SpringDataNeo4jWorkstreamRepository,
) : ScopedRepository<Map<String, Any?>, String> {

    override fun findById(scope: TenantScope, id: String): Map<String, Any?>? =
        repository
            .findById(WorkstreamNode.compositeId(scope.organizationId, id))
            .orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId }
            ?.toView()

    /** List the workstreams visible to [scope], ordered by their stable id. */
    fun list(scope: TenantScope): List<Map<String, Any?>> =
        repository
            .findAllByOrganization(scope.organizationId)
            .map { it.toView() }

    /** Create a workstream node and return its `{slug, name, status, revision}` view. */
    fun create(scope: TenantScope, workstreamId: String, name: String, status: String): Map<String, Any?> {
        val node = WorkstreamNode(
            id = WorkstreamNode.compositeId(scope.organizationId, workstreamId),
            organizationId = scope.organizationId,
            workstreamId = workstreamId,
            name = name,
            status = status,
        )
        repository.save(node)
        return node.toView()
    }

    override fun deleteById(scope: TenantScope, id: String): Boolean {
        if (findById(scope, id) == null) return false
        repository.deleteById(WorkstreamNode.compositeId(scope.organizationId, id))
        return true
    }

    private fun WorkstreamNode.toView(): Map<String, Any?> = linkedMapOf(
        "slug" to workstreamId,
        "name" to name,
        "status" to status,
        "revision" to revision,
    )
}
