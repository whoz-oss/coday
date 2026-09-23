package io.whozoss.agentos.plugins.http.openapi

import io.whozoss.agentos.plugins.http.config.ApiKeyPlacement
import io.whozoss.agentos.plugins.http.config.AuthConfig

/**
 * Header parameters that are never exposed as tool arguments: the ones the HTTP layer owns (framing,
 * content negotiation, user agent) or that carry credentials, plus the header the effective API key
 * placement uses. Names are compared case-insensitively.
 */
object ReservedHeaders {

    fun isReserved(name: String, auth: AuthConfig): Boolean =
        name.lowercase() in FIXED ||
            (auth.apiKeyIn == ApiKeyPlacement.HEADER && name.equals(auth.apiKeyName, ignoreCase = true))

    private val FIXED = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "host",
        "content-length",
        "content-type",
        "accept",
        "transfer-encoding",
        "user-agent",
    )
}
