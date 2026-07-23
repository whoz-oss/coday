package io.whozoss.agentos.agentConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class AgentDocumentResolverUnitSpec : StringSpec({

    val resolver = AgentDocumentResolver()

    // -------------------------------------------------------------------------
    // Helpers — build a temp dir tree for each test
    // -------------------------------------------------------------------------

    fun tempDir(block: (Path) -> Unit): Path {
        val dir = Files.createTempDirectory("agentos-doc-resolver-test")
        dir.toFile().deleteOnExit()
        block(dir)
        return dir
    }

    // -------------------------------------------------------------------------
    // null / empty input
    // -------------------------------------------------------------------------

    "buildDocsBlock returns null when docs is null" {
        resolver.buildDocsBlock(null).shouldBeNull()
    }

    "buildDocsBlock returns null when docs is empty" {
        resolver.buildDocsBlock(emptyList()).shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // Single file pattern (no trailing slash)
    // -------------------------------------------------------------------------

    "buildDocsBlock injects single file content verbatim" {
        val dir = tempDir { root ->
            root.resolve("readme.md").writeText("# Hello World")
        }
        val entry = "${dir.resolve("readme.md")}"

        val result = resolver.buildDocsBlock(listOf(entry))

        result.shouldNotBeNull()
        result shouldContain "File: readme.md"
        result shouldContain "# Hello World"
    }

    "buildDocsBlock logs and skips missing single file" {
        val entry = "/tmp/agentos-doc-resolver-test-nonexistent/missing.md"

        val result = resolver.buildDocsBlock(listOf(entry))

        result.shouldBeNull()
    }

    "buildDocsBlock logs and skips when entry is a directory but no trailing slash" {
        val dir = tempDir {}
        val entry = dir.toString() // no trailing slash → single-file branch

        val result = resolver.buildDocsBlock(listOf(entry))

        // Resolves to a directory, not a regular file — skipped
        result.shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // Directory listing pattern (trailing '/')
    // -------------------------------------------------------------------------

    "buildDocsBlock emits directory listing when entry ends with slash" {
        val dir = tempDir { root ->
            root.resolve("alpha.txt").writeText("A")
            root.resolve("beta.txt").writeText("B")
            Files.createDirectory(root.resolve("subdir"))
        }
        val entry = "${dir}/"

        val result = resolver.buildDocsBlock(listOf(entry))

        result.shouldNotBeNull()
        result shouldContain "alpha.txt"
        result shouldContain "beta.txt"
        result shouldContain "subdir/" // directory entries get a trailing slash
        // Content of files must NOT appear
        result shouldNotContain "A"
        result shouldNotContain "B"
    }

    "buildDocsBlock lists entries sorted alphabetically for directory listing" {
        val dir = tempDir { root ->
            root.resolve("z.txt").writeText("Z")
            root.resolve("a.txt").writeText("A")
            root.resolve("m.txt").writeText("M")
        }
        val entry = "${dir}/"

        val result = resolver.buildDocsBlock(listOf(entry))

        result.shouldNotBeNull()
        val aIdx = result.indexOf("a.txt")
        val mIdx = result.indexOf("m.txt")
        val zIdx = result.indexOf("z.txt")
        check(aIdx < mIdx && mIdx < zIdx) {
            "Expected alphabetical order a < m < z but got positions $aIdx, $mIdx, $zIdx"
        }
    }

    "buildDocsBlock logs and skips missing directory for listing pattern" {
        val entry = "/tmp/agentos-doc-resolver-test-nonexistent-dir/"

        val result = resolver.buildDocsBlock(listOf(entry))

        result.shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // Directory content pattern (trailing '/*')
    // -------------------------------------------------------------------------

    "buildDocsBlock injects all readable files in directory for star pattern" {
        val dir = tempDir { root ->
            root.resolve("doc1.md").writeText("Content of doc1")
            root.resolve("doc2.md").writeText("Content of doc2")
        }
        val entry = "${dir}/*"

        val result = resolver.buildDocsBlock(listOf(entry))

        result.shouldNotBeNull()
        result shouldContain "Content of doc1"
        result shouldContain "Content of doc2"
    }

    "buildDocsBlock skips subdirectories for star pattern (files only)" {
        val dir = tempDir { root ->
            root.resolve("file.txt").writeText("file content")
            Files.createDirectory(root.resolve("nested"))
            root.resolve("nested").resolve("inner.txt").writeText("should not appear")
        }
        val entry = "${dir}/*"

        val result = resolver.buildDocsBlock(listOf(entry))

        result.shouldNotBeNull()
        result shouldContain "file content"
        result shouldNotContain "should not appear"
    }

    "buildDocsBlock logs and skips missing directory for star pattern" {
        val entry = "/tmp/agentos-doc-resolver-test-nonexistent-star/*"

        val result = resolver.buildDocsBlock(listOf(entry))

        result.shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // Sensitive file deny-list
    // -------------------------------------------------------------------------

    "buildDocsBlock skips .env file in star pattern" {
        val dir = tempDir { root ->
            root.resolve(".env").writeText("SECRET=hunter2")
            root.resolve("safe.txt").writeText("safe content")
        }
        val entry = "${dir}/*"

        val result = resolver.buildDocsBlock(listOf(entry))

        result.shouldNotBeNull()
        result shouldNotContain "SECRET"
        result shouldContain "safe content"
    }

    "buildDocsBlock skips private key file in star pattern" {
        val dir = tempDir { root ->
            root.resolve("id_rsa").writeText("-----BEGIN RSA PRIVATE KEY-----")
            root.resolve("app.md").writeText("app docs")
        }
        val entry = "${dir}/*"

        val result = resolver.buildDocsBlock(listOf(entry))

        result.shouldNotBeNull()
        result shouldNotContain "BEGIN RSA"
        result shouldContain "app docs"
    }

    "buildDocsBlock skips pem file matched by wildcard pattern" {
        val dir = tempDir { root ->
            root.resolve("cert.pem").writeText("-----BEGIN CERTIFICATE-----")
            root.resolve("notes.txt").writeText("notes")
        }
        val entry = "${dir}/*"

        val result = resolver.buildDocsBlock(listOf(entry))

        result.shouldNotBeNull()
        result shouldNotContain "BEGIN CERTIFICATE"
        result shouldContain "notes"
    }

    // -------------------------------------------------------------------------
    // docs block lands in instructions (via AgentServiceImpl — tested here
    // at the resolver level to confirm the block format)
    // -------------------------------------------------------------------------

    "buildDocsBlock result contains expected section header" {
        val dir = tempDir { root ->
            root.resolve("guide.md").writeText("some guide")
        }
        val entry = dir.resolve("guide.md").toString()

        val result = resolver.buildDocsBlock(listOf(entry))

        result.shouldNotBeNull()
        result shouldContain "Mandatory documents"
        result shouldContain "Each of the following files are included entirely as deemed important."
    }
})
