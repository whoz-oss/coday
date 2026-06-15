package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [IntegrationConfigRepository].
 *
 * [save] is `@Transactional` so the entity write and the BELONGS_TO link are part of a single
 * Neo4j transaction. If [Neo4jChildLinkService.link] throws (e.g. the parent Namespace node is
 * missing or the bolt connection drops mid-write), the transaction rolls back and no orphan
 * IntegrationConfig node is left behind (NFR-REL-3).
 *
 * User-only configs (`namespaceId == null && userId != null`) skip the link step — there is no
 * User-side edge yet (planned for story 6.2). The transactional boundary still applies, the link
 * call is just a no-op for that branch.
 *
 * [findByParent] delegates to [findByNamespaceId] by convention to preserve Epic 4 semantics.
 */
open class Neo4jIntegrationConfigRepository(
    private val neo4jRepository: IntegrationConfigNodeNeo4jRepository,
    private val objectMapper: ObjectMapper,
    private val childLinkService: Neo4jChildLinkService,
) : IntegrationConfigRepository {
    @Transactional
    open override fun save(entity: IntegrationConfig): IntegrationConfig =
        neo4jRepository
            .save(IntegrationConfigNode.fromDomain(entity, objectMapper))
            .also { savedNode ->
                entity.namespaceId?.let { nsId ->
                    childLinkService.link("IntegrationConfig", savedNode.id, "Namespace", nsId.toString())
                }
            }
            .toDomain(objectMapper)
            .also {
                logger.debug {
                    "[Neo4jIntegrationConfigRepository] Saved config ${it.id} ('${entity.name}') " +
                        "scope=(namespaceId=${entity.namespaceId}, userId=${entity.userId})"
                }
            }

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<IntegrationConfig> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain(objectMapper) }

    // findByParent by convention delegates to findByNamespaceId (Epic 4 behaviour preserved).
    override fun findByParent(parentId: UUID): List<IntegrationConfig> = findByNamespaceId(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<IntegrationConfig> =
        neo4jRepository
            .findActiveByNamespaceId(namespaceId.toString())
            .map { it.toDomain(objectMapper) }

    override fun findByUserId(userId: UUID): List<IntegrationConfig> =
        neo4jRepository
            .findActiveByUserId(userId.toString())
            .map { it.toDomain(objectMapper) }

    override fun findPlatform(): List<IntegrationConfig> =
        neo4jRepository
            .findActivePlatform()
            .map { it.toDomain(objectMapper) }

    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): IntegrationConfig? =
        neo4jRepository
            .findActiveByTripleKey(IntegrationConfigNode.computeTripleKey(namespaceId, userId, name))
            ?.toDomain(objectMapper)

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                // Switch the unique-constraint discriminant to a per-id tombstone so a future
                // create with the same triple is not blocked by this row's lingering active key.
                neo4jRepository.save(
                    node.copy(
                        removed = true,
                        tripleKey = IntegrationConfigNode.tombstoneTripleKey(node.id),
                    ),
                )
                logger.debug { "[Neo4jIntegrationConfigRepository] Soft-deleted config $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(
            active.map {
                it.copy(
                    removed = true,
                    tripleKey = IntegrationConfigNode.tombstoneTripleKey(it.id),
                )
            },
        )
        logger.debug { "[Neo4jIntegrationConfigRepository] Soft-deleted ${active.size} configs under namespace $parentId" }
        return active.size
    }

    companion object : KLogging()
}
