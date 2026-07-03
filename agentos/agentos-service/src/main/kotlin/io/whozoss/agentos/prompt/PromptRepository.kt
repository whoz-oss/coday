package io.whozoss.agentos.prompt

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [Prompt] persistence.
 *
 * [findByParent] returns only non-removed namespace-shared prompts (userId IS NULL)
 * for the given namespace — user-scoped overlays are excluded.
 * Platform-level prompts (namespaceId == null, userId == null) are retrieved via [findPlatform].
 */
interface PromptRepository : EntityRepository<Prompt, UUID> {
    /**
     * Find all non-removed platform-level prompts (namespaceId IS NULL AND userId IS NULL).
     */
    fun findPlatform(): List<Prompt>

    /**
     * Find all non-removed prompts scoped to the given user,
     * regardless of [Prompt.namespaceId].
     */
    fun findByUserId(userId: UUID): List<Prompt>

    /**
     * Find a single non-removed prompt matching the (namespaceId, userId, name) triple.
     * NULL values are matched literally.
     */
    fun findByTriple(namespaceId: UUID?, userId: UUID?, name: String): Prompt?
}
