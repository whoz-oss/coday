package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.sdk.aiProvider.AiProvider
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [AiProviderRepository].
 *
 * [findByParent] delegates to [findByNamespaceId] by convention — namespace is the
 * primary scope for this ticket. [findByUserId] will be the primary path once
 * user-scoped configs are introduced (WZ-31210).
 */
open class Neo4jAiProviderRepository(
    private val neo4jRepository: AiProviderNodeNeo4jRepository,
) : AiProviderRepository {
    override fun save(entity: AiProvider): AiProvider =
        neo4jRepository
            .save(AiProviderNode.fromDomain(entity))
            .toDomain()
            .also { logger.debug { "[Neo4jAiProviderRepository] Saved AiProvider ${it.id} ('${entity.name}')" } }

    override fun findByIds(ids: Collection<UUID>): List<AiProvider> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { it.removed != true }
            .map { it.toDomain() }

    // findByParent by convention delegates to findByNamespaceId
    override fun findByParent(parentId: UUID): List<AiProvider> = findByNamespaceId(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<AiProvider> =
        neo4jRepository
            .findActiveByNamespaceId(namespaceId.toString())
            .map { it.toDomain() }

    override fun findByUserId(userId: UUID): List<AiProvider> =
        neo4jRepository
            .findActiveByUserId(userId.toString())
            .map { it.toDomain() }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                logger.debug { "[Neo4jAiProviderRepository] Soft-deleted AiProvider $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jAiProviderRepository] Soft-deleted ${active.size} AiProviders under namespace $parentId" }
        return active.size
    }

    companion object : KLogging()
}
