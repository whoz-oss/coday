package io.whozoss.agentos.user

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository

/**
 * In-memory implementation of [UserRepository].
 *
 * Active when `agentos.persistence.mode` is absent, `in-memory`, or any value
 * other than `neo4j` or `embedded-neo4j`. This is the default fallback used by
 * the openapi spec generation task and lightweight local runs.
 *
 * [findByExternalId] performs a linear scan — acceptable given the bounded user
 * count in non-production modes.
 */
@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryUserRepository :
    UserRepository,
    EntityRepository<User, String> by InMemoryEntityRepository(
        parentIdExtractor = { UserRepository.USER_PARENT_KEY },
        comparator = compareBy { it.email },
    ) {
    override fun findByExternalId(externalId: String): User? =
        findByParent(UserRepository.USER_PARENT_KEY)
            .firstOrNull { !it.metadata.removed && it.externalId == externalId }

    override fun count(): Long =
        findByParent(UserRepository.USER_PARENT_KEY)
            .count { !it.metadata.removed }
            .toLong()
}
