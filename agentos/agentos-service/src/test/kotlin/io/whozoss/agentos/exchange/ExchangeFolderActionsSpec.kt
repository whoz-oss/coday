package io.whozoss.agentos.exchange

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.git.GitMetadataEntries
import io.whozoss.agentos.sdk.api.exchange.ExchangeScope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Folder actions of the file exchange: create a folder, delete one with its content, and archive one.
 *
 * They exist because the Files drawer could only act on single files: a folder could not be created or
 * removed, and "download all" had to send one file at a time. The guarantees under test are the
 * destructive ones: the scope root and reserved entries (a worktree's `.git`) are never removed, and a
 * link is never followed out of the scope.
 */
class ExchangeFolderActionsSpec :
    StringSpec({

        val service = ExchangeStorageService(ExchangeStorageConfigProperties(), listOf(GitMetadataEntries()))

        fun scope(): Path {
            val root = Files.createTempDirectory("agentos-folder-actions-")
            root.resolve("top.txt").writeText("top\n")
            root.resolve("docs/old").createDirectories()
            root.resolve("docs/guide.md").writeText("guide\n")
            root.resolve("docs/old/v1.txt").writeText("v1\n")
            root.resolve("other").createDirectories()
            root.resolve("other/keep.txt").writeText("keep\n")
            return root
        }

        fun unzip(bytes: ByteArray): Map<String, String> {
            val entries = mutableMapOf<String, String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readAllBytes().decodeToString() }
            }
            return entries
        }

        fun archive(
            root: Path,
            path: String,
        ): Map<String, String> {
            val out = ByteArrayOutputStream()
            service.writeArchive(root, path, ExchangeScope.CASE, out)
            return unzip(out.toByteArray())
        }

        // ---------------------------------------------------------------- create

        "creating a folder makes it, and its missing parents" {
            val root = scope()

            val entry = service.createDirectory(root, "reports/2026/q1")

            Files.isDirectory(root.resolve("reports/2026/q1")) shouldBe true
            entry.path shouldBe "reports/2026/q1"
            entry.directory shouldBe true
        }

        "creating a folder in a scope never written to creates the scope" {
            val root = Files.createTempDirectory("agentos-folder-actions-").resolve("absent")

            service.createDirectory(root, "first")

            Files.isDirectory(root.resolve("first")) shouldBe true
        }

        "creating a folder that already exists is a conflict" {
            val root = scope()
            shouldThrow<FileExistsException> { service.createDirectory(root, "docs") }
        }

        "creating a folder over an existing file is a conflict" {
            val root = scope()
            shouldThrow<FileExistsException> { service.createDirectory(root, "top.txt") }
        }

        "creating a folder refuses traversal, a blank name and reserved entries" {
            val root = scope()
            shouldThrow<InvalidExchangePathException> { service.createDirectory(root, "../outside") }
            shouldThrow<InvalidExchangePathException> { service.createDirectory(root, "  ") }
            shouldThrow<InvalidExchangePathException> { service.createDirectory(root, ".git") }
        }

        // ---------------------------------------------------------------- delete

        "deleting a folder removes it with everything it contains, and only that" {
            val root = scope()

            service.deleteDirectory(root, "docs")

            Files.exists(root.resolve("docs")) shouldBe false
            Files.exists(root.resolve("other/keep.txt")) shouldBe true
            Files.exists(root.resolve("top.txt")) shouldBe true
        }

        "deleting an empty folder works" {
            val root = scope()
            service.createDirectory(root, "empty")

            service.deleteDirectory(root, "empty")

            Files.exists(root.resolve("empty")) shouldBe false
        }

        "deleting a missing folder is reported as missing" {
            shouldThrow<NoSuchFileException> { service.deleteDirectory(scope(), "nope") }
        }

        "deleting a file through the folder operation is refused" {
            val root = scope()
            shouldThrow<NotDirectoryException> { service.deleteDirectory(root, "top.txt") }
            Files.exists(root.resolve("top.txt")) shouldBe true
        }

        "the scope root can never be deleted" {
            val root = scope()
            shouldThrow<InvalidExchangePathException> { service.deleteDirectory(root, "") }
            shouldThrow<InvalidExchangePathException> { service.deleteDirectory(root, "/") }
            shouldThrow<InvalidExchangePathException> { service.deleteDirectory(root, ".") }
            shouldThrow<InvalidExchangePathException> { service.deleteDirectory(root, "docs/..") }
            Files.exists(root.resolve("docs/guide.md")) shouldBe true
        }

        "a folder holding a reserved entry is refused and nothing is deleted" {
            val root = scope()
            root.resolve("repo/.git/objects").createDirectories()
            root.resolve("repo/.git/config").writeText("[core]\n")
            root.resolve("repo/src").createDirectories()
            root.resolve("repo/src/index.ts").writeText("x\n")

            shouldThrow<ReservedEntryConflictException> { service.deleteDirectory(root, "repo") }

            Files.exists(root.resolve("repo/.git/config")) shouldBe true
            Files.exists(root.resolve("repo/src/index.ts")) shouldBe true
        }

        "a linked worktree's .git file also protects its folder" {
            val root = scope()
            root.resolve("wt").createDirectories()
            root.resolve("wt/.git").writeText("gitdir: /elsewhere\n")
            root.resolve("wt/a.txt").writeText("a\n")

            shouldThrow<ReservedEntryConflictException> { service.deleteDirectory(root, "wt") }

            Files.exists(root.resolve("wt/a.txt")) shouldBe true
        }

        "a link inside the folder is removed as a link, never followed" {
            val root = scope()
            val outside = Files.createTempDirectory("agentos-outside-")
            outside.resolve("secret.txt").writeText("not yours\n")
            val linked = runCatching { Files.createSymbolicLink(root.resolve("docs/escape"), outside) }.isSuccess

            if (linked) {
                service.deleteDirectory(root, "docs")

                Files.exists(root.resolve("docs")) shouldBe false
                Files.exists(outside.resolve("secret.txt")) shouldBe true
            }
        }

        // ---------------------------------------------------------------- archive

        "archiving a folder contains only its files, named relative to it" {
            val entries = archive(scope(), "docs")

            entries.keys shouldContainExactlyInAnyOrder listOf("guide.md", "old/v1.txt")
            entries["guide.md"] shouldBe "guide\n"
        }

        "archiving the root contains every file of the scope" {
            val entries = archive(scope(), "")

            entries.keys shouldContainExactlyInAnyOrder listOf("top.txt", "docs/guide.md", "docs/old/v1.txt", "other/keep.txt")
        }

        "an archive leaves reserved entries out" {
            val root = scope()
            root.resolve("repo/.git").createDirectories()
            root.resolve("repo/.git/config").writeText("[core]\n")
            root.resolve("repo/a.txt").writeText("a\n")

            archive(root, "repo").keys shouldContainExactlyInAnyOrder listOf("a.txt")
            archive(root, "").keys.none { it.contains(".git") } shouldBe true
        }

        "archiving a scope never written to gives an empty archive" {
            val root = Files.createTempDirectory("agentos-folder-actions-").resolve("absent")

            archive(root, "") shouldBe emptyMap()
        }

        "archiving refuses a missing folder, a file and traversal before anything is written" {
            val root = scope()
            val out = ByteArrayOutputStream()

            shouldThrow<NoSuchFileException> { service.writeArchive(root, "nope", ExchangeScope.CASE, out) }
            shouldThrow<NotDirectoryException> { service.writeArchive(root, "top.txt", ExchangeScope.CASE, out) }
            shouldThrow<InvalidExchangePathException> { service.writeArchive(root, "../x", ExchangeScope.CASE, out) }
            shouldThrow<InvalidExchangePathException> { service.writeArchive(root, ".git", ExchangeScope.CASE, out) }
            out.size() shouldBe 0
        }
    })
