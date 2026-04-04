package io.whozoss.agentos.user

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository

/**
 * In-memory implementation of [UserRepository].
 *
 * Active only when `agentos.persistence.mode=in-memory`.
 * The default mode is file-system persistence via [FilesystemUserRepository].
 *
 * [findByExternalId] performs a linear scan and explicitly excludes soft-deleted users —
 * identical semantics to the filesystem variant and acceptable for the in-memory dev/test use case.
 */
@Repository
@ConditionalOnProperty(name = ["agentos.persistence.mode"], havingValue = "in-memory")
class InMemoryUserRepository :
    UserRepository,
    EntityRepository<User, String> by InMemoryEntityRepository(
        parentIdExtractor = { UserRepository.USER_PARENT_KEY },
        comparator = compareBy { it.email },
    ) {
    override fun findByExternalId(externalId: String): User? =
        findByParent(UserRepository.USER_PARENT_KEY)
            .firstOrNull { !it.metadata.removed && it.externalId == externalId }
}
