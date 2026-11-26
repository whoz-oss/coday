package io.whozoss.factory.ci

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import org.springframework.stereotype.Component

/** Typed result of one GitHub call: never throws for an expected HTTP outcome. */
sealed interface GitHubResult<out T> {
    data class Ok<T>(val value: T) : GitHubResult<T>

    /** Network error, 5xx, 429 or 408: the condition may settle on its own, poll again later. */
    data class Transient(val reason: String) : GitHubResult<Nothing>

    /** 401, 403, 404 or another 4xx: retrying will not help, the step must be refused. */
    data class Refused(val code: Int, val message: String) : GitHubResult<Nothing>
}

/** One open pull request, as far as the CI check needs it. */
data class GitHubPullRequest(
    val number: Int,
    val url: String,
    val draft: Boolean,
    val headSha: String,
)

/** The tail of a job log. [truncated] is true when the beginning was dropped. */
data class GitHubLogTail(val text: String, val truncated: Boolean)

/**
 * Minimal GitHub REST client (`java.net.http`), read-only, used by [PullRequestChecksCapability].
 *
 * It never spawns a process and never calls an LLM. The bearer token is only put in the `Authorization`
 * header and is never part of an error message.
 */
@Component
class GitHubChecksClient(
    private val properties: GitHubChecksProperties,
    private val objectMapper: ObjectMapper = ObjectMapper(),
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.requestTimeoutMs))
        // Job logs answer with a redirect to a pre-signed URL that must NOT receive the bearer token: redirects
        // are followed manually and the Authorization header is only sent to the configured API host.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun findOpenPullRequest(owner: String, repo: String, branch: String): GitHubResult<GitHubPullRequest?> {
        val head = URLEncoder.encode("$owner:$branch", StandardCharsets.UTF_8)
        return getJson("/repos/$owner/$repo/pulls?head=$head&state=open&per_page=10").map { node ->
            val first = node.firstOrNull { it.path("head").path("ref").asText() == branch } ?: return@map null
            GitHubPullRequest(
                number = first.path("number").asInt(),
                url = first.path("html_url").asText(),
                draft = first.path("draft").asBoolean(false),
                headSha = first.path("head").path("sha").asText(),
            )
        }
    }

    /** All check runs of a commit, following pagination (at most [MAX_PAGES] pages of 100). */
    fun checkRuns(owner: String, repo: String, sha: String): GitHubResult<List<JsonNode>> {
        val runs = ArrayList<JsonNode>()
        var next: String? = "/repos/$owner/$repo/commits/$sha/check-runs?per_page=100"
        var pages = 0
        while (next != null && pages < MAX_PAGES) {
            val response = when (val result = get(next, ACCEPT_JSON)) {
                is GitHubResult.Ok -> result.value
                is GitHubResult.Transient -> return result
                is GitHubResult.Refused -> return result
            }
            val body = runCatching { objectMapper.readTree(response.body()) }.getOrNull()
                ?: return GitHubResult.Transient("GitHub returned an unreadable check-runs body")
            body.path("check_runs").forEach(runs::add)
            next = nextLink(response.headers().firstValue("Link").orElse(null))
            pages++
        }
        return GitHubResult.Ok(runs)
    }

    fun combinedStatus(owner: String, repo: String, sha: String): GitHubResult<JsonNode> =
        getJson("/repos/$owner/$repo/commits/$sha/status")

    /** The last [maxBytes] of a job log (GitHub redirects to the log archive; the redirect is followed manually). */
    fun jobLogTail(owner: String, repo: String, jobId: Long, maxBytes: Int): GitHubResult<GitHubLogTail> {
        val first = when (val result = get("/repos/$owner/$repo/actions/jobs/$jobId/logs", ACCEPT_JSON)) {
            is GitHubResult.Ok -> result.value
            is GitHubResult.Transient -> return result
            is GitHubResult.Refused -> return result
        }
        val response = if (first.statusCode() in 300..399) {
            val location = first.headers().firstValue("Location").orElse(null)
                ?: return GitHubResult.Transient("GitHub log redirect without a location")
            // Pre-signed URL: no credential is forwarded.
            when (val result = send(HttpRequest.newBuilder(URI.create(location)).GET().timeout(timeout()).build())) {
                is GitHubResult.Ok -> result.value
                is GitHubResult.Transient -> return result
                is GitHubResult.Refused -> return result
            }
        } else {
            first
        }
        val bytes = response.body().toByteArray(StandardCharsets.UTF_8)
        val truncated = bytes.size > maxBytes
        val tail = if (truncated) bytes.copyOfRange(bytes.size - maxBytes, bytes.size) else bytes
        return GitHubResult.Ok(GitHubLogTail(String(tail, StandardCharsets.UTF_8), truncated))
    }

    private fun getJson(path: String): GitHubResult<JsonNode> =
        when (val result = get(path, ACCEPT_JSON)) {
            is GitHubResult.Ok -> runCatching { objectMapper.readTree(result.value.body()) }.getOrNull()
                ?.let { GitHubResult.Ok(it) }
                ?: GitHubResult.Transient("GitHub returned an unreadable body")
            is GitHubResult.Transient -> result
            is GitHubResult.Refused -> result
        }

    private fun get(pathOrUrl: String, accept: String): GitHubResult<HttpResponse<String>> {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else properties.apiBaseUrl.trimEnd('/') + pathOrUrl
        val request = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .timeout(timeout())
            .header("Authorization", "Bearer ${properties.token}")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", API_VERSION)
            .build()
        return send(request)
    }

    private fun send(request: HttpRequest): GitHubResult<HttpResponse<String>> {
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return GitHubResult.Transient("GitHub call interrupted")
        } catch (error: IOException) {
            return GitHubResult.Transient("GitHub unreachable: ${error.javaClass.simpleName}")
        }
        val status = response.statusCode()
        return when {
            status in 200..399 -> GitHubResult.Ok(response)
            status == 429 || status == 408 || status >= 500 -> GitHubResult.Transient("GitHub answered HTTP $status")
            else -> GitHubResult.Refused(status, "GitHub answered HTTP $status")
        }
    }

    private fun timeout(): Duration = Duration.ofMillis(properties.requestTimeoutMs)

    private fun nextLink(header: String?): String? =
        header?.split(',')?.map(String::trim)?.firstOrNull { it.endsWith("rel=\"next\"") }
            ?.substringAfter('<')?.substringBefore('>')

    private companion object {
        const val ACCEPT_JSON = "application/vnd.github+json"
        const val API_VERSION = "2022-11-28"
        const val MAX_PAGES = 5
    }
}

private inline fun <T, R> GitHubResult<T>.map(transform: (T) -> R): GitHubResult<R> = when (this) {
    is GitHubResult.Ok -> GitHubResult.Ok(transform(value))
    is GitHubResult.Transient -> this
    is GitHubResult.Refused -> this
}
