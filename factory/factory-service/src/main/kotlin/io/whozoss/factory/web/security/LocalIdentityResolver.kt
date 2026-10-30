package io.whozoss.factory.web.security

import jakarta.servlet.http.HttpServletRequest

/**
 * `local` security mode: the caller is the OS user running Factory.
 *
 * Every header and every bearer is ignored, so a stale or garbage token (an AgentOS
 * Auth Setting may still hold an old one) can never produce a 401. A system account
 * (root, daemon, www-data, ...) is refused so a server started under a shared account
 * does not silently impersonate a real user.
 *
 * Mirrors `io.whozoss.agentos.security.LocalSecurityService` of `agentos-service`,
 * including [FORBIDDEN_USERNAMES]. The builds are separate: this is a copy, keep both
 * in sync.
 *
 * @param osUsername supplies the OS username; replaceable so tests do not depend on the
 * machine's user (CI containers often run as root, which is forbidden).
 */
class LocalIdentityResolver(
    private val osUsername: () -> String? = ::systemOsUsername,
) : IdentityResolver {

    override fun resolve(request: HttpServletRequest): IdentityResult {
        val username = osUsername()?.takeIf { it.isNotBlank() }
        return when {
            username == null -> IdentityResult.Unavailable("Cannot determine OS username")
            username in FORBIDDEN_USERNAMES -> IdentityResult.Forbidden(
                "Factory is running under a system account ('$username'). " +
                    "Start it under a personal user account instead.",
            )
            else -> IdentityResult.Authenticated(username)
        }
    }

    companion object {
        /**
         * OS / service account names refused as a Factory identity. Identical to
         * `LocalSecurityService.FORBIDDEN_USERNAMES` in AgentOS.
         */
        val FORBIDDEN_USERNAMES: Set<String> = setOf(
            "root", "admin", "administrator",
            "daemon", "nobody", "www-data",
            "nginx", "apache", "httpd",
            "node", "app", "service",
            "docker", "ubuntu", "ec2-user",
        )

        /** `user.name`, then `USER` (Unix/macOS), then `USERNAME` (Windows). */
        fun systemOsUsername(): String? =
            sequenceOf(
                System.getProperty("user.name"),
                System.getenv("USER"),
                System.getenv("USERNAME"),
            ).firstOrNull { !it.isNullOrBlank() }
    }
}
