package io.whozoss.agentos.exchange

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Browsing one directory level at a time.
 *
 * This exists because the recursive manifest is unusable on a repository: an associated namespace
 * returns thousands of entries flat, on every open. Here the cost and the response are bounded by
 * one directory.
 */
class ExchangeDirectoryListingSpec :
    StringSpec({

        val service = ExchangeStorageService(ExchangeStorageConfigProperties())

        /** A scope shaped like a checkout: nested sources, a dotfile, and git metadata. */
        fun checkout(): Path {
            val root = Files.createTempDirectory("agentos-browse-")
            root.resolve("README.md").writeText("readme\n")
            root.resolve(".gitignore").writeText("node_modules/\n")
            root.resolve("src/main").createDirectories()
            root.resolve("src/main/app.ts").writeText("export const x = 1\n")
            root.resolve("src/index.ts").writeText("export * from './main/app'\n")
            root.resolve("docs").createDirectories()
            root.resolve("docs/guide.md").writeText("guide\n")
            root.resolve(".git/objects").createDirectories()
            root.resolve(".git/config").writeText("[core]\n")
            return root
        }

        "the root level lists its own children only, never the whole tree" {
            val (entries, total) = service.listDirectory(checkout(), "", 0, 100)

            // src/main/app.ts is two levels down and must not appear here.
            entries.map { it.path } shouldContainExactly listOf("docs", "src", ".gitignore", "README.md")
            total shouldBe 4
        }

        "directories come first, then files, each alphabetically" {
            val (entries, _) = service.listDirectory(checkout(), "", 0, 100)

            entries.map { it.directory } shouldContainExactly listOf(true, true, false, false)
        }

        "descending into a directory lists that level" {
            val (entries, total) = service.listDirectory(checkout(), "src", 0, 100)

            entries.map { it.path } shouldContainExactly listOf("src/main", "src/index.ts")
            total shouldBe 2
        }

        "a directory reports no size, a file reports its own" {
            val (entries, _) = service.listDirectory(checkout(), "", 0, 100)

            entries.first { it.name == "src" }.size shouldBe null
            (entries.first { it.name == "README.md" }.size ?: 0) shouldBe "readme\n".length.toLong()
        }

        "git metadata is never listed" {
            val (entries, _) = service.listDirectory(checkout(), "", 0, 100)

            entries.none { it.name == ".git" } shouldBe true
            shouldThrow<InvalidExchangePathException> { service.listDirectory(checkout(), ".git", 0, 100) }
        }

        "results are paginated, and the total counts the whole directory" {
            val root = Files.createTempDirectory("agentos-browse-many-")
            repeat(25) { root.resolve("file-%02d.txt".format(it)).writeText("x") }

            val (firstPage, total) = service.listDirectory(root, "", 0, 10)
            val (thirdPage, _) = service.listDirectory(root, "", 2, 10)

            firstPage.size shouldBe 10
            total shouldBe 25
            thirdPage.size shouldBe 5
            firstPage.first().name shouldBe "file-00.txt"
            thirdPage.last().name shouldBe "file-24.txt"
        }

        "a very large page is empty rather than overflowing its offset" {
            val (entries, total) = service.listDirectory(checkout(), "", Int.MAX_VALUE, 500)
            entries shouldBe emptyList()
            total shouldBe 4
        }

        "a symlink is skipped rather than followed out of the scope" {
            val root = checkout()
            val outside = Files.createTempDirectory("agentos-outside-")
            outside.resolve("secret.txt").writeText("not yours\n")
            val linked = runCatching { Files.createSymbolicLink(root.resolve("escape"), outside) }.isSuccess

            if (linked) {
                val (entries, _) = service.listDirectory(root, "", 0, 100)
                entries.none { it.name == "escape" } shouldBe true
            }
        }

        "listing a file rather than a directory is refused" {
            shouldThrow<NotDirectoryException> { service.listDirectory(checkout(), "README.md", 0, 100) }
        }

        "listing a missing directory is refused" {
            shouldThrow<NoSuchFileException> { service.listDirectory(checkout(), "nope", 0, 100) }
        }

        "traversal outside the scope is refused" {
            shouldThrow<InvalidExchangePathException> { service.listDirectory(checkout(), "../..", 0, 100) }
        }

        "a scope that was never written to is empty rather than an error" {
            val never = Files.createTempDirectory("agentos-browse-").resolve("absent")

            service.listDirectory(never, "", 0, 100) shouldBe (emptyList<Any>() to 0)
        }
    })
