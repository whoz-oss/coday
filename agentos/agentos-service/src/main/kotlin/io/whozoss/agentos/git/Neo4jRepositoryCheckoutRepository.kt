package io.whozoss.agentos.git

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [RepositoryCheckoutRepository].
 *
 * Writes are `@Transactional` so the node, its BELONGS_TO edge to the Namespace and its
 * `ActiveRepositoryCheckout` label land together: a failure to link must not leave an orphan
 * checkout behind, and a second active checkout must roll back rather than stay unlabelled.
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
                if (entity.metadata.removed) neo4jRepository.setInactive(saved.id) else neo4jRepository.setActive(saved.id)
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
            .findActiveByNamespaceId(namespaceId.toString())
            .firstOrNull()
            ?.toDomain()

    override fun findByStatusIn(
        statuses: Collection<RepositoryCheckoutStatus>,
        limit: Int,
    ): List<RepositoryCheckout> =
        neo4jRepository
            .findActiveByStatusIn(statuses.map { it.name }, limit)
            .map { it.toDomain() }

    @Transactional
    open override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                // Removing the label is what lets the namespace be associated again.
                neo4jRepository.setInactive(node.id)
                logger.debug { "[Neo4jRepositoryCheckoutRepository] Soft-deleted checkout $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        neo4jRepository.setInactiveByNamespaceId(parentId.toString())
        logger.debug {
            "[Neo4jRepositoryCheckoutRepository] Soft-deleted ${active.size} checkout(s) under namespace $parentId"
        }
        return active.size
    }

    companion object : KLogging()
}
