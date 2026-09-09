package io.whozoss.agentos.plugins.http.config

/**
 * Configuration of one `HTTP_API` integration: where the OpenAPI document comes from,
 * which operations become tools and how calls and responses are shaped.
 *
 * Kotlin model of the `HTTP_API` integration config; the defaults below are the module defaults and are
 * the ones the plugin `configSchema` ([io.whozoss.agentos.plugins.http.HttpApiConfigSchema]) advertises.
 *
 * @property spec Source of the OpenAPI 3.x document.
 * @property baseUrl Absolute https URL prepended to every operation path; null uses the absolute `servers[0].url`
 *   of the document (variables replaced by their defaults), which must then exist and pass the URL policy.
 * @property includeTags Keep only operations carrying at least one of these tags (empty = no filter).
 * @property includePathPrefixes Keep only operations whose path starts with one of these prefixes (empty = no filter).
 * @property includeOperations Keep only operations whose operationId matches one of these globs (`*`, `?`;
 *   empty = no filter).
 * @property excludeOperations Drop operations whose operationId matches one of these globs.
 * @property maxTools Maximum number of tools exposed after curation; more operations is a configuration error.
 * @property operations Per-operation overrides keyed by operationId.
 * @property auth How the integration API key is transmitted; left at its defaults, the document `apiKey` security
 *   scheme decides.
 * @property defaultHeaders Static headers added to every call; hop-by-hop and credential headers are refused.
 * @property responseFormat Default rendering of responses for the LLM.
 * @property maxResponseChars Default cap on the rendered response length.
 * @property timeoutSeconds Timeout of one call once it holds a concurrency slot; the wait for a slot is bounded
 *   by the same value.
 * @property maxConcurrentCalls Upper bound on simultaneous calls of this config across all agents and runs.
 * @property allowMutations Expose non-GET operations as tools (default: read-only).
 */
data class HttpApiConfig(
    val spec: SpecConfig,
    val baseUrl: String? = null,
    val includeTags: List<String> = emptyList(),
    val includePathPrefixes: List<String> = emptyList(),
    val includeOperations: List<String> = emptyList(),
    val excludeOperations: List<String> = emptyList(),
    val maxTools: Int = DEFAULT_MAX_TOOLS,
    val operations: List<OperationOverride> = emptyList(),
    val auth: AuthConfig = AuthConfig(),
    val defaultHeaders: Map<String, String> = emptyMap(),
    val responseFormat: ResponseFormat = DEFAULT_RESPONSE_FORMAT,
    val maxResponseChars: Int = DEFAULT_MAX_RESPONSE_CHARS,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val maxConcurrentCalls: Int = DEFAULT_MAX_CONCURRENT_CALLS,
    val allowMutations: Boolean = DEFAULT_ALLOW_MUTATIONS,
) {
    companion object {
        const val DEFAULT_MAX_TOOLS = 64
        const val MAX_TOOLS_UPPER_BOUND = 128
        val DEFAULT_RESPONSE_FORMAT = ResponseFormat.JSON
        const val DEFAULT_MAX_RESPONSE_CHARS = 20_000
        const val MIN_RESPONSE_CHARS = 500
        const val DEFAULT_TIMEOUT_SECONDS = 30
        const val DEFAULT_MAX_CONCURRENT_CALLS = 4
        const val DEFAULT_ALLOW_MUTATIONS = false
    }
}

/**
 * Where the OpenAPI document is read from: exactly one of [url], [inline] and [file] is set.
 *
 * @property url https URL of the document, fetched and refreshed every [refreshMinutes].
 * @property inline The document text itself (JSON or YAML).
 * @property file Absolute path of a `.json`, `.yaml` or `.yml` document on the service host, reloaded when the
 *   file changes. The service substitutes `{{NAMESPACE_CONFIG_PATH}}` in filesystem configs before parsing.
 * @property refreshMinutes Re-fetch interval for a [url] document; 0 keeps the first fetched document until the
 *   service restarts or the config changes.
 * @property maxBytes Maximum accepted document size, at least [MIN_MAX_BYTES].
 */
data class SpecConfig(
    val url: String? = null,
    val inline: String? = null,
    val file: String? = null,
    val refreshMinutes: Int = DEFAULT_REFRESH_MINUTES,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    companion object {
        const val DEFAULT_REFRESH_MINUTES = 60
        const val DEFAULT_MAX_BYTES: Long = 5L * 1024 * 1024
        const val MIN_MAX_BYTES: Long = 1024
    }
}

/**
 * Per-operation override; a null field keeps the integration-level default.
 *
 * @property operationId The OpenAPI operationId this override applies to.
 * @property description Replaces the summary/description derived from the document.
 * @property keepPaths Dot-notation paths (with `*` wildcards) kept in the response; everything else is dropped.
 * @property ignorePaths Dot-notation paths removed from the response.
 * @property responseFormat Rendering of this operation's responses.
 * @property maxResponseChars Cap on this operation's rendered response length.
 */
data class OperationOverride(
    val operationId: String,
    val description: String? = null,
    val keepPaths: List<String> = emptyList(),
    val ignorePaths: List<String> = emptyList(),
    val responseFormat: ResponseFormat? = null,
    val maxResponseChars: Int? = null,
)

/**
 * Transport of the integration API key.
 *
 * @property apiKeyIn Header, query parameter or `Authorization: Bearer`.
 * @property apiKeyName Header or query parameter name (ignored for [ApiKeyPlacement.BEARER]).
 */
data class AuthConfig(
    val apiKeyIn: ApiKeyPlacement = DEFAULT_API_KEY_PLACEMENT,
    val apiKeyName: String = DEFAULT_API_KEY_NAME,
) {
    companion object {
        val DEFAULT_API_KEY_PLACEMENT = ApiKeyPlacement.HEADER
        const val DEFAULT_API_KEY_NAME = "X-API-Key"
    }
}

enum class ApiKeyPlacement { HEADER, QUERY, BEARER }

enum class ResponseFormat { JSON, YAML }
