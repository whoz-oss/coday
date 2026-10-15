package io.whozoss.factory.workstream.persistence

import io.whozoss.factory.error.ResourceNotFoundException
import io.whozoss.factory.persistence.ScopedRepository
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workstream.domain.Workstream
import io.whozoss.factory.workstream.domain.WorkstreamStatus
import io.whozoss.factory.workstream.domain.toDomain
import io.whozoss.factory.workstream.domain.toNode
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Neo4j implementation of the tenant-scoped workstream repository.
 *
 * Replaces `JdbcWorkstreamRepository`. Every statement is constrained to the
 * caller's [TenantScope]; rows are exposed with the same `{slug, name, status,
 * revision}` shape as the retired JDBC adapter, enriched with the versioned
 * registry fields (namespace, governance metadata, audit timestamps).
 */
@Repository
@Primary
class Neo4jWorkstreamRepository(
    private val repository: SpringDataNeo4jWorkstreamRepository,
) : ScopedRepository<Map<String, Any?>, String> {

    override fun findById(scope: TenantScope, id: String): Map<String, Any?>? =
        findNode(scope, id)?.toView()

    /** The domain registry entry of [slug] in [scope], or `null` when absent. */
    fun findDomain(scope: TenantScope, slug: String): Workstream? =
        findNode(scope, slug)?.toDomain()

    /** List the workstreams visible to [scope], ordered by their stable id. */
    fun list(scope: TenantScope): List<Map<String, Any?>> =
        repository
            .findAllByOrganization(scope.organizationId)
            .map { it.toView() }

    /** Create a workstream node and return its enriched registry view. */
    fun create(scope: TenantScope, workstreamId: String, name: String, status: String): Map<String, Any?> =
        create(
            scope,
            Workstream(
                organizationId = scope.organizationId,
                workstreamId = workstreamId,
                name = name,
                status = WorkstreamStatus.fromDbValue(status),
            ),
        ).toView()

    /**
     * Create a workstream node from its domain representation (revision 1,
     * `createdAt == updatedAt == now`) and return the persisted domain entry.
     * The organization always comes from [scope], never from caller input.
     */
    fun create(scope: TenantScope, workstream: Workstream): Workstream {
        val now = Instant.now()
        val node = WorkstreamNode(
            id = WorkstreamNode.compositeId(scope.organizationId, workstream.workstreamId),
            organizationId = scope.organizationId,
            workstreamId = workstream.workstreamId,
            name = workstream.name,
            status = workstream.status.dbValue,
            namespaceId = workstream.namespaceId,
            controllerAgentRef = workstream.controllerAgentRef,
            allowedWorkflowTypes = workstream.allowedWorkflowTypes,
            governancePolicyRef = workstream.governancePolicyRef,
            revision = 1,
            createdAt = now,
            updatedAt = now,
        )
        return repository.save(node).toDomain()
    }

    /**
     * Save a new revision of an existing registry entry: [Workstream.revision]
     * is bumped by one, `updatedAt` is set to now and `createdAt` is preserved.
     *
     * @throws ResourceNotFoundException when no entry matches the slug in [scope].
     */
    fun save(scope: TenantScope, workstream: Workstream): Workstream {
        val existing = findNode(scope, workstream.workstreamId)
            ?: throw ResourceNotFoundException(
                "Le workstream '${workstream.workstreamId}' n'existe pas",
                mapOf("code" to "WORKSTREAM_NOT_FOUND"),
            )
        val persisted = repository.save(
            existing.copy(
                name = workstream.name,
                status = workstream.status.dbValue,
                namespaceId = workstream.namespaceId,
                controllerAgentRef = workstream.controllerAgentRef,
                allowedWorkflowTypes = workstream.allowedWorkflowTypes,
                governancePolicyRef = workstream.governancePolicyRef,
                revision = existing.revision + 1,
                createdAt = existing.createdAt,
                updatedAt = Instant.now(),
            ),
        )
        return persisted.toDomain()
    }

    override fun deleteById(scope: TenantScope, id: String): Boolean {
        if (findById(scope, id) == null) return false
        repository.deleteById(WorkstreamNode.compositeId(scope.organizationId, id))
        return true
    }

    private fun findNode(scope: TenantScope, slug: String): WorkstreamNode? =
        repository
            .findById(WorkstreamNode.compositeId(scope.organizationId, slug))
            .orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId }

    private fun WorkstreamNode.toView(): Map<String, Any?> = linkedMapOf(
        "workstreamId" to workstreamId,
        "slug" to workstreamId,
        "name" to name,
        "title" to name,
        "status" to status,
        "namespaceId" to namespaceId,
        "controllerAgentRef" to controllerAgentRef,
        "allowedWorkflowTypes" to allowedWorkflowTypes,
        "governancePolicyRef" to governancePolicyRef,
        "revision" to revision,
        "createdAt" to createdAt.toString(),
        "updatedAt" to updatedAt.toString(),
    )

    private fun Workstream.toView(): Map<String, Any?> = toNode().toView()
}
