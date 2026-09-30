package io.whozoss.agentos.git.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal client for the github.com REST API, authenticated per call.
 *
 * The API root is fixed: repository configuration can never redirect a token. A token never
 * reaches an exception either; an unusable header value is reported without it.
 */
class GitHubApi(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
    private val mapper: ObjectMapper = ObjectMapper(),
    private val apiRoot: URI = GITHUB_API,
) {
    /** A GitHub answer. [body] is null when it is empty or not JSON. */
    data class Response(
        val status: Int,
        val body: JsonNode?,
    )

    fun get(path: String, token: String): Response = send(request(path, token).GET())

    fun post(path: String, token: String, payload: Map<String, Any?>): Response =
        send(
            request(path, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))),
        )

    private fun request(path: String, token: String): HttpRequest.Builder =
        try {
            HttpRequest.newBuilder(apiRoot.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer $token")
        } catch (_: IllegalArgumentException) {
            // HttpRequest includes an invalid header's value in its exception message.
            // Do not retain that exception as a cause: it may contain the token.
            throw IllegalArgumentException("GitHub credentials cannot be used in an HTTP request")
        }

    private fun send(builder: HttpRequest.Builder): Response {
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val body = response.body()?.takeIf { it.isNotBlank() }?.let { runCatching { mapper.readTree(it) }.getOrNull() }
        return Response(response.statusCode(), body)
    }

    companion object {
        private val GITHUB_API = URI("https://api.github.com/")
    }
}

/** A github.com repository, named by the HTTPS remote URL a workspace was cloned from. */
data class GitHubRepository(
    val owner: String,
    val name: String,
) {
    val fullName: String get() = "$owner/$name"

    companion object {
        private val SEGMENT = Regex("[A-Za-z0-9_.-]+")

        /** The repository behind [remoteUrl], or null when it is not an HTTPS github.com repository. */
        fun fromRemoteUrl(remoteUrl: String): GitHubRepository? {
            val remote = runCatching { URI(remoteUrl) }.getOrNull() ?: return null
            if (remote.scheme != "https" || remote.host?.equals("github.com", ignoreCase = true) != true) return null
            val parts = remote.path.orEmpty().trim('/').removeSuffix(".git").split('/')
            if (parts.size != 2 || !parts.all { it.matches(SEGMENT) }) return null
            return GitHubRepository(parts[0], parts[1])
        }
    }
}
