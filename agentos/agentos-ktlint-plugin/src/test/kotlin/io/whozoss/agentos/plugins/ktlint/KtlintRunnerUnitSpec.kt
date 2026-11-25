package io.whozoss.agentos.plugins.ktlint

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

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
    })
