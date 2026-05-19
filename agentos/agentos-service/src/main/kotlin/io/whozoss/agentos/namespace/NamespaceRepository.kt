package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for NamespaceModel persistence.
 *
 * Namespaces are root-level entities grouped under a fixed parent key ([NAMESPACE_PARENT_KEY]).
 * All namespaces share a single directory named after that key.
 */
interface NamespaceRepository : EntityRepository<Namespace, String> {
    fun findByExternalId(externalId: String): Namespace?

    fun findByExternalIds(externalIds: Collection<String>): List<Namespace>

    /**
     * Create a DEPLOYED_TO relationship from each agent in [agentConfigIds] to the namespace [namespaceId].
     * Idempotent: calling multiple times with the same pair is safe.
     */
    fun deployAgents(namespaceId: UUID, agentConfigIds: Collection<UUID>)

    /**
     * Delete the DEPLOYED_TO relationship from each agent in [agentConfigIds] to the namespace [namespaceId].
     * No-op if a relationship does not exist.
     */
    fun undeployAgents(namespaceId: UUID, agentConfigIds: Collection<UUID>)

    companion object {
        const val NAMESPACE_PARENT_KEY = "all"
    }
}
