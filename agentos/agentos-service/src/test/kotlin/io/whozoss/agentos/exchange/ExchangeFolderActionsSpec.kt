package io.whozoss.agentos.exchange

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.git.GitMetadataEntries
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Folder actions of the exchange: creating and deleting a folder, and downloading one as a ZIP.
 *
 * The folder is the unit users work with in the Files drawer, but until these existed the storage only knew
 * single files: a folder could be neither created nor deleted, and "download all" ignored the folder browsed.
 */
class ExchangeFolderActionsSpec :
    StringSpec({

        val service = ExchangeStorageService(ExchangeStorageConfigProperties(), listOf(GitMetadataEntries()))

        /** A scope with two sibling folders, a nested one, a root file and git metadata inside `repo`. */
        fun scope(): Path {
            val root = Files.createTempDirectory("agentos-folders-")
            root.resolve("top.txt").writeText("top")
            root.resolve("docs/specs").createDirectories()
            root.resolve("docs/readme.md").writeText("readme")
            root.resolve("docs/specs/api.md").writeText("api")
            root.resolve("other").createDirectories()
            root.resolve("other/keep.txt").writeText("keep")
            root.resolve("repo/src").createDirectories()
            root.resolve("repo/src/main.ts").writeText("main")
            root.resolve("repo/.git").createDirectories()
            root.resolve("repo/.git/config").writeText("[core]")
            return root
        }

        fun zipEntries(root: Path, path: String): Map<String, String> {
            val out = ByteArrayOutputStream()
            service.writeArchive(root, path, out)
            val entries = mutableMapOf<String, String>()
            ZipInputStream(out.toByteArray().inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().decodeToString() }
            }
            return entries
        }

        // ---------------------------------------------------------------- createDirectory

        "creating a folder makes it browsable and reports it as a directory" {
            val root = scope()

            val created = service.createDirectory(root, "docs/drafts")

            created.path shouldBe "docs/drafts"
            created.name shouldBe "drafts"
            created.directory shouldBe true
            Files.isDirectory(root.resolve("docs/drafts")) shouldBe true
        }

        "creating a nested folder creates its missing parents" {
            val root = scope()

            service.createDirectory(root, "a/b/c").path shouldBe "a/b/c"
            Files.isDirectory(root.resolve("a/b/c")) shouldBe true
        }

        "creating a folder in a scope nobody wrote to yet creates the scope" {
            val root = Files.createTempDirectory("agentos-folders-").resolve("absent")

            service.createDirectory(root, "first").path shouldBe "first"
            Files.isDirectory(root.resolve("first")) shouldBe true
        }

        "creating a folder that exists, as a folder or as a file, is a conflict" {
            val root = scope()

            shouldThrow<FileExistsException> { service.createDirectory(root, "docs") }
            shouldThrow<FileExistsException> { service.createDirectory(root, "top.txt") }
        }

        "creating a folder outside the scope, blank or reserved is refused" {
            val root = scope()

            shouldThrow<InvalidExchangePathException> { service.createDirectory(root, "../escape") }
            shouldThrow<InvalidExchangePathException> { service.createDirectory(root, "  ") }
            shouldThrow<InvalidExchangePathException> { service.createDirectory(root, "repo/.git") }
            shouldThrow<InvalidExchangePathException> { service.createDirectory(root, ".git") }
        }

        // ---------------------------------------------------------------- deleteDirectory

        "deleting a folder removes it with everything in it and leaves its siblings alone" {
            val root = scope()

            service.deleteDirectory(root, "docs")

            Files.exists(root.resolve("docs")) shouldBe false
            Files.exists(root.resolve("other/keep.txt")) shouldBe true
            Files.exists(root.resolve("top.txt")) shouldBe true
        }

        "deleting a missing folder is refused" {
            shouldThrow<NoSuchFileException> { service.deleteDirectory(scope(), "nope") }
        }

        "deleting a file through the folder operation is refused and keeps the file" {
            val root = scope()

            shouldThrow<NotDirectoryException> { service.deleteDirectory(root, "top.txt") }
            Files.exists(root.resolve("top.txt")) shouldBe true
        }

        "the scope root can never be deleted" {
            val root = scope()

            shouldThrow<InvalidExchangePathException> { service.deleteDirectory(root, "") }
            shouldThrow<InvalidExchangePathException> { service.deleteDirectory(root, ".") }
            shouldThrow<InvalidExchangePathException> { service.deleteDirectory(root, "docs/..") }
            Files.exists(root.resolve("docs/readme.md")) shouldBe true
        }

        "deleting outside the scope is refused" {
            shouldThrow<InvalidExchangePathException> { service.deleteDirectory(scope(), "../escape") }
        }

        "a folder whose subtree holds git metadata is a conflict and nothing is deleted" {
            val root = scope()
            // `.git` sits in a sub-folder, with a deletable file ahead of it in the walk order.
            root.resolve("repo/a-first.txt").writeText("first")

            shouldThrow<ReservedEntryConflictException> { service.deleteDirectory(root, "repo") }

            Files.exists(root.resolve("repo/.git/config")) shouldBe true
            Files.exists(root.resolve("repo/a-first.txt")) shouldBe true
            Files.exists(root.resolve("repo/src/main.ts")) shouldBe true
        }

        "an ancestor of a folder holding git metadata is a conflict too" {
            val root = Files.createTempDirectory("agentos-folders-")
            root.resolve("cases/repo/.git").createDirectories()
            root.resolve("cases/repo/.git/config").writeText("[core]")

            shouldThrow<ReservedEntryConflictException> { service.deleteDirectory(root, "cases") }
            Files.exists(root.resolve("cases/repo/.git/config")) shouldBe true
        }

        "a symlink inside the folder is removed as a link, never followed" {
            val root = scope()
            val outside = Files.createTempDirectory("agentos-outside-")
            outside.resolve("secret.txt").writeText("not yours")
            val linked = runCatching { Files.createSymbolicLink(root.resolve("docs/escape"), outside) }.isSuccess

            if (linked) {
                service.deleteDirectory(root, "docs")

                Files.exists(root.resolve("docs")) shouldBe false
                Files.exists(outside.resolve("secret.txt")) shouldBe true
            }
        }

        // ---------------------------------------------------------------- writeArchive

        "archiving a folder holds only its files, named relative to it" {
            val entries = zipEntries(scope(), "docs")

            entries shouldBe mapOf("readme.md" to "readme", "specs/api.md" to "api")
        }

        "archiving the root holds every file of the scope" {
            val entries = zipEntries(scope(), "")

            entries.keys shouldContainExactlyInAnyOrder
                listOf("top.txt", "docs/readme.md", "docs/specs/api.md", "other/keep.txt", "repo/src/main.ts")
        }

        "the archive leaves git metadata out" {
            val entries = zipEntries(scope(), "repo")

            entries shouldBe mapOf("src/main.ts" to "main")
        }

        "the archive leaves symlinks out" {
            val root = scope()
            val outside = Files.createTempDirectory("agentos-outside-")
            outside.resolve("secret.txt").writeText("not yours")
            val linked = runCatching { Files.createSymbolicLink(root.resolve("docs/escape"), outside) }.isSuccess

            if (linked) {
                zipEntries(root, "docs").keys shouldContainExactlyInAnyOrder listOf("readme.md", "specs/api.md")
            }
        }

        "archiving an empty folder or a scope nobody wrote to gives an empty archive" {
            val root = scope()
            root.resolve("empty").createDirectories()
            val never = Files.createTempDirectory("agentos-folders-").resolve("absent")

            zipEntries(root, "empty") shouldBe emptyMap()
            zipEntries(never, "") shouldBe emptyMap()
        }

        "archiving a missing folder, a file or a path outside the scope is refused before anything is written" {
            val root = scope()
            val out = ByteArrayOutputStream()

            shouldThrow<NoSuchFileException> { service.assertArchivable(root, "nope") }
            shouldThrow<NotDirectoryException> { service.assertArchivable(root, "top.txt") }
            shouldThrow<InvalidExchangePathException> { service.assertArchivable(root, "../escape") }
            shouldThrow<InvalidExchangePathException> { service.assertArchivable(root, ".git") }
            shouldThrow<NoSuchFileException> { service.writeArchive(root, "nope", out) }
            out.size() shouldBe 0
        }
    })
