package io.whozoss.agentos.plugins.http

import io.whozoss.agentos.plugins.http.cache.OperationCatalogueCache
import io.whozoss.agentos.plugins.http.net.OutboundUrlPolicy
import okhttp3.OkHttpClient

/**
 * What every [HttpApiToolProvider] of the plugin shares: the single HTTP client, the catalogue cache, the
 * failure registry, the per-config call limiters and the outbound URL policy. Tools hold no per-run
 * resource that would need closing.
 */
class HttpApiPluginServices(
    val client: OkHttpClient,
    val catalogueCache: OperationCatalogueCache,
    val failures: LastFailureRegistry,
    val limiters: CallLimiters,
    val urlPolicy: OutboundUrlPolicy,
)
