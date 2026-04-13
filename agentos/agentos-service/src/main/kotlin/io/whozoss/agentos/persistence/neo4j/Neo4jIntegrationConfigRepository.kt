package io.whozoss.agentos.persistence.neo4j

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigRepository
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [IntegrationConfigRepository].
 *
 * Parent type is [UUID] representing the namespaceId.
 *
 * [parameters] is serialised to/from JSON string by [IntegrationConfigNode] via
 * the injected [objectMapper], keeping the Neo4j node flat.
 */
open class Neo4jIntegrationConfigRepository(
    private val neo4jRepository: IntegrationConfigNodeNeo4jRepository,
    private val objectMapper: ObjectMapper,
) : IntegrationConfigRepository {

    override fun save(entity: IntegrationConfig): IntegrationConfig =
        neo4jRepository
            .save(IntegrationConfigNode.fromDomain(entity, objectMapper))
            .toDomain(objectMapper)
            .also { logger.debug { "[Neo4jIntegrationConfigRepository] Saved config ${it.id} ('${entity.name}') under namespace ${entity.namespaceId}" } }

    override fun findByIds(ids: Collection<UUID>): List<IntegrationConfig> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { it.removed != true }
            .map { it.toDomain(objectMapper) }

    override fun findByParent(parentId: UUID): List<IntegrationConfig> =
        neo4jRepository
            .findActiveByNamespaceId(parentId.toString())
            .map { it.toDomain(objectMapper) }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                logger.debug { "[Neo4jIntegrationConfigRepository] Soft-deleted config $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jIntegrationConfigRepository] Soft-deleted ${active.size} configs under namespace $parentId" }
        return active.size
    }

    companion object : KLogging()
}
