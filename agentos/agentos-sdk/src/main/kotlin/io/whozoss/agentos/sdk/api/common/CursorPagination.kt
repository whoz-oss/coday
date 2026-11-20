package io.whozoss.agentos.sdk.api.common

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema

/** Page size bounds shared by the cursor-paged `POST .../list` endpoints; a `limit` outside them is rejected with 400. */
object CursorPagination {
    const val DEFAULT_LIMIT = 100
    const val MAX_LIMIT = 500L
}

/** Paging metadata of a cursor-paged response. [next] is opaque: send it back unchanged, never parse it. */
@Schema(name = "ListMetadata")
@JsonIgnoreProperties(ignoreUnknown = true)
data class ListMetadata(
    @field:Schema(description = "Cursor of the next page; null on the last page", nullable = true)
    val next: String?,
    @field:Schema(description = "Number of items matching the request, counted when its first page was read and carried by the cursor")
    val totalCount: Long,
)
