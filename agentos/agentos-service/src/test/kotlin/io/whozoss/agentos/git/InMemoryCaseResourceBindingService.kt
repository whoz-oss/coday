package io.whozoss.agentos.git

import org.springframework.dao.OptimisticLockingFailureException
import java.util.UUID

/**
 * Minimal in-memory [CaseResourceBindingService] for the provisioning tests.
 *
 * Versions rows like the Neo4j repository does: writing a copy read before a newer write fails.
 */
class InMemoryCaseResourceBindingService : CaseResourceBindingService {
    private val rows = mutableMapOf<UUID, CaseResourceBinding>()

    override fun create(entity: CaseResourceBinding): CaseResourceBinding = store(entity)

    override fun update(entity: CaseResourceBinding): CaseResourceBinding = store(entity)

    private fun store(entity: CaseResourceBinding): CaseResourceBinding {
        val stored = rows[entity.id]
        if (stored != null && stored.metadata.version != entity.metadata.version) {
            throw OptimisticLockingFailureException("Binding ${entity.id} was written since version ${entity.metadata.version}")
        }
        val next = (stored?.metadata?.version ?: -1) + 1
        return entity.copy(metadata = entity.metadata.copy(version = next)).also { rows[it.id] = it }
    }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<CaseResourceBinding> = ids.mapNotNull { rows[it] }.filter { withRemoved || !it.metadata.removed }

    override fun findByParent(parentId: UUID): List<CaseResourceBinding> = rows.values.filter { it.namespaceId == parentId }

    override fun findByRootCaseId(rootCaseId: UUID): CaseResourceBinding? =
        rows.values.firstOrNull { it.rootCaseId == rootCaseId && !it.metadata.removed }

    override fun findByStatusIn(
        statuses: Collection<CaseResourceStatus>,
        limit: Int,
        after: CaseResourceBindingCursor?,
    ): List<CaseResourceBinding> =
        rows.values
            .filter { !it.metadata.removed && it.status in statuses }
            .filter { after == null || it.metadata.created > after.created ||
                (it.metadata.created == after.created && it.id.toString() > after.id.toString()) }
            .sortedWith(compareBy({ it.metadata.created }, { it.id.toString() }))
            .take(limit)

    override fun delete(id: UUID): Boolean =
        rows[id]?.let { store(it.copy(metadata = it.metadata.copy(removed = true))); true } ?: false

    override fun deleteByParent(parentId: UUID): Int = findByParent(parentId).count { delete(it.id) }

    override fun markStatus(
        id: UUID,
        status: CaseResourceStatus,
        failureReason: String?,
    ): CaseResourceBinding {
        val current = requireNotNull(rows[id]) { "binding $id not found" }
        return store(current.copy(status = status, failureReason = failureReason))
    }
}
