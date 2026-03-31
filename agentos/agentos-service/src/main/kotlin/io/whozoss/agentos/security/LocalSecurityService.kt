package io.whozoss.agentos.security

import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Local-mode implementation of [SecurityService].
 *
 * Resolves the current user from the OS username (`user.name` system property).
 * On first access, a [User] record is auto-created and persisted so that the rest
 * of AgentOS can reference a stable UUID for this identity.
 *
 * Rejects usernames that belong to system/service accounts (root, daemon, www-data, etc.)
 * to prevent a server running under a shared OS account from silently impersonating a
 * real user — mirrors the same guard in Coday's Express server.
 */
class LocalSecurityService(
    private val userService: UserService,
) : SecurityService {

    override fun resolveCurrentUser(): User {
        val username = System.getProperty("user.name") ?: System.getenv("USER")
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot determine OS username")

        if (username in FORBIDDEN_USERNAMES) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "AgentOS is running under a system account ('$username'). " +
                    "Start it under a personal user account instead.",
            )
        }

        return userService.findByExternalId(username) ?: createLocalUser(username)
    }

    private fun createLocalUser(username: String): User {
        logger.info { "[Security/local] Auto-creating user for OS username '$username'" }
        val user = User(
            metadata = EntityMetadata(),
            externalId = username,
            email = username,
            firstname = username,
        )
        return userService.create(user)
    }

    companion object : KLogging() {
        /**
         * OS / service account names that must not be accepted as AgentOS user identities.
         * Mirrors the FORBIDDEN_USERNAMES list in Coday's server.ts.
         */
        val FORBIDDEN_USERNAMES: Set<String> = setOf(
            "root", "admin", "administrator",
            "daemon", "nobody", "www-data",
            "nginx", "apache", "httpd",
            "node", "app", "service",
            "docker", "ubuntu", "ec2-user",
        )
    }
}
