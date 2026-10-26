package io.whozoss.factory.workunit.persistence

import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workunit.domain.WorkUnit
import io.whozoss.factory.workunit.domain.WorkUnitNotFoundException
import io.whozoss.factory.workunit.domain.WorkUnitState
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Neo4j implementation of [WorkUnitRepository].
 *
 * Replaces `JdbcWorkUnitRepository`. Every operation is constrained to the
 * supplied [TenantScope]; optimistic locking is enforced by the graph-native
 * compare-and-swap in [SpringDataNeo4jWorkUnitRepository].
 */
@Repository
@Primary
class Neo4jWorkUnitRepository(
    private val repository: SpringDataNeo4jWorkUnitRepository,
) : WorkUnitRepository {

    override fun insert(scope: TenantScope, workUnit: WorkUnit): WorkUnit {
        repository.save(workUnit.toNode())
        return require(scope, workUnit.workUnitId)
    }

    override fun findById(scope: TenantScope, workUnitId: String): WorkUnit? =
        repository
            .findById(WorkUnitNode.compositeId(scope.organizationId, scope.workstreamId, workUnitId))
            .orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId && it.workstreamId == scope.workstreamId }
            ?.toDomain()

    override fun list(scope: TenantScope, statuses: Set<WorkUnitState>?): List<WorkUnit> =
        repository
            .findAllByScope(scope.organizationId, scope.workstreamId)
            .asSequence()
            .map { it.toDomain() }
            .filter { statuses == null || it.status in statuses }
            .toList()

    override fun updateStatus(
        scope: TenantScope,
        workUnitId: String,
        status: WorkUnitState,
        expectedRevision: Int,
        updatedAt: Instant,
    ): WorkUnit {
        val updated = repository.casUpdateStatus(
            id = WorkUnitNode.compositeId(scope.organizationId, scope.workstreamId, workUnitId),
            expectedRevision = expectedRevision,
            status = status.dbValue,
            updatedAt = updatedAt,
        )
        if (updated == 0L) {
            val existing = findById(scope, workUnitId)
            if (existing != null) {
                throw RevisionConflictException(
                    "Revision conflict for work unit '$workUnitId': expected revision $expectedRevision, " +
                        "found ${existing.revision}",
                )
            }
            throw WorkUnitNotFoundException("Work unit '$workUnitId' not found")
        }
        return require(scope, workUnitId)
    }

    private fun require(scope: TenantScope, workUnitId: String): WorkUnit =
        findById(scope, workUnitId)
            ?: throw IllegalStateException("Work unit '$workUnitId' vanished after update")
}
