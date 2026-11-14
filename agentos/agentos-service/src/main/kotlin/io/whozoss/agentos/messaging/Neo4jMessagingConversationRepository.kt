package io.whozoss.agentos.messaging

import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [MessagingConversationRepository].
 *
 * Writes are `@Transactional` so a node and its `ActiveMessagingConversation` label land together: a second
 * active conversation for the same chat must roll back rather than stay unlabelled.
 */
open class Neo4jMessagingConversationRepository(
    private val neo4jRepository: MessagingConversationNodeNeo4jRepository,
) : MessagingConversationRepository {
    @Transactional
    open override fun save(entity: MessagingConversation): MessagingConversation =
        neo4jRepository
            .save(MessagingConversationNode.fromDomain(entity))
            .also { saved ->
                if (entity.metadata.removed) neo4jRepository.setInactive(saved.id) else neo4jRepository.setActive(saved.id)
            }.toDomain()

    @Transactional(readOnly = true)
    open override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<MessagingConversation> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    @Transactional(readOnly = true)
    open override fun findByParent(parentId: UUID): List<MessagingConversation> =
        neo4jRepository.findActiveByUserId(parentId.toString()).map { it.toDomain() }

    @Transactional(readOnly = true)
    open override fun findByConversationKey(conversationKey: String): MessagingConversation? =
        neo4jRepository.findActiveByConversationKey(conversationKey)?.toDomain()

    @Transactional
    open override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                neo4jRepository.setInactive(node.id)
                logger.debug { "[Neo4jMessagingConversationRepository] Soft-deleted conversation $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByUserId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        neo4jRepository.setInactiveByUserId(parentId.toString())
        return active.size
    }

    companion object : KLogging()
}
