package io.whozoss.agentos.plugins.http

import io.whozoss.agentos.plugins.http.auth.AuthHeaderSpec
import io.whozoss.agentos.plugins.http.net.OutboundUrlPolicy
import kotlinx.coroutines.sync.Semaphore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * What every tool of one integration config shares for the duration of a run: the normalised base URL,
 * the credential resolved once for the run, the transport settings, the plugin-wide client and the
 * plugin-wide semaphore of the config.
 *
 * Immutable and holding nothing that needs closing: the client belongs to the plugin.
 *
 * @property baseUrl Effective base URL of the catalogue without query, fragment or trailing slash; every
 *   request URL is built from it and must stay under it.
 * @property client The plugin client with this config's call timeout applied.
 * @property semaphore Bounds the simultaneous calls of this integration config across all agents and runs
 *   (`maxConcurrentCalls`); shared by every runtime of the config through [CallLimiters].
 */
class HttpApiRuntime(
    val configName: String,
    baseUrl: String,
    val authSpec: AuthHeaderSpec,
    val defaultHeaders: Map<String, String>,
    val timeoutSeconds: Int,
    val semaphore: Semaphore,
    val urlPolicy: OutboundUrlPolicy,
    client: OkHttpClient,
) {
    val baseUrl: HttpUrl = normalise(baseUrl.toHttpUrl())

    val client: OkHttpClient = client.newBuilder().callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS).build()

    private fun normalise(url: HttpUrl): HttpUrl =
        HttpUrl.Builder()
            .scheme(url.scheme)
            .host(url.host)
            .port(url.port)
            .apply { url.encodedPathSegments.filter { it.isNotEmpty() }.forEach { addEncodedPathSegment(it) } }
            .build()
}
