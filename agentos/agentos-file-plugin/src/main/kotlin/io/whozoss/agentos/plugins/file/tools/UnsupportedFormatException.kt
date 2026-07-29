package io.whozoss.agentos.plugins.file.tools

/**
 * Thrown when a read tool is asked for a file format it does not handle; mapped to the
 * UNSUPPORTED_FORMAT error type so the model can route to the right sibling tool.
 */
internal class UnsupportedFormatException(message: String) : IllegalArgumentException(message)
