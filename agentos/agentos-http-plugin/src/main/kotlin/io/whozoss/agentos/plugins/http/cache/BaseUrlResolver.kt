package io.whozoss.agentos.plugins.http.cache

import io.whozoss.agentos.plugins.http.net.OutboundUrlPolicy
import io.whozoss.agentos.plugins.http.net.UrlCheck

/** Outcome of [BaseUrlResolver.resolve]. */
sealed interface BaseUrlResolution {
    data class Resolved(val url: String) : BaseUrlResolution

    /** No base URL can be used; [reason] names what the administrator must fix. */
    data class Unusable(val reason: String) : BaseUrlResolution
}

/**
 * Chooses the base URL of a catalogue: an explicit config `baseUrl` (already validated by the parser) always
 * wins; otherwise the document server URL, which must be absolute, keep no `{variable}` the reader could
 * not replace by a default, and pass the [OutboundUrlPolicy].
 */
object BaseUrlResolver {

    fun resolve(configured: String?, serverUrl: String?, urlPolicy: OutboundUrlPolicy): BaseUrlResolution {
        if (configured != null) return BaseUrlResolution.Resolved(configured)
        if (serverUrl == null || !serverUrl.contains(SCHEME_SEPARATOR)) {
            return BaseUrlResolution.Unusable(NO_ABSOLUTE_SERVER_URL)
        }
        VARIABLE.find(serverUrl)?.let { variable ->
            return BaseUrlResolution.Unusable("$SERVER_URL_REJECTED keeps an unresolved variable '${variable.value}'")
        }
        return when (val check = urlPolicy.validate(serverUrl)) {
            is UrlCheck.Ok -> BaseUrlResolution.Resolved(serverUrl)
            is UrlCheck.Rejected -> BaseUrlResolution.Unusable("$SERVER_URL_REJECTED ${check.reason}")
        }
    }

    private const val SCHEME_SEPARATOR = "://"
    private const val SERVER_URL_REJECTED = "'baseUrl' is required because the OpenAPI document server URL"
    private val VARIABLE = Regex("\\{[^}]*}")
    private const val NO_ABSOLUTE_SERVER_URL =
        "'baseUrl' is required because the OpenAPI document declares no absolute server URL"
}
