package io.whozoss.agentos.git

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [RepositoryCheckoutRepository].
 *
 * [save] is `@Transactional` so the node write and its BELONGS_TO edge to the Namespace land in
 * one transaction; a failure to link must not leave an orphan checkout behind.
 */
open class Neo4jRepositoryCheckoutRepository(
    private val neo4jRepository: RepositoryCheckoutNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
) : RepositoryCheckoutRepository {
    @Transactional
    open override fun save(entity: RepositoryCheckout): RepositoryCheckout =
        neo4jRepository
            .save(RepositoryCheckoutNode.fromDomain(entity))
            .also { saved ->
                childLinkService.link("RepositoryCheckout", saved.id, "Namespace", entity.namespaceId.toString())
            }.toDomain()

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<RepositoryCheckout> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<RepositoryCheckout> =
        neo4jRepository
            .findActiveByNamespaceId(parentId.toString())
            .map { it.toDomain() }

    override fun findByNamespaceId(namespaceId: UUID): RepositoryCheckout? =
        neo4jRepository
            .findActiveByNamespaceKey(namespaceId.toString())
            ?.toDomain()

    override fun findByStatusIn(
        statuses: Collection<RepositoryCheckoutStatus>,
        limit: Int,
    ): List<RepositoryCheckout> =
        neo4jRepository
            .findActiveByStatusIn(statuses.map { it.name }, limit)
            .map { it.toDomain() }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                // Clear the discriminant so the namespace can be associated again: uniqueness is
                // expressed by the property's absence, not by a tombstone value.
                neo4jRepository.save(node.copy(removed = true, activeNamespaceKey = null))
                logger.debug { "[Neo4jRepositoryCheckoutRepository] Soft-deleted checkout $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true, activeNamespaceKey = null) })
        logger.debug {
            "[Neo4jRepositoryCheckoutRepository] Soft-deleted ${active.size} checkout(s) under namespace $parentId"
        }
        return active.size
    }

    companion object : KLogging()
}
