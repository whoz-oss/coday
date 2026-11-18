package io.whozoss.agentos.git.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

/**
 * Minimal client for the github.com REST API, authenticated per call.
 *
 * The API root is fixed: repository configuration can never redirect a token. A token never
 * reaches an exception either; an unusable header value is reported without it.
 */
class GitHubApi(
    private val client: HttpClient = newClient(),
    private val mapper: ObjectMapper = ObjectMapper(),
    private val apiRoot: URI = GITHUB_API,
) {
    /** A GitHub answer. [body] is null when it is empty or not JSON. */
    data class Response(
        val status: Int,
        val body: JsonNode?,
    )

    fun get(path: String, token: String): Response = send(request(path, token).GET())

    /**
     * GitHub's job-log endpoint returns a short-lived signed redirect. The
     * bearer credential is sent only to the GitHub API; never to the log host.
     * Missing, unsafe or oversized logs are unavailable evidence.
     */
    fun getJobLog(path: String, token: String): ByteArray? {
        val redirect = client.send(request(path, token).GET().build(), BodyHandlers.discarding())
        if (redirect.statusCode() == 404) return null
        check(redirect.statusCode() == 302) { "GitHub job log unavailable (HTTP ${redirect.statusCode()})" }
        val location = redirect.headers().firstValue("Location").orElse(null) ?: return null
        val signed = runCatching { URI.create(location) }.getOrNull() ?: return null
        if (!safeLogRedirect(signed)) return null
        return runCatching {
            val unsignedRequest = HttpRequest.newBuilder(signed).timeout(REQUEST_TIMEOUT).GET().build()
            val response = client.send(unsignedRequest, BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                response.body().close()
                return null
            }
            response.body().use { stream ->
                stream.readNBytes(MAX_JOB_LOG_BYTES + 1).takeIf { it.size <= MAX_JOB_LOG_BYTES }
            }
        }.getOrNull()
    }

    fun post(path: String, token: String, payload: Map<String, Any?>): Response =
        send(
            request(path, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))),
        )

    private fun request(path: String, token: String): HttpRequest.Builder =
        try {
            HttpRequest.newBuilder(apiRoot.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", MEDIA_TYPE)
                .header("X-GitHub-Api-Version", API_VERSION)
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

    private fun safeLogRedirect(uri: URI): Boolean {
        if (uri.rawUserInfo != null || uri.fragment != null) return false
        val host = uri.host?.lowercase() ?: return false
        if (apiRoot.host == "127.0.0.1") return uri.scheme == "http" && host == "127.0.0.1"
        return uri.scheme == "https" && (uri.port == -1 || uri.port == 443) &&
            (host.endsWith(".blob.core.windows.net") || host.endsWith(".actions.githubusercontent.com"))
    }

    companion object {
        private val GITHUB_API = URI("https://api.github.com/")
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)
        private const val MEDIA_TYPE = "application/vnd.github+json"
        private const val MAX_JOB_LOG_BYTES = 8 * 1024 * 1024

        /** The REST API version every call asks for, so a GitHub change of default never alters an answer. */
        private const val API_VERSION = "2022-11-28"

        /** A client for this API: it connects within the same bound as every call made through it. */
        fun newClient(): HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
    }
}
