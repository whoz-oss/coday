package io.whozoss.agentos.plugins.kotlin

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SymbolKind

/** A symbol found by the server, independent of the way it was found (workspace or document). */
internal data class SymbolCandidate(
    val name: String,
    val kind: SymbolKind,
    val uri: String,
    val position: Position,
    val containerName: String?,
) {
    /** `Kind Container.name — relative/path.kt:42` */
    fun describe(paths: ProjectPaths): String =
        "$kind ${listOfNotNull(
            containerName,
            name.withoutSignature(),
        ).joinToString(separator = ".")} — ${paths.place(uri = uri, position = position)}"
}

/** How well a symbol name matches a query, best first. */
internal enum class MatchTier { EXACT, EXACT_IGNORING_CASE, PREFIX, CONTAINS }

/** Some servers append the signature to the name: `load(path: String)` is the symbol `load`. */
internal fun String.withoutSignature(): String = substringBefore(delimiter = '(')

/** `AgentLoader.load` → `load`. */
internal fun String.lastSegment(): String = substringAfterLast(delimiter = '.')

/** Qualifier segments of a qualified name: `A.B.load` → `[A, B]`. */
internal fun String.qualifierSegments(): List<String> = split('.').dropLast(n = 1).filter { it.isNotEmpty() }

/** Ranking and rendering of symbol search results. */
internal object SymbolMatching {
    const val DEFAULT_LIMIT = 25

    /** The [MatchTier] of [symbolName] for [query], `null` when it does not match. */
    fun tierOf(
        query: String,
        symbolName: String,
    ): MatchTier? {
        val name = symbolName.withoutSignature()
        return when {
            name == query -> MatchTier.EXACT
            name.equals(query, ignoreCase = true) -> MatchTier.EXACT_IGNORING_CASE
            name.startsWith(query, ignoreCase = true) -> MatchTier.PREFIX
            name.contains(query, ignoreCase = true) -> MatchTier.CONTAINS
            else -> null
        }
    }

    /** Matching candidates, best tier first, then shortest name; same symbol at the same place appears once. */
    fun rank(
        query: String,
        candidates: List<SymbolCandidate>,
    ): List<SymbolCandidate> =
        candidates
            .distinctBy { Triple(it.uri, it.position.line, it.name) }
            .mapNotNull { candidate -> tierOf(query = query, symbolName = candidate.name)?.let { tier -> tier to candidate } }
            .sortedWith(
                compareBy<Pair<MatchTier, SymbolCandidate>>(
                    { it.first },
                    { it.second.name.length },
                    { it.second.name },
                ),
            ).map { it.second }

    /** The answer for the agent: a count header, then at most [limit] symbols. */
    fun render(
        query: String,
        ranked: List<SymbolCandidate>,
        paths: ProjectPaths,
        limit: Int = DEFAULT_LIMIT,
    ): String =
        if (ranked.isEmpty()) {
            "No symbols match '$query'"
        } else {
            val hidden = ranked.size - limit
            (
                listOf("Symbols matching '$query' — ${ranked.size}") +
                    ranked.take(limit).map { "  ${it.describe(paths = paths)}" } +
                    listOfNotNull(hidden.takeIf { it > 0 }?.let { "… $it more not shown — refine the query" })
            ).joinToString(separator = "\n")
        }
}
