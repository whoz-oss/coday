package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.llmModelConfig.LlmModelConfig
import io.whozoss.agentos.llmModelConfig.LlmModelConfigRepository
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [LlmModelConfigRepository].
 *
 * Parent type is [UUID] representing the llmConfigId.
 */
open class Neo4jLlmModelConfigRepository(
    private val neo4jRepository: LlmModelConfigNodeNeo4jRepository,
) : LlmModelConfigRepository {

    override fun save(entity: LlmModelConfig): LlmModelConfig =
        neo4jRepository
            .save(LlmModelConfigNode.fromDomain(entity))
            .toDomain()
            .also { logger.debug { "[Neo4jLlmModelConfigRepository] Saved LlmModelConfig ${it.id} ('${entity.apiName}') under LlmConfig ${entity.llmConfigId}" } }

    override fun findByIds(ids: Collection<UUID>): List<LlmModelConfig> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<LlmModelConfig> =
        neo4jRepository
            .findActiveByLlmConfigId(parentId.toString())
            .map { it.toDomain() }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                logger.debug { "[Neo4jLlmModelConfigRepository] Soft-deleted LlmModelConfig $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByLlmConfigId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jLlmModelConfigRepository] Soft-deleted ${active.size} LlmModelConfigs under LlmConfig $parentId" }
        return active.size
    }

    companion object : KLogging()
}
