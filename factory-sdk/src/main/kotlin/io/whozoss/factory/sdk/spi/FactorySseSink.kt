package io.whozoss.factory.sdk.spi

/**
 * Minimal Server-Sent-Events string sink a plugin can use to stream events to a
 * client without depending on the host's web framework.
 */
interface FactorySseSink {
    /** Emit a named SSE event carrying [data]. */
    fun send(
        event: String,
        data: String,
    )

    /** Close the sink, releasing any underlying resource. */
    fun close()
}
