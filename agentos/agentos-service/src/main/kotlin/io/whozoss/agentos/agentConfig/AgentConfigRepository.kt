package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.entity.EntityRepository
import java.util.*

/**
 * Repository for [AgentConfig] persistence.
 *
 * Agent configs are scoped under a namespace — [parentId] is the namespace UUID.
 */
interface AgentConfigRepository : EntityRepository<AgentConfig, UUID> {
    /**
     * Returns [AgentConfig]s accessible to [userId] in [namespaceId].
     * When [agentName] is non-null, further filters to configs whose name matches
     * [agentName] case-insensitively. The comparison is pushed to Neo4j via
     * `toLower()` — no Kotlin-side filtering needed.
     */
    fun findAvailableByNamespaceIdAndUserId(namespaceId: UUID, userId: UUID, agentName: String?): List<AgentConfig>

    /**
     * Returns [AgentConfig]s belonging to [parentId], optionally filtered to published ones.
     *
     * When [withDisabled] is `true` (the default), all active (non-removed) configs are returned.
     * When [withDisabled] is `false`, only enabled (published) configs are returned.
     *
     * @param parentId The namespace UUID
     * @param withDisabled When `false`, restricts results to published configs only
     */
    fun findByParent(parentId: UUID, withDisabled: Boolean): List<AgentConfig>
}
