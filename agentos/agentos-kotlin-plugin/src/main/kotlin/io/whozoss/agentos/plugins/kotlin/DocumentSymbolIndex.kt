package io.whozoss.agentos.plugins.kotlin

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * A declared symbol of one file. [path] holds the container names then the symbol name.
 * [nameStart] is where the name starts when the server reports a selection range,
 * otherwise where the declaration starts.
 */
internal data class FlatSymbol(
    val path: List<String>,
    val kind: SymbolKind,
    val nameStart: Position,
    val range: Range,
) {
    val name: String get() = path.last()
    val qualifiedName: String get() = path.joinToString(separator = ".")
}

/** Declarations of one file, searchable by (qualified) name and by line. */
internal class DocumentSymbolIndex(
    symbols: List<FlatSymbol>,
) {
    val all: List<FlatSymbol> = symbols

    /** Symbols whose path ends with [qualifiedName]: `load` matches every `load`, `AgentLoader.load` only one. */
    fun find(qualifiedName: String): List<FlatSymbol> {
        val wanted = qualifiedName.split('.').filter { it.isNotEmpty() }
        return all.filter { it.path.takeLast(n = wanted.size) == wanted }
    }

    /** Innermost symbol whose range contains the 0-based [line]. */
    fun enclosing(line: Int): FlatSymbol? =
        all
            .filter { line in it.range.start.line..it.range.end.line }
            .minWithOrNull(compareBy<FlatSymbol>({ it.range.end.line - it.range.start.line }, { -it.path.size }))

    companion object {
        /** Handles both shapes a server may answer: hierarchical [DocumentSymbol] and flat [SymbolInformation]. */
        fun from(entries: List<Either<SymbolInformation, DocumentSymbol>>): DocumentSymbolIndex =
            DocumentSymbolIndex(
                symbols =
                    entries.flatMap { entry ->
                        if (entry.isLeft) {
                            listOf(flatOf(information = entry.left))
                        } else {
                            flatten(symbol = entry.right, parentPath = emptyList())
                        }
                    },
            )

        private fun flatten(
            symbol: DocumentSymbol,
            parentPath: List<String>,
        ): List<FlatSymbol> {
            val path = parentPath + symbol.name.withoutSignature()
            return listOf(
                FlatSymbol(path = path, kind = symbol.kind, nameStart = symbol.selectionRange.start, range = symbol.range),
            ) + symbol.children.orEmpty().flatMap { child -> flatten(symbol = child, parentPath = path) }
        }

        private fun flatOf(information: SymbolInformation): FlatSymbol =
            FlatSymbol(
                path =
                    information.containerName
                        .orEmpty()
                        .split('.')
                        .filter { it.isNotEmpty() } + information.name.withoutSignature(),
                kind = information.kind,
                nameStart = information.location.range.start,
                range = information.location.range,
            )
    }
}
