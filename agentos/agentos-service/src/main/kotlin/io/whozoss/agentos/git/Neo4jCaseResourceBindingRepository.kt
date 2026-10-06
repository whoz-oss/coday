package io.whozoss.agentos.git

import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [CaseResourceBindingRepository].
 *
 * Writes are `@Transactional` so a node and its `ActiveCaseResourceBinding` label land together:
 * a second active binding for a root case must roll back rather than stay unlabelled.
 */
open class Neo4jCaseResourceBindingRepository(
    private val neo4jRepository: CaseResourceBindingNodeNeo4jRepository,
) : CaseResourceBindingRepository {
    @Transactional
    open override fun save(entity: CaseResourceBinding): CaseResourceBinding =
        neo4jRepository
            .save(CaseResourceBindingNode.fromDomain(entity))
            .also { saved ->
                if (entity.metadata.removed) neo4jRepository.setInactive(saved.id) else neo4jRepository.setActive(saved.id)
            }.toDomain()

    @Transactional(readOnly = true)
    open override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<CaseResourceBinding> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    @Transactional(readOnly = true)
    open override fun findByParent(parentId: UUID): List<CaseResourceBinding> =
        neo4jRepository
            .findActiveByNamespaceId(parentId.toString())
            .map { it.toDomain() }

    @Transactional(readOnly = true)
    open override fun findByRootCaseId(rootCaseId: UUID): CaseResourceBinding? =
        neo4jRepository
            .findActiveByRootCaseId(rootCaseId.toString())
            ?.toDomain()

    @Transactional
    open override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                neo4jRepository.setInactive(node.id)
                logger.debug { "[Neo4jCaseResourceBindingRepository] Soft-deleted binding $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        neo4jRepository.setInactiveByNamespaceId(parentId.toString())
        return active.size
    }

    companion object : KLogging()
}
