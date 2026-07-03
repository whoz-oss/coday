package io.whozoss.agentos.prompt

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [PromptRepository].
 *
 * [save] is @Transactional so the entity write and the BELONGS_TO link are part of a
 * single Neo4j transaction. When Neo4jChildLinkService.link throws mid-write the
 * transaction rolls back and no orphan Prompt node is left behind.
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
                entity.namespaceId?.let { nsId ->
                    childLinkService.link("Prompt", savedNode.id, "Namespace", nsId.toString())
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

    companion object : KLogging()
}
