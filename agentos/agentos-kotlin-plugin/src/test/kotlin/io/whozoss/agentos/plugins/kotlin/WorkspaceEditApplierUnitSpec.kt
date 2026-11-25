package io.whozoss.agentos.plugins.kotlin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.DeleteFile
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private fun withProject(block: (root: Path, applier: WorkspaceEditApplier) -> Unit) {
    val root = Files.createTempDirectory("kotlin-plugin-edit")
    try {
        block(root, WorkspaceEditApplier(paths = ProjectPaths(root = root)))
    } finally {
        root.toFile().deleteRecursively()
    }
}

private fun Path.uri(): String = "file://${toAbsolutePath().normalize()}"

private fun Path.absolute(): String = toAbsolutePath().normalize().toString()

private fun edit(
    startLine: Int,
    startChar: Int,
    endLine: Int,
    endChar: Int,
    text: String,
) = TextEdit(Range(Position(startLine, startChar), Position(endLine, endChar)), text)

private fun changes(vararg perFile: Pair<Path, List<TextEdit>>) = WorkspaceEdit(perFile.associate { (file, edits) -> file.uri() to edits })

private fun documentChanges(vararg operations: Either<TextDocumentEdit, ResourceOperation>) = WorkspaceEdit(operations.toList())

private fun textEdits(
    file: Path,
    vararg edits: TextEdit,
): Either<TextDocumentEdit, ResourceOperation> =
    Either.forLeft(TextDocumentEdit(VersionedTextDocumentIdentifier(file.uri(), null), edits.map { Either.forLeft(it) }))

private fun resource(operation: ResourceOperation): Either<TextDocumentEdit, ResourceOperation> = Either.forRight(operation)

class WorkspaceEditApplierUnitSpec :
    StringSpec({
        "an edit replaces its range" {
            withProject { root, applier ->
                val file = root.resolve("A.kt").also { it.writeText("class Old\n") }

                applier.apply(changes(file to listOf(edit(0, 6, 0, 9, "New"))))

                file.readText() shouldBe "class New\n"
            }
        }

        "several edits of one file apply whatever their order" {
            withProject { root, applier ->
                val file = root.resolve("A.kt").also { it.writeText("foo(foo)\nfoo\n") }

                applier.apply(changes(file to listOf(edit(0, 0, 0, 3, "bar"), edit(1, 0, 1, 3, "bar"), edit(0, 4, 0, 7, "bar"))))

                file.readText() shouldBe "bar(bar)\nbar\n"
            }
        }

        "two inserts at the same position keep the order of the edit list" {
            withProject { root, applier ->
                val file = root.resolve("A.kt").also { it.writeText("x") }

                applier.apply(changes(file to listOf(edit(0, 0, 0, 0, "a"), edit(0, 0, 0, 0, "b"))))

                file.readText() shouldBe "abx"
            }
        }

        "positions are counted per line on CRLF files" {
            withProject { root, applier ->
                val file = root.resolve("A.kt").also { it.writeText("a\r\nfoo\r\n") }

                applier.apply(changes(file to listOf(edit(1, 0, 1, 3, "bar"))))

                file.readText() shouldBe "a\r\nbar\r\n"
            }
        }

        "the result lists the modified files" {
            withProject { root, applier ->
                val file = root.resolve("A.kt").also { it.writeText("a") }

                val applied = applier.apply(changes(file to listOf(edit(0, 0, 0, 1, "b"))))

                applied.modified shouldContainExactly listOf(file.absolute())
                applied.vacated shouldContainExactly emptyList()
            }
        }

        "an edit on a missing file is skipped and the other edits still apply" {
            withProject { root, applier ->
                val missing = root.resolve("Missing.kt")
                val present = root.resolve("A.kt").also { it.writeText("a") }

                val applied = applier.apply(changes(missing to listOf(edit(0, 0, 0, 1, "b")), present to listOf(edit(0, 0, 0, 1, "b"))))

                present.readText() shouldBe "b"
                missing.exists() shouldBe false
                applied.modified shouldContainExactly listOf(present.absolute())
                applied.skipped shouldContainExactly listOf(missing.absolute())
            }
        }

        "document changes apply text edits and a file rename in order" {
            withProject { root, applier ->
                val old = root.resolve("Old.kt").also { it.writeText("class Old") }
                val new = root.resolve("New.kt")

                val applied =
                    applier.apply(
                        documentChanges(textEdits(old, edit(0, 6, 0, 9, "New")), resource(RenameFile(old.uri(), new.uri()))),
                    )

                old.exists() shouldBe false
                new.readText() shouldBe "class New"
                applied.vacated shouldContainExactly listOf(old.absolute())
                applied.modified shouldContainExactly listOf(new.absolute())
            }
        }

        "a rename onto an existing file is refused and nothing is touched" {
            withProject { root, applier ->
                val old = root.resolve("Old.kt").also { it.writeText("old") }
                val taken = root.resolve("Taken.kt").also { it.writeText("taken") }

                shouldThrow<IllegalArgumentException> { applier.apply(documentChanges(resource(RenameFile(old.uri(), taken.uri())))) }

                old.readText() shouldBe "old"
                taken.readText() shouldBe "taken"
            }
        }

        "create and delete operations act on disk" {
            withProject { root, applier ->
                val created = root.resolve("sub/Created.kt")
                val deleted = root.resolve("Deleted.kt").also { it.writeText("x") }

                applier.apply(documentChanges(resource(CreateFile(created.uri())), resource(DeleteFile(deleted.uri()))))

                created.exists() shouldBe true
                deleted.exists() shouldBe false
            }
        }

        "an edit outside the project is refused before anything is written" {
            withProject { root, applier ->
                val inside = root.resolve("A.kt").also { it.writeText("a") }
                val outside = Files.createTempFile("kotlin-plugin-outside", ".kt").also { it.writeText("a") }

                try {
                    shouldThrow<IllegalArgumentException> {
                        applier.apply(changes(inside to listOf(edit(0, 0, 0, 1, "b")), outside to listOf(edit(0, 0, 0, 1, "b"))))
                    }

                    inside.readText() shouldBe "a"
                    outside.readText() shouldBe "a"
                } finally {
                    Files.deleteIfExists(outside)
                }
            }
        }

        "an edit of a sensitive file is refused and nothing is written" {
            withProject { root, applier ->
                val inside = root.resolve("A.kt").also { it.writeText("a") }
                val secret = root.resolve(".env").also { it.writeText("KEY=1") }

                shouldThrow<IllegalArgumentException> {
                    applier.apply(changes(inside to listOf(edit(0, 0, 0, 1, "b")), secret to listOf(edit(0, 0, 0, 3, "XXX"))))
                }

                inside.readText() shouldBe "a"
                secret.readText() shouldBe "KEY=1"
            }
        }

        "an edit of the git configuration is refused" {
            withProject { root, applier ->
                val config = root.resolve(".git/config").also { it.parent.toFile().mkdirs() }.also { it.writeText("[core]") }

                shouldThrow<IllegalArgumentException> { applier.apply(changes(config to listOf(edit(0, 0, 0, 1, "x")))) }

                config.readText() shouldBe "[core]"
            }
        }

        "an edit through a symbolic link that leads out of the project is refused" {
            withProject { root, applier ->
                val outside = Files.createTempDirectory("kotlin-plugin-outside")
                val target = outside.resolve("Target.kt").also { it.writeText("a") }
                Files.createSymbolicLink(root.resolve("escape"), outside)

                try {
                    shouldThrow<IllegalArgumentException> {
                        applier.apply(changes(root.resolve("escape/Target.kt") to listOf(edit(0, 0, 0, 1, "b"))))
                    }

                    target.readText() shouldBe "a"
                } finally {
                    outside.toFile().deleteRecursively()
                }
            }
        }
    })
