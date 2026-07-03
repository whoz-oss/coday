package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [AgentConfigRepository].
 *
 * Parent type is [UUID?] representing the namespaceId — null means platform-level.
 */
open class Neo4jAgentConfigRepository(
    private val neo4jRepository: AgentConfigNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
) : AgentConfigRepository {
    override fun save(entity: AgentConfig): AgentConfig =
        neo4jRepository
            .save(AgentConfigNode.fromDomain(entity))
            .also { node ->
                // Platform agents (namespaceId = null) have no namespace edge.
                entity.namespaceId?.let { childLinkService.link("AgentConfig", node.id, "Namespace", it.toString()) }
            }.toDomain()
            .also {
                logger.debug {
                    "[Neo4jAgentConfigRepository] Saved agent config ${it.id} ('${entity.name}') under namespace ${entity.namespaceId ?: "<platform>"}"
                }
            }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<AgentConfig> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID?): List<AgentConfig> = findByNamespaceId(parentId).map { it.toDomain() }

    override fun findByParent(
        parentId: UUID?,
        withDisabled: Boolean,
    ): List<AgentConfig> = findByNamespaceId(parentId, withDisabled).map { it.toDomain() }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                logger.debug { "[Neo4jAgentConfigRepository] Soft-deleted agent config $id" }
                true
            } ?: false

    override fun findDeployedByNamespaceIdAndUserIdAndName(
        namespaceId: UUID?,
        userId: UUID?,
        agentName: String?,
        withDisabled: Boolean,
    ): List<AgentConfig> =
        neo4jRepository
            .findDeployedByNamespaceIdAndUserId(
                namespaceId = namespaceId.toString(),
                userId = userId?.toString(),
                agentName = agentName,
                withDisabled = withDisabled,
            ).map { it.toDomain() }

    @Transactional
    open override fun deleteByParent(parentId: UUID?): Int {
        val active = findByNamespaceId(namespaceId = parentId, withDisabled = false)
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        val scope = parentId?.toString() ?: "<platform>"
        logger.debug { "[Neo4jAgentConfigRepository] Soft-deleted ${active.size} agent configs under $scope" }
        return active.size
    }

    private fun findByNamespaceId(
        namespaceId: UUID?,
        withDisabled: Boolean = false,
    ): List<AgentConfigNode> =
        when {
            namespaceId == null -> neo4jRepository.findPlatformAgents(withDisabled)
            else -> neo4jRepository.findByNamespaceId(namespaceId.toString(), withDisabled)
        }

    companion object : KLogging()
}
