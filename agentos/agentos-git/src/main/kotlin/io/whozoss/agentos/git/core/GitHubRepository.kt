package io.whozoss.agentos.git.core

import java.net.URI

/** A github.com repository, named by the HTTPS remote URL a workspace was cloned from. */
data class GitHubRepository(
    val owner: String,
    val name: String,
) {
    val fullName: String get() = "$owner/$name"

    companion object {
        private const val HTTPS = "https"
        private const val HOST = "github.com"
        private const val GIT_SUFFIX = ".git"
        private val SEGMENT = Regex("[A-Za-z0-9_.-]+")

        /** Path segments the API URL would resolve away, reaching another endpoint than the repository's. */
        private val DOT_SEGMENTS = setOf(".", "..")

        /** The repository behind [remoteUrl], or null when it is not an HTTPS github.com repository. */
        fun fromRemoteUrl(remoteUrl: String): GitHubRepository? =
            runCatching { URI(remoteUrl) }.getOrNull()
                ?.takeIf { it.scheme == HTTPS && it.host.equals(HOST, ignoreCase = true) }
                ?.let { it.path.orEmpty().trim('/').removeSuffix(GIT_SUFFIX).split('/') }
                ?.takeIf { parts -> parts.size == 2 && parts.all { it.matches(SEGMENT) && it !in DOT_SEGMENTS } }
                ?.let { (owner, name) -> GitHubRepository(owner, name) }
    }
}
