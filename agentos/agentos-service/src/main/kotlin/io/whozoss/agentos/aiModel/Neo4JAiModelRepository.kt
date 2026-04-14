package io.whozoss.agentos.aiModel

import io.whozoss.agentos.sdk.aiProvider.AiModel
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [AiModelRepository].
 *
 * Parent type is [java.util.UUID] representing the llmConfigId.
 */
open class Neo4JAiModelRepository(
    private val neo4jRepository: AiModelNodeNeo4jRepository,
) : AiModelRepository {
    override fun save(entity: AiModel): AiModel =
        neo4jRepository
            .save(AiModelNode.fromDomain(entity))
            .toDomain()
            .also {
                logger.debug {
                    "[Neo4jAiModelRepository] Saved AiModel ${it.id} ('${entity.apiName}') under LlmConfig ${entity.aiProviderId}"
                }
            }

    override fun findByIds(ids: Collection<UUID>): List<AiModel> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<AiModel> =
        neo4jRepository
            .findActiveByLlmConfigId(parentId.toString())
            .map { it.toDomain() }

    override fun findByNamespaceId(namespaceId: UUID): List<AiModel> =
        neo4jRepository
            .findActiveByNamespaceId(namespaceId.toString())
            .map { it.toDomain() }

    override fun findByLlmConfigAndApiName(
        llmConfigId: UUID,
        apiName: String,
    ): AiModel? =
        neo4jRepository
            .findActiveByLlmConfigIdAndApiName(llmConfigId.toString(), apiName)
            ?.toDomain()

    override fun findByLlmConfigAndAlias(
        llmConfigId: UUID,
        alias: String,
    ): AiModel? =
        neo4jRepository
            .findActiveByLlmConfigIdAndAlias(llmConfigId.toString(), alias)
            ?.toDomain()

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                logger.debug { "[Neo4jAiModelRepository] Soft-deleted AiModel $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByLlmConfigId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jAiModelRepository] Soft-deleted ${active.size} AiModels under LlmConfig $parentId" }
        return active.size
    }

    companion object : KLogging()
}
