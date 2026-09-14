package io.whozoss.agentos.plugins.http.auth

import io.whozoss.agentos.plugins.http.config.ApiKeyPlacement
import io.whozoss.agentos.plugins.http.config.AuthConfig
import io.whozoss.agentos.plugins.http.openapi.ApiKeyScheme
import mu.KLogging

/** @property fromDocument True when [auth] comes from the document security scheme rather than the config. */
data class ResolvedAuth(val auth: AuthConfig, val fromDocument: Boolean)

/**
 * Decides where an API key credential goes: an `auth` block the administrator customised always wins;
 * an untouched one (all defaults) takes the `apiKey` security scheme of the document when it declares one
 * in a header or a query parameter, and keeps the defaults otherwise.
 */
object ApiKeyPlacementResolver : KLogging() {

    fun resolve(configured: AuthConfig, scheme: ApiKeyScheme?, configName: String): ResolvedAuth {
        val resolved = when {
            configured != AuthConfig() -> ResolvedAuth(auth = configured, fromDocument = false)
            scheme != null -> ResolvedAuth(
                auth = AuthConfig(apiKeyIn = scheme.placement, apiKeyName = scheme.name),
                fromDocument = true,
            )
            else -> ResolvedAuth(auth = configured, fromDocument = false)
        }
        logger.debug {
            val source = if (resolved.fromDocument) "the document security scheme" else "the config"
            "HTTP_API '$configName': API key placement from $source: ${describe(resolved.auth)}"
        }
        return resolved
    }

    private fun describe(auth: AuthConfig): String =
        when (auth.apiKeyIn) {
            ApiKeyPlacement.HEADER -> "header '${auth.apiKeyName}'"
            ApiKeyPlacement.QUERY -> "query parameter '${auth.apiKeyName}'"
            ApiKeyPlacement.BEARER -> "Authorization: Bearer"
        }
}
