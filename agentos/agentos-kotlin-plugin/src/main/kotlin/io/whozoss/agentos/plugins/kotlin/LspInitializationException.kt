package io.whozoss.agentos.plugins.kotlin

/** The language server could not be started or did not complete the LSP handshake. The message is safe to show the agent. */
internal class LspInitializationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
