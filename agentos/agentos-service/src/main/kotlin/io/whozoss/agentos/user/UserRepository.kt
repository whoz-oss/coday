package io.whozoss.agentos.user

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.sdk.api.user.UsersListFilters

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

    fun findByExternalIds(externalIds: Set<String>): List<User>

    /**
     * Keyset page of the non-removed users matching [filters], sorted by external id ascending:
     * at most [limit] users whose external id is strictly greater than [afterExternalId]
     * (from the start when null).
     */
    fun findPage(filters: UsersListFilters, afterExternalId: String?, limit: Int): List<User>

    /** Number of non-removed users matching [filters]. */
    fun countMatching(filters: UsersListFilters): Long

    /**
     * Count the total number of non-removed users in the system.
     *
     * @return The number of active (non-removed) users
     */
    fun count(): Long

    companion object {
        const val USER_PARENT_KEY = "all"
    }
}
