package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [ScheduledPrompt] persistence.
 *
 * [findByParent] returns only non-removed namespace-shared scheduled prompts (userId IS NULL)
 * for the given namespace — user-scoped overlays are excluded.
 * Platform-level scheduled prompts (namespaceId == null, userId == null) are retrieved via [findPlatform].
 */
interface ScheduledPromptRepository : EntityRepository<ScheduledPrompt, UUID> {

    /** Find all non-removed platform-level scheduled prompts (namespaceId IS NULL AND userId IS NULL). */
    fun findPlatform(): List<ScheduledPrompt>

    /**
     * Find a single non-removed scheduled prompt matching the (namespaceId, userId, name) triple.
     * NULL values are matched literally.
     */
    fun findByTriple(namespaceId: UUID?, userId: UUID?, name: String): ScheduledPrompt?

    /**
     * Find all non-removed scheduled prompts across the four overlay layers for the given
     * (namespaceId, userId) pair: platform, user-global, namespace-shared, user×namespace.
     * Returned in name-ascending order; callers are responsible for priority folding.
     */
    fun findEffective(namespaceId: UUID, userId: UUID): List<ScheduledPrompt>

    /**
     * Find all non-removed scheduled prompts at an exact scope level (no merge, no inheritance).
     * [agentConfigIds] is an optional filter; null or empty means no filter.
     */
    fun findByScope(namespaceId: UUID?, userId: UUID?, agentConfigIds: List<UUID>?): List<ScheduledPrompt>
}
