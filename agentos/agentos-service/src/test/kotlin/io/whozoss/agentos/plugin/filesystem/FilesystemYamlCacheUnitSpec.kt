package io.whozoss.agentos.plugin.filesystem

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

const val WAIT_DELAY_MS = 5000L

class FilesystemYamlCacheUnitSpec :
    StringSpec({

        fun tempDir(): Path = Files.createTempDirectory("yaml-cache-test")

        fun writeYaml(
            dir: Path,
            filename: String,
            content: String,
        ): Path = dir.resolve(filename).also { Files.writeString(it, content) }

        // Simple parser: reads the file content as a trimmed string, returns null if blank.
        val stringParser: (Path) -> String? = { file ->
            Files.readString(file).trim().takeIf { it.isNotBlank() }
        }

        // Counting parser: tracks how many times it has been invoked.
        fun countingParser(counter: MutableList<Path>): (Path) -> String? =
            { file ->
                counter.add(file)
                Files.readString(file).trim().takeIf { it.isNotBlank() }
            }

        // Failing parser: always throws.
        val failingParser: (Path) -> String? = { throw RuntimeException("parse error") }

        "getAll returns parsed items from a valid directory" {
            val dir = tempDir()
            writeYaml(dir, "a.yaml", "hello")
            writeYaml(dir, "b.yml", "world")

            val cache = FilesystemYamlCache(dir, stringParser)
            val result = cache.getAll()

            result shouldHaveSize 2
            result.toSet() shouldBe setOf("hello", "world")
        }

        "getAll returns empty list when directory does not exist" {
            val nonExistent = Path.of("/tmp/does-not-exist-${System.nanoTime()}")

            val cache = FilesystemYamlCache(nonExistent, stringParser)

            cache.getAll().shouldBeEmpty()
        }

        "getAll returns empty list when path is a file not a directory" {
            val file = Files.createTempFile("not-a-dir", ".yaml")
            Files.writeString(file, "content")

            val cache = FilesystemYamlCache(file, stringParser)

            cache.getAll().shouldBeEmpty()
        }

        "getAll ignores files whose parsing fails and returns the rest" {
            val dir = tempDir()
            writeYaml(dir, "good.yaml", "ok")
            writeYaml(dir, "bad.yaml", "will-fail")

            var callCount = 0
            val partiallyFailingParser: (Path) -> String? = { file ->
                callCount++
                if (file.fileName.toString() == "bad.yaml") throw RuntimeException("intentional")
                Files.readString(file).trim()
            }

            val cache = FilesystemYamlCache(dir, partiallyFailingParser)
            val result = cache.getAll()

            result shouldHaveSize 1
            result.first() shouldBe "ok"
        }

        "getAll returns empty list when directory is empty" {
            val dir = tempDir()

            val cache = FilesystemYamlCache(dir, stringParser)

            cache.getAll().shouldBeEmpty()
        }

        "getAll ignores non-yaml files" {
            val dir = tempDir()
            writeYaml(dir, "agent.yaml", "yaml-content")
            dir.resolve("readme.txt").also { Files.writeString(it, "ignored") }
            dir.resolve("config.json").also { Files.writeString(it, "ignored") }

            val cache = FilesystemYamlCache(dir, stringParser)
            val result = cache.getAll()

            result shouldHaveSize 1
            result.first() shouldBe "yaml-content"
        }

        "getAll calls parser only once within TTL on successive calls" {
            val dir = tempDir()
            writeYaml(dir, "a.yaml", "item")

            val invocations = mutableListOf<Path>()
            val cache = FilesystemYamlCache(dir, countingParser(invocations), ttl = Duration.ofMinutes(5))

            cache.getAll()
            cache.getAll()
            cache.getAll()

            invocations shouldHaveSize 1
        }

        "getAll reloads after TTL expiry" {
            val dir = tempDir()
            writeYaml(dir, "a.yaml", "v1")

            val invocations = mutableListOf<Path>()
            val cache = FilesystemYamlCache(dir, countingParser(invocations), ttl = Duration.ofMillis(200))

            cache.getAll()
            Thread.sleep(WAIT_DELAY_MS)
            cache.getAll()

            invocations shouldHaveSize 2
        }

        "getAll with custom filePredicate matches only matching files and ignores yaml files" {
            val dir = tempDir()
            writeYaml(dir, "SKILL.md", "skill-content")
            dir.resolve("notes.yaml").also { Files.writeString(it, "yaml-content") }
            dir.resolve("README.md").also { Files.writeString(it, "readme") }

            val skillPredicate: (Path) -> Boolean = { it.fileName.toString() == "SKILL.md" }
            val cache = FilesystemYamlCache(dir, stringParser, filePredicate = skillPredicate)
            val result = cache.getAll()

            result shouldHaveSize 1
            result.first() shouldBe "skill-content"
        }

        "getAll with default predicate still ignores non-yaml files after predicate parameter addition" {
            val dir = tempDir()
            writeYaml(dir, "agent.yaml", "yaml-content")
            dir.resolve("SKILL.md").also { Files.writeString(it, "skill-content") }

            // Default predicate: only .yaml/.yml
            val cache = FilesystemYamlCache(dir, stringParser)
            val result = cache.getAll()

            result shouldHaveSize 1
            result.first() shouldBe "yaml-content"
        }

        "getAll reflects updated file content after TTL expiry" {
            val dir = tempDir()
            val file = writeYaml(dir, "a.yaml", "original")

            val cache = FilesystemYamlCache(dir, stringParser, ttl = Duration.ofMillis(200))

            cache.getAll().first() shouldBe "original"

            Files.writeString(file, "updated")
            Thread.sleep(WAIT_DELAY_MS)

            cache.getAll().first() shouldBe "updated"
        }

        "getAll with default maxDepth scans nested subdirectories (unbounded, backward compatible)" {
            val dir = tempDir()
            writeYaml(dir, "top.yaml", "top")
            val nested = dir.resolve("a").resolve("b").also { Files.createDirectories(it) }
            writeYaml(nested, "deep.yaml", "deep")

            val cache = FilesystemYamlCache(dir, stringParser)
            val result = cache.getAll()

            result.toSet() shouldBe setOf("top", "deep")
        }

        "getAll with explicit maxDepth stops traversal before reaching deeper files" {
            val dir = tempDir()
            writeYaml(dir, "top.yaml", "top") // depth 1
            val nested = dir.resolve("a").also { Files.createDirectories(it) } // depth 1 directory
            writeYaml(nested, "shallow.yaml", "shallow") // depth 2
            val deeper = nested.resolve("b").also { Files.createDirectories(it) } // depth 2 directory
            writeYaml(deeper, "deep.yaml", "deep") // depth 3

            // maxDepth = 2: root is depth 0; \"top.yaml\" and directory \"a\" are depth 1;
            // \"shallow.yaml\" and directory \"a/b\" are depth 2 (last depth visited);
            // \"deep.yaml\" at depth 3 is never visited.
            val cache = FilesystemYamlCache(dir, stringParser, maxDepth = 2)
            val result = cache.getAll()

            result.toSet() shouldBe setOf("top", "shallow")
        }

        "getAll with maxDepth of 1 only scans files directly in the root directory" {
            val dir = tempDir()
            writeYaml(dir, "top.yaml", "top")
            val nested = dir.resolve("a").also { Files.createDirectories(it) }
            writeYaml(nested, "nested.yaml", "nested")

            val cache = FilesystemYamlCache(dir, stringParser, maxDepth = 1)
            val result = cache.getAll()

            result shouldBe listOf("top")
        }

        "FilesystemYamlCacheRegistry forwards maxDepth to each per-directory cache" {
            val dir = tempDir()
            writeYaml(dir, "top.yaml", "top")
            val nested = dir.resolve("a").also { Files.createDirectories(it) }
            writeYaml(nested, "nested.yaml", "nested")

            val registry =
                FilesystemYamlCacheRegistry<String>(
                    parser = { _, file -> Files.readString(file).trim().takeIf { it.isNotBlank() } },
                    maxDepth = 1,
                )
            val result = registry.getAll(dir)

            result shouldBe listOf("top")
        }
    })
