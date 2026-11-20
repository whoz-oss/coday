package io.whozoss.agentos.user

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.pagination.KeysetPage
import io.whozoss.agentos.pagination.KeysetPaging
import io.whozoss.agentos.sdk.api.user.UsersListFilters

/**
 * Service for managing [User] entities.
 *
 * Users are root-level entities; [findAll] is the primary listing operation.
 * [findByExternalId] is the low-level lookup used internally.
 * [resolveOrCreateByExternalId] resolves or auto-creates a user from a raw identity key.
 * [getCurrentUser] is the request-scoped entry point: resolves the caller's identity via
 * [io.whozoss.agentos.security.SecurityService] and returns the matching persisted [User].
 */
interface UserService : EntityService<User, String> {
    /**
     * Retrieve all non-removed users.
     */
    fun findAll(): List<User>

    /**
     * Count the total number of non-removed users in the system.
     * Prefer this over [findAll] when only the count is needed to avoid loading all entities.
     *
     * @return The number of active (non-removed) users
     */
    fun count(): Long

    /**
     * Find a non-removed user by their external identity provider key.
     *
     * @param externalId The IdP identifier (e.g. email from Cloudflare JWT, OS username).
     * @return The matching user, or null if not found.
     */
    fun findByExternalId(externalId: String): User?

    fun findByExternalIds(externalIds: Set<String>): List<User>

    /**
     * One page of the non-removed users matching [filters], sorted by external id; [next] is the cursor of the
     * previous page (see [KeysetPaging]). `totalCount` respects [filters].
     */
    fun findPage(filters: UsersListFilters, limit: Int?, next: String?): KeysetPage<User>

    /**
     * Resolve the user matching [externalId], auto-creating one on first access.
     *
     * @param externalId The IdP identifier (e.g. email from Cloudflare JWT, OS username).
     * @return The existing or newly-created [User].
     */
    fun resolveOrCreateByExternalId(externalId: String): User

    fun createByExternalIds(externalIds: Set<String>): List<User>

    /**
     * Resolve the [User] for the current HTTP request.
     *
     * Delegates identity extraction to [io.whozoss.agentos.security.SecurityService],
     * then calls [resolveOrCreateByExternalId]. Controllers call this method directly
     * without any knowledge of how identity is resolved.
     *
     * @return The caller's persisted [User], auto-created on first access.
     */
    fun getCurrentUser(): User
}
