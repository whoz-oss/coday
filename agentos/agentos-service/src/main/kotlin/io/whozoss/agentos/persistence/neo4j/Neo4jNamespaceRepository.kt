package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.namespace.NamespaceRepository.Companion.NAMESPACE_PARENT_KEY
import mu.KLogging
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
    private val sdnRepo: NamespaceNeo4jRepository,
) : NamespaceRepository {
    override fun save(entity: Namespace): Namespace {
        val node = NamespaceNode.fromDomain(entity)
        val saved = sdnRepo.save(node)
        logger.debug { "[Neo4jNamespaceRepository] Saved namespace ${saved.id}" }
        return saved.toDomain()
    }

    override fun findByIds(ids: Collection<UUID>): List<Namespace> =
        sdnRepo
            .findAllById(ids.map { it.toString() })
            .filter { !it.removed }
            .map { it.toDomain() }

    /**
     * [parentId] is always [NAMESPACE_PARENT_KEY] = "all".
     * Returns all non-removed namespaces.
     */
    override fun findByParent(parentId: String): List<Namespace> =
        sdnRepo.findAllActive().map { it.toDomain() }

    override fun delete(id: UUID): Boolean {
        val node = sdnRepo.findById(id.toString()).orElse(null) ?: return false
        if (node.removed) return false
        sdnRepo.save(node.copy(removed = true))
        logger.debug { "[Neo4jNamespaceRepository] Soft-deleted namespace $id" }
        return true
    }

    @Transactional
    override fun deleteByParent(parentId: String): Int {
        val active = sdnRepo.findAllActive()
        sdnRepo.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jNamespaceRepository] Soft-deleted ${active.size} namespaces" }
        return active.size
    }

    companion object : KLogging()
}
