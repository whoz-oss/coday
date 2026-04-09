package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.llmConfig.LlmConfig
import io.whozoss.agentos.llmConfig.LlmConfigRepository
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [LlmConfigRepository].
 *
 * [findByParent] delegates to [findByNamespaceId] by convention — namespace is the
 * primary scope for this ticket. [findByUserId] will be the primary path once
 * user-scoped configs are introduced (WZ-31210).
 */
open class Neo4jLlmConfigRepository(
    private val neo4jRepository: LlmConfigNodeNeo4jRepository,
) : LlmConfigRepository {

    override fun save(entity: LlmConfig): LlmConfig =
        neo4jRepository
            .save(LlmConfigNode.fromDomain(entity))
            .toDomain()
            .also { logger.debug { "[Neo4jLlmConfigRepository] Saved LlmConfig ${it.id} ('${entity.name}')" } }

    override fun findByIds(ids: Collection<UUID>): List<LlmConfig> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { it.removed != true }
            .map { it.toDomain() }

    // findByParent by convention delegates to findByNamespaceId
    override fun findByParent(parentId: UUID): List<LlmConfig> = findByNamespaceId(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<LlmConfig> =
        neo4jRepository
            .findActiveByNamespaceId(namespaceId.toString())
            .map { it.toDomain() }

    override fun findByUserId(userId: UUID): List<LlmConfig> =
        neo4jRepository
            .findActiveByUserId(userId.toString())
            .map { it.toDomain() }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                logger.debug { "[Neo4jLlmConfigRepository] Soft-deleted LlmConfig $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jLlmConfigRepository] Soft-deleted ${active.size} LlmConfigs under namespace $parentId" }
        return active.size
    }

    companion object : KLogging()
}
