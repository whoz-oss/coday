package io.whozoss.agentos.git

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Persistence for [RepositoryCheckout].
 *
 * [findByParent] is the namespace listing, by the same convention the other namespace-scoped
 * repositories follow.
 */
interface RepositoryCheckoutRepository : EntityRepository<RepositoryCheckout, UUID> {
    /**
     * The one active checkout of a namespace, or null.
     *
     * Uniqueness is guaranteed by the `repository_checkout_namespace_unique` constraint rather
     * than by this read: two concurrent provisioning attempts would both see null and both insert.
     */
    fun findByNamespaceId(namespaceId: UUID): RepositoryCheckout?

    /** Active checkouts in one of [statuses], oldest first, capped by [limit]. */
    fun findByStatusIn(
        statuses: Collection<RepositoryCheckoutStatus>,
        limit: Int,
    ): List<RepositoryCheckout>
}
