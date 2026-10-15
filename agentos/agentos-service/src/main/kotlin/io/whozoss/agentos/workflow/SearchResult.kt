package io.whozoss.agentos.workflow

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// All types ignore unknown properties: a Search tool may return richer rows than this contract
// without breaking the loop, whatever the ObjectMapper configuration.

/**
 * A single entity returned by the Search tool.
 * This is temporary code that is tied too tightly to external vendor.
 *
 * @param entityType Category of the entity (e.g. "TALENT", "TASK"). Informational only — optional so a
 *                   row without it is still processed.
 * @param entityId   External identifier of the business entity (e.g. task ID). Injected into
 *                   the prompt template via `{entityId}`.
 * @param targets    AgentOS users associated with this entity. The first target's [SearchResultTarget.entityId]
 *                   is used to resolve the end-user via [io.whozoss.agentos.user.UserService.findByExternalId].
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResultItem(
    val entityType: String? = null,
    val entityId: String,
    val targets: List<SearchResultTarget>? = null,
)

/**
 * A target user linked to a [SearchResultItem].
 *
 * @param entityType Category of the target entity (e.g. "USER"). Informational only — optional.
 * @param entityId   External identifier of the AgentOS user.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResultTarget(
    val entityType: String? = null,
    val entityId: String,
)

/**
 * Pagination metadata returned alongside [SearchResult.data].
 *
 * @param totalCount Total number of matching entities across all pages.
 * @param next       Cursor for the next page, or null when this is the last page.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResultMetadata(
    val totalCount: Long?,
    val next: String?,
)

/**
 * Structured output of a Search tool call.
 *
 * @param data     List of entities on the current page.
 * @param metadata Pagination metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResult(
    val data: List<SearchResultItem>,
    val metadata: SearchResultMetadata?,
)
