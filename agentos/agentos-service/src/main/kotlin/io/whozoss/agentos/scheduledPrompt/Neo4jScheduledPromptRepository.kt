package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Neo4j-backed implementation of [ScheduledPromptRepository].
 *
 * [save] is @Transactional: it performs two Neo4j operations ([neo4jRepository.save] then
 * [childLinkService.link]) that must succeed or roll back together.
 *
 * Platform-level scheduled prompts (namespaceId == null) skip the Namespace link step.
 * The AgentConfig link is always created (agentConfigId is mandatory).
 *
 * [findByParent] returns only non-removed namespace-shared scheduled prompts (userId IS NULL).
 */
open class Neo4jScheduledPromptRepository(
    private val neo4jRepository: ScheduledPromptNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
) : ScheduledPromptRepository {

    @Transactional
    open override fun save(entity: ScheduledPrompt): ScheduledPrompt =
        neo4jRepository
            .save(ScheduledPromptNode.fromDomain(entity))
            .also { savedNode ->
                if (savedNode.version == 0L) {
                    entity.namespaceId?.let { nsId ->
                        childLinkService.link(
                            EntityType.SCHEDULED_PROMPT.label,
                            savedNode.id,
                            EntityType.NAMESPACE.label,
                            nsId.toString(),
                        )
                    }
                    childLinkService.link(
                        EntityType.SCHEDULED_PROMPT.label,
                        savedNode.id,
                        EntityType.AGENT_CONFIG.label,
                        entity.agentConfigId.toString(),
                    )
                }
            }.toDomain()
            .also { logger.debug { "[Neo4jScheduledPromptRepository] Saved '${entity.name}' (${entity.id}) scope=(ns=${entity.namespaceId}, user=${entity.userId})" } }

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<ScheduledPrompt> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<ScheduledPrompt> =
        neo4jRepository.findActiveByNamespaceId(parentId.toString()).map { it.toDomain() }

    override fun findPlatform(): List<ScheduledPrompt> =
        neo4jRepository.findActivePlatform().map { it.toDomain() }

    override fun findByTriple(namespaceId: UUID?, userId: UUID?, name: String): ScheduledPrompt? =
        neo4jRepository
            .findActiveByTripleKey(ScheduledPromptNode.computeTripleKey(namespaceId, userId, name))
            ?.toDomain()

    override fun findEffective(namespaceId: UUID, userId: UUID): List<ScheduledPrompt> =
        neo4jRepository.findEffective(namespaceId.toString(), userId.toString()).map { it.toDomain() }

    override fun findByScope(namespaceId: UUID?, userId: UUID?, agentConfigIds: List<UUID>?): List<ScheduledPrompt> =
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
                        tripleKey = ScheduledPromptNode.tombstoneTripleKey(node.id),
                    ),
                )
                logger.debug { "[Neo4jScheduledPromptRepository] Soft-deleted $id" }
                true
            } ?: false

    override fun findDue(now: Instant): List<ScheduledPrompt> =
        neo4jRepository.findDue(now).map { it.toDomain() }

    override fun advanceNextRunAt(id: UUID, currentSlot: Instant, nextSlot: Instant): Boolean =
        neo4jRepository.advanceNextRunAt(id.toString(), currentSlot, nextSlot)

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(
            active.map {
                it.copy(
                    removed = true,
                    tripleKey = ScheduledPromptNode.tombstoneTripleKey(it.id),
                )
            },
        )
        logger.debug { "[Neo4jScheduledPromptRepository] Soft-deleted ${active.size} scheduled prompts under namespace $parentId" }
        return active.size
    }

    companion object : KLogging()
}
