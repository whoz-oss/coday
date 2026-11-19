package io.whozoss.agentos.pagination

import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.sdk.api.common.CursorPagination
import java.util.Base64

/** One page of a list sorted by external id, with the cursor of the next page (null on the last). */
data class KeysetPage<T>(
    val items: List<T>,
    val next: String?,
    val totalCount: Long,
)

/**
 * Keyset paging over a list sorted by `externalId` ascending: the next page is "everything after the last external id
 * returned". The cursor also carries the total, so the count runs once, on the first page of a walk.
 *
 * `totalCount` is therefore a snapshot of the first page, reused by every later page of the same walk: rows added or
 * removed mid-walk make it slightly stale until the list is reloaded. A client can forge it, which only changes a
 * displayed count, never the rows returned. A cursor must be used with the filters it was issued for.
 */
object KeysetPaging {
    private const val CURSOR_PREFIX = "after"

    private data class Cursor(
        val totalCount: Long,
        val afterExternalId: String,
    )

    /**
     * [fetch] gets the external id to start after (null for the first page) and a size, and returns that many rows
     * sorted by external id. One extra row is requested to know whether a next page exists. [totalCount] is only
     * called when [next] is absent.
     */
    fun <T> read(
        limit: Int?,
        next: String?,
        externalIdOf: (T) -> String,
        totalCount: () -> Long,
        fetch: (afterExternalId: String?, size: Int) -> List<T>,
    ): KeysetPage<T> {
        val pageSize = limit ?: CursorPagination.DEFAULT_LIMIT
        val cursor = decode(next)
        val rows = fetch(cursor?.afterExternalId, pageSize + 1)
        val total = cursor?.totalCount ?: totalCount()
        val items = rows.take(pageSize)
        return KeysetPage(items, items.lastOrNull()?.takeIf { rows.size > pageSize }?.let { encode(Cursor(total, externalIdOf(it))) }, total)
    }

    // The external id comes last: it may be blank or contain ':'.
    private fun encode(cursor: Cursor) =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$CURSOR_PREFIX:${cursor.totalCount}:${cursor.afterExternalId}".toByteArray())

    private fun decode(cursor: String?): Cursor? =
        cursor?.takeIf { it.isNotEmpty() }?.let {
            val parts = runCatching { String(Base64.getUrlDecoder().decode(it)) }.getOrNull()?.split(":", limit = 3)
            val total = parts?.takeIf { p -> p.size == 3 && p[0] == CURSOR_PREFIX }?.get(1)?.toLongOrNull()?.takeIf { t -> t >= 0 }
            if (total == null) throw BadRequestException("Invalid cursor") else Cursor(total, parts[2])
        }
}
