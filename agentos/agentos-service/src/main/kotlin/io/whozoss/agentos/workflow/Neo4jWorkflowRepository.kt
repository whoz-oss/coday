package io.whozoss.agentos.workflow

import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed [WorkflowRepository].
 *
 * Workflows carry their namespace as a property only: no `BELONGS_TO` edge is written, because
 * no query traverses from a namespace to its workflows.
 */
open class Neo4jWorkflowRepository(
    private val neo4jRepository: WorkflowNodeNeo4jRepository,
) : WorkflowRepository {
    override fun save(entity: Workflow): Workflow = neo4jRepository.save(WorkflowNode.fromDomain(entity)).toDomain()

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Workflow> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<Workflow> =
        neo4jRepository.findActiveByScope(parentId.toString()).map { it.toDomain() }

    override fun findPlatform(): List<Workflow> = neo4jRepository.findActiveByScope(null).map { it.toDomain() }

    override fun findByName(
        namespaceId: UUID?,
        name: String,
    ): Workflow? = neo4jRepository.findActiveByDoubleKey(WorkflowNode.computeDoubleKey(namespaceId, name))?.toDomain()

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true, doubleKey = WorkflowNode.tombstoneDoubleKey(node.id)))
                logger.debug { "[Neo4jWorkflowRepository] Soft-deleted workflow $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByScope(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true, doubleKey = WorkflowNode.tombstoneDoubleKey(it.id)) })
        return active.size
    }

    companion object : KLogging()
}
