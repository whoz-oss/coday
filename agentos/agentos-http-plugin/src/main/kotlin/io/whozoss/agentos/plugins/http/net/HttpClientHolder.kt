package io.whozoss.agentos.plugins.http.net

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Owns the single [OkHttpClient] of the plugin: redirects are never followed (a redirect could leave the
 * allowed origin), failed connections are never retried (a write must not be replayed), every resolved
 * address goes through [PolicyDns], and the JVM default proxy selector is kept so that the standard
 * `-Dhttps.proxyHost` / `-Dhttps.proxyPort` properties apply. Calls are enqueued (so a cancelled run can
 * cancel them), hence the dispatcher's per-host cap is raised to its global cap: concurrency is bounded per
 * integration config by the plugin itself, not by OkHttp's default of five calls per host.
 *
 * Only the connection is bounded here: OkHttp's default read and write timeouts (10 s) are lifted so that
 * the call timeout every caller sets on its derived client (`timeoutSeconds` for tool calls, the fetch
 * budget for documents) is the one and only bound of a call.
 *
 * Created by the plugin `start()` and released by `stop()` through [shutdown].
 */
class HttpClientHolder(policy: OutboundUrlPolicy) {

    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(UNBOUNDED, TimeUnit.SECONDS)
        .writeTimeout(UNBOUNDED, TimeUnit.SECONDS)
        .dns(PolicyDns(policy))
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = maxRequests })
        .build()

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 10L

        /** OkHttp reads zero as "no timeout"; the call timeout of the derived clients bounds the call instead. */
        private const val UNBOUNDED = 0L
    }
}
