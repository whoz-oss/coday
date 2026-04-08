package io.whozoss.agentos.user

import io.whozoss.agentos.entity.EntityRepository

/**
 * Repository for [User] persistence.
 *
 * Users are root-level entities grouped under a fixed parent key ([USER_PARENT_KEY]).
 * All users share a single directory named after that key.
 *
 * Extends [EntityRepository] with [findByExternalId] for identity-based lookup
 * (resolving a request's identity header to an AgentOS user record).
 */
interface UserRepository : EntityRepository<User, String> {
    /**
     * Find a non-removed user by their external identity provider key (e.g. email).
     *
     * @param externalId The IdP identifier — must match [User.externalId] exactly.
     * @return The matching user, or null if absent or soft-deleted.
     */
    fun findByExternalId(externalId: String): User?

    companion object {
        const val USER_PARENT_KEY = "all"
    }
}
