package io.whozoss.agentos.messaging

import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [MessagingIdentityRepository].
 *
 * Writes are `@Transactional` so a node and its `ActiveMessagingIdentity` label land together: a second
 * active identity for the same account or the same user must roll back rather than stay unlabelled.
 */
open class Neo4jMessagingIdentityRepository(
    private val neo4jRepository: MessagingIdentityNodeNeo4jRepository,
) : MessagingIdentityRepository {
    @Transactional
    open override fun save(entity: MessagingIdentity): MessagingIdentity =
        neo4jRepository
            .save(MessagingIdentityNode.fromDomain(entity))
            .also { saved ->
                if (entity.metadata.removed) neo4jRepository.setInactive(saved.id) else neo4jRepository.setActive(saved.id)
            }.toDomain()

    @Transactional(readOnly = true)
    open override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<MessagingIdentity> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    @Transactional(readOnly = true)
    open override fun findByParent(parentId: UUID): List<MessagingIdentity> =
        neo4jRepository.findActiveByUserId(parentId.toString()).map { it.toDomain() }

    @Transactional(readOnly = true)
    open override fun findByIdentityKey(identityKey: String): MessagingIdentity? =
        neo4jRepository.findActiveByIdentityKey(identityKey)?.toDomain()

    @Transactional(readOnly = true)
    open override fun findByUserKey(userKey: String): MessagingIdentity? =
        neo4jRepository.findActiveByUserKey(userKey)?.toDomain()

    @Transactional(readOnly = true)
    open override fun findByAccount(
        platform: MessagingPlatform,
        accountId: String,
    ): List<MessagingIdentity> = neo4jRepository.findActiveByAccount(platform.name, accountId).map { it.toDomain() }

    @Transactional
    open override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                neo4jRepository.setInactive(node.id)
                logger.debug { "[Neo4jMessagingIdentityRepository] Soft-deleted identity $id" }
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
