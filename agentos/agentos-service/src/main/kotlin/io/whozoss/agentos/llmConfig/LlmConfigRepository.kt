package io.whozoss.agentos.llmConfig

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [LlmConfig] persistence.
 *
 * Because [LlmConfig] can be scoped to a namespace, a user, or both, there is no
 * single "parent" key. [findByParent] from [EntityRepository] is therefore not the
 * primary listing mechanism here — use [findByNamespaceId] or [findByUserId] instead.
 *
 * The [ParentIdentifier] type is [UUID] to satisfy the interface; [findByParent] is
 * implemented as [findByNamespaceId] by convention (namespace is the primary scope
 * for this ticket).
 */
interface LlmConfigRepository : EntityRepository<LlmConfig, UUID> {
    /**
     * Find all non-removed configs scoped to the given namespace,
     * regardless of [LlmConfig.userId].
     */
    fun findByNamespaceId(namespaceId: UUID): List<LlmConfig>

    /**
     * Find all non-removed configs scoped to the given user,
     * regardless of [LlmConfig.namespaceId].
     */
    fun findByUserId(userId: UUID): List<LlmConfig>
}
