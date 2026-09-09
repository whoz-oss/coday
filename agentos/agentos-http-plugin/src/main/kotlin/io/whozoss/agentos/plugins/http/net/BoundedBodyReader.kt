package io.whozoss.agentos.plugins.http.net

import okio.Buffer
import okio.BufferedSource

/** @property truncated True when the source held more than the bytes kept. */
class BoundedBody(val bytes: ByteArray, val truncated: Boolean)

/** Reads at most `maxBytes` from a body without ever buffering more than that plus one byte. */
object BoundedBodyReader {

    fun read(source: BufferedSource, maxBytes: Long): BoundedBody {
        val buffer = Buffer()
        while (buffer.size <= maxBytes && source.read(buffer, maxBytes + 1 - buffer.size) != -1L) {
            // keep reading until one byte past the cap or the end of the body
        }
        val truncated = buffer.size > maxBytes
        return BoundedBody(bytes = buffer.readByteArray(minOf(buffer.size, maxBytes)), truncated = truncated)
    }
}
