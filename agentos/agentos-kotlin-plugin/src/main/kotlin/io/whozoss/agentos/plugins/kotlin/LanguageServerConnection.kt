package io.whozoss.agentos.plugins.kotlin

import org.eclipse.lsp4j.services.LanguageServer

/** A connection to a running language server the tools talk to, with the documents it has open. [LspConnection] is the real one. */
internal interface LanguageServerConnection : AutoCloseable {
    /** The remote language server. */
    val server: LanguageServer

    /** `true` as long as the server is reachable. */
    fun isAlive(): Boolean

    /**
     * Announces the current content of [filePath] to the server (open, or change when it differs) and returns its
     * `file:` URI. The path must have been through [ProjectPaths.checked].
     */
    fun syncFile(filePath: String): String

    /** Tells the server that [filePath] is no longer open. */
    fun closeFile(filePath: String)
}
