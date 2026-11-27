package io.whozoss.agentos.plugins.kotlin

import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * Adapter of a live [LanguageServerConnection] for the pure navigation code.
 *
 * Meant to live for one tool call: it caches document symbols and source lines per file,
 * so a result spread over many files does not query the same file twice.
 * Every file it reads or announces goes through [ProjectPaths.checked]: a sensitive file is never read.
 */
internal class ConnectionSymbolSource(
    private val connection: LanguageServerConnection,
    private val paths: ProjectPaths,
) : LspSymbolSource,
    SourceLines,
    EnclosingSymbolNames {
    private val indexes = mutableMapOf<String, DocumentSymbolIndex>()
    private val sources = mutableMapOf<String, List<String>?>()

    override fun documentSymbols(filePath: String): DocumentSymbolIndex =
        indexes.getOrPut(filePath) {
            val uri = connection.syncFile(filePath = paths.checked(filePath = filePath))
            val entries =
                connection.server.textDocumentService
                    .documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri)))
                    .awaitResult()
                    .orEmpty()
            DocumentSymbolIndex.from(entries = entries)
        }

    override fun workspaceSymbols(query: String): List<SymbolCandidate> {
        val result =
            connection.server.workspaceService
                .symbol(WorkspaceSymbolParams(query))
                .awaitResult()
        return when {
            result == null -> emptyList()
            result.isLeft -> result.left.map { candidateOf(information = it) }
            else -> result.right.map { candidateOf(symbol = it) }
        }
    }

    override fun lines(filePath: String): List<String>? =
        sources.getOrPut(filePath) { runCatching { Path.of(paths.checked(filePath = filePath)).readLines() }.getOrNull() }

    override fun linesOf(uri: String): List<String>? = paths.filePathOf(uri = uri)?.let { lines(filePath = it) }

    /**
     * The enclosing name is a nicety: when the server fails, the location is shown without it.
     * An interruption, such as the timeout the agent set for the whole call, is never swallowed.
     */
    override fun nameAt(
        uri: String,
        line: Int,
    ): String? =
        paths.filePathOf(uri = uri)?.let { filePath ->
            try {
                documentSymbols(filePath = filePath).enclosing(line = line)?.qualifiedName
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    private fun candidateOf(information: SymbolInformation) =
        SymbolCandidate(
            name = information.name,
            kind = information.kind,
            uri = information.location.uri,
            position = information.location.range.start,
            containerName = information.containerName,
        )

    private fun candidateOf(symbol: WorkspaceSymbol): SymbolCandidate {
        val location = symbol.location
        return SymbolCandidate(
            name = symbol.name,
            kind = symbol.kind,
            uri = if (location.isLeft) location.left.uri else location.right.uri,
            position = if (location.isLeft) location.left.range.start else Position(0, 0),
            containerName = symbol.containerName,
        )
    }
}
