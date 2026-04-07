package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
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
class Neo4jNamespaceRepository(
    private val namespaceNodeNeo4jRepository: NamespaceNodeNeo4jRepository,
) : NamespaceRepository {
    override fun save(entity: Namespace): Namespace =
        namespaceNodeNeo4jRepository
            .save(NamespaceNode.fromDomain(entity))
            .toDomain()
            .also { logger.debug { "[Neo4jNamespaceRepository] Saved namespace ${it.id}" } }

    override fun findByIds(ids: Collection<UUID>): List<Namespace> =
        namespaceNodeNeo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { it.removed != true }
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
                logger.debug { "[Neo4jNamespaceRepository] Soft-deleted namespace $id" }
                true
            } ?: false

    @Transactional
    override fun deleteByParent(parentId: String): Int {
        val active = namespaceNodeNeo4jRepository.findAllActive()
        namespaceNodeNeo4jRepository.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jNamespaceRepository] Soft-deleted ${active.size} namespaces" }
        return active.size
    }

    companion object : KLogging()
}
