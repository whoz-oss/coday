package io.whozoss.agentos.plugins.kotlin

import org.eclipse.lsp4j.Location

/** Source lines of the file behind a `file:` URI, `null` when unreadable. */
internal fun interface SourceLines {
    fun linesOf(uri: String): List<String>?
}

/** Qualified name of the symbol enclosing a 0-based line, `null` when unknown. */
internal fun interface EnclosingSymbolNames {
    fun nameAt(
        uri: String,
        line: Int,
    ): String?
}

/**
 * Renders locations the way an agent wants to read them: grouped by file, project relative paths,
 * 1-based lines, the enclosing symbol and the code itself — so no follow-up file read is needed.
 */
internal class LocationRenderer(
    private val paths: ProjectPaths,
    private val sourceLines: SourceLines,
    private val enclosingSymbols: EnclosingSymbolNames,
    private val maxResults: Int = DEFAULT_MAX_RESULTS,
) {
    /**
     * Renders [locations] under the heading [subject].
     *
     * @param maxSnippetLines how many lines of a location to show, never past the end of its range
     */
    fun render(
        subject: String,
        locations: List<Location>,
        maxSnippetLines: Int = 1,
    ): String {
        val ordered = orderedWithoutDuplicates(locations = locations)
        return if (ordered.isEmpty()) {
            "$subject — none found"
        } else {
            val shown = ordered.take(maxResults)
            val fileCount = ordered.map { it.uri }.distinct().size
            val hidden = ordered.size - shown.size
            (
                listOf("$subject — ${ordered.size} in $fileCount ${if (fileCount == 1) "file" else "files"}") +
                    shown.groupBy { it.uri }.flatMap { (uri, group) ->
                        fileLines(uri = uri, group = group, maxSnippetLines = maxSnippetLines)
                    } +
                    listOfNotNull(hidden.takeIf { it > 0 }?.let { "… $it more not shown" })
            ).joinToString(separator = "\n")
        }
    }

    private fun orderedWithoutDuplicates(locations: List<Location>): List<Location> =
        locations
            .distinctBy { Triple(it.uri, it.range.start.line, it.range.start.character) }
            .sortedWith(
                compareBy<Location>(
                    { paths.relativeOf(uri = it.uri) == null },
                    { paths.relativeOf(uri = it.uri) ?: it.uri },
                    { it.range.start.line },
                    { it.range.start.character },
                ),
            )

    private fun fileLines(
        uri: String,
        group: List<Location>,
        maxSnippetLines: Int,
    ): List<String> {
        val relativePath = paths.relativeOf(uri = uri)
        return if (relativePath == null) {
            listOf("[library] $uri")
        } else {
            val lines = sourceLines.linesOf(uri = uri)
            listOf(relativePath) + group.flatMap { entryLines(uri = uri, location = it, lines = lines, maxSnippetLines = maxSnippetLines) }
        }
    }

    private fun entryLines(
        uri: String,
        location: Location,
        lines: List<String>?,
        maxSnippetLines: Int,
    ): List<String> {
        val firstLine = location.range.start.line
        val lastLine = minOf(location.range.end.line, firstLine + maxSnippetLines - 1).coerceAtLeast(firstLine)
        val enclosing = enclosingSymbols.nameAt(uri = uri, line = firstLine)?.let { "[$it]  " }.orEmpty()
        val first = lines?.getOrNull(firstLine).orEmpty()
        val baseIndent = first.takeWhile { it.isWhitespace() }.length
        val followingLines =
            (firstLine + 1..lastLine).mapNotNull { index ->
                lines?.getOrNull(index)?.let { text ->
                    "  ${index + 1}  ${text.drop(n = minOf(baseIndent, text.takeWhile { it.isWhitespace() }.length)).trimEnd()}"
                }
            }
        return listOf("  ${firstLine + 1}  $enclosing${first.trim()}".trimEnd()) + followingLines
    }

    companion object {
        const val DEFAULT_MAX_RESULTS = 50
    }
}
