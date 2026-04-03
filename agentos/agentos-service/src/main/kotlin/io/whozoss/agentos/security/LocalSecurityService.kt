package io.whozoss.agentos.security

import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Local-mode implementation of [SecurityService].
 *
 * Resolves the current identity from the OS username (`user.name` system property,
 * with fallback to `USER` / `USERNAME` environment variables for Unix/macOS and Windows).
 *
 * Rejects usernames that belong to system/service accounts (root, daemon, www-data, etc.)
 * to prevent a server running under a shared OS account from silently impersonating a
 * real user — mirrors the same guard in Coday's Express server.
 *
 * User persistence (lookup / auto-create) is handled upstream by
 * [io.whozoss.agentos.user.UserService.resolveOrCreateByExternalId].
 */
class LocalSecurityService : SecurityService {

    override fun resolveCurrentIdentity(): String {
        val username = resolveOsUsername()

        if (username in FORBIDDEN_USERNAMES) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "AgentOS is running under a system account ('$username'). " +
                    "Start it under a personal user account instead.",
            )
        }

        return username
    }

    private fun resolveOsUsername(): String =
        sequenceOf(
            System.getProperty("user.name"),
            System.getenv("USER"),      // Unix/macOS
            System.getenv("USERNAME"),  // Windows
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?: throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Cannot determine OS username",
            )

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
