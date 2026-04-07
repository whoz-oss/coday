package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.user.UserRepository.Companion.USER_PARENT_KEY
import mu.KLogging
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
class Neo4jUserRepository(
    private val sdnRepo: UserNodeNeo4jRepository,
) : UserRepository {
    override fun save(entity: User): User =
        sdnRepo
            .save(UserNode.fromDomain(entity))
            .toDomain()
            .also { logger.debug { "[Neo4jUserRepository] Saved user ${it.id} (${entity.email})" } }

    override fun findByIds(ids: Collection<UUID>): List<User> =
        sdnRepo
            .findAllById(ids.map { it.toString() })
            .filter { !it.removed }
            .map { it.toDomain() }

    /**
     * [parentId] is always [USER_PARENT_KEY] = "all".
     * Returns all non-removed users.
     */
    override fun findByParent(parentId: String): List<User> =
        sdnRepo.findAllActive().map { it.toDomain() }

    override fun findByExternalId(externalId: String): User? =
        sdnRepo.findActiveByExternalId(externalId)?.toDomain()

    override fun delete(id: UUID): Boolean =
        sdnRepo
            .findById(id.toString())
            .filter { !it.removed }
            .map { node ->
                sdnRepo.save(node.copy(removed = true))
                logger.debug { "[Neo4jUserRepository] Soft-deleted user $id" }
                true
            }.orElse(false)

    @Transactional
    override fun deleteByParent(parentId: String): Int {
        val active = sdnRepo.findAllActive()
        sdnRepo.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jUserRepository] Soft-deleted ${active.size} users" }
        return active.size
    }

    companion object : KLogging()
}
