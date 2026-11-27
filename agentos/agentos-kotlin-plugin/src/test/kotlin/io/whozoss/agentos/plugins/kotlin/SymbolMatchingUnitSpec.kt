package io.whozoss.agentos.plugins.kotlin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SymbolKind
import java.nio.file.Path

private const val FILE_A = "file:///project/engine/src/A.kt"

private fun candidate(
    name: String,
    uri: String = FILE_A,
    line: Int = 5,
    containerName: String? = null,
) = SymbolCandidate(
    name = name,
    kind = SymbolKind.Class,
    uri = uri,
    position = Position(line, 0),
    containerName = containerName,
)

class SymbolMatchingUnitSpec :
    StringSpec({
        val paths = ProjectPaths(root = Path.of("/project"))

        "tierOf classifies exact, case-insensitive exact, prefix and contains" {
            SymbolMatching.tierOf(query = "Loader", symbolName = "Loader") shouldBe MatchTier.EXACT
            SymbolMatching.tierOf(query = "Loader", symbolName = "loader") shouldBe MatchTier.EXACT_IGNORING_CASE
            SymbolMatching.tierOf(query = "Loader", symbolName = "LoaderTests") shouldBe MatchTier.PREFIX
            SymbolMatching.tierOf(query = "Loader", symbolName = "AgentLoader") shouldBe MatchTier.CONTAINS
            SymbolMatching.tierOf(query = "Loader", symbolName = "Unrelated") shouldBe null
        }

        "tierOf ignores a signature appended to the symbol name" {
            SymbolMatching.tierOf(query = "load", symbolName = "load(path: String)") shouldBe MatchTier.EXACT
        }

        "rank orders by tier then drops non matching symbols" {
            val candidates =
                listOf(
                    candidate(name = "MyAgentLoader"),
                    candidate(name = "Unrelated"),
                    candidate(name = "AgentLoaderTests"),
                    candidate(name = "agentloader"),
                    candidate(name = "AgentLoader"),
                )

            val ranked = SymbolMatching.rank(query = "AgentLoader", candidates = candidates)

            ranked.map { it.name } shouldContainExactly listOf("AgentLoader", "agentloader", "AgentLoaderTests", "MyAgentLoader")
        }

        "rank removes duplicates declared at the same place" {
            val ranked =
                SymbolMatching.rank(
                    query = "AgentLoader",
                    candidates = listOf(candidate(name = "AgentLoader"), candidate(name = "AgentLoader")),
                )

            ranked.size shouldBe 1
        }

        "describe shows kind, qualified name and place" {
            candidate(name = "load", containerName = "AgentLoader").describe(paths = paths) shouldBe
                "Class AgentLoader.load — engine/src/A.kt:6"
        }

        "render lists ranked symbols with a count header" {
            val ranked = listOf(candidate(name = "AgentLoader"), candidate(name = "AgentLoaderTests", line = 9))

            val rendered = SymbolMatching.render(query = "AgentLoader", ranked = ranked, paths = paths, limit = 25)

            rendered shouldBe
                listOf(
                    "Symbols matching 'AgentLoader' — 2",
                    "  Class AgentLoader — engine/src/A.kt:6",
                    "  Class AgentLoaderTests — engine/src/A.kt:10",
                ).joinToString(separator = "\n")
        }

        "render caps the list and reports how many symbols are hidden" {
            val ranked = listOf(candidate(name = "A1", line = 1), candidate(name = "A2", line = 2), candidate(name = "A3", line = 3))

            val rendered = SymbolMatching.render(query = "A", ranked = ranked, paths = paths, limit = 2)

            rendered shouldContain "Symbols matching 'A' — 3"
            rendered shouldContain "A2"
            rendered shouldNotContain "A3"
            rendered shouldContain "… 1 more not shown — refine the query"
        }

        "render says so when nothing matches" {
            SymbolMatching.render(query = "Nope", ranked = emptyList(), paths = paths, limit = 25) shouldBe "No symbols match 'Nope'"
        }
    })
