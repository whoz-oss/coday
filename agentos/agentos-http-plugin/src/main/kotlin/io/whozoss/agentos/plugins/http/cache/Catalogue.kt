package io.whozoss.agentos.plugins.http.cache

import io.whozoss.agentos.plugins.http.config.AuthConfig
import io.whozoss.agentos.plugins.http.openapi.CurationWarning
import io.whozoss.agentos.plugins.http.openapi.OperationDescriptor

/**
 * The curated operations of one integration config, as cached by [OperationCatalogueCache].
 *
 * @property baseUrl Effective base URL of every call: the config `baseUrl`, else the document server URL.
 * @property auth Effective API key placement: the config `auth` when customised, else the document scheme.
 * @property authFromDocument True when [auth] comes from the document security scheme.
 * @property readOnly True when the config does not allow mutations (only GET operations are exposed).
 */
data class Catalogue(
    val title: String?,
    val version: String?,
    val baseUrl: String,
    val auth: AuthConfig,
    val authFromDocument: Boolean,
    val operations: List<OperationDescriptor>,
    val warnings: List<CurationWarning>,
    val readOnly: Boolean,
) {
    /** True when an API key placement is in effect: a customised config `auth`, or one taken from the document. */
    val apiKeyPlacementInEffect: Boolean
        get() = authFromDocument || auth != AuthConfig()
}

/** Outcome of [OperationCatalogueCache.getOrLoad]. */
sealed interface CatalogueOutcome {
    /**
     * @property staleReason Set when a refresh failed and the previous catalogue is served instead; the
     *   reason is safe to log and to show to an administrator.
     */
    data class Ready(val catalogue: Catalogue, val staleReason: String? = null) : CatalogueOutcome

    /** No usable catalogue: the reason names the configuration or document problem to fix. */
    data class Failed(val reason: String) : CatalogueOutcome
}
