package io.whozoss.agentos.git

import java.util.UUID

/** Minimal in-memory [CaseResourceBindingService] for the provisioning tests. */
class InMemoryCaseResourceBindingService : CaseResourceBindingService {
    private val rows = mutableMapOf<UUID, CaseResourceBinding>()

    override fun create(entity: CaseResourceBinding): CaseResourceBinding = entity.also { rows[it.id] = it }

    override fun update(entity: CaseResourceBinding): CaseResourceBinding = entity.also { rows[it.id] = it }

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
        rows[id]?.let { rows[id] = it.copy(metadata = it.metadata.copy(removed = true)); true } ?: false

    override fun deleteByParent(parentId: UUID): Int = findByParent(parentId).count { delete(it.id) }

    override fun markStatus(
        id: UUID,
        status: CaseResourceStatus,
        failureReason: String?,
    ): CaseResourceBinding {
        val current = requireNotNull(rows[id]) { "binding $id not found" }
        return current.copy(status = status, failureReason = failureReason).also { rows[id] = it }
    }
}
