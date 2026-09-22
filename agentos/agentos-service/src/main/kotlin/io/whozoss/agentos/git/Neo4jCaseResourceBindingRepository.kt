package io.whozoss.agentos.git

import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [CaseResourceBindingRepository].
 */
open class Neo4jCaseResourceBindingRepository(
    private val neo4jRepository: CaseResourceBindingNodeNeo4jRepository,
) : CaseResourceBindingRepository {
    override fun save(entity: CaseResourceBinding): CaseResourceBinding =
        neo4jRepository.save(CaseResourceBindingNode.fromDomain(entity)).toDomain()

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<CaseResourceBinding> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<CaseResourceBinding> =
        neo4jRepository
            .findActiveByNamespaceId(parentId.toString())
            .map { it.toDomain() }

    override fun findByRootCaseId(rootCaseId: UUID): CaseResourceBinding? =
        neo4jRepository
            .findActiveByRootCaseKey(rootCaseId.toString())
            ?.toDomain()

    override fun findByStatusIn(
        statuses: Collection<CaseResourceStatus>,
        limit: Int,
    ): List<CaseResourceBinding> =
        neo4jRepository
            .findActiveByStatusIn(statuses.map { it.name }, limit)
            .map { it.toDomain() }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true, activeRootCaseKey = null))
                logger.debug { "[Neo4jCaseResourceBindingRepository] Soft-deleted binding $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true, activeRootCaseKey = null) })
        return active.size
    }

    companion object : KLogging()
}
