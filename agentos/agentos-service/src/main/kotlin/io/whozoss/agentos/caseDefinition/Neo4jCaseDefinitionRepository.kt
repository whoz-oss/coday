package io.whozoss.agentos.caseDefinition

import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [CaseDefinitionRepository].
 *
 * [save] is @Transactional because it performs two distinct Neo4j operations:
 * [neo4jRepository.save] then [childLinkService.link]. Both must succeed or roll back
 * together — no orphan CaseDefinition node should be left without its BELONGS_TO edge.
 *
 * All other methods delegate a single Neo4j operation and rely on the transaction
 * propagated by the calling service.
 *
 * Platform-level case definitions (namespaceId == null) skip the Namespace link step.
 * The AgentConfig link is always created (agentConfigId is mandatory).
 *
 * [findByParent] returns only non-removed namespace-shared case definitions (userId IS NULL)
 * for the given namespace.
 */
open class Neo4jCaseDefinitionRepository(
    private val neo4jRepository: CaseDefinitionNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
) : CaseDefinitionRepository {

    @Transactional
    open override fun save(entity: CaseDefinition): CaseDefinition =
        neo4jRepository
            .save(CaseDefinitionNode.fromDomain(entity))
            .also { savedNode ->
                // Links are created only on first save — SDN sets @Version to 0 on initial persist.
                // namespaceId and agentConfigId are immutable post-create so edges never need updating.
                if (savedNode.version == 0L) {
                    entity.namespaceId?.let { nsId ->
                        childLinkService.link(
                            EntityType.CASE_DEFINITION.label,
                            savedNode.id,
                            EntityType.NAMESPACE.label,
                            nsId.toString(),
                        )
                    }
                    childLinkService.link(
                        EntityType.CASE_DEFINITION.label,
                        savedNode.id,
                        EntityType.AGENT_CONFIG.label,
                        entity.agentConfigId.toString(),
                    )
                }
            }.toDomain()
            .also { logger.debug { "[Neo4jCaseDefinitionRepository] Saved '${entity.name}' (${entity.id}) scope=(ns=${entity.namespaceId}, user=${entity.userId})" } }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<CaseDefinition> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<CaseDefinition> =
        neo4jRepository
            .findActiveByNamespaceId(parentId.toString())
            .map { it.toDomain() }

    override fun findPlatform(): List<CaseDefinition> =
        neo4jRepository
            .findActivePlatform()
            .map { it.toDomain() }

    override fun findByTriple(namespaceId: UUID?, userId: UUID?, name: String): CaseDefinition? =
        neo4jRepository
            .findActiveByTripleKey(CaseDefinitionNode.computeTripleKey(namespaceId, userId, name))
            ?.toDomain()

    override fun findEffective(namespaceId: UUID, userId: UUID): List<CaseDefinition> =
        neo4jRepository
            .findEffective(namespaceId.toString(), userId.toString())
            .map { it.toDomain() }

    override fun findByScope(
        namespaceId: UUID?,
        userId: UUID?,
        agentConfigIds: List<UUID>?,
    ): List<CaseDefinition> =
        neo4jRepository
            .findByScope(
                namespaceId = namespaceId?.toString(),
                userId = userId?.toString(),
                agentConfigIds = agentConfigIds?.map { it.toString() }?.takeIf { it.isNotEmpty() },
            ).map { it.toDomain() }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(
                    node.copy(
                        removed = true,
                        tripleKey = CaseDefinitionNode.tombstoneTripleKey(node.id),
                    ),
                )
                logger.debug { "[Neo4jCaseDefinitionRepository] Soft-deleted $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(
            active.map {
                it.copy(
                    removed = true,
                    tripleKey = CaseDefinitionNode.tombstoneTripleKey(it.id),
                )
            },
        )
        logger.debug { "[Neo4jCaseDefinitionRepository] Soft-deleted ${active.size} definitions under namespace $parentId" }
        return active.size
    }

    companion object : KLogging()
}
