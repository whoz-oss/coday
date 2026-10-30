package io.whozoss.agentos.git

import io.whozoss.agentos.exception.ResourceNotFoundException
import mu.KLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Default [RepositoryCheckoutService], delegating persistence to [RepositoryCheckoutRepository].
 */
@Service
class RepositoryCheckoutServiceImpl(
    private val repository: RepositoryCheckoutRepository,
) : RepositoryCheckoutService {
    override fun create(entity: RepositoryCheckout): RepositoryCheckout = repository.save(entity)

    override fun update(entity: RepositoryCheckout): RepositoryCheckout = repository.save(entity)

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<RepositoryCheckout> = repository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<RepositoryCheckout> = repository.findByParent(parentId)

    override fun findByNamespaceId(namespaceId: UUID): RepositoryCheckout? = repository.findByNamespaceId(namespaceId)

    override fun findByStatusIn(
        statuses: Collection<RepositoryCheckoutStatus>,
        limit: Int,
    ): List<RepositoryCheckout> = repository.findByStatusIn(statuses, limit)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun markStatus(
        id: UUID,
        status: RepositoryCheckoutStatus,
        failureReason: String?,
    ): RepositoryCheckout {
        val current = repository.findById(id) ?: throw ResourceNotFoundException("Repository checkout $id not found")
        val updated =
            current.copy(
                status = status,
                failureReason = failureReason,
                lastFetchedAt = if (status == RepositoryCheckoutStatus.READY) Instant.now() else current.lastFetchedAt,
            )
        logger.info { "[RepositoryCheckout] $id namespace=${current.namespaceId} -> $status" }
        return repository.save(updated)
    }

    companion object : KLogging()
}
