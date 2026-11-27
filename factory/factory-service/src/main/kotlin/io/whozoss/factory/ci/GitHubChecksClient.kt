package io.whozoss.factory.ci

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Component

/** Typed result of one GitHub call: never throws for an expected HTTP outcome. */
sealed interface GitHubResult<out T> {
    data class Ok<T>(val value: T) : GitHubResult<T>

    /**
     * Network error, 5xx, 408, 429 or a rate-limited 403: the condition may settle on its own, poll again later.
     * [retryAfterSeconds] is the delay GitHub asked for, when it said so.
     */
    data class Transient(val reason: String, val retryAfterSeconds: Int? = null) : GitHubResult<Nothing>

    /** 401, a non rate-limit 403, 404 or another 4xx, or an untrusted URL: retrying will not help, refuse the step. */
    data class Refused(val code: Int, val message: String) : GitHubResult<Nothing>
}

/** One open pull request, as far as the CI check needs it. */
data class GitHubPullRequest(
    val number: Int,
    val url: String,
    val draft: Boolean,
    val headSha: String,
)

/** The tail of a job log, trimmed to a line boundary. [truncated] is true when the beginning was dropped. */
data class GitHubLogTail(val text: String, val truncated: Boolean)

/**
 * Minimal GitHub REST client (`java.net.http`), read-only, used by [PullRequestChecksCapability].
 *
 * It never spawns a process and never calls an LLM. The bearer token is sent ONLY to URLs that share the scheme,
 * host, port and path prefix of the configured API base URL: any other absolute URL (a `Link` header included) is
 * refused without a request. Error messages carry a status or a fixed text, never a URL with a query string.
 */
@Component
class GitHubChecksClient(
    private val properties: GitHubChecksProperties,
    private val objectMapper: ObjectMapper = ObjectMapper(),
) {
    private val baseUri: URI = URI.create(properties.apiBaseUrl.trimEnd('/'))
    private val basePath: String = baseUri.path.orEmpty()

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.requestTimeoutMs))
        // Job logs answer with a redirect to a pre-signed URL that must NOT receive the bearer token: redirects
        // are followed manually and the Authorization header is only sent to the configured API origin.
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
    fun checkRuns(owner: String, repo: String, sha: String): GitHubResult<List<JsonNode>> =
        paginated("/repos/$owner/$repo/commits/$sha/check-runs?per_page=100", "check_runs")

    /** All commit statuses of a commit (legacy status API), following pagination (at most [MAX_PAGES] pages). */
    fun statuses(owner: String, repo: String, sha: String): GitHubResult<List<JsonNode>> =
        paginated("/repos/$owner/$repo/commits/$sha/status?per_page=100", "statuses")

    /**
     * The last [maxBytes] of a job log, trimmed to a line boundary. GitHub redirects to a pre-signed log archive:
     * the redirect is followed manually, without credential, over https only (an http API base, used by local
     * tests, allows http). The body is streamed into a ring buffer and never held entirely in memory; reading stops
     * after [GitHubChecksProperties.maxLogDownloadBytes].
     */
    fun jobLogTail(owner: String, repo: String, jobId: Long, maxBytes: Int): GitHubResult<GitHubLogTail> {
        val first = when (val result = stream(apiRequest("/repos/$owner/$repo/actions/jobs/$jobId/logs"))) {
            is GitHubResult.Ok -> result.value
            is GitHubResult.Transient -> return result
            is GitHubResult.Refused -> return result
        }
        val response = if (first.statusCode() in 300..399) {
            first.body().close()
            val location = first.headers().firstValue("Location").orElse(null)
                ?: return GitHubResult.Transient("GitHub log redirect without a location")
            val target = runCatching { URI.create(location) }.getOrNull()
                ?: return GitHubResult.Refused(0, "CI_GITHUB_UNTRUSTED_URL")
            val scheme = target.scheme?.lowercase()
            val allowed = scheme == "https" || (scheme == "http" && baseUri.scheme.equals("http", ignoreCase = true))
            if (!allowed || target.host.isNullOrBlank()) return GitHubResult.Refused(0, "CI_GITHUB_UNTRUSTED_URL")
            // Pre-signed URL: no credential is forwarded.
            when (val result = stream(HttpRequest.newBuilder(target).GET().timeout(timeout()).build())) {
                is GitHubResult.Ok -> result.value
                is GitHubResult.Transient -> return result
                is GitHubResult.Refused -> return result
            }
        } else {
            first
        }
        return try {
            response.body().use { GitHubResult.Ok(readTail(it, maxBytes.coerceAtLeast(1), properties.maxLogDownloadBytes)) }
        } catch (error: IOException) {
            GitHubResult.Transient("GitHub log download failed: ${error.javaClass.simpleName}")
        }
    }

    private fun readTail(input: InputStream, maxBytes: Int, maxDownload: Long): GitHubLogTail {
        val ring = ByteArray(maxBytes)
        val chunk = ByteArray(CHUNK)
        var written = 0L
        var stoppedEarly = false
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            for (i in 0 until read) {
                ring[(written % maxBytes).toInt()] = chunk[i]
                written++
            }
            if (written >= maxDownload) {
                stoppedEarly = input.read() >= 0
                break
            }
        }
        val tail = if (written <= maxBytes) {
            ring.copyOfRange(0, written.toInt())
        } else {
            val start = (written % maxBytes).toInt()
            ring.copyOfRange(start, maxBytes) + ring.copyOfRange(0, start)
        }
        val truncated = written > maxBytes || stoppedEarly
        val decoded = String(tail, StandardCharsets.UTF_8)
        // The cut can land inside a multi-byte character or inside a KEY=value pair: drop the partial first line.
        val text = if (truncated) decoded.substringAfter('\n', "") else decoded
        return GitHubLogTail(text, truncated)
    }

    private fun paginated(firstPath: String, arrayField: String): GitHubResult<List<JsonNode>> {
        val items = ArrayList<JsonNode>()
        var next: String? = firstPath
        var pages = 0
        while (next != null && pages < MAX_PAGES) {
            val response = when (val result = get(next)) {
                is GitHubResult.Ok -> result.value
                is GitHubResult.Transient -> return result
                is GitHubResult.Refused -> return result
            }
            val body = runCatching { objectMapper.readTree(response.body()) }.getOrNull()
                ?: return GitHubResult.Transient("GitHub returned an unreadable body")
            body.path(arrayField).forEach(items::add)
            next = nextLink(response.headers().firstValue("Link").orElse(null))
            pages++
        }
        return GitHubResult.Ok(items)
    }

    private fun getJson(path: String): GitHubResult<JsonNode> =
        when (val result = get(path)) {
            is GitHubResult.Ok -> runCatching { objectMapper.readTree(result.value.body()) }.getOrNull()
                ?.let { GitHubResult.Ok(it) }
                ?: GitHubResult.Transient("GitHub returned an unreadable body")
            is GitHubResult.Transient -> result
            is GitHubResult.Refused -> result
        }

    /** GET of an API path or of an absolute URL, which must belong to the API origin (see [isTrusted]). */
    private fun get(pathOrUrl: String): GitHubResult<HttpResponse<String>> {
        val request = when (val built = buildApiRequest(pathOrUrl)) {
            is GitHubResult.Ok -> built.value
            is GitHubResult.Transient -> return built
            is GitHubResult.Refused -> return built
        }
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return GitHubResult.Transient("GitHub call interrupted")
        } catch (error: IOException) {
            return GitHubResult.Transient("GitHub unreachable: ${error.javaClass.simpleName}")
        }
        return failure(response.statusCode(), response.headers(), response.body().take(SNIPPET_CHARS)) ?: GitHubResult.Ok(response)
    }

    /** Streaming GET: the body is left unread on success so the caller can bound it. */
    private fun stream(request: GitHubResult<HttpRequest>): GitHubResult<HttpResponse<InputStream>> =
        when (request) {
            is GitHubResult.Ok -> stream(request.value)
            is GitHubResult.Transient -> request
            is GitHubResult.Refused -> request
        }

    private fun stream(request: HttpRequest): GitHubResult<HttpResponse<InputStream>> {
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return GitHubResult.Transient("GitHub call interrupted")
        } catch (error: IOException) {
            return GitHubResult.Transient("GitHub unreachable: ${error.javaClass.simpleName}")
        }
        if (response.statusCode() < 400) return GitHubResult.Ok(response)
        val snippet = response.body().use { runCatching { String(it.readNBytes(SNIPPET_CHARS), StandardCharsets.UTF_8) }.getOrDefault("") }
        return failure(response.statusCode(), response.headers(), snippet) ?: GitHubResult.Ok(response)
    }

    private fun apiRequest(pathOrUrl: String): GitHubResult<HttpRequest> = buildApiRequest(pathOrUrl)

    private fun buildApiRequest(pathOrUrl: String): GitHubResult<HttpRequest> {
        val uri = if (pathOrUrl.startsWith("http", ignoreCase = true)) {
            val absolute = runCatching { URI.create(pathOrUrl) }.getOrNull()
            if (absolute == null || !isTrusted(absolute)) return GitHubResult.Refused(0, "CI_GITHUB_UNTRUSTED_URL")
            absolute
        } else {
            URI.create(properties.apiBaseUrl.trimEnd('/') + pathOrUrl)
        }
        return GitHubResult.Ok(
            HttpRequest.newBuilder(uri)
                .GET()
                .timeout(timeout())
                .header("Authorization", "Bearer ${properties.effectiveToken}")
                .header("Accept", ACCEPT_JSON)
                .header("X-GitHub-Api-Version", API_VERSION)
                .build(),
        )
    }

    /** Same scheme, host and effective port as the API base URL, and a path inside its path prefix. */
    private fun isTrusted(uri: URI): Boolean =
        uri.scheme.equals(baseUri.scheme, ignoreCase = true) &&
            uri.host.equals(baseUri.host, ignoreCase = true) &&
            effectivePort(uri) == effectivePort(baseUri) &&
            uri.path.orEmpty().startsWith(basePath)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    /** Classifies an HTTP failure, or null when the status is a success or a redirect. */
    private fun failure(status: Int, headers: HttpHeaders, snippet: String): GitHubResult<Nothing>? = when {
        status in 200..399 -> null
        status == 429 -> GitHubResult.Transient("GitHub answered HTTP 429", retryAfterSeconds(headers))
        status == 403 && rateLimited(headers, snippet) ->
            GitHubResult.Transient("GitHub rate limit reached (HTTP 403)", retryAfterSeconds(headers))
        status == 408 || status >= 500 -> GitHubResult.Transient("GitHub answered HTTP $status", retryAfterSeconds(headers))
        else -> GitHubResult.Refused(status, "GitHub answered HTTP $status")
    }

    private fun rateLimited(headers: HttpHeaders, snippet: String): Boolean =
        headers.firstValue("x-ratelimit-remaining").map { it.trim() == "0" }.orElse(false) ||
            headers.firstValue("Retry-After").isPresent ||
            snippet.contains("rate limit", ignoreCase = true)

    /** Seconds GitHub asked to wait: `Retry-After`, else `x-ratelimit-reset` (epoch seconds) minus now. */
    private fun retryAfterSeconds(headers: HttpHeaders): Int? {
        headers.firstValue("Retry-After").orElse(null)?.trim()?.toLongOrNull()?.let { return it.coerceIn(0, MAX_RETRY_AFTER).toInt() }
        val reset = headers.firstValue("x-ratelimit-reset").orElse(null)?.trim()?.toLongOrNull() ?: return null
        return (reset - Instant.now().epochSecond).coerceIn(0, MAX_RETRY_AFTER).toInt()
    }

    private fun timeout(): Duration = Duration.ofMillis(properties.requestTimeoutMs)

    private fun nextLink(header: String?): String? =
        header?.split(',')?.map(String::trim)?.firstOrNull { it.endsWith("rel=\"next\"") }
            ?.substringAfter('<')?.substringBefore('>')

    private companion object {
        const val ACCEPT_JSON = "application/vnd.github+json"
        const val API_VERSION = "2022-11-28"
        const val MAX_PAGES = 5
        const val CHUNK = 8192
        const val SNIPPET_CHARS = 4096
        const val MAX_RETRY_AFTER = 3600L
    }
}

private inline fun <T, R> GitHubResult<T>.map(transform: (T) -> R): GitHubResult<R> = when (this) {
    is GitHubResult.Ok -> GitHubResult.Ok(transform(value))
    is GitHubResult.Transient -> this
    is GitHubResult.Refused -> this
}
