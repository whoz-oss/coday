package io.whozoss.agentos.plugins.kotlin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either

private fun range(
    startLine: Int,
    endLine: Int,
    startColumn: Int = 0,
) = Range(Position(startLine, startColumn), Position(endLine, 0))

private fun documentSymbol(
    name: String,
    kind: SymbolKind,
    range: Range,
    nameLine: Int,
    nameColumn: Int,
    children: List<DocumentSymbol> = emptyList(),
) = DocumentSymbol(
    name,
    kind,
    range,
    Range(Position(nameLine, nameColumn), Position(nameLine, nameColumn + name.length)),
    null,
    children,
)

private fun hierarchical(vararg symbols: DocumentSymbol): List<Either<SymbolInformation, DocumentSymbol>> =
    symbols.map {
        Either.forRight(it)
    }

private fun loader(vararg members: DocumentSymbol) =
    documentSymbol(
        name = "AgentLoader",
        kind = SymbolKind.Class,
        range = range(startLine = 0, endLine = 20),
        nameLine = 0,
        nameColumn = 6,
        children = members.toList(),
    )

private fun load(
    startLine: Int,
    endLine: Int,
    name: String = "load",
) = documentSymbol(
    name = name,
    kind = SymbolKind.Function,
    range = range(startLine = startLine, endLine = endLine),
    nameLine = startLine,
    nameColumn = 8,
)

class DocumentSymbolIndexUnitSpec :
    StringSpec({
        "flattens nested symbols into qualified paths" {
            val index = DocumentSymbolIndex.from(entries = hierarchical(loader(load(startLine = 5, endLine = 10))))

            index.all.map { it.qualifiedName } shouldContainExactly listOf("AgentLoader", "AgentLoader.load")
        }

        "find by simple name returns the selection start of the declaration name" {
            val index = DocumentSymbolIndex.from(entries = hierarchical(loader(load(startLine = 5, endLine = 10))))

            val found = index.find(qualifiedName = "load")

            found shouldHaveSize 1
            found.single().nameStart shouldBe Position(5, 8)
        }

        "find by simple name returns every homonym" {
            val a =
                documentSymbol(
                    "A",
                    SymbolKind.Class,
                    range(startLine = 0, endLine = 5),
                    nameLine = 0,
                    nameColumn = 6,
                    children = listOf(load(startLine = 2, endLine = 4)),
                )
            val b =
                documentSymbol(
                    "B",
                    SymbolKind.Class,
                    range(startLine = 10, endLine = 15),
                    nameLine = 10,
                    nameColumn = 6,
                    children = listOf(load(startLine = 12, endLine = 14)),
                )
            val index = DocumentSymbolIndex.from(entries = hierarchical(a, b))

            index.find(qualifiedName = "load").map { it.qualifiedName } shouldContainExactly listOf("A.load", "B.load")
        }

        "find by qualified name disambiguates homonyms" {
            val a =
                documentSymbol(
                    "A",
                    SymbolKind.Class,
                    range(startLine = 0, endLine = 5),
                    nameLine = 0,
                    nameColumn = 6,
                    children = listOf(load(startLine = 2, endLine = 4)),
                )
            val b =
                documentSymbol(
                    "B",
                    SymbolKind.Class,
                    range(startLine = 10, endLine = 15),
                    nameLine = 10,
                    nameColumn = 6,
                    children = listOf(load(startLine = 12, endLine = 14)),
                )
            val index = DocumentSymbolIndex.from(entries = hierarchical(a, b))

            index.find(qualifiedName = "B.load").single().nameStart shouldBe Position(12, 8)
        }

        "find ignores a signature appended to the symbol name" {
            val index = DocumentSymbolIndex.from(entries = hierarchical(load(startLine = 5, endLine = 10, name = "load(path: String)")))

            index.find(qualifiedName = "load") shouldHaveSize 1
        }

        "find is empty when nothing is declared with that name" {
            DocumentSymbolIndex.from(entries = emptyList()).find(qualifiedName = "load") shouldHaveSize 0
        }

        "enclosing returns the innermost symbol containing the line" {
            val index = DocumentSymbolIndex.from(entries = hierarchical(loader(load(startLine = 5, endLine = 10))))

            index.enclosing(line = 7)?.qualifiedName shouldBe "AgentLoader.load"
            index.enclosing(line = 15)?.qualifiedName shouldBe "AgentLoader"
            index.enclosing(line = 30) shouldBe null
        }

        "flat symbol information is indexed using the container name as path prefix" {
            val information =
                SymbolInformation(
                    "load",
                    SymbolKind.Function,
                    Location("file:///project/A.kt", range(startLine = 5, endLine = 10, startColumn = 4)),
                    "fr.tokazio.AgentLoader",
                )
            val index = DocumentSymbolIndex.from(entries = listOf(Either.forLeft(information)))

            val found = index.find(qualifiedName = "AgentLoader.load")

            found shouldHaveSize 1
            found.single().nameStart shouldBe Position(5, 4)
        }
    })
