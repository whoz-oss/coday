package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Service for managing [AgentConfig] entities.
 *
 * Agent configs are scoped under a namespace — [parentId] is the namespace UUID.
 */
interface AgentConfigService : EntityService<AgentConfig, UUID> {
    /**
     * Find the first [AgentConfig] in [namespaceId] whose [AgentConfig.name] matches
     * [name] (case-insensitive). Returns null if none is found.
     */
    fun findByName(
        namespaceId: UUID,
        name: String,
    ): AgentConfig?

    /**
     * Returns the deduplicated list of [AgentConfig] available to the user identified
     * by [userExternalId], across all their group and namespace memberships.
     *
     * See [AgentConfigRepository.findAvailableByUserExternalId] for the full semantics.
     */
    fun findAvailableByUserExternalId(namespaceExternalId: String, userExternalId: String): List<AgentConfig>
}
