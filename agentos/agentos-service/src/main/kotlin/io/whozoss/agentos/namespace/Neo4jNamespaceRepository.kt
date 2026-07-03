package io.whozoss.agentos.namespace

import io.whozoss.agentos.namespace.NamespaceRepository.Companion.NAMESPACE_PARENT_KEY
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [NamespaceRepository].
 *
 * Namespaces are root-level entities. The parent type is [String] and the only
 * valid value is [NAMESPACE_PARENT_KEY] = "all" (matching the filesystem convention).
 * [findByParent] and [deleteByParent] ignore the actual value and operate on all
 * non-removed namespaces.
 */
open class Neo4jNamespaceRepository(
    private val namespaceNodeNeo4jRepository: NamespaceNodeNeo4jRepository,
) : NamespaceRepository {
    override fun save(entity: Namespace): Namespace =
        namespaceNodeNeo4jRepository
            .save(NamespaceNode.fromDomain(entity))
            .also { if (!entity.metadata.removed) namespaceNodeNeo4jRepository.setActive(it.id) }
            .toDomain()
            .also { logger.debug { "[Neo4jNamespaceRepository] Saved namespace ${it.id}" } }

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<Namespace> =
        namespaceNodeNeo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    /**
     * [parentId] is always [NAMESPACE_PARENT_KEY] = "all".
     * Returns all non-removed namespaces.
     */
    override fun findByParent(parentId: String): List<Namespace> = namespaceNodeNeo4jRepository.findAllActive().map { it.toDomain() }

    override fun delete(id: UUID): Boolean =
        namespaceNodeNeo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                namespaceNodeNeo4jRepository.save(node.copy(removed = true))
                namespaceNodeNeo4jRepository.setInactive(node.id)
                logger.debug { "[Neo4jNamespaceRepository] Soft-deleted namespace $id" }
                true
            } ?: false

    override fun findByExternalId(externalId: String): Namespace? =
        namespaceNodeNeo4jRepository.findActiveByExternalId(externalId)?.toDomain()

    override fun findByExternalIds(externalIds: Collection<String>): List<Namespace> =
        if (externalIds.isEmpty()) emptyList()
        else namespaceNodeNeo4jRepository.findActiveByExternalIdIn(externalIds).map { it.toDomain() }

    override fun deployAgents(namespaceId: UUID, agentConfigIds: Collection<UUID>) {
        namespaceNodeNeo4jRepository.deployAgents(namespaceId.toString(), agentConfigIds.map { it.toString() })
    }

    override fun undeployAgents(namespaceId: UUID, agentConfigIds: Collection<UUID>) {
        namespaceNodeNeo4jRepository.undeployAgents(namespaceId.toString(), agentConfigIds.map { it.toString() })
    }

    @Transactional
    open override fun deleteByParent(parentId: String): Int {
        val active = namespaceNodeNeo4jRepository.findAllActive()
        namespaceNodeNeo4jRepository.saveAll(active.map { it.copy(removed = true) })
        namespaceNodeNeo4jRepository.setInactiveByIds(active.map { it.id })
        logger.debug { "[Neo4jNamespaceRepository] Soft-deleted ${active.size} namespaces" }
        return active.size
    }

    companion object : KLogging()
}
