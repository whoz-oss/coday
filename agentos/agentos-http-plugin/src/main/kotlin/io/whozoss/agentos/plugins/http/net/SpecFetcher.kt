package io.whozoss.agentos.plugins.http.net

import mu.KLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Outcome of [SpecFetcher.fetch]. */
sealed interface FetchOutcome {
    /** @property etag The `ETag` of the fetched document, to be sent back as `If-None-Match`. */
    data class Fetched(val text: String, val etag: String?) : FetchOutcome

    /** The server answered 304 to the conditional request: the known document is still current. */
    data object NotModified : FetchOutcome

    /** @property reason Safe to log and to show to an administrator: never the body of the answer. */
    data class Failed(val reason: String) : FetchOutcome
}

/** Where OpenAPI documents come from; the seam the catalogue cache is tested through. */
interface SpecSource {
    /** @param etag The etag of the document already held, sent as `If-None-Match`; null for a first fetch. */
    fun fetch(url: String, maxBytes: Long, etag: String?): FetchOutcome
}

/**
 * Downloads an OpenAPI document: unauthenticated `GET`, conditional on the known etag, URL checked by the
 * [OutboundUrlPolicy] first, body bounded by `maxBytes`, no redirect followed (the shared client never does).
 */
class SpecFetcher(
    private val client: OkHttpClient,
    private val urlPolicy: OutboundUrlPolicy,
) : SpecSource {

    private val fetchClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }

    override fun fetch(url: String, maxBytes: Long, etag: String?): FetchOutcome {
        val check = urlPolicy.validate(url)
        if (check is UrlCheck.Rejected) return FetchOutcome.Failed("document URL ${check.reason}")
        val request = Request.Builder()
            .url(url)
            .header(name = "Accept", value = ACCEPT)
            .apply { etag?.let { header(name = "If-None-Match", value = it) } }
            .build()
        return try {
            fetchClient.newCall(request).execute().use { response -> outcome(response, maxBytes) }
        } catch (e: IOException) {
            logger.warn { "OpenAPI document fetch failed: ${e::class.simpleName}: ${e.message}" }
            FetchOutcome.Failed("${e::class.simpleName}: ${e.message}")
        }
    }

    private fun outcome(response: Response, maxBytes: Long): FetchOutcome {
        if (response.code == NOT_MODIFIED) return FetchOutcome.NotModified
        if (!response.isSuccessful) return FetchOutcome.Failed("HTTP ${response.code} fetching the OpenAPI document")
        val body = response.body ?: return FetchOutcome.Failed("empty answer fetching the OpenAPI document")
        val bounded = BoundedBodyReader.read(body.source(), maxBytes)
        if (bounded.truncated) return FetchOutcome.Failed("document larger than the allowed $maxBytes bytes")
        return FetchOutcome.Fetched(text = bounded.bytes.toString(Charsets.UTF_8), etag = response.header("ETag"))
    }

    companion object : KLogging() {
        private const val CALL_TIMEOUT_SECONDS = 15L
        private const val NOT_MODIFIED = 304
        private const val ACCEPT = "application/json, application/yaml, text/yaml, text/plain;q=0.5"
    }
}
