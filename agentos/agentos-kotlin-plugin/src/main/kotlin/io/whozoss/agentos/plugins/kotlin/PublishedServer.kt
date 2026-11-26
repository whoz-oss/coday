package io.whozoss.agentos.plugins.kotlin

/**
 * A language server as it announces itself in its port file: the TCP port it listens on, on the loopback interface,
 * and the id of the process that owns it.
 */
internal data class PublishedServer(
    val port: Int,
    val processId: Long,
) {
    companion object {
        /** Name of the port file inside the system path of a server, given to the server with `--port-file`. */
        const val PORT_FILE_NAME = "port"

        private const val MAX_PORT = 65_535

        /**
         * Reads the text of a port file: the port on the first line, the process id on the second. A third line names the
         * build of the server and is not needed.
         *
         * @return null when the text holds no usable port and process id, e.g. a file the server is still writing
         */
        fun parse(text: String): PublishedServer? {
            val lines = text.lines().map { line -> line.trim() }
            val port = lines.getOrNull(index = 0)?.toIntOrNull()
            val processId = lines.getOrNull(index = 1)?.toLongOrNull()
            return when {
                port != null && processId != null && port in 1..MAX_PORT -> PublishedServer(port = port, processId = processId)
                else -> null
            }
        }
    }
}
