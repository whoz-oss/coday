package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import java.util.UUID

/**
 * Repository for [AiProvider] persistence.
 *
 * Because [AiProvider] can be scoped to a namespace, a user, or both, there is no
 * single "parent" key. [findByParent] from [EntityRepository] is therefore not the
 * primary listing mechanism here — use [findByNamespaceId] or [findByUserId] instead.
 *
 * The [ParentIdentifier] type is [UUID] to satisfy the interface; [findByParent] is
 * implemented as [findByNamespaceId] by convention (namespace is the primary scope
 * for this ticket).
 */
interface AiProviderRepository : EntityRepository<AiProvider, UUID> {
    /**
     * Find all non-removed configs scoped to the given namespace,
     * regardless of [AiProvider.userId].
     */
    fun findByNamespaceId(namespaceId: UUID): List<AiProvider>

    /**
     * Find all non-removed configs scoped to the given user,
     * regardless of [AiProvider.namespaceId].
     */
    fun findByUserId(userId: UUID): List<AiProvider>
}
