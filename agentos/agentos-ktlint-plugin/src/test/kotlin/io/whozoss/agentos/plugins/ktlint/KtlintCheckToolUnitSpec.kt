package io.whozoss.agentos.plugins.ktlint

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolTimeout
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

                tool.check(files = listOf("../outside.kt"), timeoutSeconds = TIMEOUT_SECONDS).errorText() shouldContain
                    "outside the project"
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
                        bounded = isolatedRun(projectRoot = projectDir, runner = HangingRunner(hangingOn = setOf("Slow.kt"))),
                    )

                val error = tool.check(files = listOf("Slow.kt"), timeoutSeconds = 1).errorText()

                error shouldContain "timed out after 1 seconds"
                error shouldContain "timeoutSeconds"
            }
        }

        "check names the failing file and keeps the violations of the files checked before it" {
            withProject { projectDir ->
                listOf("A.kt", "B.kt", "C.kt").forEach { projectDir.writeFile(relativePath = it) }
                val tool =
                    KtlintCheckTool(
                        bounded = BoundedKtlintRun(projectRoot = projectDir, runner = FailingRunner(failingOn = setOf("B.kt"))),
                    )

                val error = tool.check(files = listOf("A.kt", "B.kt", "C.kt"), timeoutSeconds = TIMEOUT_SECONDS).errorText()

                error shouldContain "ktlint failed: Not a valid Kotlin file"
                error shouldContain "A.kt:1:1 violation (test:rule)"
                error shouldContain "Failing file: B.kt"
                error shouldNotContain "C.kt:1:1"
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

        "check lists at most the first violations and says how many were left out, the header keeping the real total" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "Generated.kt")
                val total = MAX_REPORTED_VIOLATIONS + 50
                val tool = KtlintCheckTool(bounded = isolatedRun(projectRoot = projectDir, runner = ManyViolationsRunner(perFile = total)))

                val report = tool.check(files = listOf("Generated.kt"), timeoutSeconds = TIMEOUT_SECONDS).failureText()

                report shouldContain "Checked 1 file(s): $total violation(s)"
                report shouldContain "Generated.kt:$MAX_REPORTED_VIOLATIONS:1 violation"
                report shouldNotContain "Generated.kt:${MAX_REPORTED_VIOLATIONS + 1}:1 violation"
                report shouldContain "... 50 more violation(s) not shown ($total total)"
            }
        }

        "check does not mention any cut when the violations fit in the report" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "Generated.kt")
                val tool =
                    KtlintCheckTool(
                        bounded = isolatedRun(projectRoot = projectDir, runner = ManyViolationsRunner(perFile = MAX_REPORTED_VIOLATIONS)),
                    )

                val report = tool.check(files = listOf("Generated.kt"), timeoutSeconds = TIMEOUT_SECONDS).failureText()

                report shouldContain "Generated.kt:$MAX_REPORTED_VIOLATIONS:1 violation"
                report shouldNotContain "not shown"
            }
        }

        "check caps the violations listed with a failure too, and still names the failing file" {
            withProject { projectDir ->
                listOf("A.kt", "B.kt").forEach { projectDir.writeFile(relativePath = it) }
                val total = MAX_REPORTED_VIOLATIONS + 50
                val tool =
                    KtlintCheckTool(
                        bounded =
                            BoundedKtlintRun(
                                projectRoot = projectDir,
                                runner = ManyViolationsRunner(perFile = total, failingOn = setOf("B.kt")),
                            ),
                    )

                val error = tool.check(files = listOf("A.kt", "B.kt"), timeoutSeconds = TIMEOUT_SECONDS).errorText()

                error shouldContain "... 50 more violation(s) not shown ($total total)"
                error shouldNotContain "A.kt:${MAX_REPORTED_VIOLATIONS + 1}:1 violation"
                error shouldContain "Failing file: B.kt"
            }
        }

        "check follows the editorconfig of the project: the same file is clean without it and has a violation with it" {
            val longLine = "val aVeryLongVariableNameThatGoesBeyondFortyCharacters = 42\n"
            withProject { withoutConfig ->
                withoutConfig.writeFile(relativePath = "Sample.kt", content = longLine)

                KtlintCheckTool(projectRoot = withoutConfig)
                    .check(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS)
                    .successText() shouldContain "no violation"
            }
            withProject { withConfig ->
                withConfig.writeFile(relativePath = ".editorconfig", content = "root = true\n\n[*.{kt,kts}]\nmax_line_length = 40\n")
                withConfig.writeFile(relativePath = "Sample.kt", content = longLine)

                val report =
                    KtlintCheckTool(projectRoot = withConfig)
                        .check(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS)
                        .failureText()

                report shouldContain "max-line-length"
            }
        }

        "two projects with different editorconfig get their own style from the one shared engine" {
            val longLine = "val aVeryLongVariableNameThatGoesBeyondFortyCharacters = 42\n"
            withProject { strict ->
                withProject { lax ->
                    strict.writeFile(relativePath = ".editorconfig", content = "root = true\n\n[*.{kt,kts}]\nmax_line_length = 40\n")
                    lax.writeFile(relativePath = ".editorconfig", content = "root = true\n\n[*.{kt,kts}]\nmax_line_length = 200\n")
                    strict.writeFile(relativePath = "Sample.kt", content = longLine)
                    lax.writeFile(relativePath = "Sample.kt", content = longLine)
                    val strictTool = KtlintCheckTool(projectRoot = strict)
                    val laxTool = KtlintCheckTool(projectRoot = lax)

                    (
                        strictTool.bounded.runner
                            .shouldBeInstanceOf<KtlintRunner>()
                            .engine ===
                            laxTool.bounded.runner
                                .shouldBeInstanceOf<KtlintRunner>()
                                .engine
                    ) shouldBe
                        true
                    strictTool.check(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS).failureText() shouldContain
                        "max-line-length"
                    laxTool.check(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS).successText() shouldContain "no violation"
                    // and again in the other order, with the editorconfig cache of the engine now filled
                    laxTool.check(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS).successText() shouldContain "no violation"
                    strictTool.check(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS).failureText() shouldContain
                        "max-line-length"
                }
            }
        }

        "check follows a different value of the editorconfig: a line that is too long for 40 is fine for 200" {
            val longLine = "val aVeryLongVariableNameThatGoesBeyondFortyCharacters = 42\n"
            withProject { projectDir ->
                projectDir.writeFile(relativePath = ".editorconfig", content = "root = true\n\n[*.{kt,kts}]\nmax_line_length = 200\n")
                projectDir.writeFile(relativePath = "Sample.kt", content = longLine)

                KtlintCheckTool(projectRoot = projectDir)
                    .check(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS)
                    .successText() shouldContain "no violation"
            }
        }

        "check on a file that is not valid Kotlin answers an error that names the file and lets the earlier files be reported" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "A.kt", content = WILDCARD_IMPORT_SOURCE)
                projectDir.writeFile(relativePath = "B.kt", content = NOT_KOTLIN_SOURCE)
                projectDir.writeFile(relativePath = "C.kt", content = WILDCARD_IMPORT_SOURCE)
                val tool = KtlintCheckTool(projectRoot = projectDir)

                val error = tool.check(files = listOf("A.kt", "B.kt", "C.kt"), timeoutSeconds = TIMEOUT_SECONDS).errorText()

                error shouldContain "ktlint failed:"
                error shouldContain "Failing file: B.kt"
                error shouldContain "A.kt:1:"
                error shouldNotContain "C.kt:1:"
            }
        }

        "check names the class of the failure when the engine throws without a message" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "A.kt")
                val tool =
                    KtlintCheckTool(
                        bounded = isolatedRun(projectRoot = projectDir, runner = ThrowingRunner(failure = { StackOverflowError() })),
                    )

                val error = tool.check(files = listOf("A.kt"), timeoutSeconds = TIMEOUT_SECONDS).errorText()

                error shouldContain "ktlint failed: StackOverflowError"
                error shouldNotContain "null"
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

        "the description tells the agent that the report is capped" {
            withProject { projectDir ->
                KtlintCheckTool(projectRoot = projectDir).description shouldContain "At most $MAX_REPORTED_VIOLATIONS violations"
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
