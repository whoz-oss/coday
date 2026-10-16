package io.whozoss.agentos.plugins.http

import io.whozoss.agentos.plugins.http.cache.OperationCatalogueCache
import io.whozoss.agentos.plugins.http.net.HttpClientHolder
import io.whozoss.agentos.plugins.http.net.OutboundUrlPolicy
import io.whozoss.agentos.plugins.http.net.SpecFetcher

/**
 * Singleton holder for the [HttpApiPluginServices], living in the plugin classloader: created once when
 * the plugin starts, released when it stops.
 */
object HttpApiPluginHolder {

    private class Started(val clientHolder: HttpClientHolder, val services: HttpApiPluginServices)

    @Volatile
    private var started: Started? = null

    val services: HttpApiPluginServices
        get() = checkNotNull(started?.services) { "HTTP API plugin is not started" }

    fun start() {
        val policy = OutboundUrlPolicy(allowLoopbackHttp = allowLocalHttp())
        val clientHolder = HttpClientHolder(policy)
        val cache = OperationCatalogueCache(fetcher = SpecFetcher(clientHolder.client, policy), urlPolicy = policy)
        started = Started(
            clientHolder = clientHolder,
            services = HttpApiPluginServices(
                client = clientHolder.client,
                catalogueCache = cache,
                failures = LastFailureRegistry(),
                limiters = CallLimiters(),
                urlPolicy = policy,
            ),
        )
    }

    fun shutdown() {
        started?.clientHolder?.shutdown()
        started = null
    }

    /** Only the explicit value "true" (case-insensitive) enables local HTTP; all other values fail closed. */
    internal fun allowLocalHttp(value: String? = System.getenv(ALLOW_LOCAL_HTTP_ENV)): Boolean =
        value?.equals("true", ignoreCase = true) == true

    internal const val ALLOW_LOCAL_HTTP_ENV = "AGENTOS_HTTP_API_ALLOW_LOCAL_HTTP"
}
