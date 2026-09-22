package io.whozoss.agentos.exchange

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.api.exchange.ExchangeScope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Git metadata is never reachable through the file-management API.
 *
 * An Exchange can contain a repository, where `.git` sits inside a browsable scope. Deleting it detaches the worktree, rewriting a linked worktree's
 * `.git` pointer file redirects later server-side commands at another worktree, and reading
 * `config` discloses remote URLs and absolute server paths.
 *
 * Ordinary dotfiles must stay fully usable: the point is to protect git's own metadata, not to
 * hide hidden files.
 */
class ExchangeGitMetadataGuardSpec :
    StringSpec({

        val service = ExchangeStorageService(ExchangeStorageConfigProperties())

        /** A scope root shaped like a real checkout: tracked files, a dotfile, and git metadata. */
        fun checkoutRoot(): Path {
            val root = Files.createTempDirectory("agentos-exchange-git-")
            root.resolve("README.md").writeText("readme\n")
            root.resolve(".gitignore").writeText("node_modules/\n")
            root.resolve("src").createDirectories()
            root.resolve("src/index.ts").writeText("export const x = 1\n")
            root.resolve(".git/hooks").createDirectories()
            root.resolve(".git/config").writeText("[remote \"origin\"]\n\turl = https://forge.example/org/p.git\n")
            root.resolve(".git/hooks/pre-commit").writeText("#!/bin/sh\n")
            return root
        }

        "reading a file inside .git is refused" {
            val root = checkoutRoot()
            shouldThrow<InvalidExchangePathException> { service.readContent(root, ".git/config") }
        }

        "deleting git metadata is refused" {
            val root = checkoutRoot()
            shouldThrow<InvalidExchangePathException> { service.delete(root, ".git") }
            shouldThrow<InvalidExchangePathException> { service.delete(root, ".git/config") }
            Files.exists(root.resolve(".git/config")) shouldBe true
        }

        "writing into .git is refused" {
            val root = checkoutRoot()
            shouldThrow<InvalidExchangePathException> {
                service.writeNew(root, ".git/hooks/post-checkout", "#!/bin/sh\n".toByteArray(), ExchangeScope.NAMESPACE)
            }
        }

        "the guard is case-insensitive, so it holds on a case-insensitive filesystem" {
            val root = checkoutRoot()
            shouldThrow<InvalidExchangePathException> { service.readContent(root, ".GIT/config") }
            shouldThrow<InvalidExchangePathException> { service.readContent(root, "src/../.Git/config") }
        }

        "a symlink aliasing .git does not bypass the guard" {
            val root = checkoutRoot()
            // A filesystem without symlink support has nothing to assert here.
            val linked = runCatching { Files.createSymbolicLink(root.resolve("docs"), root.resolve(".git")) }.isSuccess

            if (linked) {
                shouldThrow<InvalidExchangePathException> { service.readContent(root, "docs/config") }
            }
        }

        "the manifest lists the working tree but never git metadata" {
            val root = checkoutRoot()

            val listed = service.listManifest(root, ExchangeScope.NAMESPACE).map { it.path }

            listed shouldContainExactlyInAnyOrder listOf("README.md", ".gitignore", "src/index.ts")
        }

        "ordinary dotfiles stay readable and writable" {
            val root = checkoutRoot()

            service.readContent(root, ".gitignore").content shouldBe "node_modules/\n"
            service.writeNew(root, ".editorconfig", "root = true\n".toByteArray(), ExchangeScope.NAMESPACE)
            service.readContent(root, ".editorconfig").content shouldBe "root = true\n"
        }
    })
