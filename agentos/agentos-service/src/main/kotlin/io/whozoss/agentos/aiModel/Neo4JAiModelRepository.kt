package io.whozoss.agentos.aiModel

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import io.whozoss.agentos.sdk.aiProvider.AiModel
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [AiModelRepository].
 *
 * Parent type is [java.util.UUID] representing the aiProviderId.
 */
open class Neo4JAiModelRepository(
    private val neo4jRepository: AiModelNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
) : AiModelRepository {
    override fun save(entity: AiModel): AiModel =
        neo4jRepository
            .save(AiModelNode.fromDomain(entity))
            .also { savedNode ->
                entity.namespaceId?.let { nsId ->
                    childLinkService.link("AiModel", savedNode.id, "Namespace", nsId.toString())
                }
            }
            .toDomain()
            .also {
                logger.debug {
                    "[Neo4jAiModelRepository] Saved AiModel ${it.id} ('${entity.apiModelName}') under AiProvider ${entity.aiProviderId}"
                }
            }

    override fun findByIds(ids: Collection<UUID>): List<AiModel> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<AiModel> =
        neo4jRepository
            .findActiveByAiProviderId(parentId.toString())
            .map { it.toDomain() }

    override fun findByNamespaceId(namespaceId: UUID): List<AiModel> =
        neo4jRepository
            .findActiveByNamespaceId(namespaceId.toString())
            .map { it.toDomain() }

    override fun findByAiProviderAndApiName(
        aiProviderId: UUID,
        apiName: String,
    ): AiModel? =
        neo4jRepository
            .findActiveByAiProviderIdAndApiName(aiProviderId.toString(), apiName)
            ?.toDomain()

    override fun findByAiProviderAndAlias(
        aiProviderId: UUID,
        alias: String,
    ): AiModel? =
        neo4jRepository
            .findActiveByAiProviderIdAndAlias(aiProviderId.toString(), alias)
            ?.toDomain()

    override fun findByUserId(userId: UUID): List<AiModel> =
        neo4jRepository
            .findActiveByUserId(userId.toString())
            .map { it.toDomain() }

    /**
     * Triple lookup matches by `coalesce(alias, apiName)` — the runtime resolves a model
     * by alias-first with apiName fallback, and the tripleKey discriminator is computed
     * from the same `<matching name>` so a single key indexes both arms (cf.
     * [AiModelNode.computeTripleKey]).
     *
     * The caller passes the `name` it wants to resolve; we use it directly as the matching
     * name. The constraint guarantees at most one row per `(namespaceId, userId, name)`.
     */
    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AiModel? =
        neo4jRepository
            .findActiveByTripleKey(AiModelNode.computeTripleKey(namespaceId, userId, alias = name, apiModelName = name))
            ?.toDomain()

    @Transactional
    open override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                // Tombstone the tripleKey at soft-delete so the unique slot is freed for
                // immediate re-creation of `(ns, user, name)`. Cf. RFC §D11.
                neo4jRepository.save(
                    node.copy(
                        removed = true,
                        tripleKey = AiModelNode.tombstoneTripleKey(node.id),
                    ),
                )
                logger.debug { "[Neo4jAiModelRepository] Soft-deleted AiModel $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByAiProviderId(parentId.toString())
        neo4jRepository.saveAll(
            active.map { it.copy(removed = true, tripleKey = AiModelNode.tombstoneTripleKey(it.id)) },
        )
        logger.debug { "[Neo4jAiModelRepository] Soft-deleted ${active.size} AiModels under AiProvider $parentId" }
        return active.size
    }

    companion object : KLogging()
}
