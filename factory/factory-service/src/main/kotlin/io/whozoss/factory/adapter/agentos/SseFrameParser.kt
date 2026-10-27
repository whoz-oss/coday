package io.whozoss.factory.adapter.agentos

/**
 * One dispatched SSE frame.
 *
 * [isHeartbeat] frames correspond to SSE comment lines (`:keep-alive`): they
 * carry no event data and are used purely as a liveness signal by the client.
 */
data class SseFrame(
    val id: String?,
    val event: String?,
    val data: String,
    val isHeartbeat: Boolean = false,
)

/**
 * Pure, incremental parser of the SSE wire format (`text/event-stream`).
 *
 * Fed line by line (without line terminators); a blank line dispatches the
 * accumulated `data:` lines (joined with `\n`) together with the last seen
 * `id:` and `event:` fields. Comment lines (`:…`) are returned immediately as
 * heartbeat frames. Unknown fields (e.g. `retry:`) are ignored per spec.
 *
 * This class is deliberately free of any I/O so it can be unit-tested over
 * arbitrary line splits (partial-buffer boundaries are invisible to it).
 */
class SseFrameParser {

    private val dataLines = mutableListOf<String>()
    private var id: String? = null
    private var event: String? = null

    /**
     * Feed one line. Returns a dispatched [SseFrame] on a blank-line boundary
     * or immediately for a comment/heartbeat line; null when the line only
     * accumulated state.
     */
    fun feedLine(rawLine: String): SseFrame? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return SseFrame(id = null, event = null, data = "", isHeartbeat = true)
        val colonIndex = line.indexOf(':')
        val field: String
        val value: String
        if (colonIndex < 0) {
            field = line
            value = ""
        } else {
            field = line.substring(0, colonIndex)
            value = line.substring(colonIndex + 1).removePrefix(" ")
        }
        when (field) {
            "data" -> dataLines.add(value)
            "id" -> id = value
            "event" -> event = value
            else -> Unit // unknown field (retry:, …) — ignored per spec
        }
        return null
    }

    /**
     * Flush any pending (unterminated) event at end-of-stream. Returns null
     * when nothing was accumulated.
     */
    fun finish(): SseFrame? = dispatch()

    private fun dispatch(): SseFrame? {
        if (dataLines.isEmpty()) {
            // No data accumulated: reset id/event per spec and emit nothing.
            id = null
            event = null
            return null
        }
        val frame = SseFrame(id = id, event = event, data = dataLines.joinToString("\n"))
        dataLines.clear()
        id = null
        event = null
        return frame
    }
}
