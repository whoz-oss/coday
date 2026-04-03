package io.whozoss.agentos.user

import io.whozoss.agentos.entity.EntityService

/**
 * Service for managing [User] entities.
 *
 * Users are root-level entities; [findAll] is the primary listing operation.
 * [findByExternalId] is the identity-resolution entry point used by the HTTP
 * layer to map an incoming request to a known AgentOS user.
 */
interface UserService : EntityService<User, String> {
    /**
     * Retrieve all non-removed users.
     */
    fun findAll(): List<User>

    /**
     * Find a non-removed user by their external identity provider key.
     *
     * @param externalId The IdP identifier (e.g. email from Cloudflare JWT).
     * @return The matching user, or null if not found.
     */
    fun findByExternalId(externalId: String): User?

}
