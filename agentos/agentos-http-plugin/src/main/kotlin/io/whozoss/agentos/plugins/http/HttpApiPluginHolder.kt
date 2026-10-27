package io.whozoss.agentos.plugins.http

import io.whozoss.agentos.plugins.http.cache.OperationCatalogueCache
import io.whozoss.agentos.plugins.http.net.HttpClientHolder
import io.whozoss.agentos.plugins.http.net.OutboundUrlPolicy
import io.whozoss.agentos.plugins.http.net.SpecFetcher
import mu.KLogging

/**
 * Singleton holder for the [HttpApiPluginServices], living in the plugin classloader: created once when
 * the plugin starts, released when it stops.
 */
object HttpApiPluginHolder : KLogging() {

    private class Started(val clientHolder: HttpClientHolder, val services: HttpApiPluginServices)

    @Volatile
    private var started: Started? = null

    val services: HttpApiPluginServices
        get() = checkNotNull(started?.services) { "HTTP API plugin is not started" }

    fun start() {
        val allowUnsecure = allowUnsecure()
        logger.info {
            if (allowUnsecure) {
                "HTTP_API outbound policy: plain HTTP and private/local destinations ALLOWED " +
                    "(set $DISALLOW_UNSECURE_ENV=true to restrict to public HTTPS)"
            } else {
                "HTTP_API outbound policy: restricted to public HTTPS"
            }
        }
        val policy = OutboundUrlPolicy(allowUnsecure = allowUnsecure)
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

    /**
     * Unsecure HTTP is allowed by default. [DISALLOW_UNSECURE_ENV] absent, blank or "false" (case-insensitive)
     * keeps it allowed, "true" disallows it; any other value fails closed (strict policy) with a WARN.
     */
    internal fun allowUnsecure(disallowValue: String? = System.getenv(DISALLOW_UNSECURE_ENV)): Boolean =
        when {
            disallowValue.isNullOrBlank() -> true
            disallowValue.equals("false", ignoreCase = true) -> true
            disallowValue.equals("true", ignoreCase = true) -> false
            else -> {
                logger.warn {
                    "$DISALLOW_UNSECURE_ENV value '$disallowValue' is not recognised (expected true or false): " +
                        "the strict HTTPS-only policy is applied"
                }
                false
            }
        }

    internal const val DISALLOW_UNSECURE_ENV = "AGENTOS_HTTP_API_DISALLOW_UNSECURE"
}
