package io.whozoss.agentos.git.core

import java.net.URI

/** A github.com repository, named by the HTTPS remote URL a workspace was cloned from. */
data class GitHubRepository(
    val owner: String,
    val name: String,
) {
    val fullName: String get() = "$owner/$name"

    companion object {
        private val SEGMENT = Regex("[A-Za-z0-9_.-]+")

        /** Path segments the API URL would resolve away, reaching another endpoint than the repository's. */
        private val DOT_SEGMENTS = setOf(".", "..")

        /** The repository behind [remoteUrl], or null when it is not an HTTPS github.com repository. */
        fun fromRemoteUrl(remoteUrl: String): GitHubRepository? {
            val remote = runCatching { URI(remoteUrl) }.getOrNull() ?: return null
            if (remote.scheme != "https" || remote.host?.equals("github.com", ignoreCase = true) != true) return null
            val parts = remote.path.orEmpty().trim('/').removeSuffix(".git").split('/')
            if (parts.size != 2 || !parts.all { it.matches(SEGMENT) && it !in DOT_SEGMENTS }) return null
            return GitHubRepository(parts[0], parts[1])
        }
    }
}
