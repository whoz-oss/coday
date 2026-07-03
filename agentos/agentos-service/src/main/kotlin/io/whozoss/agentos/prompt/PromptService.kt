package io.whozoss.agentos.prompt

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Service for managing [Prompt] entities.
 *
 * Four scope modes: platform `(null, null)`, namespace-shared `(ns, null)`,
 * user-global `(null, user)`, user×namespace `(ns, user)`.
 * Authorization is enforced in [PromptController].
 */
interface PromptService : EntityService<Prompt, UUID> {
    /**
     * Find all non-removed platform-level prompts (namespaceId IS NULL AND userId IS NULL).
     */
    fun findPlatform(): List<Prompt>

    /**
     * Find all non-removed prompts scoped to the given user,
     * regardless of [Prompt.namespaceId].
     */
    fun findByUserId(userId: UUID): List<Prompt>
}
