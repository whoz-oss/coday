package io.whozoss.agentos.plugins.kotlin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import java.nio.file.Path

private const val ROOT_FILE_A = "/project/engine/A.kt"
private const val URI_A = "file:///project/engine/A.kt"
private const val URI_B = "file:///project/engine/B.kt"
private const val URI_JAR = "jar:file:///libs/x.jar!/Foo.class"

private class FakeSymbolSource(
    private val files: Map<String, List<String>> = emptyMap(),
    private val declared: Map<String, List<FlatSymbol>> = emptyMap(),
    private val workspace: List<SymbolCandidate> = emptyList(),
) : LspSymbolSource {
    val workspaceQueries = mutableListOf<String>()

    override fun documentSymbols(filePath: String): DocumentSymbolIndex = DocumentSymbolIndex(symbols = declared[filePath].orEmpty())

    override fun workspaceSymbols(query: String): List<SymbolCandidate> {
        workspaceQueries += query
        return workspace
    }

    override fun lines(filePath: String): List<String>? = files[filePath]
}

private fun fileLines(vararg numbered: Pair<Int, String>): List<String> {
    val byIndex = numbered.toMap()
    return List(size = 12) { index -> byIndex[index].orEmpty() }
}

private fun flat(
    vararg path: String,
    line: Int,
    column: Int,
) = FlatSymbol(
    path = path.toList(),
    kind = SymbolKind.Function,
    nameStart = Position(line, column),
    range = Range(Position(line, 0), Position(line + 2, 0)),
)

private fun candidate(
    name: String,
    uri: String,
    line: Int,
    containerName: String? = null,
) = SymbolCandidate(name = name, kind = SymbolKind.Class, uri = uri, position = Position(line, 0), containerName = containerName)

private fun resolve(
    source: LspSymbolSource,
    symbol: String,
    file: String? = null,
    line: Int? = null,
): Resolution =
    SymbolLocator(source = source, paths = ProjectPaths(root = Path.of("/project"))).resolve(symbol = symbol, file = file, line = line)

private fun messageOf(resolution: Resolution): String = (resolution as Resolution.Unresolved).message

class SymbolLocatorUnitSpec :
    StringSpec({
        // anchor mode (file + line + symbol)

        "anchor: resolves the symbol found on the 1-based line" {
            val source = FakeSymbolSource(files = mapOf(ROOT_FILE_A to fileLines(1 to "    val x = load(path)")))

            resolve(source = source, symbol = "load", file = ROOT_FILE_A, line = 2) shouldBe
                Resolution.Resolved(filePath = ROOT_FILE_A, position = Position(1, 12), label = "load")
        }

        "anchor: accepts a project relative file path" {
            val source = FakeSymbolSource(files = mapOf(ROOT_FILE_A to fileLines(1 to "    val x = load(path)")))

            resolve(source = source, symbol = "load", file = "engine/A.kt", line = 2) shouldBe
                Resolution.Resolved(filePath = ROOT_FILE_A, position = Position(1, 12), label = "load")
        }

        "anchor: uses the last segment of a qualified symbol" {
            val source = FakeSymbolSource(files = mapOf(ROOT_FILE_A to fileLines(1 to "    val x = load(path)")))

            resolve(source = source, symbol = "AgentLoader.load", file = ROOT_FILE_A, line = 2) shouldBe
                Resolution.Resolved(filePath = ROOT_FILE_A, position = Position(1, 12), label = "AgentLoader.load")
        }

        "anchor: explains when the symbol is not on that line" {
            val source = FakeSymbolSource(files = mapOf(ROOT_FILE_A to fileLines(1 to "    val x = other()")))

            messageOf(resolve(source = source, symbol = "load", file = ROOT_FILE_A, line = 2)) shouldContain
                "'load' not found on line 2: val x = other()"
        }

        "anchor: explains when the line is out of range" {
            val source = FakeSymbolSource(files = mapOf(ROOT_FILE_A to fileLines()))

            messageOf(resolve(source = source, symbol = "load", file = ROOT_FILE_A, line = 99)) shouldContain
                "line 99 is out of range — file has 12 lines"
        }

        "anchor: explains when the file cannot be read" {
            messageOf(resolve(source = FakeSymbolSource(), symbol = "load", file = ROOT_FILE_A, line = 2)) shouldContain
                "Cannot read engine/A.kt"
        }

        // file mode (file + symbol)

        "file: resolves a symbol declared in the file at its name position" {
            val source =
                FakeSymbolSource(
                    files = mapOf(ROOT_FILE_A to fileLines(5 to "    fun load()")),
                    declared = mapOf(ROOT_FILE_A to listOf(flat("AgentLoader", "load", line = 5, column = 8))),
                )

            resolve(source = source, symbol = "load", file = ROOT_FILE_A) shouldBe
                Resolution.Resolved(filePath = ROOT_FILE_A, position = Position(5, 8), label = "AgentLoader.load")
        }

        "file: refines a declaration start to the name column" {
            val source =
                FakeSymbolSource(
                    files = mapOf(ROOT_FILE_A to fileLines(5 to "    fun load()")),
                    declared = mapOf(ROOT_FILE_A to listOf(flat("AgentLoader", "load", line = 5, column = 4))),
                )

            resolve(source = source, symbol = "load", file = ROOT_FILE_A) shouldBe
                Resolution.Resolved(filePath = ROOT_FILE_A, position = Position(5, 8), label = "AgentLoader.load")
        }

        "file: falls back to the first usage when the symbol is not declared in the file" {
            val source = FakeSymbolSource(files = mapOf(ROOT_FILE_A to fileLines(3 to "    val x = AgentLoader()")))

            resolve(source = source, symbol = "AgentLoader", file = ROOT_FILE_A) shouldBe
                Resolution.Resolved(filePath = ROOT_FILE_A, position = Position(3, 12), label = "AgentLoader")
        }

        "file: reports homonyms declared in the file instead of choosing one" {
            val source =
                FakeSymbolSource(
                    files = mapOf(ROOT_FILE_A to fileLines(2 to "    fun load()", 8 to "    fun load()")),
                    declared =
                        mapOf(
                            ROOT_FILE_A to listOf(flat("A", "load", line = 2, column = 8), flat("B", "load", line = 8, column = 8)),
                        ),
                )

            val message = messageOf(resolve(source = source, symbol = "load", file = ROOT_FILE_A))

            message shouldContain "'load' is ambiguous (2 matches)"
            message shouldContain "A.load — engine/A.kt:3"
            message shouldContain "B.load — engine/A.kt:9"
        }

        "file: lists the declared names when the symbol is nowhere in the file" {
            val source =
                FakeSymbolSource(
                    files = mapOf(ROOT_FILE_A to fileLines()),
                    declared = mapOf(ROOT_FILE_A to listOf(flat("AgentLoader", line = 0, column = 6))),
                )

            val message = messageOf(resolve(source = source, symbol = "unknown", file = ROOT_FILE_A))

            message shouldContain "'unknown' not found in engine/A.kt"
            message shouldContain "AgentLoader"
        }

        // workspace mode (symbol only)

        "workspace: resolves a unique exact match at its name position" {
            val source =
                FakeSymbolSource(
                    files = mapOf(ROOT_FILE_A to fileLines(5 to "class AgentLoader(")),
                    workspace = listOf(candidate(name = "AgentLoader", uri = URI_A, line = 5)),
                )

            resolve(source = source, symbol = "AgentLoader") shouldBe
                Resolution.Resolved(filePath = ROOT_FILE_A, position = Position(5, 6), label = "AgentLoader")
        }

        "workspace: ignores library symbols" {
            val source =
                FakeSymbolSource(
                    files = mapOf(ROOT_FILE_A to fileLines(5 to "class AgentLoader(")),
                    workspace =
                        listOf(
                            candidate(name = "AgentLoader", uri = URI_JAR, line = 1),
                            candidate(name = "AgentLoader", uri = URI_A, line = 5),
                        ),
                )

            resolve(source = source, symbol = "AgentLoader") shouldBe
                Resolution.Resolved(filePath = ROOT_FILE_A, position = Position(5, 6), label = "AgentLoader")
        }

        "workspace: reports homonyms across files instead of choosing one" {
            val source =
                FakeSymbolSource(
                    workspace =
                        listOf(
                            candidate(name = "Config", uri = URI_A, line = 5),
                            candidate(name = "Config", uri = URI_B, line = 2),
                        ),
                )

            val message = messageOf(resolve(source = source, symbol = "Config"))

            message shouldContain "'Config' is ambiguous (2 matches)"
            message shouldContain "engine/A.kt:6"
            message shouldContain "engine/B.kt:3"
        }

        "workspace: a qualified name picks the member of the right container and queries by simple name" {
            val source =
                FakeSymbolSource(
                    files = mapOf(ROOT_FILE_A to fileLines(5 to "    fun load()")),
                    workspace =
                        listOf(
                            candidate(name = "load", uri = URI_A, line = 5, containerName = "fr.tokazio.AgentLoader"),
                            candidate(name = "load", uri = URI_B, line = 2, containerName = "fr.tokazio.Other"),
                        ),
                )

            resolve(source = source, symbol = "AgentLoader.load") shouldBe
                Resolution.Resolved(filePath = ROOT_FILE_A, position = Position(5, 8), label = "AgentLoader.load")
            source.workspaceQueries shouldContainExactly listOf("load")
        }

        "workspace: suggests the closest names when there is no exact match" {
            val source = FakeSymbolSource(workspace = listOf(candidate(name = "AgentLoaderTests", uri = URI_A, line = 5)))

            val message = messageOf(resolve(source = source, symbol = "AgentLoader"))

            message shouldContain "No exact match for 'AgentLoader'"
            message shouldContain "AgentLoaderTests"
        }

        "workspace: explains when nothing matches" {
            messageOf(resolve(source = FakeSymbolSource(), symbol = "Nope")) shouldContain "No project symbol matches 'Nope'"
        }

        // argument validation

        "rejects a blank symbol" {
            messageOf(resolve(source = FakeSymbolSource(), symbol = "  ")) shouldContain "`symbol` must not be blank"
        }

        "rejects a line without a file" {
            messageOf(resolve(source = FakeSymbolSource(), symbol = "load", line = 3)) shouldContain "`line` requires `file`"
        }

        "refuses a file outside the project instead of reading it" {
            val error = runCatching { resolve(source = FakeSymbolSource(), symbol = "x", file = "/etc/passwd", line = 1) }.exceptionOrNull()

            (error is IllegalArgumentException) shouldBe true
        }
    })
