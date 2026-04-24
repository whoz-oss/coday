package io.whozoss.agentos.user

import io.whozoss.agentos.user.UserRepository.Companion.USER_PARENT_KEY
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [UserRepository].
 *
 * Users are root-level entities. The parent type is [String] and the only
 * valid value is [USER_PARENT_KEY] = "all".
 * [findByParent] and [deleteByParent] ignore the actual value and operate on
 * all non-removed users.
 *
 * [findByExternalId] delegates to an indexed Cypher query rather than the
 * O(n) filesystem scan used by [io.whozoss.agentos.user.FilesystemUserRepository].
 */
open class Neo4jUserRepository(
    private val userNodeNeo4jRepository: UserNodeNeo4jRepository,
) : UserRepository {
    override fun save(entity: User): User =
        userNodeNeo4jRepository
            .save(UserNode.fromDomain(entity))
            .toDomain()
            .also { logger.debug { "[Neo4jUserRepository] Saved user ${it.id} (${entity.email})" } }

    override fun findByIds(ids: Collection<UUID>): List<User> =
        userNodeNeo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { it.removed != true }
            .map { it.toDomain() }

    /**
     * [parentId] is always [USER_PARENT_KEY] = "all".
     * Returns all non-removed users.
     */
    override fun findByParent(parentId: String): List<User> = userNodeNeo4jRepository.findAllActive().map { it.toDomain() }

    override fun findByExternalId(externalId: String): User? = userNodeNeo4jRepository.findActiveByExternalId(externalId)?.toDomain()

    override fun count(): Long = userNodeNeo4jRepository.countActive()

    override fun delete(id: UUID): Boolean =
        userNodeNeo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                userNodeNeo4jRepository.save(node.copy(removed = true))
                logger.debug { "[Neo4jUserRepository] Soft-deleted user $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: String): Int {
        val active = userNodeNeo4jRepository.findAllActive()
        userNodeNeo4jRepository.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jUserRepository] Soft-deleted ${active.size} users" }
        return active.size
    }

    companion object : KLogging()
}
