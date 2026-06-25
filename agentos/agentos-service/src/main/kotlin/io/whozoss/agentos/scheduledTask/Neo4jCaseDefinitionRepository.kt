package io.whozoss.agentos.scheduledTask

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [CaseDefinitionRepository].
 */
open class Neo4jCaseDefinitionRepository(
    private val neo4jRepository: CaseDefinitionNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
) : CaseDefinitionRepository {

    @Transactional
    open override fun save(entity: CaseDefinition): CaseDefinition =
        neo4jRepository
            .save(CaseDefinitionNode.fromDomain(entity))
            .also { childLinkService.link("CaseDefinition", it.id, "Namespace", entity.namespaceId.toString()) }
            .toDomain()
            .also { logger.debug { "[Neo4jCaseDefinitionRepository] Saved '${entity.name}' (${entity.id}) ns=${entity.namespaceId}" } }

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<CaseDefinition> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<CaseDefinition> =
        neo4jRepository
            .findActiveByNamespaceId(parentId.toString())
            .map { it.toDomain() }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                logger.debug { "[Neo4jCaseDefinitionRepository] Soft-deleted $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jCaseDefinitionRepository] Soft-deleted ${active.size} definitions under namespace $parentId" }
        return active.size
    }

    companion object : KLogging()
}
