package io.whozoss.agentos.git.core

/**
 * Credentials handed to a single git invocation.
 *
 * The secret is scoped to one [GitInvocation] and reaches git through the child process
 * environment plus a generated askpass helper — never through `argv` (visible in `ps`),
 * never through `.git/config` (readable by every agent sharing the repository) and never
 * through a file on disk that outlives the call.
 *
 * [toString] is overridden so the secret cannot leak into a log line or an exception message.
 */
sealed interface GitCredentials {
    /** No credentials: anonymous access, or an operation that never touches the network. */
    data object None : GitCredentials

    /**
     * Username plus secret, the shape every HTTPS forge accepts for a service account
     * (GitHub uses `x-access-token` + token, GitLab `oauth2` + token).
     */
    data class UsernamePassword(
        val username: String,
        val secret: String,
    ) : GitCredentials {
        override fun toString(): String = "UsernamePassword(username=$username, secret=***)"
    }
}
