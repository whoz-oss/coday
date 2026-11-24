package io.whozoss.agentos.sdk.api.common

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/** Page size bounds shared by the cursor-paged `POST .../list` endpoints; a `limit` outside them is rejected with 400. */
object CursorPagination {
    const val DEFAULT_LIMIT = 100
    const val MAX_LIMIT = 500L
}

/** Request body of a cursor-paged `POST .../list` endpoint that takes no filters. */
@Schema(name = "CursorPageRequest")
@JsonIgnoreProperties(ignoreUnknown = true)
data class CursorPageRequest(
    @field:Schema(description = "Page size, ${CursorPagination.DEFAULT_LIMIT} when omitted", nullable = true)
    @field:Min(1)
    @field:Max(CursorPagination.MAX_LIMIT)
    val limit: Int? = null,
    @field:Schema(description = "Opaque cursor: the `metadata.next` of the previous response; omit for the first page", nullable = true)
    val next: String? = null,
)

/** Paging metadata of a cursor-paged response. [next] is opaque: send it back unchanged, never parse it. */
@Schema(name = "ListMetadata")
@JsonIgnoreProperties(ignoreUnknown = true)
data class ListMetadata(
    @field:Schema(description = "Cursor of the next page; null on the last page", nullable = true)
    val next: String?,
    @field:Schema(description = "Number of items matching the request, counted when its first page was read and carried by the cursor")
    val totalCount: Long,
)
