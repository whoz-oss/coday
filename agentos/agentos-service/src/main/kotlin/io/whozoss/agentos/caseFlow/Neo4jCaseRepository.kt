package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [CaseRepository].
 *
 * Delegates to [CaseNodeNeo4jRepository] for all storage operations,
 * converting between [Case] domain objects and [CaseNode] graph projections.
 *
 * Parent type is [UUID] representing the namespaceId.
 */
open class Neo4jCaseRepository(
    private val caseNodeNeo4jRepository: CaseNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
) : CaseRepository {
    override fun save(entity: Case): Case =
        caseNodeNeo4jRepository
            .save(CaseNode.fromDomain(entity))
            .also { childLinkService.link("Case", it.id, "Namespace", entity.namespaceId.toString()) }
            .also { it.createdBy?.let { createdBy -> childLinkService.link("Case", it.id, "User", createdBy, relationship = "CREATED_BY") } }
            .toDomain()
            .also { logger.debug { "[Neo4jCaseRepository] Saved case ${it.id} under namespace ${entity.namespaceId}" } }

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<Case> =
        caseNodeNeo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<Case> =
        caseNodeNeo4jRepository
            .findActiveByNamespaceId(parentId.toString())
            .map { it.toDomain() }

    override fun findAccessibleByUserInNamespace(userId: UUID, namespaceId: UUID): List<Case> =
        caseNodeNeo4jRepository
            .findAccessibleByUserInNamespace(
                userId = userId.toString(),
                namespaceId = namespaceId.toString(),
            )
            .map { it.toDomain() }

    override fun findConcerningUser(userId: UUID): List<Case> =
        caseNodeNeo4jRepository
            .findConcerningUser(userId = userId.toString())
            .map { it.toDomain() }

    override fun findConcerningUserInNamespace(userId: UUID, namespaceId: UUID): List<Case> =
        caseNodeNeo4jRepository
            .findConcerningUserInNamespace(
                userId = userId.toString(),
                namespaceId = namespaceId.toString(),
            )
            .map { it.toDomain() }

    override fun delete(id: UUID): Boolean =
        caseNodeNeo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                caseNodeNeo4jRepository.save(node.copy(removed = true))
                logger.debug { "[Neo4jCaseRepository] Soft-deleted case $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = caseNodeNeo4jRepository.findActiveByNamespaceId(parentId.toString())
        caseNodeNeo4jRepository.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jCaseRepository] Soft-deleted ${active.size} cases under namespace $parentId" }
        return active.size
    }

    companion object : KLogging()
}
