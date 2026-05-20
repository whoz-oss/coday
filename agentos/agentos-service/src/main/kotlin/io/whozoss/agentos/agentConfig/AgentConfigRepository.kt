package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.entity.EntityRepository
import java.util.*

/**
 * Repository for [AgentConfig] persistence.
 *
 * Agent configs are scoped under a namespace — [parentId] is the namespace UUID.
 */
interface AgentConfigRepository : EntityRepository<AgentConfig, UUID> {
    fun findAvailableByUserExternalId(namespaceExternalId: String, userExternalId: String): List<AgentConfig>

    /**
     * Returns the first [AgentConfig] accessible to [userId] in [namespaceId] whose
     * name matches [name] case-insensitively, or null if none is found.
     */
    fun findAvailableByUserIdAndName(namespaceId: UUID, userId: UUID, name: String): AgentConfig?
}
