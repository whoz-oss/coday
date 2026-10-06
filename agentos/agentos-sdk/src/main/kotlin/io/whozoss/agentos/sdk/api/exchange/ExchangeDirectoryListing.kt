package io.whozoss.agentos.sdk.api.exchange

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema

/**
 * One page of one directory level of an exchange scope.
 *
 * Exists because the recursive manifest cannot describe a repository: a checkout with its
 * dependencies runs to tens of thousands of files, and returning them flat is unusable for a
 * person and expensive for the server. Browsing descends one level at a time, bounded.
 */
@Schema(name = "ExchangeDirectoryListing", description = "A page of one directory level, plus the caller's capability.")
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExchangeDirectoryListing(
    @field:Schema(description = "Directory being listed, relative to the exchange root. Empty string for the root itself.")
    val path: String,
    @field:Schema(description = "Entries in this page: sub-directories first, then files, each alphabetically.")
    val entries: List<ExchangeDirectoryEntry>,
    @field:Schema(description = "Total number of entries in this directory, across all pages.")
    val totalEntries: Int,
    @field:Schema(description = "Zero-based index of the page returned.")
    val page: Int,
    @field:Schema(description = "Maximum number of entries per page.")
    val pageSize: Int,
    @field:Schema(description = "Whether further pages exist for this directory.")
    val hasMore: Boolean,
    @field:Schema(description = "Capability the caller has over this scope.")
    val capability: ExchangeCapability,
)
