package io.whozoss.agentos.messaging

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** What [input] holds, read to its end; null as soon as it holds more than [maxBytes]. */
internal fun readAtMost(
    input: InputStream,
    maxBytes: Long,
): ByteArray? {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return bytes.toByteArray()
        total += read
        if (total > maxBytes) return null
        bytes.write(buffer, 0, read)
    }
}

private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
