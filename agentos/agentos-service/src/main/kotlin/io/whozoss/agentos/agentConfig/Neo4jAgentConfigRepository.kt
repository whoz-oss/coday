package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [AgentConfigRepository].
 *
 * Parent type is [UUID] representing the namespaceId.
 */
open class Neo4jAgentConfigRepository(
    private val neo4jRepository: AgentConfigNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
) : AgentConfigRepository {

    override fun save(entity: AgentConfig): AgentConfig =
        neo4jRepository
            .save(AgentConfigNode.fromDomain(entity))
            .also { childLinkService.link("AgentConfig", it.id, "Namespace", entity.namespaceId.toString()) }
            .toDomain()
            .also { logger.debug { "[Neo4jAgentConfigRepository] Saved agent config ${it.id} ('${entity.name}') under namespace ${entity.namespaceId}" } }

    override fun findByIds(ids: Collection<UUID>): List<AgentConfig> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<AgentConfig> =
        neo4jRepository
            .findActiveByNamespaceId(parentId.toString())
            .map { it.toDomain() }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                logger.debug { "[Neo4jAgentConfigRepository] Soft-deleted agent config $id" }
                true
            } ?: false

    override fun findAvailableByUserExternalId(namespaceExternalId: String, userExternalId: String): List<AgentConfig> {
        return neo4jRepository
            .findAvailableByUserExternalId(namespaceExternalId, userExternalId)
            .map { it.toDomain() }
    }

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jAgentConfigRepository] Soft-deleted ${active.size} agent configs under namespace $parentId" }
        return active.size
    }

    companion object : KLogging()
}
