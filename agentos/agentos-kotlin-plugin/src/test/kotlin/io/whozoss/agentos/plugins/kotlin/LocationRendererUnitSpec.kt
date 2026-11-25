package io.whozoss.agentos.plugins.kotlin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.nio.file.Path

private const val FILE_A = "file:///project/engine/src/A.kt"
private const val FILE_B = "file:///project/engine/src/B.kt"
private const val JAR = "jar:file:///libs/x.jar!/Foo.class"

private val paths = ProjectPaths(root = Path.of("/project"))

private fun location(
    uri: String,
    line: Int,
    endLine: Int = line,
) = Location(uri, Range(Position(line, 0), Position(endLine, 0)))

private fun rendererOf(
    sources: Map<String, List<String>> = emptyMap(),
    enclosing: Map<Pair<String, Int>, String> = emptyMap(),
    maxResults: Int = 50,
) = LocationRenderer(
    paths = paths,
    sourceLines = { uri -> sources[uri] },
    enclosingSymbols = { uri, line -> enclosing[uri to line] },
    maxResults = maxResults,
)

class LocationRendererUnitSpec :
    StringSpec({
        "groups locations by file with relative paths, enclosing symbols and snippets" {
            // given
            val sources =
                mapOf(
                    FILE_A to listOf("package a", "", "    val a = load()", "", "", "", "", "", "val b = load()"),
                    FILE_B to listOf("load()"),
                )
            val renderer = rendererOf(sources = sources, enclosing = mapOf((FILE_A to 2) to "A.run", (FILE_B to 0) to "B"))
            val locations = listOf(location(FILE_B, line = 0), location(FILE_A, line = 8), location(FILE_A, line = 2))

            // when
            val rendered = renderer.render(subject = "References to load", locations = locations)

            // then
            rendered shouldBe
                listOf(
                    "References to load — 3 in 2 files",
                    "engine/src/A.kt",
                    "  3  [A.run]  val a = load()",
                    "  9  val b = load()",
                    "engine/src/B.kt",
                    "  1  [B]  load()",
                ).joinToString(separator = "\n")
        }

        "removes duplicated locations" {
            // given
            val renderer = rendererOf(sources = mapOf(FILE_A to listOf("load()")))

            // when
            val rendered =
                renderer.render(
                    subject = "References to load",
                    locations = listOf(location(FILE_A, line = 0), location(FILE_A, line = 0)),
                )

            // then
            rendered shouldBe listOf("References to load — 1 in 1 file", "engine/src/A.kt", "  1  load()").joinToString(separator = "\n")
        }

        "lists a library location by uri without snippet" {
            rendererOf().render(subject = "Definition of RestClient", locations = listOf(location(JAR, line = 12))) shouldBe
                listOf("Definition of RestClient — 1 in 1 file", "[library] $JAR").joinToString(separator = "\n")
        }

        "puts library locations after project files" {
            // given
            val renderer = rendererOf(sources = mapOf(FILE_A to listOf("load()")))

            // when
            val rendered =
                renderer.render(
                    subject = "References to load",
                    locations = listOf(location(JAR, line = 1), location(FILE_A, line = 0)),
                )

            // then
            rendered.lines() shouldBe listOf("References to load — 2 in 2 files", "engine/src/A.kt", "  1  load()", "[library] $JAR")
        }

        "caps the shown results and reports the hidden count" {
            // given
            val renderer = rendererOf(sources = mapOf(FILE_A to listOf("a", "b", "c")), maxResults = 2)
            val locations = listOf(location(FILE_A, line = 0), location(FILE_A, line = 1), location(FILE_A, line = 2))

            // when
            val rendered = renderer.render(subject = "References to x", locations = locations)

            // then
            rendered shouldBe
                listOf(
                    "References to x — 3 in 1 file",
                    "engine/src/A.kt",
                    "  1  a",
                    "  2  b",
                    "… 1 more not shown",
                ).joinToString(separator = "\n")
        }

        "says so when there is no location" {
            rendererOf().render(subject = "References to x", locations = emptyList()) shouldBe "References to x — none found"
        }

        "shows several lines of a multi-line declaration with the first line indentation removed" {
            // given
            val sources =
                mapOf(
                    FILE_A to
                        listOf("package a", "", "    fun load(", "        path: String,", "    ): List<Agent> {", "        body", "    }"),
                )
            val renderer = rendererOf(sources = sources, enclosing = mapOf((FILE_A to 2) to "A.load"))

            // when
            val rendered =
                renderer.render(
                    subject = "Definition of load",
                    locations = listOf(location(FILE_A, line = 2, endLine = 6)),
                    maxSnippetLines = 3,
                )

            // then
            rendered.lines() shouldBe
                listOf(
                    "Definition of load — 1 in 1 file",
                    "engine/src/A.kt",
                    "  3  [A.load]  fun load(",
                    "  4      path: String,",
                    "  5  ): List<Agent> {",
                )
        }

        "stops the snippet at the end of the range" {
            // given
            val renderer = rendererOf(sources = mapOf(FILE_A to listOf("class A", "class B", "class C")))

            // when
            val rendered =
                renderer.render(
                    subject = "Definition of A",
                    locations = listOf(location(FILE_A, line = 0, endLine = 0)),
                    maxSnippetLines = 5,
                )

            // then
            rendered.lines() shouldBe listOf("Definition of A — 1 in 1 file", "engine/src/A.kt", "  1  class A")
        }

        "omits the snippet when the file cannot be read" {
            // given
            val renderer = rendererOf(enclosing = mapOf((FILE_A to 4) to "A.run"))

            // when
            val rendered = renderer.render(subject = "References to x", locations = listOf(location(FILE_A, line = 4)))

            // then
            rendered.lines() shouldBe listOf("References to x — 1 in 1 file", "engine/src/A.kt", "  5  [A.run]")
        }
    })
