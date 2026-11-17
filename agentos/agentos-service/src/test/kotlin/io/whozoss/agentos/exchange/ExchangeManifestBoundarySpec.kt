package io.whozoss.agentos.exchange

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.git.GitMetadataEntries
import io.whozoss.agentos.sdk.api.exchange.ExchangeScope
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator

/** Manifest boundaries against an isolated filesystem, not an exhaustive search guarantee. */
class ExchangeManifestBoundarySpec : StringSpec({
    val service = ExchangeStorageService(ExchangeStorageConfigProperties(), listOf(GitMetadataEntries()))

    fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("exchange-manifest-boundary-")
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    fun requireUnreadable(directory: Path) {
        // Verify the actual operation used by walkFileTree: privileged users and some ACLs
        // can still list a mode-000 directory. Such environments must not report coverage.
        val failure = runCatching { Files.newDirectoryStream(directory).use { } }.exceptionOrNull()
        assumeTrue(failure is AccessDeniedException, "Directory enumeration must fail with AccessDeniedException; got $failure")
    }

    "manifest includes a file beyond the first 200-entry directory page" {
        withRoot { root ->
            repeat(201) { index -> Files.writeString(root.resolve("file-%03d.txt".format(index)), "x") }
            Files.writeString(root.resolve("needle.txt"), "synthetic")
            Files.createDirectories(root.resolve("docs/api"))
            Files.writeString(root.resolve("docs/api/Guide.md"), "synthetic")

            val (firstPage, total) = service.listDirectory(root, "", 0, 200)
            total shouldBe 203
            firstPage.any { it.path == "needle.txt" } shouldBe false
            val paths = service.listManifest(root, ExchangeScope.CASE).map { it.path }
            paths.size shouldBe 203
            paths.contains("needle.txt") shouldBe true
            paths.contains("docs/api/Guide.md") shouldBe true
        }
    }

    "manifest includes files at walk depths 19 and 20 but not 21" {
        withRoot { root ->
            var directory = root
            val expected = mutableListOf<String>()
            for (depth in 1..21) {
                if (depth >= 19) {
                    val file = directory.resolve("depth-$depth.txt")
                    Files.writeString(file, "synthetic")
                    if (depth <= 20) expected += root.relativize(file).joinToString("/")
                }
                if (depth < 21) directory = Files.createDirectory(directory.resolve("d"))
            }

            service.listManifest(root, ExchangeScope.CASE).map { it.path } shouldContainExactlyInAnyOrder expected
        }
    }

    "manifest excludes symlinked files and directories without following them" {
        withRoot { root ->
            Files.createDirectories(root.resolve("real"))
            Files.writeString(root.resolve("real/visible.txt"), "synthetic")
            try {
                Files.createSymbolicLink(root.resolve("linked-file.txt"), root.resolve("real/visible.txt"))
                Files.createSymbolicLink(root.resolve("linked-dir"), root.resolve("real"))
            } catch (e: UnsupportedOperationException) {
                assumeTrue(false, "Symbolic links unsupported: $e")
            } catch (e: FileSystemException) {
                assumeTrue(false, "Symbolic links unavailable: $e")
            }

            service.listManifest(root, ExchangeScope.CASE).map { it.path } shouldContainExactlyInAnyOrder listOf("real/visible.txt")
        }
    }

    "manifest excludes a linked-worktree git pointer file but keeps ordinary dotfiles" {
        withRoot { root ->
            Files.writeString(root.resolve(".git"), "gitdir: synthetic-target\n")
            Files.writeString(root.resolve(".gitignore"), "ignored\n")
            Files.writeString(root.resolve("visible.txt"), "synthetic")

            service.listManifest(root, ExchangeScope.CASE).map { it.path } shouldContainExactlyInAnyOrder
                listOf(".gitignore", "visible.txt")
        }
    }

    "manifest skips an unreadable subdirectory and still returns accessible files" {
        withRoot { root ->
            assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "POSIX permissions required")
            Files.writeString(root.resolve("visible.txt"), "synthetic")
            val blocked = Files.createDirectory(root.resolve("blocked"))
            Files.writeString(blocked.resolve("hidden.txt"), "synthetic")
            val permissions = Files.getPosixFilePermissions(blocked)
            try {
                Files.setPosixFilePermissions(blocked, emptySet<PosixFilePermission>())
                requireUnreadable(blocked)
                service.listManifest(root, ExchangeScope.CASE).map { it.path } shouldContainExactlyInAnyOrder listOf("visible.txt")
            } finally {
                Files.setPosixFilePermissions(blocked, permissions)
            }
        }
    }

    "manifest reports an unreadable root instead of an empty result" {
        withRoot { root ->
            assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "POSIX permissions required")
            Files.writeString(root.resolve("hidden.txt"), "synthetic")
            val permissions = Files.getPosixFilePermissions(root)
            try {
                Files.setPosixFilePermissions(root, emptySet<PosixFilePermission>())
                requireUnreadable(root)
                Files.exists(root) shouldBe true
                val error = shouldThrow<IOException> { service.listManifest(root, ExchangeScope.CASE) }
                error.message shouldBe "Failed to list exchange files"
            } finally {
                Files.setPosixFilePermissions(root, permissions)
            }
        }
    }
})
