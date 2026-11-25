package io.whozoss.agentos.plugins.ktlint

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolTimeout
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

class KtlintFormatToolUnitSpec :
    StringSpec({

        val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

        "format autocorrects violations, rewrites the file and is a Success when nothing remains" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "Sample.kt", content = TRAILING_SPACE_SOURCE)
                val tool = KtlintFormatTool(projectRoot = projectDir)

                val report = tool.format(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS).successText()

                report shouldContain "1 file(s) modified"
                report shouldContain "Sample.kt"
                Files.readString(file) shouldBe CLEAN_SOURCE
            }
        }

        "format lists the violations that cannot be autocorrected as a Failure" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "Sample.kt", content = WILDCARD_IMPORT_SOURCE)
                val tool = KtlintFormatTool(projectRoot = projectDir)

                val report = tool.format(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS).failureText()

                report shouldContain "no-wildcard-imports"
                report shouldContain "0 file(s) modified"
                Files.readString(file) shouldBe WILDCARD_IMPORT_SOURCE
            }
        }

        "format leaves a clean file untouched" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "Sample.kt", content = CLEAN_SOURCE)
                val tool = KtlintFormatTool(projectRoot = projectDir)

                val report = tool.format(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS).successText()

                Files.readString(file) shouldBe CLEAN_SOURCE
                report shouldContain "0 file(s) modified"
                report shouldNotContain "no-wildcard-imports"
            }
        }

        "format refuses a directory and writes nothing" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "src/Sample.kt", content = TRAILING_SPACE_SOURCE)
                val tool = KtlintFormatTool(projectRoot = projectDir)

                tool.format(files = listOf("src"), timeoutSeconds = TIMEOUT_SECONDS).errorText() shouldContain "is a directory"
                Files.readString(file) shouldBe TRAILING_SPACE_SOURCE
            }
        }

        "format refuses a path outside the project and writes nothing" {
            withProject { projectDir ->
                withProject { outsideDir ->
                    val outsideFile = outsideDir.writeFile(relativePath = "Outside.kt", content = TRAILING_SPACE_SOURCE)
                    val tool = KtlintFormatTool(projectRoot = projectDir)

                    val error =
                        tool
                            .format(
                                files = listOf("../${outsideDir.fileName}/Outside.kt"),
                                timeoutSeconds = TIMEOUT_SECONDS,
                            ).errorText()

                    error shouldContain "outside the project"
                    Files.readString(outsideFile) shouldBe TRAILING_SPACE_SOURCE
                }
            }
        }

        "format asks for at least one file when none is given" {
            withProject { projectDir ->
                val tool = KtlintFormatTool(projectRoot = projectDir)

                tool.format(files = emptyList(), timeoutSeconds = TIMEOUT_SECONDS).errorText() shouldContain "No file provided"
            }
        }

        "format stops at the timeout, does not process the next files and lists the files already modified" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "A.kt", content = CLEAN_SOURCE)
                projectDir.writeFile(relativePath = "B.kt", content = CLEAN_SOURCE)
                projectDir.writeFile(relativePath = "C.kt", content = CLEAN_SOURCE)
                val tool =
                    KtlintFormatTool(
                        bounded = isolatedRun(projectRoot = projectDir, runner = HangingRunner(hangingOn = setOf("B.kt"))),
                    )

                val error = tool.format(files = listOf("A.kt", "B.kt", "C.kt"), timeoutSeconds = 1).errorText()

                error shouldContain "timed out after 1 seconds"
                error shouldContain "modified: A.kt"
                error shouldNotContain "modified: C.kt"
            }
        }

        "format waits for the file in progress after a timeout, so that it is listed as modified" {
            withProject { projectDir ->
                listOf("A.kt", "B.kt", "C.kt").forEach { projectDir.writeFile(relativePath = it) }
                val runner = BusyRunner(busyOn = setOf("B.kt"), busyMillis = 1_500)
                val tool = KtlintFormatTool(bounded = isolatedRun(projectRoot = projectDir, runner = runner))

                val error = tool.format(files = listOf("A.kt", "B.kt", "C.kt"), timeoutSeconds = 1).errorText()

                error shouldContain "timed out after 1 seconds"
                error shouldContain "modified: B.kt"
                error shouldNotContain "still being processed"
                error shouldNotContain "modified: C.kt"
            }
        }

        "format names the file still in progress as possibly modified when its worker does not end in time" {
            withProject { projectDir ->
                listOf("A.kt", "B.kt", "C.kt").forEach { projectDir.writeFile(relativePath = it) }
                val runner = BusyRunner(busyOn = setOf("B.kt"), busyMillis = 3_000)
                val tool =
                    KtlintFormatTool(
                        bounded = isolatedRun(projectRoot = projectDir, runner = runner, settleTimeout = Duration.ofMillis(100)),
                    )

                val error = tool.format(files = listOf("A.kt", "B.kt", "C.kt"), timeoutSeconds = 1).errorText()

                error shouldContain "modified: A.kt"
                error shouldContain "may be modified after this answer: B.kt"
                error shouldContain "ktlint_format"
                runner.busyFinished.await(10, TimeUnit.SECONDS) shouldBe true
            }
        }

        "format names the failing file and lists the files already rewritten when the engine fails" {
            withProject { projectDir ->
                listOf("A.kt", "B.kt", "C.kt").forEach { projectDir.writeFile(relativePath = it) }
                val tool =
                    KtlintFormatTool(
                        bounded = BoundedKtlintRun(projectRoot = projectDir, runner = FailingRunner(failingOn = setOf("B.kt"))),
                    )

                val error = tool.format(files = listOf("A.kt", "B.kt", "C.kt"), timeoutSeconds = TIMEOUT_SECONDS).errorText()

                error shouldContain "ktlint failed: Not a valid Kotlin file"
                error shouldContain "modified: A.kt"
                error shouldContain "Failing file: B.kt"
                error shouldNotContain "modified: C.kt"
            }
        }

        listOf(0, -5, ToolTimeout.MAX_SECONDS + 1).forEach { invalid ->
            "format refuses timeoutSeconds=$invalid and writes nothing" {
                withProject { projectDir ->
                    val file = projectDir.writeFile(relativePath = "Sample.kt", content = TRAILING_SPACE_SOURCE)
                    val tool = KtlintFormatTool(projectRoot = projectDir)

                    val error = tool.format(files = listOf("Sample.kt"), timeoutSeconds = invalid).errorText()

                    error shouldContain "timeoutSeconds"
                    error shouldContain "between 1 and ${ToolTimeout.MAX_SECONDS}"
                    error shouldContain "got $invalid"
                    Files.readString(file) shouldBe TRAILING_SPACE_SOURCE
                }
            }
        }

        "format accepts the maximum timeoutSeconds" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "Sample.kt", content = CLEAN_SOURCE)
                val tool = KtlintFormatTool(projectRoot = projectDir)

                tool.format(files = listOf("Sample.kt"), timeoutSeconds = ToolTimeout.MAX_SECONDS).successText() shouldContain
                    "0 file(s) modified"
            }
        }

        "tool schema marks files and timeoutSeconds as required so the agent must always provide them" {
            withProject { projectDir ->
                val tool = KtlintFormatTool(projectRoot = projectDir)

                tool.name shouldBe "ktlint_format"
                tool.version shouldBe "1.0.0"
                tool.inputSchema shouldMatch Regex("""(?s).*"required"\s*:\s*\[[^\]]*"timeoutSeconds".*""")
                tool.inputSchema shouldMatch Regex("""(?s).*"required"\s*:\s*\[[^\]]*"files".*""")
            }
        }

        "a name is prefixed with the config name when there is one" {
            withProject { projectDir ->
                KtlintFormatTool(projectRoot = projectDir, configName = "MY_KTLINT").name shouldBe "MY_KTLINT__ktlint_format"
            }
        }

        "a missing timeoutSeconds in the input answers a KTLINT_ERROR naming timeoutSeconds and writes nothing" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "Sample.kt", content = TRAILING_SPACE_SOURCE)
                val tool = KtlintFormatTool(projectRoot = projectDir)

                val result = tool.executeWithJson("""{"files":["Sample.kt"]}""", ctx)

                result.success shouldBe false
                result.errorType shouldBe "KTLINT_ERROR"
                result.output shouldStartWith "ERROR"
                result.output shouldContain "timeoutSeconds"
                Files.readString(file) shouldBe TRAILING_SPACE_SOURCE
            }
        }

        "execute formats the file and answers a success result" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "Sample.kt", content = TRAILING_SPACE_SOURCE)
                val tool = KtlintFormatTool(projectRoot = projectDir)

                val result = tool.executeWithJson("""{"files":["Sample.kt"],"timeoutSeconds":60}""", ctx)

                result.success shouldBe true
                Files.readString(file) shouldBe CLEAN_SOURCE
            }
        }

        "execute answers a KTLINT_FAILURE with the report when violations remain" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "Sample.kt", content = WILDCARD_IMPORT_SOURCE)
                val tool = KtlintFormatTool(projectRoot = projectDir)

                val result = tool.executeWithJson("""{"files":["Sample.kt"],"timeoutSeconds":60}""", ctx)

                result.success shouldBe false
                result.errorType shouldBe "KTLINT_FAILURE"
                result.output shouldContain "no-wildcard-imports"
            }
        }
    })
