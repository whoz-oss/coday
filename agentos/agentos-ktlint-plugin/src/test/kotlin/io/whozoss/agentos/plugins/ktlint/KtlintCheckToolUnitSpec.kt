package io.whozoss.agentos.plugins.ktlint

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.whozoss.agentos.sdk.tool.ToolContext
import java.nio.file.Files
import java.util.UUID

class KtlintCheckToolUnitSpec :
    StringSpec({

        val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

        "check reports a violation with its location, message and rule id as a Failure" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "Sample.kt", content = WILDCARD_IMPORT_SOURCE)
                val tool = KtlintCheckTool(projectRoot = projectDir)

                val report = tool.check(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS).failureText()

                report shouldContain "Sample.kt:1:"
                report shouldContain "no-wildcard-imports"
            }
        }

        "check reports no violation on a clean file as a Success" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "Sample.kt", content = CLEAN_SOURCE)
                val tool = KtlintCheckTool(projectRoot = projectDir)

                tool.check(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS).successText() shouldContain "no violation"
            }
        }

        "check flags auto-correctable violations so the agent knows format can fix them" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "Sample.kt", content = TRAILING_SPACE_SOURCE)
                val tool = KtlintCheckTool(projectRoot = projectDir)

                val report = tool.check(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS).failureText()

                report shouldContain "no-trailing-spaces"
                report shouldContain "auto-correctable"
            }
        }

        "check never modifies the checked file" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "Sample.kt", content = TRAILING_SPACE_SOURCE)
                val tool = KtlintCheckTool(projectRoot = projectDir)

                tool.check(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS)

                Files.readString(file) shouldBe TRAILING_SPACE_SOURCE
            }
        }

        "check handles several files one after the other" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "src/A.kt", content = WILDCARD_IMPORT_SOURCE)
                projectDir.writeFile(relativePath = "src/nested/B.kt", content = WILDCARD_IMPORT_SOURCE)
                val tool = KtlintCheckTool(projectRoot = projectDir)

                val report =
                    tool.check(files = listOf("src/A.kt", "src/nested/B.kt"), timeoutSeconds = TIMEOUT_SECONDS).failureText()

                report shouldContain "src/A.kt:1:"
                report shouldContain "src/nested/B.kt:1:"
            }
        }

        "check refuses a directory and tells to pass files one by one" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "src/A.kt", content = WILDCARD_IMPORT_SOURCE)
                val tool = KtlintCheckTool(projectRoot = projectDir)

                val error = tool.check(files = listOf("src"), timeoutSeconds = TIMEOUT_SECONDS).errorText()

                error shouldContain "is a directory"
                error shouldNotContain "no-wildcard-imports"
            }
        }

        "check refuses a path outside the project" {
            withProject { projectDir ->
                val tool = KtlintCheckTool(projectRoot = projectDir)

                tool.check(files = listOf("../outside.kt"), timeoutSeconds = TIMEOUT_SECONDS).errorText() shouldContain "outside the project"
            }
        }

        "check asks for at least one file when none is given" {
            withProject { projectDir ->
                val tool = KtlintCheckTool(projectRoot = projectDir)

                tool.check(files = emptyList(), timeoutSeconds = TIMEOUT_SECONDS).errorText() shouldContain "No file provided"
            }
        }

        "check stops and tells it timed out when a file takes longer than timeoutSeconds" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "Slow.kt", content = CLEAN_SOURCE)
                val tool =
                    KtlintCheckTool(
                        bounded = BoundedKtlintRun(projectRoot = projectDir, runner = HangingRunner(hangingOn = setOf("Slow.kt"))),
                    )

                val error = tool.check(files = listOf("Slow.kt"), timeoutSeconds = 1).errorText()

                error shouldContain "timed out after 1 seconds"
                error shouldContain "timeoutSeconds"
            }
        }

        listOf(0, -5, ToolTimeout.MAX_SECONDS + 1).forEach { invalid ->
            "check refuses timeoutSeconds=$invalid before looking at the files" {
                withProject { projectDir ->
                    val tool = KtlintCheckTool(projectRoot = projectDir)

                    val error = tool.check(files = listOf("Missing.kt"), timeoutSeconds = invalid).errorText()

                    error shouldContain "timeoutSeconds"
                    error shouldContain "between 1 and ${ToolTimeout.MAX_SECONDS}"
                    error shouldContain "got $invalid"
                    error shouldNotContain "not found"
                }
            }
        }

        "check accepts the maximum timeoutSeconds" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "Sample.kt", content = CLEAN_SOURCE)
                val tool = KtlintCheckTool(projectRoot = projectDir)

                tool.check(files = listOf("Sample.kt"), timeoutSeconds = ToolTimeout.MAX_SECONDS).successText() shouldContain "no violation"
            }
        }

        "tool schema marks files and timeoutSeconds as required so the agent must always provide them" {
            withProject { projectDir ->
                val tool = KtlintCheckTool(projectRoot = projectDir)

                tool.name shouldBe "ktlint_check"
                tool.version shouldBe "1.0.0"
                tool.inputSchema shouldMatch Regex("""(?s).*"required"\s*:\s*\[[^\]]*"timeoutSeconds".*""")
                tool.inputSchema shouldMatch Regex("""(?s).*"required"\s*:\s*\[[^\]]*"files".*""")
            }
        }

        "a name is prefixed with the config name when there is one" {
            withProject { projectDir ->
                KtlintCheckTool(projectRoot = projectDir, configName = "MY_KTLINT").name shouldBe "MY_KTLINT__ktlint_check"
            }
        }

        "a missing timeoutSeconds in the input answers a KTLINT_ERROR naming timeoutSeconds" {
            withProject { projectDir ->
                val tool = KtlintCheckTool(projectRoot = projectDir)

                val result = tool.executeWithJson("""{"files":["Sample.kt"]}""", ctx)

                result.success shouldBe false
                result.errorType shouldBe "KTLINT_ERROR"
                result.output shouldStartWith "ERROR"
                result.output shouldContain "timeoutSeconds"
            }
        }

        "a missing input answers a KTLINT_ERROR naming timeoutSeconds" {
            withProject { projectDir ->
                val result = KtlintCheckTool(projectRoot = projectDir).executeWithJson(null, ctx)

                result.success shouldBe false
                result.errorType shouldBe "KTLINT_ERROR"
                result.output shouldContain "timeoutSeconds"
            }
        }

        "execute answers a KTLINT_FAILURE with the report when there are violations" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "Sample.kt", content = WILDCARD_IMPORT_SOURCE)
                val tool = KtlintCheckTool(projectRoot = projectDir)

                val result = tool.executeWithJson("""{"files":["Sample.kt"],"timeoutSeconds":60}""", ctx)

                result.success shouldBe false
                result.errorType shouldBe "KTLINT_FAILURE"
                result.output shouldContain "no-wildcard-imports"
                result.output shouldNotContain "ERROR"
            }
        }

        "execute answers a success on a clean file" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "Sample.kt", content = CLEAN_SOURCE)
                val tool = KtlintCheckTool(projectRoot = projectDir)

                val result = tool.executeWithJson("""{"files":["Sample.kt"],"timeoutSeconds":60}""", ctx)

                result.success shouldBe true
                result.output shouldContain "no violation"
            }
        }

        "execute answers a KTLINT_ERROR for a path outside the project" {
            withProject { projectDir ->
                val tool = KtlintCheckTool(projectRoot = projectDir)

                val result = tool.executeWithJson("""{"files":["../outside.kt"],"timeoutSeconds":60}""", ctx)

                result.success shouldBe false
                result.errorType shouldBe "KTLINT_ERROR"
                result.output shouldContain "outside the project"
            }
        }
    })
