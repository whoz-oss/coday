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
     * Find the default [AgentConfig] for [namespaceId].
     *
     * The default is the [AgentConfig] with the lowest [EntityMetadata.created] timestamp
     * (i.e. the first one ever created in the namespace). Returns null if the namespace
     * has no agent configs at all.
     */
    fun findDefault(namespaceId: UUID): AgentConfig?
}
