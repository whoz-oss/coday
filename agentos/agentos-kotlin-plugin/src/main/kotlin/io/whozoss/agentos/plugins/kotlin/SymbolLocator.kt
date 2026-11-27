package io.whozoss.agentos.plugins.kotlin

import org.eclipse.lsp4j.Position

/** What the locator needs from the language server and the disk. Implemented by [ConnectionSymbolSource]. */
internal interface LspSymbolSource {
    fun documentSymbols(filePath: String): DocumentSymbolIndex

    fun workspaceSymbols(query: String): List<SymbolCandidate>

    fun lines(filePath: String): List<String>?
}

/** The outcome of [SymbolLocator.resolve]. */
internal sealed interface Resolution {
    /** [position] is 0-based, ready for an LSP request. [label] is how the symbol is named in results. */
    data class Resolved(
        val filePath: String,
        val position: Position,
        val label: String,
    ) : Resolution

    /** Not found or ambiguous: [message] tells the agent how to refine the call. Never a silent guess. */
    data class Unresolved(
        val message: String,
    ) : Resolution
}

/**
 * Turns what an agent naturally knows — a symbol name, maybe a file, maybe a 1-based line —
 * into the exact position the language server needs.
 *
 * - `file` + `line`: the symbol is the word on that line (a usage, e.g. seen in grep output)
 * - `file`: the symbol declared in that file, else its first usage in that file
 * - nothing: the symbol declared in the project, `Class.member` accepted to lift homonyms
 */
internal class SymbolLocator(
    private val source: LspSymbolSource,
    private val paths: ProjectPaths,
) {
    /** Resolves [symbol], optionally narrowed by [file] and a 1-based [line]. */
    fun resolve(
        symbol: String,
        file: String?,
        line: Int?,
    ): Resolution =
        when {
            symbol.isBlank() -> Resolution.Unresolved(message = "`symbol` must not be blank")
            line != null && file == null -> Resolution.Unresolved(message = "`line` requires `file`")
            file != null && line != null -> resolveOnLine(symbol = symbol.trim(), filePath = paths.checked(filePath = file), line = line)
            file != null -> resolveInFile(symbol = symbol.trim(), filePath = paths.checked(filePath = file))
            else -> resolveInWorkspace(symbol = symbol.trim())
        }

    private fun resolveOnLine(
        symbol: String,
        filePath: String,
        line: Int,
    ): Resolution {
        val lines = source.lines(filePath = filePath)
        return when {
            lines == null -> {
                cannotRead(filePath = filePath)
            }

            line !in 1..lines.size -> {
                Resolution.Unresolved(message = "line $line is out of range — file has ${lines.size} lines")
            }

            else -> {
                WordAnchor
                    .find(lineText = lines[line - 1], word = symbol.lastSegment())
                    ?.let { column -> Resolution.Resolved(filePath = filePath, position = Position(line - 1, column), label = symbol) }
                    ?: Resolution.Unresolved(message = "'${symbol.lastSegment()}' not found on line $line: ${lines[line - 1].trim()}")
            }
        }
    }

    private fun resolveInFile(
        symbol: String,
        filePath: String,
    ): Resolution {
        val index = source.documentSymbols(filePath = filePath)
        val declarations = index.find(qualifiedName = symbol)
        return when {
            declarations.size == 1 -> {
                val declaration = declarations.single()
                Resolution.Resolved(
                    filePath = filePath,
                    position = refinedToName(filePath = filePath, start = declaration.nameStart, name = declaration.name),
                    label = declaration.qualifiedName,
                )
            }

            declarations.size > 1 -> {
                ambiguous(
                    symbol = symbol,
                    candidates =
                        declarations.map { declaration ->
                            SymbolCandidate(
                                name = declaration.name,
                                kind = declaration.kind,
                                uri = paths.uriOf(filePath = filePath),
                                position = declaration.nameStart,
                                containerName =
                                    declaration.path
                                        .dropLast(n = 1)
                                        .joinToString(separator = ".")
                                        .ifEmpty { null },
                            )
                        },
                )
            }

            else -> {
                firstUsageInFile(symbol = symbol, filePath = filePath, index = index)
            }
        }
    }

    private fun firstUsageInFile(
        symbol: String,
        filePath: String,
        index: DocumentSymbolIndex,
    ): Resolution {
        val lines = source.lines(filePath = filePath)
        val usage =
            lines?.withIndex()?.firstNotNullOfOrNull { (lineIndex, text) ->
                WordAnchor.find(lineText = text, word = symbol.lastSegment())?.let { column -> Position(lineIndex, column) }
            }
        return when {
            lines == null -> {
                cannotRead(filePath = filePath)
            }

            usage != null -> {
                Resolution.Resolved(filePath = filePath, position = usage, label = symbol)
            }

            else -> {
                val declaredNames =
                    index.all
                        .filter { it.path.size == 1 }
                        .map { it.name }
                        .distinct()
                        .take(MAX_LISTED)
                Resolution.Unresolved(
                    message =
                        "'$symbol' not found in ${paths.displayPath(filePath = filePath)}" +
                            declaredNames.takeIf { it.isNotEmpty() }?.let { ". Declared there: ${it.joinToString()}" }.orEmpty(),
                )
            }
        }
    }

    private fun resolveInWorkspace(symbol: String): Resolution {
        val name = symbol.lastSegment()
        val qualifier = symbol.qualifierSegments()
        val ranked =
            SymbolMatching.rank(
                query = name,
                candidates = source.workspaceSymbols(query = name).filter { paths.isProjectUri(uri = it.uri) },
            )
        val exact =
            ranked.filter { candidate ->
                SymbolMatching.tierOf(query = name, symbolName = candidate.name) == MatchTier.EXACT &&
                    containerEndsWith(candidate = candidate, qualifier = qualifier)
            }
        return when {
            exact.size == 1 -> {
                resolveCandidate(candidate = exact.single(), label = symbol)
            }

            exact.size > 1 -> {
                ambiguous(symbol = symbol, candidates = exact)
            }

            ranked.isEmpty() -> {
                Resolution.Unresolved(message = "No project symbol matches '$symbol'. Check the spelling or pass `file`.")
            }

            else -> {
                Resolution.Unresolved(
                    message =
                        "No exact match for '$symbol'. Closest:\n" +
                            ranked.take(MAX_LISTED).joinToString(separator = "\n") { "  ${it.describe(paths = paths)}" },
                )
            }
        }
    }

    private fun resolveCandidate(
        candidate: SymbolCandidate,
        label: String,
    ): Resolution {
        val filePath = requireNotNull(paths.filePathOf(uri = candidate.uri)) { "project symbol without file path: ${candidate.uri}" }
        return Resolution.Resolved(
            filePath = filePath,
            position = refinedToName(filePath = filePath, start = candidate.position, name = candidate.name.withoutSignature()),
            label = label,
        )
    }

    private fun containerEndsWith(
        candidate: SymbolCandidate,
        qualifier: List<String>,
    ): Boolean =
        candidate.containerName
            .orEmpty()
            .split('.')
            .filter { it.isNotEmpty() }
            .takeLast(n = qualifier.size) == qualifier

    /** Moves [start] onto the first whole-word [name] at or after it on the same line; [start] when not found. */
    private fun refinedToName(
        filePath: String,
        start: Position,
        name: String,
    ): Position =
        source
            .lines(filePath = filePath)
            ?.getOrNull(start.line)
            ?.let { text -> WordAnchor.find(lineText = text, word = name, fromColumn = start.character) }
            ?.let { column -> Position(start.line, column) }
            ?: start

    private fun ambiguous(
        symbol: String,
        candidates: List<SymbolCandidate>,
    ): Resolution =
        Resolution.Unresolved(
            message =
                "'$symbol' is ambiguous (${candidates.size} matches) — pass `file`, or a qualified name like Class.member:\n" +
                    candidates.take(MAX_LISTED).joinToString(separator = "\n") { "  ${it.describe(paths = paths)}" } +
                    (candidates.size - MAX_LISTED).takeIf { it > 0 }?.let { "\n  … $it more" }.orEmpty(),
        )

    private fun cannotRead(filePath: String): Resolution =
        Resolution.Unresolved(message = "Cannot read ${paths.displayPath(filePath = filePath)}")

    private companion object {
        const val MAX_LISTED = 10
    }
}
