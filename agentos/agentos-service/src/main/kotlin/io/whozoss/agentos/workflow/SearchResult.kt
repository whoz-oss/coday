package io.whozoss.agentos.workflow

/**
 * A single entity returned by the Search tool.
 *
 * @param entityType Category of the entity (e.g. "TALENT", "TASK").
 * @param entityId   External identifier of the business entity (e.g. task ID). Injected into
 *                   the prompt template via `{entityId}`.
 * @param targets    AgentOS users associated with this entity. The first target's [SearchResultTarget.entityId]
 *                   is used to resolve the end-user via [io.whozoss.agentos.user.UserService.findByExternalId].
 */
data class SearchResultItem(
    val entityType: String,
    val entityId: String,
    val targets: List<SearchResultTarget>? = null,
)

/**
 * A target user linked to a [SearchResultItem].
 *
 * @param entityType Category of the target entity (e.g. "USER").
 * @param entityId   External identifier of the AgentOS user.
 */
data class SearchResultTarget(
    val entityType: String,
    val entityId: String,
)

/**
 * Pagination metadata returned alongside [SearchResult.data].
 *
 * @param totalCount Total number of matching entities across all pages.
 * @param next       Cursor for the next page, or null when this is the last page.
 */
data class SearchResultMetadata(
    val totalCount: Int?,
    val next: String?,
)

/**
 * Structured output of a Search tool call.
 *
 * @param data     List of entities on the current page.
 * @param metadata Pagination metadata.
 */
data class SearchResult(
    val data: List<SearchResultItem>,
    val metadata: SearchResultMetadata?,
)
