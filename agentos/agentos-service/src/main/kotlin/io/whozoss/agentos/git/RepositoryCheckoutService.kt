package io.whozoss.agentos.git

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Business operations over [RepositoryCheckout].
 *
 * Intentionally has no REST controller: the checkout is internal state of a namespace, surfaced to
 * clients through the namespace's Git projection rather than as an entity of its own.
 */
interface RepositoryCheckoutService : EntityService<RepositoryCheckout, UUID> {
    /** The one active checkout of a namespace, or null when it has none yet. */
    fun findByNamespaceId(namespaceId: UUID): RepositoryCheckout?

    /** Active checkouts in one of [statuses], oldest first, capped by [limit]. */
    fun findByStatusIn(
        statuses: Collection<RepositoryCheckoutStatus>,
        limit: Int,
    ): List<RepositoryCheckout>

    /**
     * Record the outcome of a preparation attempt, re-reading the row before writing it back.
     *
     * Safe today because the provisioner is the only writer of a checkout and holds the namespace
     * slot for the duration of an attempt. Should a second writer ever appear, this read-copy-save
     * becomes a lost-update window and must move to a targeted `SET` or gain a version field.
     */
    fun markStatus(
        id: UUID,
        status: RepositoryCheckoutStatus,
        failureReason: String? = null,
    ): RepositoryCheckout
}
