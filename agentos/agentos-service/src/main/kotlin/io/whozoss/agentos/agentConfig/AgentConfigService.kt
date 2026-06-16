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
    fun findAvailableByUserExternalId(namespaceId: UUID, userExternalId: String): List<AgentConfig>

    /**
     * Returns [AgentConfig]s accessible to [userId] in [namespaceId].
     * When [agentName] is non-null, further filters to configs whose name matches
     * [agentName] case-insensitively. The comparison is pushed to Neo4j via
     * `toLower()` — no Kotlin-side filtering needed.
     */
    fun findAvailableByNamespaceIdAndUserId(namespaceId: UUID, userId: UUID, agentName: String? = null): List<AgentConfig>

    /**
     * Returns all agent configs in [namespaceId], optionally filtered to published
     * (enabled) agents only.
     *
     * @param namespaceId The namespace to list agents for
     * @param withDisabled When `true` (the default), all configs are returned; when `false`, only published agents are returned
     */
    fun findByNamespace(namespaceId: UUID, withDisabled: Boolean = true): List<AgentConfig>

    /**
     * Enables an agent config, making it active.
     *
     * @throws ResourceNotFoundException if the agent config does not exist
     */
    fun enable(id: UUID): AgentConfig

    /**
     * Disables an agent config, making it inactive.
     *
     * @throws ResourceNotFoundException if the agent config does not exist
     */
    fun disable(id: UUID): AgentConfig
}
