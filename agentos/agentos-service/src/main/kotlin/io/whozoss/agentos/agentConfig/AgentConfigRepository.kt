package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.entity.EntityRepository
import java.util.*

/**
 * Repository for [AgentConfig] persistence.
 *
 * Agent configs are scoped under a namespace — [parentId] is the namespace UUID.
 */
interface AgentConfigRepository : EntityRepository<AgentConfig, UUID> {
    fun findAvailableByUserExternalId(namespaceId: UUID, userExternalId: String): List<AgentConfig>

    /**
     * Returns all [AgentConfig]s accessible to [userId] in [namespaceId].
     * Called once per user message to build the authorized agent set used
     * for routing and redirect enforcement.
     */
    fun findAvailableByUserId(namespaceId: UUID, userId: UUID): List<AgentConfig>

    /**
     * Returns [AgentConfig]s accessible to [userId] in [namespaceId] whose name
     * matches [agentName] case-insensitively. The comparison is pushed to Neo4j
     * via `toLower()` — no Kotlin-side filtering needed.
     */
    fun findAvailableByUserIdAndName(namespaceId: UUID, userId: UUID, agentName: String): List<AgentConfig>
}
