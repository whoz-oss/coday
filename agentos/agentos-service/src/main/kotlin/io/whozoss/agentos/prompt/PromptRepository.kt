package io.whozoss.agentos.prompt

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [Prompt] persistence.
 *
 * [findByParent] is implemented as [findByNamespaceId] by convention.
 * Platform-level prompts (namespaceId == null) are retrieved via [findPlatform].
 */
interface PromptRepository : EntityRepository<Prompt, UUID> {
    /**
     * Find all non-removed prompts scoped to the given namespace.
     */
    fun findByNamespaceId(namespaceId: UUID): List<Prompt>

    /**
     * Find all non-removed platform-level prompts (namespaceId IS NULL).
     */
    fun findPlatform(): List<Prompt>
}
