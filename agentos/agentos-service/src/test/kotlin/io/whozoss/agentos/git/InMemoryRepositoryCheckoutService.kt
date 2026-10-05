package io.whozoss.agentos.git

import java.util.UUID

/** Minimal in-memory [RepositoryCheckoutService] shared by the Git workspace tests. */
class InMemoryRepositoryCheckoutService : RepositoryCheckoutService {
    private val rows = mutableMapOf<UUID, RepositoryCheckout>()

    override fun create(entity: RepositoryCheckout): RepositoryCheckout = entity.also { rows[it.id] = it }

    override fun update(entity: RepositoryCheckout): RepositoryCheckout = entity.also { rows[it.id] = it }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<RepositoryCheckout> = ids.mapNotNull { rows[it] }.filter { withRemoved || !it.metadata.removed }

    override fun findByParent(parentId: UUID): List<RepositoryCheckout> = rows.values.filter { it.namespaceId == parentId }

    override fun findByStatusIn(
        statuses: Collection<RepositoryCheckoutStatus>,
        limit: Int,
    ): List<RepositoryCheckout> = rows.values.filter { it.status in statuses }.take(limit)

    override fun findByNamespaceId(namespaceId: UUID): RepositoryCheckout? =
        rows.values.firstOrNull { it.namespaceId == namespaceId && !it.metadata.removed }

    override fun delete(id: UUID): Boolean =
        rows[id]?.let { rows[id] = it.copy(metadata = it.metadata.copy(removed = true)); true } ?: false

    override fun deleteByParent(parentId: UUID): Int = findByParent(parentId).count { delete(it.id) }

    override fun markStatus(
        id: UUID,
        status: RepositoryCheckoutStatus,
        failureReason: String?,
    ): RepositoryCheckout {
        val current = requireNotNull(rows[id]) { "checkout $id not found" }
        return current.copy(status = status, failureReason = failureReason).also { rows[id] = it }
    }
}
