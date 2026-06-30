package io.whozoss.agentos.prompt

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Service for managing [Prompt] entities.
 *
 * Prompts are either platform-level (namespaceId == null) or namespace-scoped.
 * Authorization (platform requires Super Admin, namespace requires WRITE) is enforced
 * in [PromptController].
 */
interface PromptService : EntityService<Prompt, UUID> {
    /**
     * Find all non-removed prompts scoped to the given namespace.
     */
    fun findByNamespaceId(namespaceId: UUID): List<Prompt>

    /**
     * Find all non-removed platform-level prompts (namespaceId IS NULL).
     */
    fun findPlatform(): List<Prompt>
}
