package io.whozoss.agentos.user

import io.whozoss.agentos.entity.EntityService

/**
 * Service for managing [User] entities.
 *
 * Users are root-level entities; [findAll] is the primary listing operation.
 * [findByExternalId] is the low-level lookup used internally.
 * [resolveOrCreateByExternalId] resolves or auto-creates a user from a raw identity key.
 * [getCurrentUser] is the caller's entry point: the user authenticated on the thread, else the one
 * [io.whozoss.agentos.security.SecurityService] resolves, as the matching persisted [User].
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
     * Resolve the user matching [externalId], auto-creating one on first access.
     *
     * @param externalId The IdP identifier (e.g. email from Cloudflare JWT, OS username).
     * @return The existing or newly-created [User].
     */
    fun resolveOrCreateByExternalId(externalId: String): User

    fun createByExternalIds(externalIds: Set<String>): List<User>

    /**
     * Resolve the [User] the current call is made for.
     *
     * That is the user authenticated on the thread
     * ([io.whozoss.agentos.security.declarative.AgentOsAuthentication]) when there is one: the user of
     * the HTTP request, or the user a messaging chat acts for. Otherwise identity extraction is delegated
     * to [io.whozoss.agentos.security.SecurityService], then [resolveOrCreateByExternalId] is called.
     * Both agree within a request, since the authentication filter resolves the request's user through
     * this very method. Controllers call it without any knowledge of how identity is resolved.
     *
     * @return The caller's persisted [User], auto-created on first access.
     */
    fun getCurrentUser(): User
}
