package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
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
    private val sdnRepo: CaseNodeNeo4jRepository,
) : CaseRepository {
    override fun save(entity: Case): Case =
        sdnRepo
            .save(CaseNode.fromDomain(entity))
            .toDomain()
            .also { logger.debug { "[Neo4jCaseRepository] Saved case ${it.id} under namespace ${entity.namespaceId}" } }

    override fun findByIds(ids: Collection<UUID>): List<Case> =
        sdnRepo
            .findAllById(ids.map { it.toString() })
            .filter { !it.removed }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<Case> =
        sdnRepo
            .findActiveByNamespaceId(parentId.toString())
            .map { it.toDomain() }

    override fun delete(id: UUID): Boolean =
        sdnRepo
            .findByIdOrNull(id.toString())
            ?.takeIf { !it.removed }
            ?.let { node ->
                sdnRepo.save(node.copy(removed = true))
                logger.debug { "[Neo4jCaseRepository] Soft-deleted case $id" }
                true
            } ?: false

    @Transactional
    override fun deleteByParent(parentId: UUID): Int {
        val active = sdnRepo.findActiveByNamespaceId(parentId.toString())
        sdnRepo.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jCaseRepository] Soft-deleted ${active.size} cases under namespace $parentId" }
        return active.size
    }

    companion object : KLogging()
}
