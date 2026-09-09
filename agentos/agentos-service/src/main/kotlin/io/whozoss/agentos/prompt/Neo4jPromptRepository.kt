package io.whozoss.agentos.prompt

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [PromptRepository].
 *
 * [save] is @Transactional because it performs two distinct Neo4j operations:
 * [neo4jRepository.save] then [childLinkService.link]. Both must succeed or roll back
 * together — no orphan Prompt node should be left without its BELONGS_TO edge.
 *
 * All other methods delegate a single Neo4j operation and rely on the transaction
 * propagated by the calling service (e.g. [AgentConfigServiceImpl.delete] is
 * @Transactional and covers [softDeleteByAgentConfigId]).
 *
 * Platform-level prompts (namespaceId == null) skip the link step.
 *
 * [findByParent] returns only non-removed namespace-shared prompts (userId IS NULL)
 * for the given namespace.
 */
open class Neo4jPromptRepository(
    private val neo4jRepository: PromptNodeNeo4jRepository,
    private val objectMapper: ObjectMapper,
    private val childLinkService: Neo4jChildLinkService,
) : PromptRepository {
    @Transactional
    open override fun save(entity: Prompt): Prompt =
        neo4jRepository
            .save(PromptNode.fromDomain(entity, objectMapper))
            .also { savedNode ->
                // Links are created only on first save — SDN sets @Version to 0 on initial persist.
                // namespaceId and agentConfigId are immutable post-create so edges never need updating.
                if (savedNode.version == 0L) {
                    entity.namespaceId?.let { nsId ->
                        childLinkService.link(EntityType.PROMPT.label, savedNode.id, EntityType.NAMESPACE.label, nsId.toString())
                    }
                    entity.agentConfigId?.let { agentId ->
                        childLinkService.link(EntityType.PROMPT.label, savedNode.id, EntityType.AGENT_CONFIG.label, agentId.toString())
                    }
                }
            }.toDomain(objectMapper)
            .also {
                logger.debug {
                    "[Neo4jPromptRepository] Saved prompt ${it.id} ('${entity.name}') " +
                        "scope=(namespaceId=${entity.namespaceId}, userId=${entity.userId})"
                }
            }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Prompt> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain(objectMapper) }

    override fun findByParent(parentId: UUID): List<Prompt> =
        neo4jRepository
            .findActiveByNamespaceId(parentId.toString())
            .map { it.toDomain(objectMapper) }

    override fun findPlatform(): List<Prompt> =
        neo4jRepository
            .findActivePlatform()
            .map { it.toDomain(objectMapper) }

    override fun findByUserId(userId: UUID): List<Prompt> =
        neo4jRepository
            .findActiveByUserId(userId.toString())
            .map { it.toDomain(objectMapper) }

    override fun findByTriple(namespaceId: UUID?, userId: UUID?, name: String): Prompt? =
        neo4jRepository
            .findActiveByTripleKey(PromptNode.computeTripleKey(namespaceId, userId, name))
            ?.toDomain(objectMapper)

    override fun findEffective(namespaceId: UUID, userId: UUID): List<Prompt> =
        neo4jRepository
            .findEffective(namespaceId.toString(), userId.toString())
            .map { it.toDomain(objectMapper) }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(
                    node.copy(
                        removed = true,
                        tripleKey = PromptNode.tombstoneTripleKey(node.id),
                    ),
                )
                logger.debug { "[Neo4jPromptRepository] Soft-deleted prompt $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(
            active.map {
                it.copy(
                    removed = true,
                    tripleKey = PromptNode.tombstoneTripleKey(it.id),
                )
            },
        )
        logger.debug { "[Neo4jPromptRepository] Soft-deleted ${active.size} prompts under namespace $parentId" }
        return active.size
    }

    override fun softDeleteByAgentConfigId(agentConfigId: UUID) {
        neo4jRepository.softDeleteByAgentConfigId(agentConfigId.toString())
        logger.debug { "[Neo4jPromptRepository] Soft-deleted prompts linked to agentConfigId=$agentConfigId" }
    }

    override fun findByScope(
        namespaceId: UUID?,
        userId: UUID?,
        agentConfigIds: List<UUID>?,
    ): List<Prompt> =
        neo4jRepository
            .findByScope(
                namespaceId = namespaceId?.toString(),
                userId = userId?.toString(),
                agentConfigIds = agentConfigIds?.map { it.toString() }?.takeIf { it.isNotEmpty() },
            ).map { it.toDomain(objectMapper) }

    companion object : KLogging()
}
