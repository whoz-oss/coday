package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import mu.KLogging
import java.util.UUID

/**
 * Neo4j-backed implementation of [CaseRepository].
 *
 * Delegates to [CaseNeo4jRepository] for all storage operations,
 * converting between [Case] domain objects and [CaseNode] graph projections.
 *
 * Parent type is [UUID] representing the namespaceId.
 */
class Neo4jCaseRepository(
    private val sdnRepo: CaseNeo4jRepository,
) : CaseRepository {
    override fun save(entity: Case): Case {
        val node = CaseNode.fromDomain(entity)
        val saved = sdnRepo.save(node)
        logger.debug { "[Neo4jCaseRepository] Saved case ${saved.id} under namespace ${saved.namespaceId}" }
        return saved.toDomain()
    }

    override fun findByIds(ids: Collection<UUID>): List<Case> {
        val stringIds = ids.map { it.toString() }
        return sdnRepo
            .findAllById(stringIds)
            .filter { !it.removed }
            .map { it.toDomain() }
    }

    override fun findByParent(parentId: UUID): List<Case> =
        sdnRepo
            .findActiveByNamespaceId(parentId.toString())
            .map { it.toDomain() }

    override fun delete(id: UUID): Boolean {
        val node = sdnRepo.findById(id.toString()).orElse(null) ?: return false
        if (node.removed) return false
        sdnRepo.save(node.copy(removed = true))
        logger.debug { "[Neo4jCaseRepository] Soft-deleted case $id" }
        return true
    }

    override fun deleteByParent(parentId: UUID): Int {
        val active = sdnRepo.findActiveByNamespaceId(parentId.toString())
        active.forEach { sdnRepo.save(it.copy(removed = true)) }
        logger.debug { "[Neo4jCaseRepository] Soft-deleted ${active.size} cases under namespace $parentId" }
        return active.size
    }

    companion object : KLogging()
}
