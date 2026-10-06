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

    companion object {
        private val GITHUB_API = URI("https://api.github.com/")
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)
        private const val MEDIA_TYPE = "application/vnd.github+json"

        /** The REST API version every call asks for, so a GitHub change of default never alters an answer. */
        private const val API_VERSION = "2022-11-28"

        /** A client for this API: it connects within the same bound as every call made through it. */
        fun newClient(): HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
    }
}
