package io.whozoss.agentos.plugins.ktlint

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class KtlintRunnerUnitSpec :
    StringSpec({

        /**
         * Formats [file] with the interrupt flag of the current thread raised, like a worker whose case was cancelled
         * while the engine was busy on it. The flag is always cleared afterwards so other tests are not polluted.
         */
        fun formatOnInterruptedThread(
            runner: KtlintRunner,
            file: Path,
        ): FormatResult =
            try {
                Thread.currentThread().interrupt()
                runner.format(file = file)
            } finally {
                Thread.interrupted()
            }

        "format rewrites a file with violations to its corrected content" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "Sample.kt", content = TRAILING_SPACE_SOURCE)

                val result = KtlintRunner(projectRoot = projectDir).format(file = file)

                result.modified shouldBe true
                file.toFile().readText() shouldBe CLEAN_SOURCE
            }
        }

        "format on an interrupted thread still writes the corrected content and never empties the file" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "Sample.kt", content = TRAILING_SPACE_SOURCE)

                val result = formatOnInterruptedThread(runner = KtlintRunner(projectRoot = projectDir), file = file)

                result.modified shouldBe true
                file.toFile().readText() shouldBe CLEAN_SOURCE
            }
        }

        "format on an interrupted thread leaves a clean file unchanged" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "Sample.kt", content = CLEAN_SOURCE)

                val result = formatOnInterruptedThread(runner = KtlintRunner(projectRoot = projectDir), file = file)

                result.modified shouldBe false
                file.toFile().readText() shouldBe CLEAN_SOURCE
            }
        }

        "format leaves no temporary file next to the rewritten one, on a normal and on an interrupted thread" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "src/Sample.kt", content = TRAILING_SPACE_SOURCE)
                val runner = KtlintRunner(projectRoot = projectDir)

                runner.format(file = file)
                file.toFile().writeText(TRAILING_SPACE_SOURCE)
                formatOnInterruptedThread(runner = runner, file = file)

                Files.list(file.parent).use { files -> files.map { it.fileName.toString() }.toList() } shouldBe listOf("Sample.kt")
            }
        }

        "format keeps the permissions of the file it rewrites" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "Sample.kt", content = TRAILING_SPACE_SOURCE)
                val permissions = PosixFilePermissions.fromString("rwxr-x---")
                Files.setPosixFilePermissions(file, permissions)

                KtlintRunner(projectRoot = projectDir).format(file = file)

                file.toFile().readText() shouldBe CLEAN_SOURCE
                Files.getPosixFilePermissions(file) shouldBe permissions
            }
        }

        "format leaves the file as it was, and no temporary file, when the engine fails on it" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "Broken.kt", content = NOT_KOTLIN_SOURCE)

                shouldThrow<Exception> { KtlintRunner(projectRoot = projectDir).format(file = file) }

                file.toFile().readText() shouldBe NOT_KOTLIN_SOURCE
                Files.list(projectDir).use { files -> files.map { it.fileName.toString() }.toList() } shouldBe listOf("Broken.kt")
            }
        }

        "lint on a file that is not valid Kotlin throws, which the tools report with the file named" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "Broken.kt", content = NOT_KOTLIN_SOURCE)

                val failure = shouldThrow<Exception> { KtlintRunner(projectRoot = projectDir).lint(file = file) }

                failure.javaClass.simpleName shouldBe "KtLintParseException"
            }
        }
    })
